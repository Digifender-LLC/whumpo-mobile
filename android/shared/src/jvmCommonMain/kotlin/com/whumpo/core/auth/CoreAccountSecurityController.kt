package com.whumpo.core.auth

import com.whumpo.core.api.AccountSecurityState
import com.whumpo.core.api.CORE_MAX_VERSION
import com.whumpo.core.api.CoreApiClient
import com.whumpo.core.api.CoreHttpException
import com.whumpo.core.api.CoreProtocolException
import com.whumpo.core.api.CoreRole
import com.whumpo.core.api.CoreSessionToken
import com.whumpo.core.api.RecoveryKind
import com.whumpo.core.api.SecurityAccess
import com.whumpo.core.api.SecurityAdmission
import com.whumpo.core.api.SecurityAuthority
import com.whumpo.core.api.SecurityChallenge
import com.whumpo.core.api.SecurityFinalChallenge
import com.whumpo.core.api.SecurityGrant
import com.whumpo.core.api.SecurityInvitationAcceptance
import com.whumpo.core.api.SecurityInvitationPreview
import com.whumpo.core.api.SecurityMembership
import com.whumpo.core.api.SecurityMembershipPage
import com.whumpo.core.api.SecurityMethodStatus
import com.whumpo.core.api.SecurityPasswordChallenge
import com.whumpo.core.api.SecurityPhase
import com.whumpo.core.api.SecurityProfile
import com.whumpo.core.api.SecurityProof
import com.whumpo.core.api.SecurityPurpose
import com.whumpo.core.api.SecurityReceipt
import com.whumpo.core.api.SecurityRecoveryPreparation
import com.whumpo.core.api.SecurityStart
import com.whumpo.core.api.SecurityTotpPreparation
import com.whumpo.core.api.accountGrantBearer
import com.whumpo.core.api.accountSecurityJson
import com.whumpo.core.api.consent
import com.whumpo.core.api.coreIdentifier
import com.whumpo.core.api.personInvitationCode
import com.whumpo.core.api.securityOpaqueId
import com.whumpo.core.api.wire
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

/** Credential Manager JSON crosses this boundary unchanged. Null represents user cancellation. */
interface AccountPasskeys {
    suspend fun register(options: String): String?
    suspend fun assert(options: String): String?
}

class EnrollmentAcknowledgementLost : Exception("Enrollment result could not be recovered")

class PendingTotp internal constructor(val factorId: String, val secret: String)
class PendingRecoveryDocument internal constructor(val offerId: String, val generation: Long, val text: String)

