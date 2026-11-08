package com.whumpo.android.ui.chat

import com.whumpo.core.api.ChatPage
import com.whumpo.core.api.ChatTurn
import com.whumpo.core.api.ClientInfo
import com.whumpo.core.api.MobileApiClient
import com.whumpo.core.api.MobileApiException
import com.whumpo.core.api.Review
import com.whumpo.core.api.WriteAccepted
import com.whumpo.core.api.newRequestId
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Re-proves the owner with a fresh passkey (WP5 implements it with Credential Manager plus the client's
 * `reauthOptions`/`reauthVerify`). Returns true once the session is fresh. Chat only calls it after the server
 * answered `proof_required` with `fresh_passkey`, then retries the same request ID.
 */
fun interface ReauthHook {
    suspend fun reauthenticate(): Boolean
}

sealed interface Phase {
    data object Loading : Phase
    data object Ready : Phase

    /** [what] says what happened, [safe] says what is safe. */
    data class Failed(val what: String, val safe: String) : Phase
    data object UpdateRequired : Phase
}

enum class SendStatus {
    /** The request is in flight. */
    Sending,

    /** The reply was lost: the message may or may not exist. Resolve by reading, never by sending again. */
    Unknown,

    /** A read confirmed nothing was committed. The owner may send again with the same ID. */
    NotCommitted,
}

/** The message being sent. [requestId] is the turn ID and is reused for every retry of this one send. */
data class Outbound(val requestId: String, val content: String, val expectedRevision: Int, val status: SendStatus)

enum class ReviewStep { Ready, Reauthenticating, Confirming, Pending, Uncertain, Done, Failed, NoLongerCurrent, UpdateRequired }

data class ReviewFlow(
    val turnId: String,
    /** The conversation revision when the sheet opened, sent as `expectedRevision` on confirm and kept for every retry of it. */
    val revision: Int,
    val review: Review,
    /** One ID per review sheet, reused for every retry of the confirm. */
    val requestId: String,
    val step: ReviewStep,
    val message: String? = null,
    /** A committed failure or an unresolved request must not offer another POST. */
    val retryAllowed: Boolean = true,
) {
    val busy: Boolean get() = step == ReviewStep.Confirming || step == ReviewStep.Reauthenticating
    val canConfirm: Boolean get() = (step == ReviewStep.Ready || step == ReviewStep.Failed) && retryAllowed && review.control != null
}

enum class HapticKind { Confirm, Reject }

/** A one-shot haptic request; [seq] makes each cue distinct so the UI performs it once. */
data class HapticCue(val kind: HapticKind, val seq: Int)

data class ChatState(
    val phase: Phase = Phase.Loading,
    val turns: List<ChatTurn> = emptyList(),
    val revision: Int = 0,
    val outbound: Outbound? = null,
    /** A short inline error about the last send or refresh. */
    val notice: String? = null,
    val offline: Boolean = false,
    val review: ReviewFlow? = null,
    /** Text for the polite live region. */
    val announcement: String = "",
    val haptic: HapticCue? = null,
) {
    val replyPending: Boolean get() = turns.any { it.status == "pending" }

    /** True while the typing indicator shows. */
    val typing: Boolean get() = replyPending || outbound?.status == SendStatus.Sending
}

/**
 * Conversation logic for the Chat screen: loading, sending, readback after a lost reply, polling a pending reply
 * and the review-and-confirm flow. All state is in [state]; there is no Android code here.
 *
 * The rules that matter:
 * - A send takes its request ID once. A lost reply is resolved by reading `chat/turns/{id}` ([MobileApiClient.resolveSend]).
 *   This class never re-sends on its own; [sendAgain] is an explicit owner action with the same ID, revision and text.
 * - A confirm uses the conversation revision from when its sheet opened and one request ID per sheet. A fresh-passkey demand goes through [ReauthHook]
 *   and then retries with the same ID.
 */
