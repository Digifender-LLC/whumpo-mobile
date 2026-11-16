package com.whumpo.core.auth

import com.whumpo.core.api.CoreApiClient
import com.whumpo.core.api.CoreHttpException
import com.whumpo.core.api.CoreOrigin
import com.whumpo.core.api.CoreProtocolException
import com.whumpo.core.api.RecoveryLossMode
import com.whumpo.core.api.SavedRecoveryProof
import com.whumpo.core.api.SecurityAccess
import com.whumpo.core.api.SecurityPhase
import com.whumpo.core.api.SecurityProfile
import com.whumpo.core.api.SecurityPurpose
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoreAccountRecoveryTest {
    private val rp = "account.example.test"
    private val origin = CoreOrigin.allowlisted("https://$rp", setOf("https://$rp"))
    private val initialGrant = "ag1." + "A".repeat(43)
    private val securedGrant = "ag1." + "E".repeat(43)
    private val accountGrant = "ag1." + "I".repeat(43)
    private val invitation = "U".repeat(43)
    private val digest = "a".repeat(64)
    private val registration = """{"challengeId":"register-1","options":{"challenge":"fixture","rp":{"id":"$rp"},
        "authenticatorSelection":{"residentKey":"required","requireResidentKey":true,"userVerification":"required"}}}"""
    private val assertion = """{"challengeId":"final-1","method":"passkey_uv","options":{"challenge":"fixture",
        "rpId":"$rp","userVerification":"required"}}"""
    private val passwordChallenge = """{"challengeId":"final-1","method":"password_totp","options":null}"""

    private fun snapshot(
        purpose: String = "recovery",
        phase: String = "ready",
        profile: String? = null,
        generation: Int = 4,
        revision: Int = 1,
        methods: List<String> = emptyList(),
        kind: String = "kit",
        bundle: Int = 3,
        offer: String = "null",
        approved: String? = null,
        workspaceAccess: String? = null,
    ): String {
        val restricted = purpose != "account"
        val attempt = if (restricted) literal("attempt-1") else "null"
        val attemptRevision = if (restricted) "$revision" else "null"
        val access = workspaceAccess ?: if (restricted) "restricted" else "available"
        val inventory = methods.joinToString()
        return """{"version":1,"accountId":"account-1","candidateId":null,"purpose":"$purpose","phase":"$phase",
            "generation":$generation,"inventoryRevision":$revision,"attemptId":$attempt,"attemptRevision":$attemptRevision,
            "intentRevision":null,"methods":[$inventory],"recovery":{"kind":"$kind",
            "generation":$bundle,"remainingCodes":null,"offer":$offer},"approvedMethod":${literal(approved)},
            "freshUntil":null,"expiresAt":9000,"workspaceAccess":"$access","setupProfile":${literal(profile)}}"""
    }

    private fun literal(value: String?): String = value?.let { "\"$it\"" } ?: "null"

    private fun grant(
        state: String, token: String = initialGrant, purpose: String = "recovery", operation: String? = null,
        receiptRevision: Int? = null, priorGeneration: Int = 4,
    ): String {
        val acknowledgement = operation?.let { ""","receipt":${receipt(it, state, receiptRevision, priorGeneration)}""" }.orEmpty()
        return """{"authority":{"kind":"account_grant","token":"$token","purpose":"$purpose","expiresAt":9000},"state":$state$acknowledgement}"""
    }

    private fun receipt(operation: String, snapshot: String, receiptRevision: Int? = null, priorGeneration: Int = 4): String {
        val state = Json.parseToJsonElement(snapshot).jsonObject
        val generation = state.field("generation")
        val revision = receiptRevision?.toString() ?: state.field("inventoryRevision")
        val bundle = state.getValue("recovery").jsonObject.field("generation")
        return """{"operation":"$operation","accountId":"account-1","candidateId":null,"attemptId":"attempt-1",
            "operationId":"00000000-0000-0000-0000-000000000001","confirmedAt":1,
            "generationBefore":$priorGeneration,"generationAfter":$generation,"inventoryRevision":$revision,"recoveryGeneration":$bundle}"""
    }

    private fun method(id: String, kind: String, status: String = "active", verified: Boolean = true) =
        """{"id":"$id","kind":"$kind","state":"$status","version":1,"verified":$verified,"label":null,"createdAt":1}"""

    private fun offer(kind: String, saved: Boolean) =
        """{"id":"offer-1","kind":"$kind","generation":4,"saved":$saved,"expiresAt":9000}"""

    private fun reviewedMethod(id: String, kind: String) = """{"id":"$id","version":1,"kind":"$kind","createdAt":1}"""

    private fun review(profile: String, mode: String, lost: List<String>, retained: List<String>, inventory: List<String>) =
        """{"id":"review-1","digest":"$digest","completionProfile":"$profile","mode":"$mode",
            "lostIds":[${lost.joinToString { literal(it) }}],"retainedIds":[${retained.joinToString { literal(it) }}],
            "inventory":[${inventory.joinToString()}]}"""

    private fun current(review: String = "null", generation: Int = 4, revision: Int = 1) =
        """{"attemptId":"attempt-1","attemptRevision":$revision,"generation":$generation,"inventoryRevision":$revision,"review":$review}"""

    private fun JsonObject.field(name: String) = getValue(name).jsonPrimitive.content

    private fun payload(raw: ByteArray) = raw.decodeToString().takeIf(String::isNotBlank)
        ?.let { Json.parseToJsonElement(it).jsonObject } ?: JsonObject(emptyMap())

    private class ExactRequest {
        var key: String? = null
            private set
        private var body: String? = null

        fun record(newKey: String?, newBody: JsonObject): Boolean {
            if (key != null) {
                assertEquals(key, newKey)
                assertEquals(body, newBody.toString())
                return false
            }
            key = assertNotNull(newKey)
            body = newBody.toString()
            return true
        }
    }

    @Test
    fun reservedReplayUsesExactKeyAndServerAttemptWithoutGrantingMembership() = runTest {
        val start = ExactRequest()
        var resumeCalls = 0
        val methods = listOf(method("password-1", "password", "staged"), method("totp-1", "totp", "staged"))
        val enrollment = snapshot("enrollment", "enrolling", "password_totp", methods = methods, offer = offer("codes", true))
        val engine = MockEngine { request ->
            val route = request.url.encodedPath.substringAfter("/v1/account-security/")
            val header = request.headers["Authorization"]
            val key = request.headers["Idempotency-Key"]
            val body = payload(request.body.toByteArray())
            when (route) {
                "enrollment/reserved/start" -> {
                    assertNull(header)
                    assertEquals(setOf("version", "invitationToken", "profile"), body.keys)
                    assertEquals(invitation, body.field("invitationToken"))
                    assertEquals("password_totp", body.field("profile"))
                    if (start.record(key, body)) respond("", HttpStatusCode.ServiceUnavailable)
                    else respond("""{"authority":null,"state":null,"receipt":${receipt("enrollment.reserved.start", enrollment)}}""")
                }
                "enrollment/reserved/resume" -> {
                    assertNull(header)
                    assertNull(key)
                    assertEquals(setOf("version", "invitationToken", "startRequestKey"), body.keys)
                    assertEquals(start.key, body.field("startRequestKey"))
                    assertEquals(invitation, body.field("invitationToken"))
                    val result = grant(enrollment, purpose = "enrollment").dropLast(1)
                    resumeCalls++
                    val id = if (resumeCalls == 1) "another-attempt" else "attempt-1"
                    respond("""$result,"attemptId":"$id","attemptExpiresAt":9000}""")
                }
                "enrollment/complete/options" -> {
                    assertEquals("AccountGrant $initialGrant", header)
                    assertNull(key)
                    assertEquals("attempt-1", body.field("attemptId"))
                    assertEquals("1", body.field("expectedAttemptRevision"))
                    assertEquals("null", body.field("expectedIntentRevision"))
                    assertEquals("offer-1", body.field("offerId"))
                    respond(passwordChallenge)
                }
                "enrollment/complete/verify" -> {
                    assertEquals("AccountGrant $initialGrant", header)
                    assertNotNull(key)
                    assertEquals("234567", body.getValue("proof").jsonObject.field("totp"))
                    respond(grant(snapshot("account", generation = 5, revision = 3, kind = "codes", approved = "password_totp"),
                        accountGrant, "account", "enrollment.complete"))
                }
                "organizations" -> {
                    assertEquals("AccountGrant $accountGrant", header)
                    respond("""{"items":[],"nextCursor":null}""")
                }
                else -> error("Unexpected route $route")
            }
        }
        CoreApiClient(engine, origin).use { api ->
            val workspace = CoreOrganizationSessionController(api) { 1 }
            val controller = CoreAccountSecurityController(api, workspace, rp) { 1 }
            assertFailsWith<CoreHttpException> { controller.enrollReserved(SecurityProfile.PasswordTotp, invitation) }
            assertEquals(CoreAccountSecurityController.Retry.ReservedStart, controller.retry.value)
            assertFailsWith<CoreProtocolException> { controller.retryPending() }
            assertNull(controller.state.value)
            controller.retryPending()
            assertEquals("attempt-1", controller.state.value?.attemptId)
            assertNull(controller.retry.value)
            assertFailsWith<IllegalStateException> { controller.organizations() }
            assertTrue(controller.complete(NoPasskeys, "synthetic password", "234567"))
            assertEquals(SecurityPurpose.Account, controller.state.value?.purpose)
            assertTrue(controller.organizations().items.isEmpty())
            assertEquals(CoreOrganizationSessionController.State.SignedOut, workspace.state.value)
        }
    }

    @Test
    fun kitRecoveryValidatesReviewSavesReplacementAndUsesNewPasskeyProof() = runTest {
        val old = method("old", "passkey")
        val retained = method("retained", "passkey")
        val new = method("new", "passkey", "staged")
        val inventory = listOf(reviewedMethod("old", "passkey"), reviewedMethod("retained", "passkey"))
        val reviewed = review("passkeys", "some", listOf("old"), listOf("retained"), inventory)
        val initial = snapshot(methods = listOf(old, retained))
        val reviewRequest = ExactRequest()
        val secure = ExactRequest()
        val final = ExactRequest()
        var revision = 1
        var secured = false
        var methods = listOf(old, retained)
        var pendingOffer = "null"
        var registerPrompts = 0
        var finalPrompts = 0
        var secureCalls = 0
        var finalCalls = 0
        val passkeys = object : AccountPasskeys {
            override suspend fun register(options: String): String? {
                registerPrompts++
                return if (registerPrompts == 1) null else """{"id":"synthetic-registration"}"""
            }
            override suspend fun assert(options: String): String {
                finalPrompts++
                return """{"id":"synthetic-final-proof"}"""
            }
        }
        fun state() = snapshot(phase = if (secured) "recovering" else "ready", profile = "passkeys",
            generation = if (secured) 5 else 4, revision = revision, methods = methods,
            bundle = if (secured) 4 else 3, offer = pendingOffer)
        val engine = MockEngine { request ->
            val route = request.url.encodedPath.substringAfter("/v1/account-security/")
            val header = request.headers["Authorization"]
            val key = request.headers["Idempotency-Key"]
            val body = payload(request.body.toByteArray())
            when (route) {
                "recovery/start" -> assertNull(header)
                "recovery/secure" -> assertEquals("AccountGrant $initialGrant", header)
                else -> assertEquals("AccountGrant ${if (secured) securedGrant else initialGrant}", header)
            }
            when (route) {
                "recovery/start" -> {
                    assertNotNull(key)
                    assertEquals("synthetic-locator", body.field("locator"))
                    assertEquals("""{"kind":"kit","key":"synthetic-kit"}""", body["proof"].toString())
                    assertEquals(43, body.field("resumeSecret").length)
                    respond(grant(initial, operation = "recovery.start"))
                }
                "recovery/review/current" -> {
                    assertNull(key)
                    assertEquals(setOf("version"), body.keys)
                    respond(current())
                }
                "recovery/review" -> {
                    assertEquals("some", body.field("mode"))
                    assertEquals("""["old"]""", body["lostIds"].toString())
                    val wrong = review("passkeys", "some", listOf("retained"), listOf("old"), inventory)
                    val actual = if (reviewRequest.record(key, body)) wrong else reviewed
                    revision = 2
                    respond("""{"receipt":{"reviewId":"review-1","revision":2,"recoveryGeneration":3},"review":$actual}""")
                }
                "state" -> respond(state())
                "recovery-material/prepare" -> {
                    assertEquals("review-1", body.field("reviewId"))
                    assertEquals("kit", body.field("kind"))
                    pendingOffer = offer("kit", false)
                    revision++
                    respond("""{"receipt":{"offerId":"offer-1","recoveryGeneration":4},"document":"SYNTHETIC REPLACEMENT KIT"}""")
                }
                "recovery-material/save" -> {
                    assertEquals("offer-1", body.field("offerId"))
                    pendingOffer = offer("kit", true)
                    revision++
                    respond("""{"offerId":"offer-1","recoveryGeneration":4}""")
                }
                "recovery/secure" -> {
                    assertEquals("secure-account", body.field("confirmation"))
                    assertEquals(digest, body.field("reviewDigest"))
                    assertEquals("offer-1", body.field("offerId"))
                    assertEquals("4", body.field("generation"))
                    if (secure.record(key, body)) respond("""{"authority":null,"state":{}}""")
                    else {
                        secureCalls++
                        if (!secured) {
                            secured = true
                            methods = listOf(retained)
                            pendingOffer = "null"
                            revision++
                        }
                        val securedState = if (secureCalls == 1) state().replace("\"version\":1,\"verified\"", "\"version\":2,\"verified\"")
                            else state()
                        respond(grant(securedState, securedGrant, operation = "recovery.secure"))
                    }
                }
                "passkeys/options" -> {
                    assertEquals("attempt-1", body.field("attemptId"))
                    respond(registration)
                }
                "passkeys/verify" -> {
                    methods += new
                    revision++
                    respond("""{"factorId":"new","recoveryGeneration":4}""")
                }
                "recovery/complete/options" -> {
                    assertNull(key)
                    assertEquals("4", body.field("generation"))
                    assertEquals("5", body.field("expectedGeneration"))
                    assertEquals("$revision", body.field("expectedAttemptRevision"))
                    assertEquals("null", body.field("expectedIntentRevision"))
                    assertEquals("review-1", body.field("reviewId"))
                    assertEquals(digest, body.field("reviewDigest"))
                    assertEquals("passkey_uv", body.field("method"))
                    respond(assertion)
                }
                "recovery/complete/verify" -> {
                    assertEquals("""{"method":"passkey_uv","response":{"id":"synthetic-final-proof"}}""", body["proof"].toString())
                    val ready = snapshot("account", generation = 5, revision = revision + 1, bundle = 4,
                        approved = "passkey_uv", workspaceAccess = "none")
                    val operation = if (final.record(key, body)) null else "recovery.complete"
                    finalCalls++
                    val receiptRevision = if (finalCalls == 2) revision else null
                    respond(grant(ready, accountGrant, "account", operation, receiptRevision, priorGeneration = 5))
                }
                else -> error("Unexpected route $route")
            }
        }
        CoreApiClient(engine, origin).use { api ->
            val workspace = CoreOrganizationSessionController(api) { 1 }
            val controller = CoreAccountSecurityController(api, workspace, rp) { 1 }
            controller.startRecovery("synthetic-locator", SavedRecoveryProof.Kit("synthetic-kit"))
            assertFailsWith<IllegalStateException> { controller.organizations() }
            assertFailsWith<CoreProtocolException> {
                controller.reviewRecovery(RecoveryLossMode.Some, setOf("old"), SecurityProfile.Passkeys)
            }
            assertNull(controller.recoveryReview.value)
            controller.retryPending()
            assertEquals(listOf("old"), controller.recoveryReview.value?.lostIds)
            assertFailsWith<CoreProtocolException> { controller.secureRecovery() }
            val material = controller.prepareRecovery()
            controller.confirmSaved(material.offerId, material.generation)
            assertFailsWith<CoreProtocolException> { controller.secureRecovery() }
            assertEquals(SecurityPhase.Ready, controller.state.value?.phase)
            assertFailsWith<CoreProtocolException> { controller.retryPending() }
            assertEquals(SecurityPhase.Ready, controller.state.value?.phase)
            controller.retryPending()
            assertEquals(SecurityPhase.Recovering, controller.state.value?.phase)
            assertFalse(controller.state.value!!.replacementMethodsReady())
            assertFailsWith<CoreProtocolException> { controller.complete(passkeys, "", "") }
            assertFalse(controller.registerPasskey("Backup passkey", passkeys))
            assertNull(controller.retry.value)
            assertTrue(controller.registerPasskey("Backup passkey", passkeys))
            assertEquals(2, registerPrompts)
            assertTrue(controller.state.value!!.replacementMethodsReady())
            assertFailsWith<CoreProtocolException> { controller.complete(passkeys, "", "") }
            assertEquals(CoreAccountSecurityController.Retry.Complete, controller.retry.value)
            assertEquals(SecurityPurpose.Recovery, controller.state.value?.purpose)
            assertFailsWith<CoreProtocolException> { controller.retryPending() }
            assertEquals(SecurityPurpose.Recovery, controller.state.value?.purpose)
            controller.retryPending()
            assertEquals(1, finalPrompts)
            assertEquals(SecurityPurpose.Account, controller.state.value?.purpose)
            assertEquals(SecurityAccess.None, controller.state.value?.workspaceAccess)
            assertFailsWith<IllegalStateException> { controller.organizations() }
            assertNull(controller.recoveryReview.value)
            assertEquals(CoreOrganizationSessionController.State.SignedOut, workspace.state.value)
        }
    }

    @Test
    fun savedCodeResumesWithMatchingReviewAndRetainedPasswordProfile() = runTest {
        val factors = listOf(method("password-1", "password"), method("totp-1", "totp"))
        val reviewed = review("password_totp", "none", emptyList(), listOf("password-1", "totp-1"),
            listOf(reviewedMethod("password-1", "password"), reviewedMethod("totp-1", "totp")))
        val start = ExactRequest()
        val final = ExactRequest()
        var currentCalls = 0
        var proofCalls = 0
        var loginCalls = 0
        val recovery = snapshot(phase = "recovering", profile = "password_totp", generation = 5, revision = 5,
            methods = factors, kind = "codes", bundle = 4)
        val engine = MockEngine { request ->
            val route = request.url.encodedPath.substringAfter("/v1/account-security/")
            val header = request.headers["Authorization"]
            val key = request.headers["Idempotency-Key"]
            val body = payload(request.body.toByteArray())
            if (route == "recovery/start" || route.startsWith("password/login/")) assertNull(header)
            else assertEquals("AccountGrant $securedGrant", header)
            when (route) {
                "recovery/start" -> {
                    start.record(key, body)
                    assertEquals("""{"kind":"code","code":"synthetic-replacement-code"}""", body["proof"].toString())
                    respond(grant(recovery, securedGrant, operation = "recovery.start"))
                }
                "recovery/review/current" -> {
                    assertNull(key)
                    currentCalls++
                    val review = if (currentCalls == 2) reviewed.replace("\"version\":1", "\"version\":2") else reviewed
                    respond(current(review, 5, if (currentCalls == 1) 6 else 5))
                }
                "recovery/complete/options" -> {
                    assertEquals("review-1", body.field("reviewId"))
                    assertEquals("password_totp", body.field("method"))
                    respond(passwordChallenge)
                }
                "recovery/complete/verify" -> {
                    proofCalls++
                    val code = body.getValue("proof").jsonObject.field("totp")
                    if (proofCalls == 1) {
                        assertEquals("123456", code)
                        respond("", HttpStatusCode.Unauthorized)
                    } else {
                        assertEquals("725183", code)
                        if (final.record(key, body)) respond("", HttpStatusCode.ServiceUnavailable)
                        else respond("", HttpStatusCode.Unauthorized)
                    }
                }
                "state" -> if (proofCalls == 1) respond(recovery) else respond("", HttpStatusCode.Unauthorized)
                "password/login/start" -> respond("""{"challengeId":"login-1","expiresAt":9000}""")
                "password/login/verify" -> {
                    loginCalls++
                    val ready = snapshot("account", generation = 5, revision = 6, kind = "codes", bundle = 4,
                        approved = "password_totp")
                    val result = if (loginCalls == 1) ready.replace("account-1", "other-account") else ready
                    respond(grant(result, accountGrant, "account"))
                }
                else -> error("Unexpected route $route")
            }
        }
        CoreApiClient(engine, origin).use { api ->
            val workspace = CoreOrganizationSessionController(api) { 1 }
            val controller = CoreAccountSecurityController(api, workspace, rp) { 1 }
            assertFailsWith<CoreProtocolException> {
                controller.startRecovery("synthetic-locator", SavedRecoveryProof.Code("synthetic-replacement-code"))
            }
            assertNull(controller.state.value)
            assertFailsWith<CoreProtocolException> { controller.retryPending() }
            assertNull(controller.state.value)
            controller.retryPending()
            assertEquals(SecurityPhase.Recovering, controller.state.value?.phase)
            assertTrue(controller.state.value!!.replacementMethodsReady())
            assertFailsWith<AccountSecurityProofRejected> {
                controller.complete(NoPasskeys, "synthetic retained password", "123456")
            }
            assertNull(controller.retry.value)
            assertEquals(SecurityPurpose.Recovery, controller.state.value?.purpose)
            assertFailsWith<CoreHttpException> { controller.complete(NoPasskeys, "synthetic retained password", "725183") }
            assertFailsWith<RecoveryAcknowledgementLost> { controller.retryPending() }
            assertEquals(CoreAccountSecurityController.Retry.Complete, controller.retry.value)
            assertEquals(SecurityPurpose.Recovery, controller.state.value?.purpose)
            assertFailsWith<IllegalStateException> { controller.organizations() }
            controller.checkCompletionThroughSignIn()
            assertNull(controller.state.value)
            assertNull(controller.recoveryReview.value)
            assertNull(controller.retry.value)
            controller.loginPassword("fixture", "synthetic retained password")
            assertFailsWith<AccountSecurityIdentityMismatch> { controller.loginTotp("934527") }
            assertNull(controller.state.value)
            controller.loginPassword("fixture", "synthetic retained password")
            controller.loginTotp("934528")
            assertEquals("account-1", controller.state.value?.accountId)
            assertEquals(SecurityPurpose.Account, controller.state.value?.purpose)
            assertEquals(CoreOrganizationSessionController.State.SignedOut, workspace.state.value)
        }
    }

    @Test
    fun clearingRecoveryPreventsLateProviderResultFromSendingProof() = runTest {
        val inventory = listOf(reviewedMethod("old-1", "passkey"), reviewedMethod("old-2", "passkey"))
        val review = review("passkeys", "all", listOf("old-1", "old-2"), emptyList(), inventory)
        val methods = listOf(method("new-1", "passkey", "staged"), method("new-2", "passkey", "staged"))
        val opened = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val delayed = object : AccountPasskeys {
            override suspend fun register(options: String): String = error("Unexpected registration")
            override suspend fun assert(options: String): String {
                opened.complete(Unit)
                release.await()
                return """{"id":"late-fixture"}"""
            }
        }
        val engine = MockEngine { request ->
            when (request.url.encodedPath.substringAfter("/v1/account-security/")) {
                "recovery/start" -> respond(grant(snapshot(phase = "recovering", profile = "passkeys", methods = methods), operation = "recovery.start"))
                "recovery/review/current" -> respond(current(review))
                "recovery/complete/options" -> respond(assertion)
                else -> error("Late credential result sent proof")
            }
        }
        CoreApiClient(engine, origin).use { api ->
            val workspace = CoreOrganizationSessionController(api) { 1 }
            val controller = CoreAccountSecurityController(api, workspace, rp) { 1 }
            controller.startRecovery("synthetic-locator", SavedRecoveryProof.Kit("synthetic-replacement-kit"))
            val completion = async { assertFailsWith<CancellationException> { controller.complete(delayed, "", "") } }
            opened.await()
            controller.clear()
            release.complete(Unit)
            completion.await()
            assertNull(controller.state.value)
            assertNull(controller.retry.value)
            assertEquals(CoreOrganizationSessionController.State.SignedOut, workspace.state.value)
        }
    }

    @Test
    fun clearingReservedStartPreventsLateReplayFromResuming() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val enrollment = snapshot("enrollment", "enrolling", "password_totp")
        val engine = MockEngine { request ->
            assertEquals("/v1/account-security/enrollment/reserved/start", request.url.encodedPath)
            started.complete(Unit)
            release.await()
            respond("""{"authority":null,"state":null,"receipt":${receipt("enrollment.reserved.start", enrollment)}}""")
        }
        CoreApiClient(engine, origin).use { api ->
            val workspace = CoreOrganizationSessionController(api) { 1 }
            val controller = CoreAccountSecurityController(api, workspace, rp) { 1 }
            val pending = async {
                assertFailsWith<CancellationException> { controller.enrollReserved(SecurityProfile.PasswordTotp, invitation) }
            }
            started.await()
            controller.clear()
            release.complete(Unit)
            pending.await()
            assertNull(controller.state.value)
            assertNull(controller.retry.value)
        }
    }

    private object NoPasskeys : AccountPasskeys {
        override suspend fun register(options: String): String = error("Unexpected registration")
        override suspend fun assert(options: String): String = error("Unexpected passkey proof")
    }
}
