package com.whumpo.core.auth

import com.whumpo.core.api.CoreApiClient
import com.whumpo.core.api.CoreHttpException
import com.whumpo.core.api.CoreOrigin
import com.whumpo.core.api.CoreProtocolException
import com.whumpo.core.api.CoreRole
import com.whumpo.core.api.SecurityAccess
import com.whumpo.core.api.SecurityAdmission
import com.whumpo.core.api.SecurityMembership
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoreAccountSecurityControllerTest {
    private val rp = "core.example.test"
    private val origin = CoreOrigin.allowlisted("https://$rp", setOf("https://$rp"))
    private val enrollmentToken = "ag1." + "A".repeat(43)
    private val accountToken = "ag1." + "E".repeat(43)
    private val workspaceToken = "I".repeat(43)
    private val bootstrap = "U".repeat(43)
    private val invitation = "wi2." + "Y".repeat(43)
    private val selected = SecurityMembership("workspace-1", CoreRole.operator)
    private val passkeys = object : AccountPasskeys {
        override suspend fun register(options: String) = """{"id":"fixture-registration"}"""
        override suspend fun assert(options: String) = """{"id":"fixture-assertion"}"""
    }

    private fun state(
        enrollment: Boolean,
        profile: String = "password_totp",
        revision: Int = 1,
        methods: List<String> = emptyList(),
        offer: String = "null",
        phase: String = if (enrollment) "enrolling" else "ready",
    ): String {
        val purpose = if (enrollment) "enrollment" else "account"
        val accountId = if (enrollment) "null" else "\"account-1\""
        val candidateId = if (enrollment) "\"candidate-1\"" else "null"
        val intent = if (enrollment) revision.toString() else "null"
        val access = if (enrollment) "restricted" else "available"
        val setupProfile = if (enrollment) "\"$profile\"" else "null"
        return """{"version":1,"accountId":$accountId,"candidateId":$candidateId,
            "purpose":"$purpose","phase":"$phase","generation":1,"inventoryRevision":$revision,
            "attemptId":null,"attemptRevision":null,"intentRevision":$intent,"methods":[${methods.joinToString()}],
            "recovery":{"kind":"none","generation":0,"remainingCodes":null,"offer":$offer},
            "approvedMethod":null,"freshUntil":null,"expiresAt":9000,
            "workspaceAccess":"$access","setupProfile":$setupProfile}"""
    }

    private fun grant(enrollment: Boolean, snapshot: String = state(enrollment)): String {
        val token = if (enrollment) enrollmentToken else accountToken
        val purpose = if (enrollment) "enrollment" else "account"
        return """{"authority":{"kind":"account_grant","token":"$token","purpose":"$purpose","expiresAt":9000},"state":$snapshot}"""
    }

    private fun method(kind: String, id: String, verified: Boolean = true): String =
        """{"id":"$id","kind":"$kind","state":"staged","version":1,"verified":$verified,"label":null,"createdAt":1}"""

    private fun offer(kind: String, saved: Boolean) =
        """{"id":"offer-1","kind":"$kind","generation":2,"saved":$saved,"expiresAt":9000}"""

    private fun requestOptions(rpId: String = rp) =
        """{"challenge":"fixture","rpId":"$rpId","userVerification":"required"}"""

    @Test
    fun invitationPasswordEnrollmentRequiresVerifiedTotpSavedOfferAndExplicitScopedSession() = runTest {
        var revision = 1
        var pendingOffer = "null"
        val methods = mutableListOf<String>()
        var finalProof = false
        var sessionReads = 0
        val engine = MockEngine { request ->
            val path = request.url.encodedPath.substringAfter("/v1/account-security/")
            val bearer = request.headers["Authorization"]
            val key = request.headers["Idempotency-Key"]
            val raw = request.body.toByteArray().decodeToString()
            val body = raw.takeIf(String::isNotBlank)?.let { Json.parseToJsonElement(it).jsonObject }

            if (body != null && path != "/v1/auth/session") assertEquals("1", body["version"]?.jsonPrimitive?.content)
            val mutationRoutes = setOf("enrollment/start", "password/stage", "totp/prepare", "totp/verify",
                "recovery-material/prepare", "recovery-material/save", "enrollment/complete/verify")
            if (path in mutationRoutes) assertNotNull(key) else assertNull(key)
            when (path) {
                "invitations/preview" -> {
                    assertNull(bearer)
                    assertEquals(invitation, body?.get("invitationCode")?.jsonPrimitive?.content)
                    respond("""{"invitationId":"invite-1","orgId":"workspace-1","organizationName":"Example","role":"operator","expiresAt":"2027-01-01T00:00:00.000Z"}""")
                }
                "enrollment/start" -> {
                    assertNull(bearer)
                    assertEquals("password_totp", body?.get("profile")?.jsonPrimitive?.content)
                    assertEquals("""{"invitationId":"invite-1","orgId":"workspace-1","role":"operator"}""", body?.get("admission")?.jsonObject?.get("consent").toString())
                    assertEquals(43, body?.get("resumeSecret")?.jsonPrimitive?.content?.length)
                    respond(grant(true))
                }
                "organizations", "session" -> {
                    assertTrue(finalProof)
                    assertEquals("Bearer $accountToken", bearer)
                    if (path == "organizations") respond("""{"items":[{"orgId":"workspace-1","role":"operator"}],"nextCursor":null}""")
                    else {
                        assertEquals("workspace-1", body?.get("orgId")?.jsonPrimitive?.content)
                        respond("""{"token":"$workspaceToken","expiresAt":9000}""")
                    }
                }
                "/v1/auth/session" -> {
                    assertEquals("Bearer $workspaceToken", bearer)
                    sessionReads++
                    respond("""{"sessionId":"session-1","orgId":"workspace-1","userId":"account-1","role":"operator","expiresAt":9000}""")
                }
                else -> {
                    assertEquals("Bearer $enrollmentToken", bearer)
                    if (body?.containsKey("expectedIntentRevision") == true) {
                        assertEquals(revision.toString(), body["expectedIntentRevision"]?.jsonPrimitive?.content)
                        assertEquals("null", body["expectedAttemptRevision"].toString())
                    }
                    val receipt = """{"recoveryGeneration":2}"""
                    when (path) {
                        "state" -> respond(state(true, revision = revision, methods = methods, offer = pendingOffer))
                        "password/stage" -> {
                            methods += method("password", "password-1")
                            revision++
                            respond(receipt)
                        }
                        "totp/prepare" -> {
                            methods += method("totp", "totp:1", verified = false)
                            revision++
                            respond("""{"receipt":{"factorId":"totp:1","recoveryGeneration":0},"secret":"SYNTHETIC","otpauthUri":null}""")
                        }
                        "totp/verify" -> {
                            assertEquals("totp:1", body?.get("factorId")?.jsonPrimitive?.content)
                            methods[1] = method("totp", "totp:1")
                            revision++
                            respond(receipt)
                        }
                        "recovery-material/prepare" -> {
                            assertEquals("codes", body?.get("kind")?.jsonPrimitive?.content)
                            pendingOffer = offer("codes", false)
                            revision++
                            respond("""{"receipt":{"offerId":"offer-1","recoveryGeneration":2},"document":"SYNTHETIC DOCUMENT"}""")
                        }
                        "recovery-material/save" -> {
                            assertEquals("offer-1", body?.get("offerId")?.jsonPrimitive?.content)
                            assertEquals("true", body?.get("saved")?.jsonPrimitive?.content)
                            pendingOffer = offer("codes", true)
                            revision++
                            respond(receipt)
                        }
                        "enrollment/complete/options" -> {
                            assertEquals("offer-1", body?.get("offerId")?.jsonPrimitive?.content)
                            respond("""{"challengeId":"final:1","method":"password_totp","options":null}""")
                        }
                        "enrollment/complete/verify" -> {
                            assertEquals("password_totp", body?.get("proof")?.jsonObject?.get("method")?.jsonPrimitive?.content)
                            finalProof = true
                            respond(grant(false))
                        }
                        else -> error("Unexpected route $path")
                    }
                }
            }
        }
        CoreApiClient(engine, origin).use { api ->
            val workspace = CoreOrganizationSessionController(api) { 1 }
            val controller = CoreAccountSecurityController(api, workspace, rp) { 1 }
            val preview = controller.previewInvitation(invitation)
            controller.enroll(SecurityProfile.PasswordTotp, SecurityAdmission.PersonInvitation(
                invitation, preview.invitationId, preview.orgId, preview.role,
            ))
            assertFailsWith<IllegalStateException> { controller.organizations() }
            assertFailsWith<IllegalStateException> { controller.chooseOrganization(selected) }
            controller.stagePassword("example-user", "synthetic example password")
            val totp = controller.prepareTotp()
            assertFalse(controller.state.value!!.methods.last().verified)
            controller.verifyTotp(totp.factorId, "123456")
            assertTrue(controller.state.value!!.methods.last().verified)
            val document = controller.prepareRecovery()
            assertFailsWith<CoreProtocolException> { controller.complete(passkeys, "example password", "234567") }
            controller.confirmSaved(document.offerId, document.generation)
            assertTrue(controller.complete(passkeys, "example password", "234567"))
            assertEquals(SecurityAccess.Available, controller.state.value?.workspaceAccess)
            assertEquals(CoreOrganizationSessionController.State.SignedOut, workspace.state.value)
            assertEquals(listOf(selected), controller.organizations().items)
            controller.chooseOrganization(selected)
            assertEquals(1, sessionReads)
            assertEquals("workspace-1", (workspace.state.value as CoreOrganizationSessionController.State.Active).session.orgId)
        }
    }

    @Test
    fun passkeyEnrollmentUsesTwoVerifiedRegistrationsAndSavedKit() = runTest {
        var revision = 1
        val methods = mutableListOf<String>()
        var pendingOffer = "null"
        var firstRegistrationKey: String? = null
        var registrationPrompts = 0
        val requestOptions = """{"challenge":"fixture","rp":{"id":"$rp"},
            "authenticatorSelection":{"residentKey":"required","requireResidentKey":true,"userVerification":"required"}}"""
        val engine = MockEngine { request ->
            val path = request.url.encodedPath.substringAfter("/v1/account-security/")
            val raw = request.body.toByteArray().decodeToString()
            val body = raw.takeIf(String::isNotBlank)?.let { Json.parseToJsonElement(it).jsonObject }
            val receipt = """{"recoveryGeneration":2}"""
            when (path) {
                "enrollment/start" -> respond(grant(true, state(true, profile = "passkeys")))
                "state" -> respond(state(true, "passkeys", revision, methods, pendingOffer))
                "passkeys/options" -> {
                    assertEquals(revision.toString(), body?.get("expectedIntentRevision")?.jsonPrimitive?.content)
                    assertNull(request.headers["Idempotency-Key"])
                    respond("""{"challengeId":"register:$revision","options":$requestOptions}""")
                }
                "passkeys/verify" -> {
                    val key = assertNotNull(request.headers["Idempotency-Key"])
                    if (key == firstRegistrationKey) {
                        assertEquals("register:1", body?.get("challengeId")?.jsonPrimitive?.content)
                        assertEquals("registration-1", body?.get("response")?.jsonObject?.get("id")?.jsonPrimitive?.content)
                        respond(receipt)
                    } else {
                        assertEquals("register:$revision", body?.get("challengeId")?.jsonPrimitive?.content)
                        assertEquals("registration-$revision", body?.get("response")?.jsonObject?.get("id")?.jsonPrimitive?.content)
                        methods += method("passkey", "passkey-$revision")
                        revision++
                        if (firstRegistrationKey == null) {
                            firstRegistrationKey = key
                            respond("", HttpStatusCode.ServiceUnavailable)
                        } else respond(receipt)
                    }
                }
                "recovery-material/prepare" -> {
                    assertEquals(2, methods.size)
                    assertEquals("kit", body?.get("kind")?.jsonPrimitive?.content)
                    pendingOffer = offer("kit", false)
                    revision++
                    respond("""{"receipt":{"offerId":"offer-1","recoveryGeneration":2},"document":"SYNTHETIC KIT"}""")
                }
                "recovery-material/save" -> {
                    pendingOffer = offer("kit", true)
                    revision++
                    respond(receipt)
                }
                "enrollment/complete/options" ->
                    respond("""{"challengeId":"complete:1","method":"passkey_uv","options":${requestOptions()}}""")
                "enrollment/complete/verify" -> {
                    assertEquals("passkey_uv", body?.get("proof")?.jsonObject?.get("method")?.jsonPrimitive?.content)
                    respond(grant(false))
                }
                else -> error("Unexpected route $path")
            }
        }
        CoreApiClient(engine, origin).use { api ->
            val workspace = CoreOrganizationSessionController(api) { 1 }
            val controller = CoreAccountSecurityController(api, workspace, rp) { 1 }
            val separatePasskeys = object : AccountPasskeys {
                override suspend fun register(options: String): String {
                    registrationPrompts++
                    return """{"id":"registration-$revision"}"""
                }
                override suspend fun assert(options: String) = """{"id":"final-proof"}"""
            }
            controller.enroll(SecurityProfile.Passkeys, SecurityAdmission.Bootstrap(bootstrap))
            assertFailsWith<CoreHttpException> { controller.registerPasskey("First passkey", separatePasskeys) }
            assertTrue(controller.registerPasskey("First passkey", separatePasskeys))
            assertEquals(1, registrationPrompts)
            assertTrue(controller.registerPasskey("Backup passkey", separatePasskeys))
            assertEquals(2, registrationPrompts)
            val document = controller.prepareRecovery()
            controller.confirmSaved(document.offerId, document.generation)
            assertTrue(controller.complete(separatePasskeys, "", ""))
            assertEquals(SecurityPurpose.Account, controller.state.value?.purpose)
            assertEquals(CoreOrganizationSessionController.State.SignedOut, workspace.state.value)
        }
    }

    @Test
    fun enrollmentStartReplayReusesRequestKeyAndResumeSecret() = runTest {
        var requestKey: String? = null
        var resumeSecret: String? = null
        val engine = MockEngine { request ->
            val path = request.url.encodedPath.substringAfter("/v1/account-security/")
            val body = Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
            val secret = body["resumeSecret"]?.jsonPrimitive?.content
            assertNull(request.headers["Authorization"])
            when (path) {
                "enrollment/start" -> {
                    val key = assertNotNull(request.headers["Idempotency-Key"])
                    if (requestKey == null) {
                        requestKey = key
                        resumeSecret = secret
                        respond("", HttpStatusCode.ServiceUnavailable)
                    } else {
                        assertEquals(requestKey, key)
                        assertEquals(resumeSecret, secret)
                        respond("""{"authority":null,"state":null}""")
                    }
                }
                "enrollment/resume" -> {
                    assertNull(request.headers["Idempotency-Key"])
                    assertEquals(requestKey, body["startRequestKey"]?.jsonPrimitive?.content)
                    assertEquals(resumeSecret, secret)
                    respond(grant(true))
                }
                else -> error("Unexpected route $path")
            }
        }
        CoreApiClient(engine, origin).use { api ->
            val workspace = CoreOrganizationSessionController(api) { 1 }
            val controller = CoreAccountSecurityController(api, workspace, rp) { 1 }
            assertFailsWith<CoreHttpException> {
                controller.enroll(SecurityProfile.PasswordTotp, SecurityAdmission.Bootstrap(bootstrap))
            }
            controller.enroll(SecurityProfile.PasswordTotp, SecurityAdmission.Bootstrap(bootstrap))
            assertEquals(SecurityPurpose.Enrollment, controller.state.value?.purpose)
        }
    }

    @Test
    fun passwordSignInRequiresTotpBeforeIssuingAnAccountGrant() = runTest {
        val engine = MockEngine { request ->
            val path = request.url.encodedPath.substringAfter("/v1/account-security/")
            val body = Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
            assertNull(request.headers["Authorization"])
            assertNull(request.headers["Idempotency-Key"])
            when (path) {
                "password/login/start" -> {
                    assertEquals("fixture", body["username"]?.jsonPrimitive?.content)
                    respond("""{"challengeId":"password:1","expiresAt":9000}""")
                }
                "password/login/verify" -> {
                    assertEquals("password:1", body["challengeId"]?.jsonPrimitive?.content)
                    assertEquals("123456", body["totp"]?.jsonPrimitive?.content)
                    assertNull(body["password"])
                    respond(grant(false))
                }
                else -> error("Unexpected route $path")
            }
        }
        CoreApiClient(engine, origin).use { api ->
            val workspace = CoreOrganizationSessionController(api) { 1 }
            val controller = CoreAccountSecurityController(api, workspace, rp) { 1 }
            controller.loginPassword("fixture", "synthetic")
            assertNull(controller.state.value)
            assertEquals(CoreOrganizationSessionController.State.SignedOut, workspace.state.value)
            controller.loginTotp("123456")
            assertEquals(SecurityPurpose.Account, controller.state.value?.purpose)
            assertEquals(CoreOrganizationSessionController.State.SignedOut, workspace.state.value)
        }
    }

    @Test
    fun currentAccountAcceptsReviewedInvitationWithoutSelectingOrganization() = runTest {
        var accepted = false
        val engine = MockEngine { request ->
            val authorization = request.headers["Authorization"]
            assertNull(request.headers["Idempotency-Key"])
            val path = request.url.encodedPath.substringAfter("/v1/account-security/")
            when (path) {
                "passkey/login/options" -> {
                    assertNull(authorization)
                    respond("""{"challengeId":"login:1","options":${requestOptions()}}""")
                }
                "passkey/login/verify" -> {
                    assertNull(authorization)
                    respond(grant(false))
                }
                "invitations/preview" -> {
                    assertNull(authorization)
                    respond("""{"invitationId":"invite-1","orgId":"workspace-1","organizationName":"Example","role":"operator","expiresAt":"2027-01-01T00:00:00.000Z"}""")
                }
                "invitations/accept" -> {
                    assertEquals("Bearer $accountToken", authorization)
                    val body = Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
                    assertEquals(setOf("version", "invitationCode", "consent"), body.keys)
                    assertEquals(invitation, body["invitationCode"]?.jsonPrimitive?.content)
                    assertEquals("""{"invitationId":"invite-1","orgId":"workspace-1","role":"operator"}""", body["consent"].toString())
                    accepted = true
                    respond("""{"invitationId":"invite-1","orgId":"workspace-1","membership":{"userId":"account-1","role":"operator","version":1},"acceptedAt":"2026-11-16T00:00:00.000Z"}""")
                }
                "state" -> {
                    assertTrue(accepted)
                    assertEquals("Bearer $accountToken", authorization)
                    respond(state(false))
                }
                "organizations" -> {
                    assertTrue(accepted)
                    assertEquals("Bearer $accountToken", authorization)
                    respond("""{"items":[{"orgId":"workspace-1","role":"operator"}],"nextCursor":null}""")
                }
                else -> error("Unexpected route $path")
            }
        }
        CoreApiClient(engine, origin).use { api ->
            val workspace = CoreOrganizationSessionController(api) { 1 }
            val controller = CoreAccountSecurityController(api, workspace, rp) { 1 }
            assertTrue(controller.loginPasskey(passkeys))
            val preview = controller.previewInvitation(invitation)
            controller.acceptInvitation(SecurityAdmission.PersonInvitation(invitation, preview.invitationId, preview.orgId, preview.role))
            assertEquals(SecurityPurpose.Account, controller.state.value?.purpose)
            assertEquals(CoreOrganizationSessionController.State.SignedOut, workspace.state.value)
            assertEquals(listOf(selected), controller.organizations().items)
        }
    }

    @Test
    fun onePasskeySignsInButDifferentWorkspaceIdentityIsRejected() = runTest {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/v1/account-security/passkey/login/options" -> {
                    assertNull(request.headers["Authorization"])
                    assertNull(request.headers["Idempotency-Key"])
                    respond("""{"challengeId":"login:1","options":${requestOptions()}}""")
                }
                "/v1/account-security/passkey/login/verify" ->
                    respond(grant(false, state(false, phase = "legacy")))
                "/v1/account-security/session" ->
                    respond("""{"token":"$workspaceToken","expiresAt":9000}""")
                "/v1/auth/session" ->
                    respond("""{"sessionId":"session-1","orgId":"workspace-1","userId":"another-account","role":"operator","expiresAt":9000}""")
                else -> error("Unexpected route")
            }
        }
        CoreApiClient(engine, origin).use { api ->
            val workspace = CoreOrganizationSessionController(api) { 1 }
            val controller = CoreAccountSecurityController(api, workspace, rp) { 1 }
            assertTrue(controller.loginPasskey(passkeys))
            assertEquals(SecurityPurpose.Account, controller.state.value?.purpose)
            assertFailsWith<CoreProtocolException> { controller.chooseOrganization(selected) }
            assertEquals(CoreOrganizationSessionController.State.SignedOut, workspace.state.value)
        }
    }

    @Test
    fun credentialManagerNeverSeesMismatchedRpOptions() = runTest {
        val engine = MockEngine { request ->
            assertEquals("/v1/account-security/passkey/login/options", request.url.encodedPath)
            respond("""{"challengeId":"login:1","options":${requestOptions("other.example.test")}}""")
        }
        CoreApiClient(engine, origin).use { api ->
            val workspace = CoreOrganizationSessionController(api) { 1 }
            val controller = CoreAccountSecurityController(api, workspace, rp) { 1 }
            val rejecting = object : AccountPasskeys {
                override suspend fun register(options: String): String = error("Unexpected credential prompt")
                override suspend fun assert(options: String): String = error("Unexpected credential prompt")
            }
            assertFailsWith<CoreProtocolException> { controller.loginPasskey(rejecting) }
            assertNull(controller.state.value)
        }
    }

    @Test
    fun clearedCredentialPromptCannotSendLateProof() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val engine = MockEngine { request ->
            assertEquals("/v1/account-security/passkey/login/options", request.url.encodedPath)
            respond("""{"challengeId":"login:1","options":${requestOptions()}}""")
        }
        CoreApiClient(engine, origin).use { api ->
            val workspace = CoreOrganizationSessionController(api) { 1 }
            val controller = CoreAccountSecurityController(api, workspace, rp) { 1 }
            val delayed = object : AccountPasskeys {
                override suspend fun register(options: String): String = error("Unexpected registration")
                override suspend fun assert(options: String): String {
                    started.complete(Unit)
                    release.await()
                    return """{"id":"late-fixture"}"""
                }
            }
            val attempt = async { assertFailsWith<CancellationException> { controller.loginPasskey(delayed) } }
            started.await()
            controller.clear()
            release.complete(Unit)
            attempt.await()
            assertNull(controller.state.value)
            assertEquals(CoreOrganizationSessionController.State.SignedOut, workspace.state.value)
        }
    }
}
