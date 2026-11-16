package com.whumpo.core.auth

import com.whumpo.core.account.CoreAccountScope
import com.whumpo.core.api.CoreApiClient
import com.whumpo.core.api.CoreHttpException
import com.whumpo.core.api.CoreOrigin
import com.whumpo.core.api.CoreProtocolException
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoreOrganizationSessionControllerTest {
    private val base = "https://core.example.test"
    private val origin = CoreOrigin.allowlisted(base, setOf(base))
    private val source = "A".repeat(43)
    private val target = "E".repeat(43)
    private val replacement = """{"token":"$target","expiresAt":9000}"""

    private fun session(org: String, user: String = "user-1") =
        """{"sessionId":"session-$org","orgId":"$org","userId":"$user","role":"owner","expiresAt":9000,"addedField":true}"""

    @Test
    fun switchRevokesSourceEpochAndGuardsAgainstLateUnauthorizedResponse() = runTest {
        val oldReadStarted = CompletableDeferred<Unit>()
        val oldReadRelease = CompletableDeferred<Unit>()
        val exchangeStarted = CompletableDeferred<Unit>()
        val exchangeRelease = CompletableDeferred<Unit>()
        var switches = 0
        val engine = MockEngine { request ->
            val bearer = request.headers["Authorization"]
            assertNull(request.headers["Origin"])
            when (request.url.encodedPath) {
                "/v1/auth/session" -> respond(session(if (bearer == "Bearer $source") "solo" else "team"))
                "/v1/auth/session/switch" -> {
                    assertEquals("Bearer $source", bearer)
                    assertEquals("""{"orgId":"team"}""", request.body.toByteArray().decodeToString())
                    switches++
                    exchangeStarted.complete(Unit)
                    exchangeRelease.await()
                    respond(replacement)
                }
                "/v1/organization" -> if (bearer == "Bearer $source") {
                    oldReadStarted.complete(Unit)
                    oldReadRelease.await()
                    respond("", HttpStatusCode.Unauthorized)
                } else respond("""{"orgId":"team","displayName":null,"version":1}""")
                else -> error("Unexpected path")
            }
        }
        CoreApiClient(engine, origin).use { api ->
            val controller = CoreOrganizationSessionController(api) { 1 }
            val old = controller.establish(source)
            val read = async { assertFailsWith<CancellationException> { controller.profile() } }
            oldReadStarted.await()
            val switching = async { controller.switchOrganization("team") }
            exchangeStarted.await()
            assertIs<CoreOrganizationSessionController.State.Transitioning>(controller.state.value)
            assertFalse(controller.commitIfCurrent(old.epoch) { error("Stale commit") })
            assertFailsWith<IllegalStateException> { controller.switchOrganization("team") }
            exchangeRelease.complete(Unit)
            val current = switching.await()
            assertEquals(1, switches)
            assertEquals("team", current.session.orgId)
            assertNotEquals(old.scope, current.scope)
            oldReadRelease.complete(Unit)
            read.await()
            assertEquals(current, controller.state.value)
            assertTrue(controller.commitIfCurrent(current.epoch) { })
            assertEquals("team", controller.profile().orgId)
        }
    }

    @Test
    fun rejectsCrossIdentityAndDoesNotReplayALostSwitch() = runTest {
        var switches = 0
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/v1/auth/session/switch" -> {
                    switches++
                    if (switches == 1) respond(replacement) else respond("", HttpStatusCode.ServiceUnavailable)
                }
                else -> respond(if (request.headers["Authorization"] == "Bearer $source") session("solo") else session("team", "other-user"))
            }
        }
        CoreApiClient(engine, origin).use { api ->
            val controller = CoreOrganizationSessionController(api) { 1 }
            controller.establish(source)
            assertFailsWith<CoreProtocolException> { controller.switchOrganization("team") }
            assertIs<CoreOrganizationSessionController.State.SignedOut>(controller.state.value)
            controller.establish(source)
            assertFailsWith<CoreHttpException> { controller.switchOrganization("team") }
            assertIs<CoreOrganizationSessionController.State.SignedOut>(controller.state.value)
            assertFailsWith<IllegalStateException> { controller.switchOrganization("team") }
            assertEquals(2, switches)
        }
    }

    @Test
    fun currentUnauthorizedClearsSession() = runTest {
        val engine = MockEngine { request ->
            if (request.url.encodedPath == "/v1/auth/session") respond(session("solo"))
            else respond("", HttpStatusCode.Unauthorized)
        }
        CoreApiClient(engine, origin).use { api ->
            val controller = CoreOrganizationSessionController(api) { 1 }
            val old = controller.establish(source)
            assertEquals(401, assertFailsWith<CoreHttpException> { controller.profile() }.status)
            assertIs<CoreOrganizationSessionController.State.SignedOut>(controller.state.value)
            assertFalse(controller.commitIfCurrent(old.epoch) { error("Stale commit") })
        }
    }

    @Test
    fun matchesOrganizationWireAndKeepsProfileReplayKey() = runTest {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/v1/auth/session" -> respond(session("solo"))
                "/v1/auth/organizations" -> {
                    assertEquals("next-1", request.url.parameters["cursor"])
                    assertEquals("2", request.url.parameters["limit"])
                    respond("""{"items":[{"orgId":"solo","role":"owner","active":true}]}""")
                }
                "/v1/organization" -> {
                    assertEquals("PATCH", request.method.value)
                    assertEquals("\"2\"", request.headers["If-Match"])
                    assertEquals("rename-1", request.headers["Idempotency-Key"])
                    assertEquals("""{"displayName":"New name"}""", request.body.toByteArray().decodeToString())
                    respond("""{"orgId":"solo","displayName":"New name","version":3}""")
                }
                "/v1/organization/entitlements" -> respond(
                    """{"orgId":"solo","status":"unconfigured","membershipMode":null,"humanSeatLimit":null,
                       "activeHumanSeats":1,"reservedHumanSeats":0,"availableHumanSeats":0,
                       "teamAdmissionAvailable":false,"siteBuildAvailable":false,"revision":null,
                       "effectiveFrom":null,"expiresAt":null,"addedField":true}""",
                )
                else -> error("Unexpected path")
            }
        }
        CoreApiClient(engine, origin).use { api ->
            val controller = CoreOrganizationSessionController(api) { 1 }
            controller.establish(source)
            assertEquals("solo", controller.organizations("next-1", 2).items.single().orgId)
            val first = controller.rename("New name", 2, "rename-1")
            assertEquals(first, controller.rename("New name", 2, "rename-1"))
            assertFalse(controller.entitlements().siteBuildAvailable)
        }
    }

    @Test
    fun rotationMustPreserveAbsoluteExpiry() = runTest {
        var verifies = 0
        val engine = MockEngine { request ->
            assertNull(request.headers["Origin"])
            when (request.url.encodedPath) {
                "/v1/auth/session" -> {
                    verifies++
                    respond(session("solo"))
                }
                "/v1/auth/session/rotate" -> respond("""{"token":"$target","expiresAt":9001}""")
                else -> error("Unexpected path")
            }
        }
        CoreApiClient(engine, origin).use { api ->
            val controller = CoreOrganizationSessionController(api) { 1 }
            controller.establish(source)
            assertFailsWith<CoreProtocolException> { controller.rotate() }
            assertEquals(1, verifies)
            assertIs<CoreOrganizationSessionController.State.SignedOut>(controller.state.value)
        }
    }

    @Test
    fun rejectsOversizedSessionResponse() = runTest {
        val engine = MockEngine { respond(session("solo") + " ".repeat(65_536)) }
        CoreApiClient(engine, origin).use { api ->
            val controller = CoreOrganizationSessionController(api) { 1 }
            assertFailsWith<CoreProtocolException> { controller.establish(source) }
            assertIs<CoreOrganizationSessionController.State.SignedOut>(controller.state.value)
        }
    }

    @Test
    fun credentialManagerFlowUsesCoreChallengeAndVerifiedSession() = runTest {
        val paths = mutableListOf<String>()
        val assertion = """{"id":"synthetic","response":{"userHandle":"fixture"}}"""
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            paths += path
            when (path) {
                "/v1/auth/passkeys/authentication/options" -> {
                    assertNull(request.headers["Authorization"])
                    assertEquals("{}", request.body.toByteArray().decodeToString())
                    respond("""{"challengeId":"$source","options":{"challenge":"fixture","rpId":"example.test"}}""")
                }
                "/v1/auth/passkeys/authentication/verify" -> {
                    assertNull(request.headers["Authorization"])
                    assertEquals("""{"challengeId":"$source","response":$assertion}""", request.body.toByteArray().decodeToString())
                    respond(replacement)
                }
                "/v1/auth/session" -> {
                    assertEquals("Bearer $target", request.headers["Authorization"])
                    respond(session("team"))
                }
                else -> error("Unexpected path")
            }
        }
        CoreApiClient(engine, origin).use { api ->
            val controller = CoreOrganizationSessionController(api) { 1 }
            val passkeys = object : PasskeyAuthenticator {
                override suspend fun getAssertion(requestJson: String): PasskeyResult {
                    assertEquals("""{"challenge":"fixture","rpId":"example.test"}""", requestJson)
                    return PasskeyResult.Assertion(assertion)
                }
            }
            assertEquals(CoreOrganizationSessionController.SignInResult.SignedIn, controller.signIn(passkeys))
            assertEquals(3, paths.size)
            assertEquals("team", assertIs<CoreOrganizationSessionController.State.Active>(controller.state.value).session.orgId)
        }
    }

    @Test
    fun lateOrganizationSuccessCannotReachTheNewScreen() = runTest {
        val requested = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val engine = MockEngine { request ->
            val old = request.headers["Authorization"] == "Bearer $source"
            when (request.url.encodedPath) {
                "/v1/auth/session" -> respond(session(if (old) "solo" else "team"))
                "/v1/auth/session/switch" -> respond(replacement)
                "/v1/organization" -> if (old) {
                    requested.complete(Unit)
                    release.await()
                    respond("""{"orgId":"solo","displayName":"Previous organization","version":1}""")
                } else respond("""{"orgId":"team","displayName":"Current organization","version":1}""")
                else -> error("Unexpected path")
            }
        }
        CoreApiClient(engine, origin).use { api ->
            val controller = CoreOrganizationSessionController(api) { 1 }
            val previous = controller.establish(source)
            val pending = async { assertFailsWith<CancellationException> { controller.profile() } }
            requested.await()
            val active = controller.switchOrganization("team")
            release.complete(Unit)
            pending.await()
            assertFalse(controller.commitIfCurrent(previous.epoch) { error("Stale page") })
            assertEquals("Current organization", controller.profile().displayName)
            assertTrue(controller.commitIfCurrent(active.epoch) { })
        }
    }

    @Test
    fun unavailablePasskeysDoNotSendVerify() = runTest {
        val engine = MockEngine { request ->
            assertEquals("/v1/auth/passkeys/authentication/options", request.url.encodedPath)
            respond("""{"challengeId":"$source","options":{"challenge":"fixture"}}""")
        }
        CoreApiClient(engine, origin).use { api ->
            val controller = CoreOrganizationSessionController(api) { 1 }
            val passkeys = object : PasskeyAuthenticator {
                override suspend fun getAssertion(requestJson: String) = PasskeyResult.Unavailable
            }
            assertEquals(CoreOrganizationSessionController.SignInResult.Unavailable, controller.signIn(passkeys))
            assertIs<CoreOrganizationSessionController.State.SignedOut>(controller.state.value)
        }
    }

    @Test
    fun scopesUseStableIdentityOrganizationAndEnvironment() {
        val otherBase = "https://test.example.test"
        val otherOrigin = CoreOrigin.allowlisted(otherBase, setOf(otherBase))
        val scope = CoreAccountScope.of(origin, "user-1", "solo")
        assertEquals(scope, CoreAccountScope.of(origin, "user-1", "solo"))
        assertNotEquals(scope, CoreAccountScope.of(otherOrigin, "user-1", "solo"))
        assertNotEquals(scope, CoreAccountScope.of(origin, "user-2", "solo"))
        assertNotEquals(scope, CoreAccountScope.of(origin, "user-1", "team"))
        assertFalse(scope.storageKey.contains("user-1"))
    }

    @Test
    fun restrictsOriginAndRefusesRedirects() = runTest {
        assertFailsWith<IllegalArgumentException> { CoreOrigin.allowlisted(base, emptySet()) }
        assertFailsWith<IllegalArgumentException> { CoreOrigin.allowlisted("http://127.0.0.1:8181", emptySet()) }
        assertEquals("http://127.0.0.1:8181", CoreOrigin.allowlisted("http://127.0.0.1:8181", emptySet(), true).value)
        var requests = 0
        val engine = MockEngine {
            requests++
            respond("", HttpStatusCode.TemporaryRedirect, io.ktor.http.headersOf("Location", "https://foreign.example.test"))
        }
        CoreApiClient(engine, origin).use { api ->
            val controller = CoreOrganizationSessionController(api) { 1 }
            assertEquals(307, assertFailsWith<CoreHttpException> { controller.establish(source) }.status)
            assertEquals(1, requests)
        }
    }
}
