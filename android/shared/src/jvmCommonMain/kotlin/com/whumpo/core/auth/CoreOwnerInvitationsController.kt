package com.whumpo.core.auth

import com.whumpo.core.api.CoreApiClient
import com.whumpo.core.api.CoreHttpException
import com.whumpo.core.api.CoreInvitationStatus
import com.whumpo.core.api.CoreOrganizationEntitlements
import com.whumpo.core.api.CorePersonInvitation
import com.whumpo.core.api.CoreProtocolException
import com.whumpo.core.api.CoreRole
import com.whumpo.core.api.SecurityAccess
import com.whumpo.core.api.SecurityGrant
import com.whumpo.core.api.SecurityPhase
import com.whumpo.core.api.SecurityPurpose
import com.whumpo.core.api.accountGrantBearer
import com.whumpo.core.api.coreIdentifier
import com.whumpo.core.api.isInvitationRole
import com.whumpo.core.api.newPersonInvitationCode
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class CoreInvitationNotice { IssuedWithoutCode, Revoked, Copied, Unavailable, ReviewChanged, Unknown }

data class CoreOwnerInvitationState(
    val seats: CoreOrganizationEntitlements? = null,
    val invitations: List<CorePersonInvitation> = emptyList(),
    val historyLoaded: Boolean = false,
    val nextCursor: String? = null,
    val busy: Boolean = false,
    val canRetry: Boolean = false,
    val codeExpiresAt: Long? = null,
    val notice: CoreInvitationNotice? = null,
)