/** Owns account and restricted grants for this activity. Workspace bearers belong to the workspace controller. */
class CoreAccountSecurityController(
    private val api: CoreApiClient,
    private val workspace: CoreOrganizationSessionController,
    private val rpId: String?,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val requests = Mutex()
    private val lock = Any()
    private var epoch = 0L
    private var grant: SecurityAuthority? = null
    private var login: SecurityPasswordChallenge? = null
    private var pendingEnrollment: EnrollmentStart? = null
    private var pendingMutation: PendingMutation? = null

    private class PendingMutation(
        val route: String,
        val authority: SecurityAuthority,
        val payload: JsonObject,
        val requestKey: String,
        var response: Any? = null,
    )

    private class EnrollmentStart(
        val requestKey: String,
        val resumeSecret: String,
        val profile: SecurityProfile,
        val admission: JsonObject,
    )
    private val mutableState = MutableStateFlow<AccountSecurityState?>(null)
    val state = mutableState.asStateFlow()

    init {
        require(rpId == null || Regex("[a-z0-9]+(?:[-.][a-z0-9]+)+").matches(rpId))
        require(!api.origin.value.startsWith("https://") || api.origin.value == "https://$rpId")
    }

    fun clear() {
        synchronized(lock) {
            epoch++
            grant = null
            login = null
            pendingEnrollment = null
            pendingMutation = null
            mutableState.value = null
            workspace.clear()
        }
    }

    suspend fun previewInvitation(code: String): SecurityInvitationPreview = exclusive { operation ->
        check(state.value == null || state.value?.purpose == SecurityPurpose.Account)
        require(personInvitationCode.matches(code))
        val preview = api.accountSecurity<SecurityInvitationPreview>("invitations/preview", body = body {
            put("invitationCode", code)
        })
        if (!coreIdentifier.matches(preview.invitationId) || !coreIdentifier.matches(preview.orgId) ||
            preview.role !in setOf(CoreRole.operator, CoreRole.viewer) ||
            Instant.parse(preview.expiresAt).toEpochMilli() <= now()) invalid()
        verify(operation)
        preview
    }

    suspend fun acceptInvitation(admission: SecurityAdmission.PersonInvitation) = exclusive { operation ->
        val authority = authority(operation, SecurityPurpose.Account)
        val accountId = state.value?.accountId ?: invalid()
        require(personInvitationCode.matches(admission.code))
        val result = api.accountSecurity<SecurityInvitationAcceptance>("invitations/accept", authority, body {
            put("invitationCode", admission.code)
            put("consent", admission.consent())
        })
        if (result.invitationId != admission.invitationId || result.orgId != admission.orgId ||
            result.membership.userId != accountId || result.membership.role != admission.role ||
            result.membership.version != 1) invalid()
        Instant.parse(result.acceptedAt)
        refresh(operation, authority)
    }

    suspend fun enroll(profile: SecurityProfile, admission: SecurityAdmission) = exclusive { operation ->
        check(state.value == null)
        val wireAdmission = admission.wire()
        val pending = synchronized(lock) {
            checkEpoch(operation)
            pendingEnrollment?.takeIf { it.admission == wireAdmission && it.profile == profile } ?: EnrollmentStart(
                key(),
                Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes)),
                profile,
                wireAdmission,
            ).also { pendingEnrollment = it }
        }
        val result = api.accountSecurity<SecurityStart>("enrollment/start", key = pending.requestKey, body = body {
            put("profile", accountSecurityJson.encodeToJsonElement(profile))
            put("admission", pending.admission)
            put("resumeSecret", pending.resumeSecret)
        })
        verify(operation)
        val credentials = when {
            result.authority != null && result.state != null -> SecurityGrant(result.authority, result.state)
            result.authority == null && result.state == null -> api.accountSecurity<SecurityGrant>("enrollment/resume", body = body {
                put("startRequestKey", pending.requestKey)
                put("admission", pending.admission)
                put("resumeSecret", pending.resumeSecret)
            })
            else -> invalid()
        }
        if (credentials.state.setupProfile != profile) invalid()
        install(operation, credentials, SecurityPurpose.Enrollment)
    }

    suspend fun loginPasskey(passkeys: AccountPasskeys): Boolean = exclusive { operation ->
        check(state.value == null)
        synchronized(lock) { checkEpoch(operation); login = null }
        val challenge = api.accountSecurity<SecurityChallenge>("passkey/login/options", body = body())
        val assertion = proof(operation, passkeys, challenge.challengeId, challenge.options, registration = false)
            ?: return@exclusive false
        val result = api.accountSecurity<SecurityGrant>("passkey/login/verify", body = body {
            put("challengeId", challenge.challengeId)
            put("response", assertion)
        })
        install(operation, result, SecurityPurpose.Account)
        true
    }

    suspend fun loginPassword(username: String, password: String) = exclusive { operation ->
        check(state.value == null)
        synchronized(lock) { checkEpoch(operation); login = null }
        val challenge = api.accountSecurity<SecurityPasswordChallenge>("password/login/start", body = body {
            put("username", username)
            put("password", password)
        })
        if (!securityOpaqueId.matches(challenge.challengeId) || challenge.expiresAt <= now()) invalid()
        verify(operation)
        synchronized(lock) { checkEpoch(operation); login = challenge }
    }

    suspend fun loginTotp(totp: String) = exclusive { operation ->
        val challenge = synchronized(lock) { checkEpoch(operation); checkNotNull(login) }
        check(challenge.expiresAt > now()) { "Sign-in challenge expired" }
        val result = api.accountSecurity<SecurityGrant>("password/login/verify", body = body {
            put("challengeId", challenge.challengeId)
            put("totp", totp)
        })
        install(operation, result, SecurityPurpose.Account)
    }

    suspend fun registerPasskey(label: String, passkeys: AccountPasskeys): Boolean = exclusive { operation ->
        val authority = enrollment(operation, SecurityProfile.Passkeys)
        val retry = pendingPayload(operation, authority, "passkeys/verify")
        if (retry != null) {
            mutation(operation, authority, "passkeys/verify", retry)
            return@exclusive true
        }
        val challenge = api.accountSecurity<SecurityChallenge>("passkeys/options", authority, revisionBody {
            put("label", label)
        })
        val registration = proof(operation, passkeys, challenge.challengeId, challenge.options, registration = true)
            ?: return@exclusive false
        mutation(operation, authority, "passkeys/verify", body {
            put("challengeId", challenge.challengeId)
            put("response", registration)
        })
        true
    }

    suspend fun stagePassword(username: String, password: String) = exclusive { operation ->
        val authority = enrollment(operation, SecurityProfile.PasswordTotp)
        mutation(operation, authority, "password/stage", revisionBody {
            put("username", username)
            put("password", password)
        })
        Unit
    }

    suspend fun prepareTotp(): PendingTotp = exclusive { operation ->
        val authority = enrollment(operation, SecurityProfile.PasswordTotp)
        val route = "totp/prepare"
        val (request, result) = keyed<SecurityTotpPreparation>(
            operation, authority, route, pendingPayload(operation, authority, route) ?: revisionBody(),
        )
        refresh(operation, authority)
        forget(operation, request)
        if (result.receipt.factorId?.let(securityOpaqueId::matches) != true || result.secret.isNullOrBlank()) invalid()
        PendingTotp(result.receipt.factorId, result.secret)
    }

    suspend fun verifyTotp(factorId: String, totp: String) = exclusive { operation ->
        val authority = enrollment(operation, SecurityProfile.PasswordTotp)
        require(securityOpaqueId.matches(factorId))
        mutation(operation, authority, "totp/verify", revisionBody {
            put("factorId", factorId)
            put("totp", totp)
        })
        Unit
    }

    suspend fun prepareRecovery(): PendingRecoveryDocument = exclusive { operation ->
        val authority = enrollment(operation)
        val kind = when (state.value?.setupProfile) {
            SecurityProfile.Passkeys -> RecoveryKind.Kit
            SecurityProfile.PasswordTotp -> RecoveryKind.Codes
            null -> invalid()
        }
        val route = "recovery-material/prepare"
        val payload = pendingPayload(operation, authority, route) ?: revisionBody {
            put("kind", accountSecurityJson.encodeToJsonElement(kind))
            put("reviewId", JsonNull)
        }
        val (request, result) = keyed<SecurityRecoveryPreparation>(operation, authority, route, payload)
        refresh(operation, authority)
        forget(operation, request)
        val offer = state.value?.recovery?.offer ?: invalid()
        if (offer.kind != kind || offer.id != result.receipt.offerId || offer.generation != result.receipt.recoveryGeneration ||
            result.document.isNullOrBlank() || offer.saved || offer.expiresAt <= now()) invalid()
        PendingRecoveryDocument(offer.id, offer.generation, result.document)
    }

    suspend fun confirmSaved(offerId: String, generation: Long) = exclusive { operation ->
        val authority = enrollment(operation)
        val offer = state.value?.recovery?.offer ?: invalid()
        check(offer.id == offerId && offer.generation == generation && offer.expiresAt > now())
        mutation(operation, authority, "recovery-material/save", revisionBody {
            put("offerId", offerId)
            put("generation", generation)
            put("saved", true)
        })
        Unit
    }

    suspend fun complete(passkeys: AccountPasskeys, password: String, totp: String): Boolean = exclusive { operation ->
        val authority = enrollment(operation)
        val route = "enrollment/complete/verify"
        val retry = pendingPayload(operation, authority, route)
        if (retry != null) {
            try {
                settle(operation, authority, retry)
            } catch (error: CoreHttpException) {
                if (error.status != 401) throw error
                synchronized(lock) { checkEpoch(operation); clear() }
                throw EnrollmentAcknowledgementLost()
            }
            return@exclusive true
        }
        val current = state.value ?: invalid()
        val profile = current.setupProfile ?: invalid()
        val offer = current.recovery.offer?.takeIf { it.saved && it.expiresAt > now() } ?: invalid()
        val challenge = api.accountSecurity<SecurityFinalChallenge>("enrollment/complete/options", authority, revisionBody {
            put("offerId", offer.id)
            put("generation", offer.generation)
            put("profile", accountSecurityJson.encodeToJsonElement(profile))
        })
        verify(operation)
        if (!securityOpaqueId.matches(challenge.challengeId)) invalid()
        val methodProof = when (profile) {
            SecurityProfile.Passkeys -> {
                if (challenge.method != SecurityProof.Passkey || challenge.options == null) invalid()
                val assertion = proof(operation, passkeys, challenge.challengeId, challenge.options, registration = false)
                    ?: return@exclusive false
                buildJsonObject { put("method", "passkey_uv"); put("response", assertion) }
            }
            SecurityProfile.PasswordTotp -> {
                if (challenge.method != SecurityProof.PasswordTotp || challenge.options != null) invalid()
                buildJsonObject { put("method", "password_totp"); put("password", password); put("totp", totp) }
            }
        }
        settle(operation, authority, body {
            put("challengeId", challenge.challengeId)
            put("proof", methodProof)
        })
        true
    }

    suspend fun organizations(cursor: String? = null): SecurityMembershipPage = exclusive { operation ->
        val authority = account(operation)
        val page = api.accountSecurity<SecurityMembershipPage>("organizations", authority, cursor = cursor)
        if (page.items.size > 20 || page.items.map { it.orgId }.distinct().size != page.items.size ||
            page.items.any { !coreIdentifier.matches(it.orgId) } ||
            page.nextCursor?.let { !coreIdentifier.matches(it) || it == cursor || page.items.isEmpty() } == true) invalid()
        verify(operation)
        page
    }

    suspend fun chooseOrganization(membership: SecurityMembership) = exclusive { operation ->
        val authority = account(operation)
        val accountId = state.value?.accountId ?: invalid()
        require(coreIdentifier.matches(membership.orgId))
        val scoped = api.accountSecurity<CoreSessionToken>("session", authority, body { put("orgId", membership.orgId) })
        verify(operation)
        workspace.establishAccountSession(scoped, accountId, membership.orgId) { current(operation, authority) }
        verify(operation)
    }

    suspend fun leave() = exclusive { operation ->
        val authority = synchronized(lock) { checkEpoch(operation); grant }
        if (authority != null && authority.expiresAt > now()) {
            if (authority.purpose == SecurityPurpose.Enrollment) {
                mutation(operation, authority, "enrollment/cancel", revisionBody { put("confirmation", "cancel-enrollment") }, readState = false)
            } else {
                api.accountSecurity<JsonObject>("sign-out", authority, body())
            }
        }
        synchronized(lock) { checkEpoch(operation); clear() }
    }

    private fun enrollment(operation: Long, profile: SecurityProfile? = null): SecurityAuthority =
        authority(operation, SecurityPurpose.Enrollment).also {
            if (profile != null) check(state.value?.setupProfile == profile)
        }

    private fun account(operation: Long): SecurityAuthority = authority(operation, SecurityPurpose.Account).also {
        check(state.value?.workspaceAccess == SecurityAccess.Available)
    }

    private fun authority(operation: Long, purpose: SecurityPurpose): SecurityAuthority = synchronized(lock) {
        checkEpoch(operation)
        checkNotNull(grant).also { check(it.purpose == purpose && it.expiresAt > now()) }
    }

    private suspend fun proof(operation: Long, passkeys: AccountPasskeys, challenge: String, options: JsonObject, registration: Boolean): JsonObject? {
        verify(operation)
        val actualRp = if (registration) (options["rp"] as? JsonObject)?.get("id") else options["rpId"]
        if (rpId == null || actualRp != JsonPrimitive(rpId) || !securityOpaqueId.matches(challenge) ||
            (options["challenge"] as? JsonPrimitive)?.isString != true) invalid()
        val selection = options["authenticatorSelection"] as? JsonObject
        val verification = if (registration) selection?.get("userVerification") else options["userVerification"]
        if (verification != JsonPrimitive("required")) invalid()
        if (registration && (selection?.get("residentKey") != JsonPrimitive("required") ||
            selection["requireResidentKey"] != JsonPrimitive(true))) invalid()
        val raw = if (registration) passkeys.register(options.toString()) else passkeys.assert(options.toString())
        verify(operation)
        return raw?.let {
            try { Json.parseToJsonElement(it) as? JsonObject ?: invalid() } catch (_: SerializationException) { invalid() }
        }
    }

    private suspend fun settle(operation: Long, authority: SecurityAuthority, payload: JsonObject) {
        val (request, result) = keyed<SecurityGrant>(operation, authority, "enrollment/complete/verify", payload)
        if (result.state.phase != SecurityPhase.Ready) invalid()
        install(operation, result, SecurityPurpose.Account)
        forget(operation, request)
    }

    private suspend fun mutation(operation: Long, authority: SecurityAuthority, route: String, payload: JsonObject, readState: Boolean = true) {
        val (request, _) = keyed<SecurityReceipt>(operation, authority, route, payload)
        if (readState) refresh(operation, authority)
        forget(operation, request)
    }

    private fun pendingPayload(operation: Long, authority: SecurityAuthority, route: String): JsonObject? =
        synchronized(lock) {
            checkEpoch(operation)
            pendingMutation?.takeIf { it.authority === authority && it.route == route }?.payload
        }

    private suspend inline fun <reified T : Any> keyed(
        operation: Long,
        authority: SecurityAuthority,
        route: String,
        payload: JsonObject,
    ): Pair<PendingMutation, T> {
        verify(operation)
        val request = synchronized(lock) {
            checkEpoch(operation)
            pendingMutation?.takeIf { it.authority === authority && it.route == route && it.payload == payload }
                ?: PendingMutation(route, authority, payload, key()).also { pendingMutation = it }
        }
        val cached = request.response as? T
        if (cached != null) return request to cached
        val result = try {
            api.accountSecurity<T>(route, authority, payload, request.requestKey)
        } catch (error: CoreHttpException) {
            // Ambiguous responses retain the exact payload/key. An explicit conflict can only be
            // followed by a new user decision against a refreshed snapshot.
            if (error.status in 400..499 && error.status !in setOf(408, 429)) {
                forget(operation, request)
                if (error.status == 409) refresh(operation, authority)
            }
            throw error
        }
        verify(operation)
        synchronized(lock) { checkEpoch(operation); request.response = result }
        return request to result
    }

    private fun forget(operation: Long, request: PendingMutation) {
        synchronized(lock) {
            checkEpoch(operation)
            if (pendingMutation === request) pendingMutation = null
        }
    }

    private suspend fun refresh(operation: Long, authority: SecurityAuthority) {
        verify(operation)
        val snapshot = api.accountSecurity<AccountSecurityState>("state", authority)
        install(operation, SecurityGrant(authority, snapshot), authority.purpose)
    }

    private suspend fun install(operation: Long, result: SecurityGrant, purpose: SecurityPurpose) {
        val (authority, snapshot) = result.let { it.authority to it.state }
        if (authority.kind != "account_grant" || authority.purpose != purpose || !accountGrantBearer.matches(authority.token) ||
            snapshot.version != 1 || snapshot.purpose != purpose || snapshot.expiresAt <= now() ||
            authority.expiresAt != snapshot.expiresAt || snapshot.generation < 0 || snapshot.inventoryRevision < 0 ||
            snapshot.generation > CORE_MAX_VERSION || snapshot.inventoryRevision > CORE_MAX_VERSION ||
            snapshot.methods.map { it.id }.distinct().size != snapshot.methods.size ||
            snapshot.methods.any { !securityOpaqueId.matches(it.id) || (it.state == SecurityMethodStatus.Active && !it.verified) } ||
            snapshot.recovery.offer?.let { !securityOpaqueId.matches(it.id) || it.kind == RecoveryKind.None } == true ||
            (purpose != SecurityPurpose.Account && snapshot.workspaceAccess == SecurityAccess.Available) ||
            (purpose == SecurityPurpose.Account && (snapshot.accountId?.let(coreIdentifier::matches) != true ||
                snapshot.phase !in setOf(SecurityPhase.Ready, SecurityPhase.Legacy))) ||
            (purpose == SecurityPurpose.Enrollment && ((snapshot.intentRevision ?: 0) !in 1..CORE_MAX_VERSION ||
                snapshot.attemptId != null || snapshot.attemptRevision != null ||
                snapshot.phase != SecurityPhase.Enrolling || snapshot.setupProfile == null))) invalid()
        verify(operation)
        synchronized(lock) {
            checkEpoch(operation)
            val prior = mutableState.value
            if (grant === authority && prior != null && (prior.accountId != snapshot.accountId ||
                prior.candidateId != snapshot.candidateId || prior.generation > snapshot.generation ||
                prior.inventoryRevision > snapshot.inventoryRevision ||
                (prior.intentRevision ?: 0) > (snapshot.intentRevision ?: 0))) invalid()
            grant = authority
            login = null
            pendingEnrollment = null
            mutableState.value = snapshot
        }
    }

    private fun revisionBody(fields: JsonObjectBuilder.() -> Unit = {}): JsonObject = body {
        val snapshot = state.value ?: invalid()
        put("attemptId", JsonNull)
        put("expectedGeneration", snapshot.generation)
        put("expectedInventoryRevision", snapshot.inventoryRevision)
        put("expectedAttemptRevision", JsonNull)
        put("expectedIntentRevision", snapshot.intentRevision ?: invalid())
        fields()
    }

    private suspend fun <T> exclusive(action: suspend (Long) -> T): T {
        val operation = synchronized(lock) { epoch }
        return requests.withLock {
            verify(operation)
            action(operation)
        }
    }

    private fun current(operation: Long, authority: SecurityAuthority): Boolean =
        synchronized(lock) { epoch == operation && grant === authority && authority.expiresAt > now() }

    private suspend fun verify(operation: Long) {
        currentCoroutineContext().ensureActive()
        synchronized(lock) { checkEpoch(operation) }
    }

    private fun checkEpoch(operation: Long) {
        if (epoch != operation) throw CancellationException("Account security flow ended")
    }

    private fun body(fields: JsonObjectBuilder.() -> Unit = {}): JsonObject = buildJsonObject {
        put("version", 1)
        fields()
    }

    private fun key() = UUID.randomUUID().toString()

    private fun invalid(): Nothing = throw CoreProtocolException()
}
