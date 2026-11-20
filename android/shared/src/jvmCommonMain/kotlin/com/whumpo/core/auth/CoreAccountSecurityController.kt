package com.whumpo.core.auth

import com.whumpo.core.api.AccountSecurityState
import com.whumpo.core.api.CORE_MAX_VERSION
import com.whumpo.core.api.CoreApiClient
import com.whumpo.core.api.CoreHttpException
import com.whumpo.core.api.CoreProtocolException
import com.whumpo.core.api.CoreRole
import com.whumpo.core.api.CoreSessionToken
import com.whumpo.core.api.CurrentSecurityRecoveryReview
import com.whumpo.core.api.RecoveryKind
import com.whumpo.core.api.RecoveryLossMode
import com.whumpo.core.api.RecoveryReview
import com.whumpo.core.api.RecoveryReviewedMethod
import com.whumpo.core.api.ReservedEnrollmentResume
import com.whumpo.core.api.ReservedEnrollmentStart
import com.whumpo.core.api.SavedRecoveryProof
import com.whumpo.core.api.SecurityAccess
import com.whumpo.core.api.SecurityAdmission
import com.whumpo.core.api.SecurityAttemptReceipt
import com.whumpo.core.api.SecurityAttemptResult
import com.whumpo.core.api.SecurityAuthority
import com.whumpo.core.api.SecurityChallenge
import com.whumpo.core.api.SecurityFinalChallenge
import com.whumpo.core.api.SecurityGrant
import com.whumpo.core.api.SecurityInvitationAcceptance
import com.whumpo.core.api.SecurityInvitationPreview
import com.whumpo.core.api.SecurityMembership
import com.whumpo.core.api.SecurityNoticePage
import com.whumpo.core.api.SecurityMembershipPage
import com.whumpo.core.api.SecurityMethod
import com.whumpo.core.api.SecurityMethodStatus
import com.whumpo.core.api.SecurityMethodKind
import com.whumpo.core.api.SecurityPasswordChallenge
import com.whumpo.core.api.SecurityPhase
import com.whumpo.core.api.SecurityProfile
import com.whumpo.core.api.SecurityProof
import com.whumpo.core.api.SecurityPurpose
import com.whumpo.core.api.SecurityReceipt
import com.whumpo.core.api.SecurityRecoveryPreparation
import com.whumpo.core.api.SecurityRecoveryReview
import com.whumpo.core.api.SecurityStart
import com.whumpo.core.api.SecurityTotpPreparation
import com.whumpo.core.api.accountGrantBearer
import com.whumpo.core.api.accountSecurityJson
import com.whumpo.core.api.consent
import com.whumpo.core.api.coreIdentifier
import com.whumpo.core.api.coreDigest
import com.whumpo.core.api.personInvitationCode
import com.whumpo.core.api.recoveryId
import com.whumpo.core.api.reservedInvitationToken
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add

/** Credential Manager JSON crosses this boundary unchanged. Null represents user cancellation. */
interface AccountPasskeys {
    suspend fun register(options: String): String?
    suspend fun assert(options: String): String?
}

class EnrollmentAcknowledgementLost : Exception("Enrollment result could not be recovered")
class RecoveryAcknowledgementLost : Exception("Recovery result could not be confirmed")
class OneTimeMaterialUnavailable : Exception("This response did not include one-time material")
class AccountSecurityProofRejected : Exception("The service rejected the credential proof")
class AccountSecurityIdentityMismatch : Exception("Signed-in identity does not match the restricted setup")

class PendingTotp internal constructor(val factorId: String, val secret: String)
class PendingRecoveryDocument internal constructor(val offerId: String, val generation: Long, val text: String)

class ReservedSetupStart internal constructor(
    val continuationKey: String,
    internal val profile: SecurityProfile,
    internal val invitationToken: String,
)