class CoreOwnerInvitationsController(
    private val sessions: CoreOrganizationSessionController,
    private val active: CoreOrganizationSessionController.State.Active,
    private val now: () -> Long = System::currentTimeMillis,
    private val canInteract: () -> Boolean = { true },
) {
    private class AuthorityChanged : Exception()
    private class GrantRejected : Exception()
    private sealed class Intent {
        class Issue(val role: CoreRole) : Intent()
        class Revoke(val invite: CorePersonInvitation) : Intent()
    }
    private class Attempt(val intent: Intent, val body: String, val key: String = UUID.randomUUID().toString())
    private class Code(val value: String, val until: Long)

    private val identity = active.session
    private val gate = Mutex()
    private val mutableState = MutableStateFlow(CoreOwnerInvitationState())
    val state = mutableState.asStateFlow()
    private var grant: SecurityGrant? = null
    private var attempt: Attempt? = null
    private var code: Code? = null
    private var disclosureEpoch = 0L
    @Volatile private var retired = false

    fun clearCode() = update {
        disclosureEpoch++
        code = null
        mutableState.value = state.value.copy(codeExpiresAt = null)
    }

    fun retire() {
        // Closing the view owns these values even after an organization switch.
        retired = true
        grant = null
        attempt = null
        code = null
        mutableState.value = CoreOwnerInvitationState()
    }

    suspend fun refreshSeats() = operation {
        val seats = owner { api, token -> checkedSeats(api, token) }
        update { mutableState.value = state.value.copy(seats = seats) }
    }

    suspend fun loadHistory(more: Boolean = false) = operation {
        val cursor = if (more) state.value.nextCursor ?: return@operation else null
        val body = body {
            put("cursor", cursor?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: JsonNull)
            put("limit", 20)
        }
        val page = owner { api, token ->
            val authority = account(api, token)
            grantCall { api.invitationPage(authority.authority, body) }
        }
        val ids = page.items.map { it.invitationId }
        if (ids.size > 20 || ids.distinct().size != ids.size ||
            page.nextCursor?.let { !coreIdentifier.matches(it) || it == cursor || it != ids.lastOrNull() } == true) {
            invalid()
        }
        page.items.forEach(::checkInvitation)
        update {
            val prior = if (more) state.value.invitations else emptyList()
            mutableState.value = state.value.copy(invitations = (prior + page.items).distinctBy { it.invitationId },
                nextCursor = page.nextCursor, historyLoaded = true)
        }
    }

    suspend fun issue(role: CoreRole) = operation {
        if (!isInvitationRole(role) || attempt != null) return@operation
        clearCode()
        if (!owner { api, token -> checkedSeats(api, token) }.teamAdmissionAvailable) {
            update { mutableState.value = state.value.copy(seats = null, notice = CoreInvitationNotice.ReviewChanged) }
            return@operation
        }
        submit(Attempt(Intent.Issue(role), body { put("role", role.name) }))
    }

    suspend fun revoke(invite: CorePersonInvitation) = operation {
        if (attempt != null || state.value.invitations.none { it == invite } ||
            invite.status != CoreInvitationStatus.pending || expiration(invite) <= now()) return@operation
        clearCode()
        submit(Attempt(Intent.Revoke(invite), body {
            put("invitationId", invite.invitationId)
            put("expectedVersion", invite.version)
        }))
    }

    suspend fun retry() = operation { attempt?.let { submit(it) } }

    suspend fun copyCode(deliver: (String) -> Unit) = operation {
        val pending = code ?: return@operation
        owner { _, _ -> Unit }
        update {
            if (code === pending && pending.until > now()) {
                deliver(pending.value)
                clearCode()
                mutableState.value = state.value.copy(notice = CoreInvitationNotice.Copied)
            } else clearCode()
        }
    }

    private suspend fun submit(request: Attempt) {
        val disclose = disclosureEpoch
        var codeToShow: Code? = null
        val invitation = owner { api, token ->
            val authority = account(api, token)
            requireScope()
            attempt = request
            when (val intent = request.intent) {
                is Intent.Issue -> {
                    val result = grantCall { api.issueInvitation(authority.authority, request.body, request.key) }
                    val invite = result.invitation
                    checkInvitation(invite)
                    if (invite.issuerUserId != identity.userId || invite.role != intent.role) invalid()
                    result.invitationCode?.let { value ->
                        if (!newPersonInvitationCode.matches(value) || invite.status != CoreInvitationStatus.pending) invalid()
                        val until = minOf(expiration(invite), identity.expiresAt, authority.authority.expiresAt,
                            authority.state.freshUntil ?: 0)
                        if (until > now() && disclose == disclosureEpoch) {
                            requireOwner(api, token)
                            val preview = api.invitationPreview(value)
                            if (preview.invitationId != invite.invitationId || preview.orgId != invite.orgId ||
                                preview.role != invite.role || preview.expiresAt != invite.expiresAt ||
                                preview.organizationName?.let { it.isBlank() || it.length > 200 } == true) invalid()
                            if (until > now()) codeToShow = Code(value, until)
                        }
                    }
                    invite
                }
                is Intent.Revoke -> grantCall { api.revokeInvitation(authority.authority, request.body, request.key) }.also {
                    checkInvitation(it)
                    val original = intent.invite
                    if (it.status != CoreInvitationStatus.revoked || it.invitationId != original.invitationId ||
                        it.role != original.role || it.issuerUserId != original.issuerUserId ||
                        it.createdAt != original.createdAt || it.expiresAt != original.expiresAt) invalid()
                }
            }
        }
        update {
            attempt = null
            val previous = state.value
            if (disclose == disclosureEpoch) code = codeToShow?.takeIf { it.until > now() }
            val notice = when (request.intent) {
                is Intent.Revoke -> CoreInvitationNotice.Revoked
                is Intent.Issue -> if (code == null) CoreInvitationNotice.IssuedWithoutCode else null
            }
            mutableState.value = previous.copy(seats = null, codeExpiresAt = code?.until, canRetry = false,
                invitations = listOf(invitation) + previous.invitations.filter { it.invitationId != invitation.invitationId },
                notice = notice)
        }
        // Seat refresh is independent of the authoritative mutation outcome.
        try {
            val seats = owner { api, token -> checkedSeats(api, token) }
            update { mutableState.value = state.value.copy(seats = seats) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: AuthorityChanged) {
            throw error
        } catch (_: Exception) {
            // The UI offers a separate seat refresh.
        }
    }

    private suspend fun <T> owner(block: suspend (CoreApiClient, String) -> T): T {
        requireScope()
        return sessions.requestForActiveSession(active) { api, token ->
            requireOwner(api, token)
            val result = block(api, token)
            requireOwner(api, token)
            result
        }
    }

    private suspend fun requireOwner(api: CoreApiClient, token: String) {
        requireScope()
        if (api.session(token) != identity) throw AuthorityChanged()
        requireScope()
    }

    private suspend fun account(api: CoreApiClient, token: String): SecurityGrant {
        val result = grant?.takeIf { it.authority.expiresAt > now() } ?: api.invitationAccount(token)
        requireOwner(api, token)
        val (authority, state) = result.let { it.authority to it.state }
        if (authority.kind != "account_grant" || authority.purpose != SecurityPurpose.Account ||
            !accountGrantBearer.matches(authority.token) || state.version != 1 || state.purpose != SecurityPurpose.Account ||
            state.accountId != identity.userId || state.phase != SecurityPhase.Ready ||
            state.workspaceAccess != SecurityAccess.Available || authority.expiresAt != state.expiresAt ||
            authority.expiresAt <= now() || state.freshUntil?.let { it > authority.expiresAt } == true) invalid()
        grant = result
        return result
    }

    private suspend fun <T> grantCall(call: suspend () -> T): T = try {
        call()
    } catch (error: CoreHttpException) {
        if (error.status != 401) throw error
        grant = null
        throw GrantRejected()
    }

    private suspend fun checkedSeats(api: CoreApiClient, token: String): CoreOrganizationEntitlements =
        api.entitlements(token).also {
            if (it.orgId != identity.orgId || it.availableHumanSeats < 0 || it.activeHumanSeats < 0 ||
                it.reservedHumanSeats < 0 || it.humanSeatLimit?.let { it < 0 } == true ||
                it.teamAdmissionAvailable != (it.availableHumanSeats > 0)) invalid()
        }

    private fun checkInvitation(invite: CorePersonInvitation) {
        if (invite.orgId != identity.orgId || !coreIdentifier.matches(invite.invitationId) ||
            !coreIdentifier.matches(invite.issuerUserId) || !isInvitationRole(invite.role)) invalid()
        val created = timestamp(invite.createdAt)
        val expires = expiration(invite)
        if (expires <= created) invalid()
        val acceptedAt = invite.acceptedAt?.let(::timestamp)
        val revokedAt = invite.revokedAt?.let(::timestamp)
        val valid = when (invite.status) {
            CoreInvitationStatus.accepted -> invite.version == 2 && acceptedAt != null &&
                acceptedAt in created until expires && invite.acceptedUserId?.let(coreIdentifier::matches) == true && revokedAt == null
            CoreInvitationStatus.revoked -> invite.version == 2 && revokedAt != null &&
                revokedAt >= created && acceptedAt == null && invite.acceptedUserId == null
            else -> invite.version == 1 && acceptedAt == null && invite.acceptedUserId == null && revokedAt == null
        }
        if (!valid) invalid()
    }

    private fun expiration(invite: CorePersonInvitation) = timestamp(invite.expiresAt)
    private fun timestamp(value: String) = runCatching { Instant.parse(value).toEpochMilli() }.getOrElse { invalid() }
    private fun invalid(): Nothing = throw CoreProtocolException()

    private fun body(fields: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject {
        put("version", 1)
        put("orgId", identity.orgId)
        fields()
    }.toString()

    private suspend fun requireScope() {
        currentCoroutineContext().ensureActive()
        if (retired || !canInteract() || !sessions.commitIfCurrent(active.epoch) {}) {
            throw CancellationException("Invitation view changed")
        }
        if (identity.role != CoreRole.owner) throw AuthorityChanged()
    }

    private fun update(block: () -> Unit) {
        sessions.commitIfCurrent(active.epoch) { if (!retired) block() }
    }

    private suspend fun operation(block: suspend () -> Unit) {
        if (!gate.tryLock()) return
        try {
            requireScope()
            update { mutableState.value = state.value.copy(busy = true, notice = null) }
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            clearCode()
            val denied = error is AuthorityChanged || error is CoreHttpException && error.status in setOf(403, 423)
            if (denied) grant = null
            update {
                val notice = if (attempt != null) CoreInvitationNotice.Unknown else CoreInvitationNotice.Unavailable
                mutableState.value = (if (denied) CoreOwnerInvitationState() else state.value)
                    .copy(busy = true, canRetry = attempt != null, notice = notice)
            }
        } finally {
            update {
                val unsettled = attempt != null
                mutableState.value = state.value.copy(busy = false, canRetry = unsettled,
                    notice = if (unsettled) CoreInvitationNotice.Unknown else state.value.notice)
            }
            gate.unlock()
        }
    }
}
