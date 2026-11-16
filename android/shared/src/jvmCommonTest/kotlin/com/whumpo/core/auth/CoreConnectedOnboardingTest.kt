package com.whumpo.core.auth

import com.whumpo.core.api.CoreApiClient
import com.whumpo.core.api.CoreOnboardingStage
import com.whumpo.core.api.CoreOrigin
import com.whumpo.core.api.CoreProtocolException
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoreConnectedOnboardingTest {
    private val base = "https://setup.example.test"
    private val initial = "A".repeat(43)
    private val replacement = "E".repeat(43)

    private fun readiness(ready: Boolean, project: String? = null, org: String = "org-1") =
        """{"orgId":"$org","ready":$ready${project?.let { ",\"projectId\":\"$it\"" }.orEmpty()}}"""

    private fun profile(
        version: Int = 1, label: String? = null, name: String? = null, goal: String? = null, status: String = "draft",
        zone: String? = null, cadence: String? = null,
    ) = """{"version":$version,"status":"$status","organizationLabel":${label.wire()},"ownerName":${name.wire()},"goal":${goal.wire()},"timeZone":${zone.wire()},"briefingCadence":${cadence.wire()}}"""

    private fun String?.wire() = this?.let { JsonPrimitive(it).toString() } ?: "null"

    private fun summary(profile: String = profile(), ready: Boolean = false, workspace: String = "workspace-1") =
        """{"orgId":"org-1","stage":"${if (ready) "ready" else "verified"}","profile":$profile${if (ready) ",\"workspaceId\":\"$workspace\",\"projectId\":\"project-1\",\"agentId\":\"agent-1\",\"conversationId\":\"conversation-1\"" else ""}}"""

    private fun MockRequestHandleScope.json(body: String, version: String? = null) =
        if (version == null) respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        else respond(body, headers = headersOf(
            HttpHeaders.ContentType to listOf("application/json"),
            HttpHeaders.ETag to listOf(version),
        ))

    private fun MockRequestHandleScope.fail(status: Int) = respond("{}", HttpStatusCode.fromValue(status))

    private suspend fun fixture(
        role: String = "owner",
        routes: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
        test: suspend (CoreOrganizationSessionController, CoreConnectedOnboardingController) -> Unit,
    ) {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/v1/auth/session" -> {
                    val org = if (request.headers[HttpHeaders.Authorization] == "Bearer $initial") "org-1" else "org-2"
                    json("""{"sessionId":"session-$org","orgId":"$org","userId":"owner","role":"$role","expiresAt":60001}""")
                }
                "/v1/auth/session/switch" -> json("""{"token":"$replacement","expiresAt":60001}""")
                else -> {
                    assertEquals("setup.example.test", request.url.host)
                    assertTrue(request.url.parameters.isEmpty())
                    routes(request)
                }
            }
        }
        CoreApiClient(engine, CoreOrigin.allowlisted(base, setOf(base))).use { client ->
            val sessions = CoreOrganizationSessionController(client) { 1L }
            val active = sessions.establish(initial)
            test(sessions, CoreConnectedOnboardingController(sessions, active))
        }
    }

    private data class Sent(val method: HttpMethod, val key: String, val version: String?, val body: String)
    private suspend fun sent(request: HttpRequestData) = Sent(
        request.method, checkNotNull(request.headers["Idempotency-Key"]), request.headers[HttpHeaders.IfMatch],
        request.body.toByteArray().decodeToString(),
    )

    @Test
    fun nonownerReadsOnlyReadinessAndLegacyReadyUnlocks() = runTest {
        var ready = false
        var reads = 0
        fixture(role = "viewer", routes = { request ->
            assertEquals("/v1/organization/readiness", request.url.encodedPath)
            assertEquals(HttpMethod.Get, request.method)
            reads++
            json(readiness(ready))
        }) { _, controller ->
            controller.refresh()
            assertFalse(controller.state.value.canOpenWorkspace)
            assertFalse(controller.isOwner)
            assertNull(controller.state.value.profile)
            controller.answer(CoreSetupQuestion.Organization, "Forbidden")
            controller.skipRemaining()
            controller.createWorkspace()
            assertEquals(1, reads)
            ready = true
            controller.refresh()
            assertTrue(controller.state.value.canOpenWorkspace)
            assertNull(controller.state.value.readiness?.projectId)
        }
    }

    @Test
    fun closedReadinessRejectsWrongOrganizationAndMalformedWire() = runTest {
        var reply = "{}"
        fixture(routes = { json(reply) }) { _, controller ->
            listOf(
                readiness(true, org = "foreign"), readiness(false, "project-1"),
                """{"orgId":"org-1","ready":"true"}""", """{"orgId":"org-1","ready":true,"projectId":null}""",
                """{"orgId":"org-1","ready":true,"unknown":"x"}""", " ".repeat(1024) + readiness(true),
            ).forEach {
                reply = it
                controller.refresh()
                assertFalse(controller.state.value.canOpenWorkspace)
                assertEquals(CoreSetupNotice.Unavailable, controller.state.value.notice)
            }
        }
    }

    @Test
    fun profileRetryFreezesPatchVersionAndKeyThroughRejections() = runTest {
        val writes = mutableListOf<Sent>()
        var persisted = profile()
        var version = 1
        fixture(routes = { request ->
            when (request.url.encodedPath) {
                "/v1/organization/readiness" -> json(readiness(false))
                "/v1/onboarding" -> json(summary(persisted), "\"$version\"")
                "/v1/onboarding/profile" -> {
                    writes += sent(request)
                    when (writes.size) {
                        1 -> {
                            version = 2
                            persisted = profile(2, label = "Café")
                            fail(503)
                        }
                        2 -> fail(403)
                        3 -> fail(409)
                        else -> json(persisted, "\"2\"")
                    }
                }
                else -> error("Unexpected route")
            }
        }) { _, controller ->
            controller.refresh()
            assertEquals(CoreSetupQuestion.Organization, controller.state.value.question)
            controller.answer(CoreSetupQuestion.Organization, "  Cafe\u0301  ")
            controller.refresh()
            assertTrue(controller.state.value.pending)
            repeat(2) {
                controller.retry()
                assertTrue(controller.state.value.pending)
                assertEquals(1L, controller.state.value.profile?.version)
            }
            controller.answer(CoreSetupQuestion.Organization, "Different")
            controller.retry()
            assertFalse(controller.state.value.pending)
            assertEquals(CoreSetupQuestion.Owner, controller.state.value.question)
            assertEquals("Café", controller.state.value.profile?.organizationLabel)
            assertEquals(4, writes.size)
            assertEquals(1, writes.distinct().size)
            assertEquals(HttpMethod.Patch, writes.first().method)
            assertEquals("\"1\"", writes.first().version)
            assertEquals("""{"organizationLabel":"Café"}""", writes.first().body)
        }
    }

    @Test
    fun resumesServerAnswersAndExplicitlySkipsOptionalFieldsWithoutCreating() = runTest {
        val ownerOnly = profile(3, label = "Workshop", name = "Leslie")
        var persisted = ownerOnly
        var version = 3
        val writes = mutableListOf<Sent>()
        fixture(routes = { request ->
            when (request.url.encodedPath) {
                "/v1/organization/readiness" -> json(readiness(false))
                "/v1/onboarding" -> json(summary(persisted), "\"$version\"")
                "/v1/onboarding/profile" -> {
                    writes += sent(request)
                    version = 4
                    persisted = profile(4, label = "Workshop", name = "Leslie")
                    json(persisted, "\"4\"")
                }
                "/v1/onboarding/profile/skip" -> {
                    writes += sent(request)
                    version = 5
                    persisted = profile(5, label = "Workshop", name = "Leslie", status = "skipped")
                    json(persisted, "\"5\"")
                }
                else -> error("Unexpected provisioning")
            }
        }) { sessions, controller ->
            controller.refresh()
            assertEquals("Leslie", controller.state.value.profile?.ownerName)
            assertEquals(CoreSetupQuestion.Goal, controller.state.value.question)
            controller.answer(CoreSetupQuestion.Goal, null)
            assertEquals(CoreSetupQuestion.TimeZone, controller.state.value.question)
            assertEquals(setOf(CoreSetupQuestion.Goal), controller.state.value.deferred)
            controller.skipRemaining()
            assertNull(controller.state.value.question)
            assertFalse(controller.state.value.canOpenWorkspace)
            val active = sessions.state.value as CoreOrganizationSessionController.State.Active
            val cold = CoreConnectedOnboardingController(sessions, active)
            cold.refresh()
            assertEquals("Workshop", cold.state.value.profile?.organizationLabel)
            assertNull(cold.state.value.question)
            assertEquals(listOf("""{"goal":null}""", "{}"), writes.map(Sent::body))
            assertEquals(listOf("\"3\"", "\"4\""), writes.map(Sent::version))
        }
    }

    @Test
    fun strongTagsAreRequiredAndFirstConflictRequiresNewReview() = runTest {
        val skipped = profile(status = "skipped")
        var tag: String? = null
        var writes = 0
        fixture(routes = { request ->
            when (request.url.encodedPath) {
                "/v1/organization/readiness" -> json(readiness(false))
                "/v1/onboarding" -> json(summary(skipped), tag)
                "/v1/onboarding/complete" -> {
                    writes++
                    fail(409)
                }
                else -> error("Unexpected request")
            }
        }) { _, controller ->
            for (invalid in listOf(null, "1", "W/\"1\"", "\"2\"", "\"01\"")) {
                tag = invalid
                controller.refresh()
                assertNull(controller.state.value.profile)
            }
            tag = "\"1\""
            controller.refresh()
            assertEquals(CoreOnboardingStage.verified, controller.state.value.stage)
            controller.createWorkspace()
            assertTrue(controller.state.value.reviewRequired)
            assertFalse(controller.state.value.pending)
            controller.createWorkspace()
            assertEquals(1, writes)
            controller.refresh()
            controller.createWorkspace()
            assertEquals(2, writes)
        }
    }

    @Test
    fun interruptedCompletionStaysClosedUntilFreshReadinessMatchesCanonicalProject() = runTest {
        val skipped = profile(status = "skipped")
        var provisioned = false
        var readinessProject = "foreign-project"
        val writes = mutableListOf<Sent>()
        val routesSeen = mutableListOf<String>()
        fixture(routes = { request ->
            routesSeen += request.url.encodedPath
            when (request.url.encodedPath) {
                "/v1/organization/readiness" -> json(readiness(provisioned, readinessProject.takeIf { provisioned }))
                "/v1/onboarding" -> json(summary(skipped, provisioned), "\"1\"")
                "/v1/onboarding/complete" -> {
                    writes += sent(request)
                    if (writes.size == 1) {
                        provisioned = true
                        fail(503)
                    } else fail(409)
                }
                else -> error("Unexpected route")
            }
        }) { _, controller ->
            controller.refresh()
            assertTrue(writes.isEmpty())
            controller.createWorkspace()
            assertFalse(controller.state.value.canOpenWorkspace)
            controller.retry()
            assertTrue(controller.state.value.pending)
            assertEquals(1, writes.distinct().size)
            assertEquals(null, writes.first().version)
            assertEquals("{}", writes.first().body)
            controller.refresh()
            assertFalse(controller.state.value.canOpenWorkspace)
            assertTrue(controller.state.value.pending)
            readinessProject = "project-1"
            controller.refresh()
            assertTrue(controller.state.value.canOpenWorkspace)
            assertFalse(controller.state.value.pending)
            assertEquals("project-1", controller.state.value.readiness?.projectId)
            assertEquals(2, writes.size)
            assertEquals(listOf("/v1/onboarding", "/v1/organization/readiness"), routesSeen.takeLast(2))
        }
    }

    @Test
    fun acknowledgedCompletionVerifiesEveryProvisionedIdentityBeforeOpening() = runTest {
        var provisioned = false
        var workspace = "different-workspace"
        var writes = 0
        val skipped = profile(status = "skipped")
        fixture(routes = { request ->
            when (request.url.encodedPath) {
                "/v1/organization/readiness" -> json(readiness(provisioned, "project-1".takeIf { provisioned }))
                "/v1/onboarding" -> json(summary(skipped, provisioned, workspace), "\"1\"")
                "/v1/onboarding/complete" -> {
                    writes++
                    provisioned = true
                    json(summary(skipped, ready = true))
                }
                else -> error("Unexpected route")
            }
        }) { _, controller ->
            controller.refresh()
            controller.createWorkspace()
            assertTrue(controller.state.value.acknowledged)
            assertFalse(controller.state.value.canOpenWorkspace)
            workspace = "workspace-1"
            controller.retry()
            assertEquals(1, writes)
            assertTrue(controller.state.value.canOpenWorkspace)
        }
    }

    @Test
    fun staleSuccessAndUnauthorizedAfterOrganizationSwitchNeverPublishOrReplay() = runTest {
        for (denied in listOf(false, true)) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var writes = 0
            fixture(routes = { request ->
                when (request.url.encodedPath) {
                    "/v1/organization/readiness" -> {
                        if (request.headers[HttpHeaders.Authorization] == "Bearer $initial") json(readiness(false))
                        else json(readiness(true, org = "org-2"))
                    }
                    "/v1/onboarding" -> json(summary(), "\"1\"")
                    "/v1/onboarding/profile" -> {
                        writes++
                        entered.complete(Unit)
                        release.await()
                        if (denied) fail(401) else json(profile(2, label = "First organization"), "\"2\"")
                    }
                    else -> error("Unexpected route")
                }
            }) { sessions, previous ->
                previous.refresh()
                val oldRequest = launch { previous.answer(CoreSetupQuestion.Organization, "First organization") }
                entered.await()
                val second = sessions.switchOrganization("org-2")
                val next = CoreConnectedOnboardingController(sessions, second)
                next.refresh()
                release.complete(Unit)
                oldRequest.join()
                previous.retry()
                assertEquals(1, writes)
                assertEquals(second, sessions.state.value)
                assertTrue(next.state.value.canOpenWorkspace)
                assertNull(next.state.value.profile)
                previous.retire()
                assertEquals(CoreConnectedSetupState(), previous.state.value)
            }
        }
    }

    @Test
    fun malformedSummaryAndProfileTypesFailClosed() = runTest {
        val valid = summary()
        var reply = valid
        fixture(routes = { json(reply, "\"1\"") }) { sessions, _ ->
            val active = sessions.state.value as CoreOrganizationSessionController.State.Active
            listOf(
                valid.replace("\"version\":1", "\"version\":\"1\""),
                valid.replace("\"goal\":null", "\"goal\":true"),
                valid.replace("\"goal\":null", "\"goal\":[\"text\"]"),
                valid.replace("\"goal\":null,", ""),
                valid.replace("\"draft\"", "\"unknown\""),
                valid.dropLast(1) + ",\"projectId\":null}",
            ).forEach {
                reply = it
                assertFailsWith<CoreProtocolException> {
                    sessions.requestForActiveSession(active) { client, token -> client.onboardingSummary(token) }
                }
            }
        }
    }
}