sealed interface AccountSecurityRetryResult {
    data object Updated : AccountSecurityRetryResult
    class Totp(val setup: PendingTotp) : AccountSecurityRetryResult
    class Material(val document: PendingRecoveryDocument) : AccountSecurityRetryResult
}

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
    private var noticeRevision = 0L
    private val noticeCursors = mutableSetOf<String>()
    private val mutableNotices = MutableStateFlow<SecurityNoticePage?>(null)
    val notices = mutableNotices.asStateFlow()

    fun hideNotices() = synchronized(lock) { clearNoticesLocked() }

    private fun clearNoticesLocked() {
        noticeRevision++
        noticeCursors.clear()
        mutableNotices.value = null
    }

    fun expireAccountIfNeeded() = synchronized(lock) {
        if (grant?.expiresAt?.let { it <= now() } == true) {
            clearAccountLocked()
            workspace.clear()
        }
    }

    /** This metadata uses the account grant even when no workspace membership remains. */
    suspend fun loadNotices(more: Boolean = false) = exclusive { operation ->
        val (ownedAuthority, revision, cursor) = synchronized(lock) {
            if (grant?.expiresAt?.let { it <= now() } == true) {
                clearAccountLocked()
                workspace.clear()
            }
            val active = authority(operation, SecurityPurpose.Account)
            if (!more) clearNoticesLocked()
            val next = if (more) mutableNotices.value?.nextCursor ?: invalid() else null
            if (more && noticeCursors.size >= 20) { clearNoticesLocked(); invalid() }
            Triple(active, noticeRevision, next)
        }
        try {
            val page = api.accountSecurity<SecurityNoticePage>("notices", ownedAuthority, cursor = cursor)
            verify(operation)
            synchronized(lock) {
                checkEpoch(operation)
                if (grant !== ownedAuthority || noticeRevision != revision) throw CancellationException("Notice view ended")
                if (ownedAuthority.expiresAt <= now()) {
                    clearAccountLocked()
                    workspace.clear()
                    throw CancellationException("Account access expired")
                }
                if (page.nextCursor != null && (page.nextCursor == cursor || page.nextCursor in noticeCursors)) invalid()
                val existing = if (more) mutableNotices.value?.items ?: invalid() else emptyList()
                val byId = existing.associateBy { it.id }
                if (page.items.any { byId[it.id]?.let { prior -> prior != it } == true }) invalid()
                val merged = existing + page.items.filter { it.id !in byId }
                if (more && merged.size == existing.size && page.nextCursor != null) invalid()
                if (merged.size > 420) invalid()
                if (cursor != null) noticeCursors.add(cursor)
                mutableNotices.value = SecurityNoticePage(merged, page.nextCursor)
            }
        } catch (error: Exception) {
            synchronized(lock) {
                if (epoch == operation && grant === ownedAuthority && noticeRevision == revision) {
                    clearNoticesLocked()
                    if (error is CoreHttpException && error.status == 401) {
                        clearAccountLocked()
                        workspace.clear()
                    }
                }
            }
            throw error
        }
    }
    private var login: SecurityPasswordChallenge? = null
    private var pendingEnrollment: EnrollmentStart? = null
    private var pendingPublicStart: PublicStart? = null
    private var pendingReservedSetup: ReservedSetup? = null
    private var pendingMutation: PendingMutation? = null
    private var pendingCancellation: PendingMutation? = null
    private var reservedEnrollment = false
    private var expectedSignIn: ExpectedSignIn? = null

    enum class Retry {
        ReservedStart, RecoveryStart, Review, PrepareMaterial, SaveMaterial, Secure,
        RegisterPasskey, StagePassword, PrepareTotp, VerifyTotp, Complete,
    }

    private val mutableRetry = MutableStateFlow<Retry?>(null)
    val retry = mutableRetry.asStateFlow()
    private val mutableRecoveryReview = MutableStateFlow<RecoveryReview?>(null)
    val recoveryReview = mutableRecoveryReview.asStateFlow()

    private class PublicStart(val route: String, val payload: JsonObject, val key: String)
    private class ExpectedSignIn(
        val accountId: String,
        val method: SecurityProof,
        val reservedCompletion: AccountSecurityState?,
    )

    private class ReservedSetup(val key: String, val token: String, val startProfile: SecurityProfile?) {
        var identity: ReservedIdentity? = null
        var issued: SecurityGrant? = null
    }

    private class ReservedIdentity(
        val accountId: String,
        val attemptId: String,
        val generation: Long,
        val profile: SecurityProfile,
        val minimumInventoryRevision: Long,
    ) {
        fun matches(snapshot: AccountSecurityState) = snapshot.accountId == accountId &&
            snapshot.attemptId == attemptId && snapshot.generation == generation && snapshot.setupProfile == profile &&
            snapshot.inventoryRevision >= minimumInventoryRevision && snapshot.candidateId == null &&
            snapshot.workspaceAccess == SecurityAccess.Restricted
    }

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
            clearAccountLocked()
            workspace.clear()
        }
    }

    private fun clearAccountLocked() {
        clearNoticesLocked()
        epoch++
        grant = null
        login = null
        pendingEnrollment = null
        pendingPublicStart = null
        pendingReservedSetup = null
        pendingMutation = null
        pendingCancellation = null
        reservedEnrollment = false
        expectedSignIn = null
        mutableRetry.value = null
        mutableRecoveryReview.value = null
        mutableState.value = null
    }

    fun checkCompletionThroughSignIn() {
        synchronized(lock) {
            check(mutableRetry.value == Retry.Complete)
            val state = mutableState.value ?: invalid()
            val expected = ExpectedSignIn(state.accountId ?: invalid(), state.setupProfile?.proofMethod() ?: invalid(),
                state.takeIf { reservedEnrollment })
            clear()
            expectedSignIn = expected
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
        checkPublicEntry(operation)
        val wireAdmission = admission.wire()
        val pending = synchronized(lock) {
            checkEpoch(operation)
            pendingEnrollment?.takeIf { it.admission == wireAdmission && it.profile == profile } ?: EnrollmentStart(
                key(),
                randomSecret(),
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
        if (credentials.state.setupProfile != profile || credentials.state.attemptId != null) invalid()
        install(operation, credentials, SecurityPurpose.Enrollment)
    }

    fun prepareReservedSetup(profile: SecurityProfile, invitationToken: String): ReservedSetupStart {
        require(reservedInvitationToken.matches(invitationToken))
        return ReservedSetupStart(key(), profile, invitationToken)
    }

    suspend fun enrollReserved(start: ReservedSetupStart) = exclusive { operation ->
        val pending = reservedSetup(operation, start.continuationKey, start.invitationToken, start.profile)
        enterReserved(operation, pending)
    }

    suspend fun resumeReserved(invitationToken: String, startRequestKey: String) = exclusive { operation ->
        require(reservedInvitationToken.matches(invitationToken) && recoveryId.matches(startRequestKey))
        val pending = reservedSetup(operation, startRequestKey, invitationToken, null)
        enterReserved(operation, pending)
    }

    suspend fun startRecovery(locator: String, proof: SavedRecoveryProof) = exclusive { operation ->
        require(locator.isNotBlank())
        val proofBody = buildJsonObject {
            when (proof) {
                is SavedRecoveryProof.Kit -> {
                    require(proof.key.isNotBlank())
                    put("kind", "kit")
                    put("key", proof.key)
                }
                is SavedRecoveryProof.Code -> {
                    require(proof.code.isNotBlank())
                    put("kind", "code")
                    put("code", proof.code)
                }
            }
        }
        val pending = publicStart(operation, "recovery/start", body {
            put("locator", locator)
            put("proof", proofBody)
            put("resumeSecret", randomSecret())
        })
        enterRecovery(operation, pending)
    }

    private fun publicStart(operation: Long, route: String, payload: JsonObject): PublicStart =
        synchronized(lock) {
            checkPublicEntry(operation)
            check(expectedSignIn == null)
            PublicStart(route, payload, key()).also { pendingPublicStart = it }
        }

    private fun checkPublicEntry(operation: Long) = synchronized(lock) {
        checkEpoch(operation)
        check(mutableState.value == null && pendingPublicStart == null && pendingReservedSetup == null)
    }

    private fun reservedSetup(operation: Long, key: String, token: String, profile: SecurityProfile?): ReservedSetup =
        synchronized(lock) {
            checkPublicEntry(operation)
            check(expectedSignIn == null)
            login = null
            ReservedSetup(key, token, profile).also {
                pendingReservedSetup = it
                mutableRetry.value = Retry.ReservedStart
            }
        }

    private suspend fun enterReserved(operation: Long, pending: ReservedSetup) {
        val profile = pending.startProfile
        val credentials = pending.issued?.takeIf { it.authority.expiresAt > now() }
            ?: (if (profile != null && pending.identity == null) startReserved(operation, pending, profile) else null)
            ?: requestReservedGrant(operation, pending)
        val identity = pending.identity ?: invalid()
        pending.issued = credentials
        val snapshot = try {
            readState(operation, credentials.authority).also {
                validateGrant(SecurityGrant(credentials.authority, it), SecurityPurpose.Enrollment)
                if (!identity.matches(it) || !it.hasAttemptRevisions() ||
                    it.inventoryRevision < credentials.state.inventoryRevision ||
                    (it.attemptRevision ?: 0) < (credentials.state.attemptRevision ?: 0)) invalid()
            }
        } catch (error: CoreHttpException) {
            if (error.status == 401 || error.status == 403) pending.issued = null
            throw error
        } catch (error: CoreProtocolException) {
            pending.issued = null
            throw error
        }
        install(operation, SecurityGrant(credentials.authority, snapshot), SecurityPurpose.Enrollment) {
            reservedEnrollment = true
            pendingReservedSetup = null
            mutableRetry.value = null
        }
    }

    private suspend fun startReserved(operation: Long, pending: ReservedSetup, profile: SecurityProfile): SecurityGrant? {
        verify(operation)
        val start = try {
            api.accountSecurity<ReservedEnrollmentStart>("enrollment/reserved/start", key = pending.key, body = body {
                put("profile", accountSecurityJson.encodeToJsonElement(profile))
                put("invitationToken", pending.token)
            })
        } catch (error: CoreHttpException) {
            if (error.status in definitiveRejection) synchronized(lock) {
                checkEpoch(operation)
                pendingReservedSetup = null
                mutableRetry.value = null
            }
            throw error
        }
        verify(operation)
        val receipt = start.receipt
        val accountId = receipt.accountId?.takeIf(coreIdentifier::matches) ?: invalid()
        val attemptId = receipt.attemptId?.takeIf(securityOpaqueId::matches) ?: invalid()
        if (receipt.operation != "enrollment.reserved.start" || receipt.candidateId != null ||
            !recoveryId.matches(receipt.operationId) || receipt.confirmedAt !in 1..CORE_MAX_VERSION ||
            receipt.generationAfter !in 1..CORE_MAX_VERSION || receipt.generationBefore != receipt.generationAfter ||
            receipt.revision != 1L || receipt.inventoryRevision !in 0..CORE_MAX_VERSION ||
            receipt.recoveryGeneration !in 0..CORE_MAX_VERSION) invalid()
        val identity = ReservedIdentity(accountId, attemptId, receipt.generationAfter, profile, receipt.inventoryRevision)
        // Keep the confirmed attempt even when the rest of the response cannot be decoded.
        pending.identity = identity
        if (start.authority == null && start.state == null) return null
        val result = try {
            SecurityGrant(
                accountSecurityJson.decodeFromJsonElement(SecurityAuthority.serializer(), start.authority ?: invalid()),
                accountSecurityJson.decodeFromJsonElement(AccountSecurityState.serializer(), start.state ?: invalid()),
            )
        } catch (_: IllegalArgumentException) {
            invalid()
        }
        if (!identity.matches(result.state) || !result.state.hasAttemptRevisions()) invalid()
        checkAttemptReceipt(receipt, "enrollment.reserved.start", result.state)
        validateGrant(result, SecurityPurpose.Enrollment)
        return result
    }

    private suspend fun requestReservedGrant(operation: Long, pending: ReservedSetup): SecurityGrant {
        verify(operation)
        val resumed = api.accountSecurity<ReservedEnrollmentResume>("enrollment/reserved/resume", body = body {
            put("startRequestKey", pending.key)
            put("invitationToken", pending.token)
        })
        verify(operation)
        val snapshot = resumed.state
        val result = SecurityGrant(resumed.authority, snapshot)
        validateGrant(result, SecurityPurpose.Enrollment)
        if (!snapshot.hasAttemptRevisions() || snapshot.attemptId != resumed.attemptId ||
            resumed.attemptExpiresAt !in snapshot.expiresAt..CORE_MAX_VERSION) invalid()
        val identity = pending.identity ?: ReservedIdentity(snapshot.accountId?.takeIf(coreIdentifier::matches) ?: invalid(),
            resumed.attemptId, snapshot.generation, snapshot.setupProfile ?: invalid(), snapshot.inventoryRevision)
        if (!identity.matches(snapshot)) invalid()
        pending.identity = identity
        return result
    }

    private suspend fun enterRecovery(operation: Long, pending: PublicStart) {
        val result = sendPublicStart<SecurityAttemptResult>(operation, pending)
        val credentials = SecurityGrant(result.authority ?: invalid(), result.state ?: invalid())
        checkAttemptReceipt(result.receipt, "recovery.start", credentials.state, replay = true)
        validateGrant(credentials, SecurityPurpose.Recovery)
        val review = readCurrentReview(operation, credentials)
        install(operation, credentials, SecurityPurpose.Recovery) {
            pendingPublicStart = null
            mutableRetry.value = null
            mutableRecoveryReview.value = review
        }
    }

    private suspend fun readCurrentReview(operation: Long, credentials: SecurityGrant): RecoveryReview? {
        verify(operation)
        val snapshot = credentials.state
        val current = api.accountSecurity<CurrentSecurityRecoveryReview>(
            "recovery/review/current", credentials.authority, body(),
        )
        if (current.attemptId != snapshot.attemptId || current.attemptRevision != snapshot.attemptRevision ||
            current.generation != snapshot.generation || current.inventoryRevision != snapshot.inventoryRevision) invalid()
        val review = current.review
        val active = activeMethods(snapshot)
        if (snapshot.phase == SecurityPhase.Recovering) {
            validateSecuredReview(snapshot, review ?: invalid())
        } else if (review != null) {
            validateReview(review, active)
            if (snapshot.setupProfile != review.completionProfile) invalid()
        }
        return review
    }

    private suspend inline fun <reified T> sendPublicStart(operation: Long, pending: PublicStart): T {
        verify(operation)
        synchronized(lock) {
            checkEpoch(operation)
            mutableRetry.value = Retry.RecoveryStart
        }
        val result = try {
            api.accountSecurity<T>(pending.route, body = pending.payload, key = pending.key)
        } catch (error: CoreHttpException) {
            if (error.status in definitiveRejection) synchronized(lock) {
                checkEpoch(operation)
                pendingPublicStart = null
                mutableRetry.value = null
            }
            throw error
        }
        verify(operation)
        return result
    }

    private fun checkAttemptReceipt(
        receipt: SecurityAttemptReceipt,
        operation: String,
        state: AccountSecurityState,
        attemptId: String? = state.attemptId,
        minimumGeneration: Long = 0,
        priorGeneration: Long? = null,
        replay: Boolean = false,
    ) {
        if (!recoveryId.matches(receipt.operationId) || receipt.confirmedAt !in 0..CORE_MAX_VERSION ||
            state.accountId?.let(coreIdentifier::matches) != true || state.candidateId != null ||
            receipt.operation != operation || receipt.accountId != state.accountId ||
            receipt.candidateId != null || receipt.attemptId != attemptId ||
            receipt.generationAfter !in maxOf(receipt.generationBefore, minimumGeneration)..state.generation ||
            receipt.generationBefore < 0 || receipt.inventoryRevision !in 0..state.inventoryRevision ||
            (priorGeneration != null && receipt.generationBefore != priorGeneration) ||
            receipt.recoveryGeneration !in 0..CORE_MAX_VERSION ||
            (!replay && (receipt.generationAfter != state.generation || receipt.inventoryRevision != state.inventoryRevision))) invalid()
    }

    suspend fun reviewRecovery(mode: RecoveryLossMode, lostIds: Set<String>, profile: SecurityProfile) =
        exclusive { operation ->
            val authority = authority(operation, SecurityPurpose.Recovery)
            check(state.value?.phase != SecurityPhase.Recovering)
            val active = activeMethods()
            val ids = active.map { it.id }.toSet()
            val selected = when (mode) {
                RecoveryLossMode.All -> ids
                RecoveryLossMode.None -> emptySet<String>().also { require(ids.isNotEmpty()) }
                RecoveryLossMode.Some -> lostIds.also { require(it.isNotEmpty() && it.size < ids.size && ids.containsAll(it)) }
            }
            require(selected.size <= 12)
            if (mode != RecoveryLossMode.All && profile == SecurityProfile.Passkeys) {
                require(active.any { it.kind == SecurityMethodKind.Passkey && it.id !in selected })
            }
            reviewRecovery(operation, authority, revisionBody {
                put("mode", accountSecurityJson.encodeToJsonElement(mode))
                putJsonArray("lostIds") { selected.sorted().forEach { add(it) } }
                put("completionProfile", accountSecurityJson.encodeToJsonElement(profile))
            })
        }

    suspend fun refreshRecoveryReview() = exclusive { operation ->
        val authority = authority(operation, SecurityPurpose.Recovery)
        val snapshot = SecurityGrant(authority, readState(operation, authority))
        val review = readCurrentReview(operation, snapshot)
        install(operation, snapshot, SecurityPurpose.Recovery) { mutableRecoveryReview.value = review }
    }

    private suspend fun reviewRecovery(operation: Long, authority: SecurityAuthority, payload: JsonObject) {
        val route = "recovery/review"
        val (request, result) = keyed<SecurityRecoveryReview>(operation, authority, route, payload)
        val review = result.review
        val expectedMode = accountSecurityJson.decodeFromJsonElement(RecoveryLossMode.serializer(), payload.getValue("mode"))
        val expectedProfile = accountSecurityJson.decodeFromJsonElement(SecurityProfile.serializer(), payload.getValue("completionProfile"))
        val expectedLost = (payload["lostIds"] as JsonArray).map { it.jsonPrimitive.content }.toSet()
        try {
            validateReview(review, activeMethods())
        } catch (error: CoreProtocolException) {
            rejectedResponse(operation, request, error)
        }
        if (review.mode != expectedMode || review.completionProfile != expectedProfile ||
            review.lostIds.toSet() != expectedLost || result.receipt.reviewId != review.id) rejectedResponse(operation, request)
        val refreshed = readState(operation, authority)
        if (refreshed.attemptRevision != result.receipt.revision || refreshed.setupProfile != review.completionProfile ||
            refreshed.recovery.offer != null) rejectedResponse(operation, request)
        install(operation, SecurityGrant(authority, refreshed), SecurityPurpose.Recovery) {
            mutableRecoveryReview.value = review
        }
        forget(operation, request)
    }

    private fun activeMethods(snapshot: AccountSecurityState? = state.value) =
        snapshot?.methods.orEmpty().filter { it.state == SecurityMethodStatus.Active }

    private fun reviewedMethods(methods: List<SecurityMethod>) = methods.map {
        RecoveryReviewedMethod(it.id, it.version, it.kind, it.createdAt)
    }.toSet()

    private fun validateSecuredReview(snapshot: AccountSecurityState, review: RecoveryReview) {
        validateReview(review)
        val retained = review.inventory.filter { it.id in review.retainedIds }.toSet()
        val active = activeMethods(snapshot)
        if (snapshot.setupProfile != review.completionProfile || active.any { !it.verified } ||
            reviewedMethods(active) != retained) invalid()
    }

    private fun validateReview(review: RecoveryReview, active: List<SecurityMethod>? = null) {
        val reviewedIds = review.inventory.map { it.id }.toSet()
        val lost = review.lostIds.toSet()
        val retained = review.retainedIds.toSet()
        val expectedMode = when {
            lost.isEmpty() && retained.isNotEmpty() && review.mode == RecoveryLossMode.None -> RecoveryLossMode.None
            lost == reviewedIds -> RecoveryLossMode.All
            else -> RecoveryLossMode.Some
        }
        if (!securityOpaqueId.matches(review.id) || !coreDigest.matches(review.digest) || review.mode != expectedMode ||
            (review.mode == RecoveryLossMode.Some && lost.isEmpty()) ||
            review.inventory.size != reviewedIds.size || review.lostIds.size != lost.size ||
            review.retainedIds.size != retained.size || lost.size > 12 || retained.size > 12 ||
            lost.intersect(retained).isNotEmpty() || lost + retained != reviewedIds ||
            (review.mode != RecoveryLossMode.All && review.completionProfile == SecurityProfile.Passkeys &&
                review.inventory.none { it.kind == SecurityMethodKind.Passkey && it.id in retained }) ||
            review.inventory.any { !securityOpaqueId.matches(it.id) || it.version !in 1..CORE_MAX_VERSION ||
                it.createdAt !in 0..CORE_MAX_VERSION } ||
            (active != null && review.inventory.toSet() != reviewedMethods(active))) invalid()
    }

    suspend fun secureRecovery() = exclusive { operation ->
        val authority = authority(operation, SecurityPurpose.Recovery)
        check(state.value?.phase != SecurityPhase.Recovering)
        val review = mutableRecoveryReview.value ?: invalid()
        val offer = state.value?.recovery?.offer?.takeIf { it.saved && it.expiresAt > now() } ?: invalid()
        secureRecovery(operation, authority, revisionBody {
            put("reviewId", review.id)
            put("reviewDigest", review.digest)
            put("offerId", offer.id)
            put("generation", offer.generation)
            put("confirmation", "secure-account")
        })
    }

    private suspend fun secureRecovery(operation: Long, authority: SecurityAuthority, payload: JsonObject) {
        val previous = state.value ?: invalid()
        val review = mutableRecoveryReview.value ?: invalid()
        val (request, result) = try {
            keyed<SecurityAttemptResult>(operation, authority, "recovery/secure", payload)
        } catch (error: CoreHttpException) {
            if (error.status == 401) throw RecoveryAcknowledgementLost()
            throw error
        }
        val next = result.state ?: rejectedResponse(operation, request, RecoveryAcknowledgementLost())
        val credential = result.authority ?: rejectedResponse(operation, request, RecoveryAcknowledgementLost())
        if (next.accountId != previous.accountId || next.attemptId != previous.attemptId ||
            next.generation <= previous.generation || next.inventoryRevision < previous.inventoryRevision ||
            (next.attemptRevision ?: 0) <= (previous.attemptRevision ?: 0) ||
            next.phase != SecurityPhase.Recovering ||
            next.recovery.offer != null || next.recovery.kind != review.completionProfile.recoveryKind() ||
            next.recovery.generation.toString() != payload["generation"]?.jsonPrimitive?.content) {
            rejectedResponse(operation, request)
        }
        try {
            validateSecuredReview(next, review)
            checkAttemptReceipt(result.receipt, "recovery.secure", next,
                minimumGeneration = previous.generation + 1, priorGeneration = previous.generation)
            if (result.receipt.recoveryGeneration != next.recovery.generation) invalid()
            install(operation, SecurityGrant(credential, next), SecurityPurpose.Recovery)
        } catch (error: CoreProtocolException) {
            rejectedResponse(operation, request, error)
        }
        forget(operation, request)
    }

    suspend fun loginPasskey(passkeys: AccountPasskeys): Boolean = exclusive { operation ->
        checkPublicEntry(operation)
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
        checkPublicEntry(operation)
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
        checkPublicEntry(operation)
        val challenge = synchronized(lock) { checkEpoch(operation); checkNotNull(login) }
        check(challenge.expiresAt > now()) { "Sign-in challenge expired" }
        val result = api.accountSecurity<SecurityGrant>("password/login/verify", body = body {
            put("challengeId", challenge.challengeId)
            put("totp", totp)
        })
        install(operation, result, SecurityPurpose.Account)
    }

    suspend fun registerPasskey(label: String, passkeys: AccountPasskeys): Boolean = exclusive { operation ->
        val authority = setupAuthority(operation, SecurityProfile.Passkeys)
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
        val authority = setupAuthority(operation, SecurityProfile.PasswordTotp)
        mutation(operation, authority, "password/stage", revisionBody {
            put("username", username)
            put("password", password)
        })
        Unit
    }

    suspend fun prepareTotp(): PendingTotp = exclusive { operation ->
        val authority = setupAuthority(operation, SecurityProfile.PasswordTotp)
        val route = "totp/prepare"
        prepareTotp(operation, authority, pendingPayload(operation, authority, route) ?: revisionBody())
    }

    private suspend fun prepareTotp(operation: Long, authority: SecurityAuthority, payload: JsonObject): PendingTotp {
        val route = "totp/prepare"
        val (request, result) = keyed<SecurityTotpPreparation>(
            operation, authority, route, payload,
        )
        refresh(operation, authority)
        val factorId = result.receipt.factorId
        if (factorId?.let(securityOpaqueId::matches) != true ||
            state.value?.methods?.none { it.id == factorId && it.kind == SecurityMethodKind.Totp &&
                it.state == SecurityMethodStatus.Staged && !it.verified } != false) rejectedResponse(operation, request)
        // A valid replay can omit the one-time secret. The refreshed state supplies revisions
        // for an explicit new preparation.
        forget(operation, request)
        return PendingTotp(factorId, result.secret?.takeIf(String::isNotBlank) ?: throw OneTimeMaterialUnavailable())
    }

    suspend fun verifyTotp(factorId: String, totp: String) = exclusive { operation ->
        val authority = setupAuthority(operation, SecurityProfile.PasswordTotp)
        require(securityOpaqueId.matches(factorId))
        mutation(operation, authority, "totp/verify", revisionBody {
            put("factorId", factorId)
            put("totp", totp)
        })
        Unit
    }

    suspend fun prepareRecovery(): PendingRecoveryDocument = exclusive { operation ->
        val authority = materialAuthority(operation)
        val review = mutableRecoveryReview.value
        val kind = (review?.completionProfile ?: state.value?.setupProfile ?: invalid()).recoveryKind()
        val route = "recovery-material/prepare"
        val payload = pendingPayload(operation, authority, route) ?: revisionBody {
            put("kind", accountSecurityJson.encodeToJsonElement(kind))
            put("reviewId", review?.id?.let(::JsonPrimitive) ?: JsonNull)
        }
        prepareRecovery(operation, authority, payload)
    }

    private suspend fun prepareRecovery(operation: Long, authority: SecurityAuthority, payload: JsonObject): PendingRecoveryDocument {
        val route = "recovery-material/prepare"
        val (request, result) = keyed<SecurityRecoveryPreparation>(operation, authority, route, payload)
        refresh(operation, authority)
        val offer = state.value?.recovery?.offer ?: rejectedResponse(operation, request)
        if (accountSecurityJson.encodeToJsonElement(offer.kind) != payload["kind"] ||
            offer.id != result.receipt.offerId || offer.generation != result.receipt.recoveryGeneration ||
            offer.saved || offer.expiresAt <= now()) rejectedResponse(operation, request)
        forget(operation, request)
        return PendingRecoveryDocument(offer.id, offer.generation,
            result.document?.takeIf(String::isNotBlank) ?: throw OneTimeMaterialUnavailable())
    }

    suspend fun confirmSaved(offerId: String, generation: Long) = exclusive { operation ->
        val authority = materialAuthority(operation)
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
        val authority = setupAuthority(operation)
        val recovery = authority.purpose == SecurityPurpose.Recovery
        val path = if (recovery) "recovery/complete" else "enrollment/complete"
        val route = "$path/verify"
        val retry = pendingPayload(operation, authority, route)
        if (retry != null) {
            settle(operation, authority, route, retry, replay = true)
            return@exclusive true
        }
        val current = state.value ?: invalid()
        val profile = current.setupProfile ?: invalid()
        val challenge = api.accountSecurity<SecurityFinalChallenge>("$path/options", authority, revisionBody {
            if (recovery) {
                val review = mutableRecoveryReview.value ?: invalid()
                if (review.completionProfile != profile || !current.replacementMethodsReady() ||
                    current.recovery.kind != profile.recoveryKind() || current.recovery.generation <= 0) invalid()
                put("reviewId", review.id)
                put("reviewDigest", review.digest)
                put("generation", current.recovery.generation)
                put("method", accountSecurityJson.encodeToJsonElement(profile.proofMethod()))
            } else {
                val offer = current.recovery.offer?.takeIf { it.saved && it.expiresAt > now() } ?: invalid()
                if (reservedEnrollment && !current.replacementMethodsReady()) invalid()
                put("offerId", offer.id)
                put("generation", offer.generation)
                put("profile", accountSecurityJson.encodeToJsonElement(profile))
            }
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
        settle(operation, authority, route, body {
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

    /** Signs out the established workspace and its account grant without keeping either in local state. */
    suspend fun signOutWorkspace() {
        val (revokeWorkspace, accountGrant) = synchronized(lock) {
            val revoke = workspace.clearForRevocation()
            val authority = grant?.takeIf { it.purpose == SecurityPurpose.Account && it.expiresAt > now() }
            clearAccountLocked()
            revoke to authority
        }
        val workspaceFailure = try {
            revokeWorkspace?.invoke()
            null
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            error
        }
        if (accountGrant != null) api.accountSecurity<JsonObject>("sign-out", accountGrant, body())
        if (workspaceFailure != null) throw workspaceFailure
    }

    suspend fun leave() = exclusive { operation ->
        val authority = synchronized(lock) { checkEpoch(operation); grant }
        if (authority != null && authority.expiresAt > now()) {
            if (authority.purpose == SecurityPurpose.Enrollment) {
                val cancel = synchronized(lock) {
                    checkEpoch(operation)
                    pendingCancellation ?: PendingMutation("enrollment/cancel", authority,
                        revisionBody { put("confirmation", "cancel-enrollment") }, key()).also { pendingCancellation = it }
                }
                api.accountSecurity<SecurityReceipt>(cancel.route, authority, cancel.payload, cancel.requestKey)
            } else {
                api.accountSecurity<JsonObject>("sign-out", authority, body())
            }
        }
        synchronized(lock) { checkEpoch(operation); clear() }
    }

    private fun setupAuthority(operation: Long, profile: SecurityProfile? = null): SecurityAuthority =
        when (state.value?.purpose) {
            SecurityPurpose.Enrollment -> authority(operation, SecurityPurpose.Enrollment)
            SecurityPurpose.Recovery -> authority(operation, SecurityPurpose.Recovery).also {
                check(state.value?.phase == SecurityPhase.Recovering && mutableRecoveryReview.value != null)
            }
            else -> invalid()
        }.also {
            if (profile != null) check(state.value?.setupProfile == profile)
        }

    private fun materialAuthority(operation: Long): SecurityAuthority =
        if (state.value?.purpose == SecurityPurpose.Recovery) authority(operation, SecurityPurpose.Recovery).also {
            check(state.value?.phase != SecurityPhase.Recovering && mutableRecoveryReview.value != null)
        } else authority(operation, SecurityPurpose.Enrollment)

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

    private suspend fun settle(
        operation: Long, authority: SecurityAuthority, route: String, payload: JsonObject, replay: Boolean = false,
    ) {
        val previous = state.value ?: invalid()
        val strict = authority.purpose == SecurityPurpose.Recovery || reservedEnrollment
        var pending: PendingMutation? = null
        var reservedReady: SecurityGrant? = null
        try {
            val result = if (strict) {
                val (request, response) = keyed<SecurityAttemptResult>(operation, authority, route, payload)
                pending = request
                val ready = response.state ?: invalid()
                val expectedOperation = if (authority.purpose == SecurityPurpose.Recovery) "recovery.complete" else "enrollment.complete"
                val minimumGeneration = previous.generation + if (reservedEnrollment) 1 else 0
                checkAttemptReceipt(response.receipt, expectedOperation, ready, previous.attemptId,
                    minimumGeneration, priorGeneration = previous.generation)
                if (response.receipt.recoveryGeneration != ready.recovery.generation) invalid()
                SecurityGrant(response.authority ?: invalid(), ready)
            } else {
                val (request, response) = keyed<SecurityGrant>(operation, authority, route, payload)
                pending = request
                response
            }
            if (result.state.phase != SecurityPhase.Ready || result.state.generation < previous.generation ||
                result.state.inventoryRevision < previous.inventoryRevision ||
                (previous.accountId != null && result.state.accountId != previous.accountId) ||
                (strict && (result.state.approvedMethod != previous.setupProfile?.proofMethod() ||
                    result.state.recovery.kind != previous.setupProfile?.recoveryKind() ||
                    result.state.workspaceAccess == SecurityAccess.Restricted)) ||
                (reservedEnrollment && !reservedCompletionMatches(result.state, previous))) invalid()
            val confirmed = if (reservedEnrollment) {
                validateGrant(result, SecurityPurpose.Account)
                reservedReady = result
                readReservedCompletion(operation, result, previous)
            } else result
            install(operation, confirmed, SecurityPurpose.Account) {
                mutableRecoveryReview.value = null
                reservedEnrollment = false
            }
            forget(operation, checkNotNull(pending))
        } catch (error: CoreProtocolException) {
            if (strict) {
                if (reservedReady == null) pending?.let { rejectedResponse(operation, it, error) }
                throw error
            }
            // A malformed successful response may follow an already-settled enrollment.
            // Do not keep using a restricted grant the service may have revoked.
            synchronized(lock) { checkEpoch(operation); clear() }
            throw EnrollmentAcknowledgementLost()
        } catch (error: CoreHttpException) {
            if (error.status != 401) throw error
            if (strict) {
                if (reservedReady != null) throw RecoveryAcknowledgementLost()
                if (completionStillRestricted(operation, authority, previous)) {
                    val request = synchronized(lock) { checkEpoch(operation); pendingMutation } ?: invalid()
                    forget(operation, request)
                    throw AccountSecurityProofRejected()
                }
                throw RecoveryAcknowledgementLost()
            }
            if (!replay) throw error
            synchronized(lock) { checkEpoch(operation); clear() }
            throw EnrollmentAcknowledgementLost()
        }
    }

    private fun reservedCompletionMatches(snapshot: AccountSecurityState, previous: AccountSecurityState): Boolean {
        val profile = previous.setupProfile ?: return false
        val offer = previous.recovery.offer ?: return false
        return snapshot.accountId == previous.accountId && snapshot.candidateId == null && snapshot.attemptId == null &&
            snapshot.phase == SecurityPhase.Ready && snapshot.generation == previous.generation + 1 &&
            snapshot.inventoryRevision >= previous.inventoryRevision && snapshot.approvedMethod == profile.proofMethod() &&
            snapshot.workspaceAccess == SecurityAccess.Available && snapshot.recovery.kind == profile.recoveryKind() &&
            snapshot.recovery.generation == offer.generation && previous.replacementMethodsReady() &&
            previous.methods.filter { it.state == SecurityMethodStatus.Staged && it.verified }.all { staged ->
                val activeId = if (staged.kind == SecurityMethodKind.Passkey) "passkey:${staged.id}" else staged.id
                snapshot.methods.any { it.id == activeId && it.kind == staged.kind &&
                    it.state == SecurityMethodStatus.Active && it.verified &&
                    (staged.kind == SecurityMethodKind.Passkey || it.version >= staged.version) }
            }
    }

    private suspend fun readReservedCompletion(operation: Long, result: SecurityGrant, previous: AccountSecurityState): SecurityGrant {
        val snapshot = readState(operation, result.authority)
        if (!reservedCompletionMatches(snapshot, previous) || snapshot.inventoryRevision < result.state.inventoryRevision) invalid()
        return SecurityGrant(result.authority, snapshot)
    }

    private suspend fun completionStillRestricted(
        operation: Long, authority: SecurityAuthority, previous: AccountSecurityState,
    ): Boolean = try {
        val snapshot = readState(operation, authority)
        validateGrant(SecurityGrant(authority, snapshot), authority.purpose)
        verify(operation)
        snapshot == previous
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        false
    }

    private suspend fun mutation(operation: Long, authority: SecurityAuthority, route: String, payload: JsonObject) {
        val (request, receipt) = keyed<SecurityReceipt>(operation, authority, route, payload)
        val snapshot = readState(operation, authority)
        if (route == "recovery-material/save") {
            val offer = snapshot.recovery.offer
            if (offer?.id != payload["offerId"]?.jsonPrimitive?.content ||
                offer?.generation.toString() != payload["generation"]?.jsonPrimitive?.content || offer?.saved != true) {
                rejectedResponse(operation, request)
            }
        }
        if (authority.purpose == SecurityPurpose.Recovery || reservedEnrollment) {
            if (route == "recovery-material/save" && receipt.offerId != snapshot.recovery.offer?.id) {
                rejectedResponse(operation, request)
            }
            val kind = when (route) {
                "passkeys/verify" -> SecurityMethodKind.Passkey
                "password/stage" -> SecurityMethodKind.Password
                "totp/verify" -> SecurityMethodKind.Totp
                else -> null
            }
            if (kind != null && snapshot.methods.none {
                    it.id == receipt.factorId && it.kind == kind && it.state == SecurityMethodStatus.Staged && it.verified
                }) rejectedResponse(operation, request)
        }
        install(operation, SecurityGrant(authority, snapshot), authority.purpose)
        forget(operation, request)
    }

    suspend fun retryPending(): AccountSecurityRetryResult = exclusive { operation ->
        val (reserved, public, request) = synchronized(lock) {
            checkEpoch(operation)
            Triple(pendingReservedSetup, pendingPublicStart, pendingMutation)
        }
        if (reserved != null) {
            enterReserved(operation, reserved)
        } else if (public != null) {
            enterRecovery(operation, public)
        } else {
            val pending = request ?: invalid()
            val authority = pending.authority
            if (reservedEnrollment && pending.route == "enrollment/complete/verify" && pending.response != null) {
                synchronized(lock) { checkEpoch(operation); check(grant === authority) }
            } else check(authority(operation, authority.purpose) === authority)
            val payload = pending.payload
            when (pending.route) {
                "recovery/review" -> reviewRecovery(operation, authority, payload)
                "recovery/secure" -> secureRecovery(operation, authority, payload)
                "enrollment/complete/verify", "recovery/complete/verify" ->
                    settle(operation, authority, pending.route, payload, replay = true)
                "totp/prepare" -> return@exclusive AccountSecurityRetryResult.Totp(prepareTotp(operation, authority, payload))
                "recovery-material/prepare" ->
                    return@exclusive AccountSecurityRetryResult.Material(prepareRecovery(operation, authority, payload))
                else -> mutation(operation, authority, pending.route, payload)
            }
        }
        AccountSecurityRetryResult.Updated
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
            val strict = authority.purpose == SecurityPurpose.Recovery || reservedEnrollment
            val existing = pendingMutation?.takeIf { it.authority === authority && it.route == route && it.payload == payload }
            if (strict && existing == null) check(pendingMutation == null)
            (existing ?: PendingMutation(route, authority, payload, key()).also { pendingMutation = it }).also {
                if (strict) mutableRetry.value = routeRetries[route]
            }
        }
        val cached = request.response as? T
        if (cached != null) return request to cached
        val result = try {
            api.accountSecurity<T>(route, authority, payload, request.requestKey)
        } catch (error: CoreHttpException) {
            // Ambiguous responses retain the exact payload/key. An explicit conflict can only be
            // followed by a new user decision against a refreshed snapshot.
            val preserveOnUnauthorized = mutableRetry.value in setOf(Retry.Secure, Retry.Complete)
            if (error.status in definitiveRejection && !(error.status == 401 && preserveOnUnauthorized)) {
                forget(operation, request)
                if (error.status == 409) {
                    val snapshot = SecurityGrant(authority, readState(operation, authority))
                    val review = if (authority.purpose == SecurityPurpose.Recovery) readCurrentReview(operation, snapshot) else null
                    install(operation, snapshot, authority.purpose) { mutableRecoveryReview.value = review }
                }
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
            if (pendingMutation === request) {
                pendingMutation = null
                mutableRetry.value = null
            }
        }
    }

    private fun rejectedResponse(operation: Long, request: PendingMutation, error: Exception = CoreProtocolException()): Nothing {
        synchronized(lock) {
            checkEpoch(operation)
            if (pendingMutation === request) request.response = null
        }
        throw error
    }

    private suspend fun readState(operation: Long, authority: SecurityAuthority): AccountSecurityState {
        verify(operation)
        return api.accountSecurity<AccountSecurityState>("state", authority).also { verify(operation) }
    }

    private suspend fun refresh(operation: Long, authority: SecurityAuthority) {
        install(operation, SecurityGrant(authority, readState(operation, authority)), authority.purpose)
    }

    private fun validateGrant(result: SecurityGrant, purpose: SecurityPurpose) {
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
            (purpose == SecurityPurpose.Enrollment && (snapshot.phase != SecurityPhase.Enrolling ||
                snapshot.setupProfile == null || !snapshot.hasEnrollmentRevisions())) ||
            (purpose == SecurityPurpose.Recovery && (snapshot.accountId?.let(coreIdentifier::matches) != true ||
                snapshot.phase !in setOf(SecurityPhase.Ready, SecurityPhase.Recovering) ||
                !snapshot.hasAttemptRevisions() || (snapshot.phase == SecurityPhase.Recovering && snapshot.setupProfile == null)))) invalid()
    }

    private suspend fun install(
        operation: Long, result: SecurityGrant, purpose: SecurityPurpose, onInstall: () -> Unit = {},
    ) {
        validateGrant(result, purpose)
        val authority = result.authority
        val reservedCompletion = synchronized(lock) { checkEpoch(operation); expectedSignIn?.reservedCompletion }
        val snapshot = if (purpose == SecurityPurpose.Account && reservedCompletion != null) {
            if (!reservedCompletionMatches(result.state, reservedCompletion)) throw AccountSecurityIdentityMismatch()
            readReservedCompletion(operation, result, reservedCompletion).also { validateGrant(it, purpose) }.state
        } else result.state
        verify(operation)
        synchronized(lock) {
            checkEpoch(operation)
            val prior = mutableState.value
            if (grant === authority && prior != null && (prior.accountId != snapshot.accountId ||
                prior.candidateId != snapshot.candidateId || prior.generation > snapshot.generation ||
                prior.inventoryRevision > snapshot.inventoryRevision ||
                prior.attemptId != snapshot.attemptId ||
                (prior.attemptRevision ?: 0) > (snapshot.attemptRevision ?: 0) ||
                (prior.intentRevision ?: 0) > (snapshot.intentRevision ?: 0))) invalid()
            if (reservedEnrollment && purpose == SecurityPurpose.Enrollment && prior != null &&
                (prior.generation != snapshot.generation || prior.setupProfile != snapshot.setupProfile ||
                    snapshot.workspaceAccess != SecurityAccess.Restricted)) invalid()
            expectedSignIn?.let { expected ->
                if (purpose != SecurityPurpose.Account || snapshot.accountId != expected.accountId ||
                    snapshot.phase != SecurityPhase.Ready || snapshot.approvedMethod != expected.method) {
                    throw AccountSecurityIdentityMismatch()
                }
                expectedSignIn = null
            }
            if (grant !== authority) clearNoticesLocked()
            grant = authority
            login = null
            pendingEnrollment = null
            onInstall()
            mutableState.value = snapshot
        }
    }

    private fun revisionBody(fields: JsonObjectBuilder.() -> Unit = {}): JsonObject = body {
        val snapshot = state.value ?: invalid()
        put("attemptId", snapshot.attemptId?.let(::JsonPrimitive) ?: JsonNull)
        put("expectedGeneration", snapshot.generation)
        put("expectedInventoryRevision", snapshot.inventoryRevision)
        put("expectedAttemptRevision", snapshot.attemptRevision?.let(::JsonPrimitive) ?: JsonNull)
        put("expectedIntentRevision", snapshot.intentRevision?.let(::JsonPrimitive) ?: JsonNull)
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

    private fun randomSecret() =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))

    private fun invalid(): Nothing = throw CoreProtocolException()

    private companion object {
        val definitiveRejection = (400..499).toSet() - setOf(408, 425, 429)
        val routeRetries = mapOf(
            "recovery/review" to Retry.Review,
            "recovery-material/prepare" to Retry.PrepareMaterial,
            "recovery-material/save" to Retry.SaveMaterial,
            "recovery/secure" to Retry.Secure,
            "passkeys/verify" to Retry.RegisterPasskey,
            "password/stage" to Retry.StagePassword,
            "totp/prepare" to Retry.PrepareTotp,
            "totp/verify" to Retry.VerifyTotp,
            "enrollment/complete/verify" to Retry.Complete,
            "recovery/complete/verify" to Retry.Complete,
        )
    }
}

private fun AccountSecurityState.hasAttemptRevisions() =
    generation > 0 && attemptId?.let(securityOpaqueId::matches) == true &&
        (attemptRevision ?: 0) in 1..CORE_MAX_VERSION && intentRevision == null

private fun AccountSecurityState.hasEnrollmentRevisions() =
    hasAttemptRevisions() || (attemptId == null && attemptRevision == null && (intentRevision ?: 0) in 1..CORE_MAX_VERSION)

private fun SecurityProfile.recoveryKind() = when (this) {
    SecurityProfile.Passkeys -> RecoveryKind.Kit
    SecurityProfile.PasswordTotp -> RecoveryKind.Codes
}

private fun SecurityProfile.proofMethod() = when (this) {
    SecurityProfile.Passkeys -> SecurityProof.Passkey
    SecurityProfile.PasswordTotp -> SecurityProof.PasswordTotp
}

fun AccountSecurityState.replacementMethodsReady(): Boolean {
    val staged = methods.filter { it.verified && it.state == SecurityMethodStatus.Staged }
    return when (setupProfile) {
        SecurityProfile.Passkeys -> {
            val new = staged.count { it.kind == SecurityMethodKind.Passkey }
            val retained = methods.count { it.kind == SecurityMethodKind.Passkey && it.state == SecurityMethodStatus.Active && it.verified }
            if (purpose == SecurityPurpose.Recovery) new > 0 && (new + retained) >= 2 else new >= 2
        }
        SecurityProfile.PasswordTotp -> verifiedSetupCount(SecurityMethodKind.Password) == 1 &&
            verifiedSetupCount(SecurityMethodKind.Totp) == 1
        null -> false
    }
}

fun AccountSecurityState.verifiedSetupCount(kind: SecurityMethodKind): Int = methods.count {
    it.kind == kind && it.verified && (it.state == SecurityMethodStatus.Staged || purpose == SecurityPurpose.Recovery)
}