class ChatSession(
    private val scope: CoroutineScope,
    private val client: MobileApiClient,
    private val clientInfo: ClientInfo,
    private val reauth: ReauthHook,
    private val onSendCommitted: () -> Unit = {},
    private val newId: () -> String = ::newRequestId,
    private val pollDelayMs: Long = POLL_DELAY_MS,
    private val maxPolls: Int = MAX_POLLS,
) {
    private val mutable = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = mutable.asStateFlow()

    private val parent: Job? = scope.coroutineContext[Job]
    private var work: CoroutineScope = newWork()
    private var pollJob: Job? = null
    private var seq = 0
    private val confirmIds = mutableMapOf<String, String>()
    // Submitted payloads and outcomes outlive their sheet; reopening cannot replace them with a new revision/control.
    private val confirmAttempts = mutableMapOf<String, ReviewFlow>()
    private val checkingOutcomes = mutableSetOf<String>()

    private fun newWork() = CoroutineScope(scope.coroutineContext + SupervisorJob(parent))

    /** Forget everything (sign-out or account change) and stop all work. */
    fun reset() {
        work.cancel()
        work = newWork()
        pollJob = null
        confirmIds.clear()
        confirmAttempts.clear()
        checkingOutcomes.clear()
        mutable.value = ChatState()
    }

    fun load() = launchWork {
        if (mutable.value.phase !is Phase.Ready) mutable.update { it.copy(phase = Phase.Loading) }
        refresh()
    }

    /** Re-reads the conversation (the retry button and "check again"). */
    fun refreshNow() = launchWork { refresh() }

    /** Suspends until all launched work, polling included, has finished. For tests and shutdown. */
    internal suspend fun awaitIdle() {
        while (true) {
            val running = work.coroutineContext[Job]?.children?.toList().orEmpty()
            if (running.isEmpty()) return
            running.forEach { it.join() }
        }
    }

    private fun launchWork(block: suspend () -> Unit) {
        work.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The UI edge: anything unexpected becomes a calm message, never a crash.
                mutable.update { it.copy(notice = "Something went wrong. Nothing was changed.") }
            }
        }
    }

    // ---- Reading ---------------------------------------------------------------------------------------------------

    private suspend fun refresh() {
        try {
            val page = client.getChat(limit = PAGE_LIMIT)
            currentCoroutineContext().ensureActive()
            applyPage(page)
        } catch (e: MobileApiException) {
            refreshFailed(e)
        }
        // A receipt can be older than the newest page, or readable even when that page cannot be refreshed.
        confirmAttempts.values.filter { it.step == ReviewStep.Pending || it.step == ReviewStep.Uncertain }
            .toList().forEach { checkOutcome(it) }
    }

    private fun applyPage(page: ChatPage) {
        mutable.update { s ->
            val known = s.turns.associateBy { it.id }
            val reply = page.items.lastOrNull { it.status == "completed" && it.assistantText != null && known[it.id]?.assistantText == null }
            val announce = if (s.phase is Phase.Ready && reply != null) "Whumpo replied: ${reply.assistantText.orEmpty().take(ANNOUNCE_CHARS)}" else s.announcement
            s.copy(phase = Phase.Ready, turns = page.items, revision = page.revision, offline = false, announcement = announce)
        }
        confirmAttempts.values.filter { it.canConfirm && !reviewStillCurrent(it) }.toList().forEach {
            setStep(it.requestId, ReviewStep.NoLongerCurrent, "This review is no longer current.", retryAllowed = false)
        }
        if (page.items.any { it.status == "pending" }) startPolling()
    }

    private fun refreshFailed(e: MobileApiException) {
        val confirmationUnresolved = confirmAttempts.values.any {
            it.step == ReviewStep.Confirming || it.step == ReviewStep.Pending || it.step == ReviewStep.Uncertain
        }
        mutable.update { s ->
            when {
                e is MobileApiException.ClientUpdateRequired -> s.copy(phase = Phase.UpdateRequired)
                s.turns.isNotEmpty() -> s.copy(
                    offline = e is MobileApiException.Transport,
                    notice = if (e is MobileApiException.Transport) s.notice else if (confirmationUnresolved) e.headline() else e.userMessage(),
                )
                else -> s.copy(phase = Phase.Failed(e.headline(), if (confirmationUnresolved) "Check the confirmation result before trying again." else e.safeLine()))
            }
        }
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = work.launch {
            try {
                var polls = 0
                while (polls < maxPolls) {
                    delay(pollDelayMs)
                    polls += 1
                    refresh()
                    val s = mutable.value
                    if (s.phase !is Phase.Ready || !s.replyPending) break
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Polling is best effort; the owner can refresh by hand.
            }
        }
    }

    // ---- Sending ---------------------------------------------------------------------------------------------------

    /** Sends [text] as a new message. Ignored while another send is open or a reply is pending (a duplicate tap sends once). */
    fun send(text: String) {
        val content = text.trim()
        val s = mutable.value
        if (content.isEmpty() || content.length > MAX_MESSAGE_CHARS) return
        if (s.phase !is Phase.Ready || s.outbound != null || s.replyPending) return
        val out = Outbound(newId(), content, s.revision, SendStatus.Sending)
        mutable.update { it.copy(outbound = out, notice = null) }
        launchWork { transmit(out) }
    }

    /** The owner chose "Send again" after a read confirmed nothing was committed. Same ID, revision and text. */
    fun sendAgain() {
        val out = mutable.value.outbound ?: return
        if (out.status != SendStatus.NotCommitted) return
        val sending = out.copy(status = SendStatus.Sending)
        mutable.update { it.copy(outbound = sending, notice = null) }
        launchWork { transmit(sending) }
    }

    /** Reads the outcome of a lost send. Never sends. */
    fun checkSend() {
        val out = mutable.value.outbound ?: return
        if (out.status == SendStatus.Sending) return
        launchWork { readback(out) }
    }

    /** Drops an unsent message. The draft was never cleared, so the owner can edit and send it as a new message. */
    fun discardUnsent() {
        val out = mutable.value.outbound ?: return
        if (out.status != SendStatus.NotCommitted) return
        mutable.update { it.copy(outbound = null) }
    }

    private suspend fun transmit(out: Outbound) {
        try {
            val ack = client.sendMessage(out.requestId, out.expectedRevision, out.content)
            if (ack.accepted) {
                committed()
            } else {
                rejected("Whumpo could not take that message. Nothing was sent.")
            }
        } catch (e: MobileApiException.Transport) {
            if (e.outcomeUnknown) {
                setOutbound(out.requestId, SendStatus.Unknown)
                readback(out)
            } else {
                setOutbound(out.requestId, SendStatus.NotCommitted)
                mutable.update { it.copy(offline = true) }
            }
        } catch (e: MobileApiException) {
            if (e is MobileApiException.StaleRevision || e is MobileApiException.ChatBusy) refresh()
            rejected(e.userMessage())
        }
    }

    private suspend fun readback(out: Outbound) {
        try {
            val turn = client.resolveSend(out.requestId)
            if (turn != null) committed() else setOutbound(out.requestId, SendStatus.NotCommitted)
        } catch (e: MobileApiException) {
            mutable.update { it.copy(notice = "Still can't reach Whumpo. ${e.safeLine()}", offline = e is MobileApiException.Transport) }
        }
    }

    private suspend fun committed() {
        mutable.update { it.copy(outbound = null, notice = null) }
        onSendCommitted()
        refresh()
    }

    private fun rejected(message: String) {
        mutable.update { it.copy(outbound = null, notice = message, announcement = message, haptic = cue(HapticKind.Reject)) }
    }

    private fun setOutbound(requestId: String, status: SendStatus) {
        mutable.update { s ->
            val o = s.outbound
            if (o != null && o.requestId == requestId) s.copy(outbound = o.copy(status = status)) else s
        }
    }

    // ---- Review and confirm ----------------------------------------------------------------------------------------

    fun openReview(turnId: String) {
        val turn = mutable.value.turns.firstOrNull { it.id == turnId } ?: return
        val review = turn.review ?: return
        val presentation = review.present(clientInfo)
        val known = review.kind in KNOWN_KINDS
        val (step, message) = when {
            presentation.enabled && known -> ReviewStep.Ready to null
            review.state == "applied" -> ReviewStep.Done to "This change was already applied."
            !known || presentation.reason == "client_update_required" -> ReviewStep.UpdateRequired to "Update Whumpo to review this change."
            else -> ReviewStep.NoLongerCurrent to "This review is no longer current."
        }
        val id = confirmIds.getOrPut(review.reviewId) { newId() }
        // The confirm is a new turn, so the server wants the CURRENT workspace revision (stale_revision otherwise). The turn's own
        // revision is old as soon as anything is said after the review; the control still binds the exact reviewed change.
        val previous = confirmAttempts[id]
        val flow = when {
            previous == null -> ReviewFlow(turn.id, mutable.value.revision, review, id, step, message)
            previous.canConfirm && (step != ReviewStep.Ready || previous.review.control != review.control) ->
                previous.copy(step = ReviewStep.NoLongerCurrent, message = "This review is no longer current.", retryAllowed = false)
            else -> previous
        }
        if (previous != null) confirmAttempts[id] = flow
        mutable.update { it.copy(review = flow) }
        if (flow.step == ReviewStep.Pending || flow.step == ReviewStep.Uncertain) launchWork { checkOutcome(flow) }
    }

    /** Closing a sheet confirms nothing. A confirm already sent keeps going and reports through the live region. */
    fun dismissReview() {
        mutable.update { it.copy(review = null) }
    }

    /** The primary action. Repeat taps while a confirm is running are ignored. */
    fun confirmReview() {
        val flow = mutable.value.review ?: return
        if (!flow.canConfirm) return
        if (!reviewStillCurrent(flow)) {
            setStep(flow.requestId, ReviewStep.NoLongerCurrent, "This review is no longer current.", retryAllowed = false)
            return
        }
        if (confirmAttempts[flow.requestId]?.canConfirm == false) return
        val attempt = confirmAttempts.getOrPut(flow.requestId) { flow }
        setStep(flow.requestId, ReviewStep.Confirming, null)
        launchWork { runConfirm(attempt) }
    }

    private suspend fun runConfirm(flow: ReviewFlow) {
        val control = flow.review.control ?: return
        var reauthenticated = false
        while (true) {
            try {
                val ack = client.confirmReview(flow.requestId, flow.revision, control)
                currentCoroutineContext().ensureActive()
                finishConfirm(flow, ack)
                return
            } catch (e: MobileApiException) {
                currentCoroutineContext().ensureActive()
                if (e is MobileApiException.ProofRequired && e.proof == MobileApiException.ProofRequired.Proof.FRESH_PASSKEY && !reauthenticated) {
                    reauthenticated = true
                    setStep(flow.requestId, ReviewStep.Reauthenticating, null)
                    if (!askForPasskey()) {
                        failConfirm(flow, "We couldn't verify your passkey. Nothing was changed.")
                        return
                    }
                    setStep(flow.requestId, ReviewStep.Confirming, null)
                    continue
                }
                confirmError(flow, e)
                return
            }
        }
    }

    private suspend fun askForPasskey(): Boolean =
        try {
            reauth.reauthenticate()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }

    private suspend fun confirmError(flow: ReviewFlow, e: MobileApiException) {
        when {
            e is MobileApiException.StaleRevision || e is MobileApiException.ReviewUnavailable -> {
                setStep(flow.requestId, ReviewStep.NoLongerCurrent, "This review is no longer current. Nothing was changed.")
                announce("This review is no longer current.", HapticKind.Reject)
                refresh()
            }
            e is MobileApiException.ClientUpdateRequired -> setStep(flow.requestId, ReviewStep.UpdateRequired, "Update Whumpo to review this change.")
            e is MobileApiException.Transport && e.outcomeUnknown || e is MobileApiException.InvalidResponse -> {
                setStep(flow.requestId, ReviewStep.Confirming, "Didn't hear back yet. Checking.")
                checkOutcome(flow)
            }
            else -> failConfirm(flow, e.userMessage())
        }
    }

    /** Resolves this request, independently of the newest chat page. Recovery only reads; it never confirms. */
    private suspend fun checkOutcome(flow: ReviewFlow) {
        if (!confirmAttempts.containsKey(flow.requestId) || !checkingOutcomes.add(flow.requestId)) return
        try {
            val turn = client.resolveSend(flow.requestId)
            currentCoroutineContext().ensureActive()
            when {
                turn == null -> {
                    if (!reviewStillCurrent(flow)) {
                        setStep(flow.requestId, ReviewStep.NoLongerCurrent, "This review is no longer current.", retryAllowed = false)
                    } else {
                        failConfirm(flow, "The request was not found. You can try again with the same request.")
                    }
                }
                turn.id != flow.requestId -> uncertainConfirm(flow)
                else -> applyConfirmOutcome(flow, turn.status)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: MobileApiException) {
            currentCoroutineContext().ensureActive()
            if (e is MobileApiException.ClientUpdateRequired) {
                setStep(flow.requestId, ReviewStep.UpdateRequired, "Update Whumpo to check this change.", retryAllowed = false)
            } else {
                uncertainConfirm(flow)
            }
        } finally {
            checkingOutcomes.remove(flow.requestId)
        }
    }

    private suspend fun finishConfirm(flow: ReviewFlow, ack: WriteAccepted) {
        if (ack.requestId == flow.requestId && ack.accepted) applyConfirmOutcome(flow, ack.turnStatus)
        else uncertainConfirm(flow)
        refresh()
    }

    private fun applyConfirmOutcome(flow: ReviewFlow, status: String) {
        when (status) {
            "failed" -> failConfirm(flow, "Whumpo couldn't finish this change. Check Activity before trying again.", retryAllowed = false)
            "completed" -> {
                setStep(flow.requestId, ReviewStep.Done, receiptFor(flow.review.kind))
                announce(receiptFor(flow.review.kind), HapticKind.Confirm)
            }
            "pending" -> {
                setStep(flow.requestId, ReviewStep.Pending, "Confirmed. Whumpo is finishing this.", retryAllowed = false)
                mutable.update { it.copy(announcement = "Confirmed. Whumpo is finishing this.") }
            }
            else -> uncertainConfirm(flow)
        }
    }

    private fun uncertainConfirm(flow: ReviewFlow) {
        val message = "The outcome is still unknown. Check again or check Activity before trying again."
        setStep(flow.requestId, ReviewStep.Uncertain, message, retryAllowed = false)
        mutable.update { it.copy(announcement = message) }
    }

    private fun failConfirm(flow: ReviewFlow, message: String, retryAllowed: Boolean = true) {
        setStep(flow.requestId, ReviewStep.Failed, message, retryAllowed)
        announce(message, HapticKind.Reject)
    }

    private fun reviewStillCurrent(flow: ReviewFlow): Boolean {
        val turn = mutable.value.turns.firstOrNull { it.id == flow.turnId } ?: return true // It may be outside this page.
        val review = turn.review ?: return false
        return review.present(clientInfo).enabled && review.control == flow.review.control
    }

    private fun setStep(requestId: String, step: ReviewStep, message: String?, retryAllowed: Boolean = true) {
        confirmAttempts[requestId]?.let { attempt ->
            confirmAttempts[requestId] = attempt.copy(step = step, message = message, retryAllowed = retryAllowed)
        }
        mutable.update { s ->
            val flow = s.review
            if (flow != null && flow.requestId == requestId) s.copy(review = flow.copy(step = step, message = message, retryAllowed = retryAllowed)) else s
        }
    }

    private fun announce(text: String, haptic: HapticKind) {
        mutable.update { it.copy(announcement = text, haptic = cue(haptic)) }
    }

    private fun cue(kind: HapticKind): HapticCue {
        seq += 1
        return HapticCue(kind, seq)
    }

    companion object {
        const val POLL_DELAY_MS: Long = 2_000
        const val MAX_POLLS: Int = 30
        const val MAX_MESSAGE_CHARS: Int = 1000
        private const val PAGE_LIMIT = 50
        private const val ANNOUNCE_CHARS = 200
        private val KNOWN_KINDS = setOf("specification", "site", "application", "app-restore")

        /** What the receipt says, by review kind. */
        fun receiptFor(kind: String): String = when (kind) {
            "site" -> "Published. Your site is live."
            "specification" -> "Saved. Your private website is updated."
            "application" -> "Done. The app is on."
            "app-restore" -> "Done. Your app data is restored."
            else -> "Done."
        }
    }
}

internal fun MobileApiException.headline(): String = when (this) {
    is MobileApiException.ProofRequired, is MobileApiException.IdentityChanged -> "Sign in to continue."
    is MobileApiException.Transport -> "Couldn't reach Whumpo."
    is MobileApiException.Unavailable -> "Whumpo is temporarily unavailable."
    is MobileApiException.RateLimited -> "Too many requests."
    else -> "Couldn't load your conversation."
}

internal fun MobileApiException.safeLine(): String = when (this) {
    is MobileApiException.ProofRequired, is MobileApiException.IdentityChanged -> "Your unsent message is kept on this device."
    else -> "Nothing was changed."
}

internal fun MobileApiException.userMessage(): String = when (this) {
    is MobileApiException.StaleRevision -> "The conversation changed. Your message is still in the box."
    is MobileApiException.ChatBusy -> "Whumpo is still replying. Try again in a moment."
    is MobileApiException.RateLimited -> "Too many requests. Try again in ${retryAfterSeconds ?: 60} seconds."
    is MobileApiException.ProofRequired, is MobileApiException.IdentityChanged -> "Sign in to continue. Nothing was changed."
    is MobileApiException.Transport -> "Couldn't reach Whumpo. Nothing was changed."
    is MobileApiException.Unavailable -> "Whumpo is temporarily unavailable. Nothing was changed."
    else -> "Something went wrong. Nothing was changed."
}
