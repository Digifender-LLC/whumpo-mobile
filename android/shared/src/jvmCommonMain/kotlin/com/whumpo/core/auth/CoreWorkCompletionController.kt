package com.whumpo.core.auth

import com.whumpo.core.api.*
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class CoreWorkCompletionNotice { Unconfirmed, Changed, FreshOwnerRequired, Rejected, ReviewChanged }
data class CoreWorkCompletionState(
    val busy: Boolean = false,
    val accepted: Boolean = false,
    val started: Boolean = false,
    val approval: PartnerApproval? = null,
    val proof: PartnerTaskProof? = null,
    val notice: CoreWorkCompletionNotice? = null,
)

/** One frozen owner assertion; retry reuses its original request body and operation key. */
class CoreWorkCompletionController(
    private val sessions: CoreOrganizationSessionController,
    private val active: CoreOrganizationSessionController.State.Active,
    val task: CoreWorkTask,
) {
    private val command = PartnerWrite(partnerOperationKey(), buildJsonObject {
        put("status", "done"); put("reason", reason)
    }.toString())
    private val protocol = CorePartnerProtocol(sessions, active)
    private val running = Mutex()
    private val mutableState = MutableStateFlow(CoreWorkCompletionState())
    val state = mutableState.asStateFlow()
    private var accepted: CoreTaskCommandAcceptance? = null
    private var decision: Pair<PartnerApproval, PartnerWrite>? = null
    @Volatile private var retired = false

    init {
        require(task.status == CoreTaskStatus.InProgress && task.archivedAt == null &&
            coreIdentifier.matches(task.id) && task.version in 1 until CORE_MAX_VERSION)
    }

    suspend fun submit() = perform {
        if (accepted == null) {
            update { it.copy(started = true) }
            val result = request { api, token ->
                api.partner<CoreTaskCommandAcceptance>(token, PartnerRoute.WorkTransition, task.id,
                    key = command.key, body = command.body, version = task.version)
            }
            if (!coreIdentifier.matches(result.runId) || !coreIdentifier.matches(result.effectId) ||
                result.approvalId?.let { !coreIdentifier.matches(it) } == true ||
                (result.status == CoreTaskCommandStatus.WaitingApproval && result.approvalId == null)) throw CoreProtocolException()
            accepted = result
        }
        refreshProof()
    }

    suspend fun refresh() = perform { if (accepted != null) refreshProof() }

    /** Uses the existing canonical proposal decision implementation; no local approval/grant is made. */
    suspend fun approve() = perform {
        val old = state.value.approval ?: return@perform
        val fresh = approval(checkNotNull(accepted))
        if (old != fresh || fresh.status != PartnerApprovalStatus.pending || Instant.parse(fresh.expiresAt) <= Instant.now()) {
            update { it.copy(approval = fresh, notice = CoreWorkCompletionNotice.ReviewChanged) }; return@perform
        }
        val pending = decision?.takeIf { it.first == fresh }?.second
            ?: PartnerWrite.decision(fresh, PartnerDecision.approve).also { decision = fresh to it }
        protocol.decide(fresh, PartnerDecision.approve, pending)
        refreshProof()
    }

    fun retire() { retired = true; mutableState.value = CoreWorkCompletionState() }

    private suspend fun refreshProof() {
        val intent = checkNotNull(accepted)
        val review = if (intent.approvalId != null) approval(intent) else null
        if (review?.status == PartnerApprovalStatus.pending) {
            update { it.copy(accepted = true, approval = review, proof = PartnerTaskProof.WaitingApproval) }; return
        }
        val effect = request { api, token -> api.partner<PartnerEffect>(token, PartnerRoute.Effect, intent.effectId) }
        if (effect.id != intent.effectId || effect.runId != intent.runId || !coreDigest.matches(effect.payloadHash) ||
            effect.version !in 1..CORE_MAX_VERSION || effect.policyRevision !in 0..CORE_MAX_VERSION ||
            effect.approvalId != intent.approvalId || (effect.taskId != null) != (effect.state == CoreEffectState.Confirmed) ||
            effect.taskId?.let { it != task.id } == true ||
            effect.errorCode?.let { !Regex("[A-Z0-9_]{1,64}").matches(it) } == true ||
            review?.let { it.payloadHash != effect.payloadHash || it.policyRevision != effect.policyRevision } == true) throw CoreProtocolException()
        val proof = when (effect.state) {
            CoreEffectState.Queued -> PartnerTaskProof.Queued
            CoreEffectState.WaitingApproval -> PartnerTaskProof.WaitingApproval
            CoreEffectState.Failed -> PartnerTaskProof.Failed
            CoreEffectState.Canceled -> PartnerTaskProof.Canceled
            CoreEffectState.Confirmed -> {
                if (review != null && review.status != PartnerApprovalStatus.approved) throw CoreProtocolException()
                verified(intent)
            }
        }
        update { it.copy(accepted = true, approval = review, proof = proof) }
    }

    private suspend fun approval(intent: CoreTaskCommandAcceptance): PartnerApproval {
        val id = checkNotNull(intent.approvalId)
        val value = request { api, token -> api.partner<PartnerApproval>(token, PartnerRoute.Approval, id) }
        val payload = value.proposal.payload
        if (value.id != id || value.effectId != intent.effectId || value.runId != intent.runId ||
            value.requesterId != active.session.userId || !coreDigest.matches(value.payloadHash) ||
            value.version !in 1..CORE_MAX_VERSION || value.policyRevision !in 0..CORE_MAX_VERSION ||
            value.proposal.operation != PartnerTool.Transition || value.proposal.taskId != task.id ||
            value.proposal.expectedVersion != task.version || payload.keys != setOf("status", "reason") ||
            payload["status"] != JsonPrimitive("done") || payload["reason"] != JsonPrimitive(reason) ||
            value.errorCode?.let { !Regex("[A-Z0-9_]{1,64}").matches(it) } == true) throw CoreProtocolException()
        Instant.parse(value.expiresAt)
        return value
    }

    private suspend fun verified(intent: CoreTaskCommandAcceptance): PartnerTaskProof.Verified {
        val receipt = request { api, token -> api.partner<PartnerReceipts>(token, PartnerRoute.Receipts, intent.effectId) }
            .items.singleOrNull() ?: throw CoreProtocolException()
        val evidence = receipt.taskEvidence
        if (receipt.schemaVersion != 1 || receipt.orgId != active.session.orgId || receipt.effectId != intent.effectId ||
            receipt.runId != intent.runId || !coreIdentifier.matches(receipt.receiptId) ||
            evidence.taskId != task.id || evidence.taskVersion != task.version + 1 || !coreDigest.matches(evidence.taskDigest) ||
            receipt.completion?.actorId != active.session.userId ||
            !partnerText(receipt.redactedSummary, 8000) || Instant.parse(receipt.verifiedAt) > Instant.parse(receipt.createdAt)) throw CoreProtocolException()
        val current = sessions.canonicalTaskForPartner(active, task.id)
        if (current.version < evidence.taskVersion || current.version == evidence.taskVersion && current.status != CoreTaskStatus.Done) throw CoreProtocolException()
        return PartnerTaskProof.Verified(current, receipt.receiptId, evidence.taskVersion)
    }

    private suspend fun <T> request(action: suspend (CoreApiClient, String) -> T): T {
        checkCurrent()
        return sessions.requestForActiveSession(active, action).also { checkCurrent() }
    }

    private suspend fun perform(action: suspend () -> Unit) {
        if (!running.tryLock()) return
        try {
            checkCurrent()
            if (active.session.role != CoreRole.owner) {
                update { it.copy(notice = CoreWorkCompletionNotice.FreshOwnerRequired) }; return
            }
            update { it.copy(busy = true, notice = null) }
            val completed = withTimeoutOrNull(15_000) { action(); true } ?: false
            if (!completed) update { it.copy(notice = CoreWorkCompletionNotice.Unconfirmed) }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            update { it.copy(notice = when {
                error is CoreHttpException && error.status == 409 -> CoreWorkCompletionNotice.Changed
                error is CoreHttpException && error.status == 403 -> CoreWorkCompletionNotice.FreshOwnerRequired
                error is PartnerHttpException && error.rejectsWrite -> CoreWorkCompletionNotice.Rejected
                else -> CoreWorkCompletionNotice.Unconfirmed
            }) }
        } finally { update { it.copy(busy = false) }; running.unlock() }
    }

    private fun checkCurrent() {
        if (retired || !sessions.commitIfCurrent(active.epoch) {}) throw CancellationException("Work completion changed")
    }
    private fun update(change: (CoreWorkCompletionState) -> CoreWorkCompletionState) {
        sessions.commitIfCurrent(active.epoch) { if (!retired) mutableState.value = change(mutableState.value) }
    }
    private companion object { const val reason = "Completed from Work." }
}
