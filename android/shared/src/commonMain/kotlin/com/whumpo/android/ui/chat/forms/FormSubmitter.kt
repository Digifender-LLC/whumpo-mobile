package com.whumpo.android.ui.chat.forms

import com.whumpo.core.api.FrozenMutation
import com.whumpo.core.api.InteractionMutateRequest
import com.whumpo.core.api.InteractionReceipt
import com.whumpo.core.api.MobileApiException
import com.whumpo.core.api.newRequestId
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/** The interaction routes the form screen uses. */
interface FormApi {
    /** Validates and encodes a mutation once; its bytes are sent unchanged by every retry. */
    fun freeze(request: InteractionMutateRequest): FrozenMutation
    suspend fun mutate(mutation: FrozenMutation): InteractionReceipt
    suspend fun receipt(requestId: String): InteractionReceipt
    suspend fun read(interactionId: String): JsonObject
}

enum class SendOutcome {
    Sending,

    /** No usable reply: the write may have committed. Only a tapped receipt check resolves it. */
    Unknown,
    Checking,

    /** The receipt check found nothing recorded. Only Send again, with the same request, is offered. */
    NotReceived,
    Committed,

    /** A contract error: nothing was committed, so the owner may edit and send with a new request ID. */
    Rejected,
}

/** One mutation attempt. [mutation] is encoded once and every retry sends its exact bytes. */
data class SendAttempt(
    val mutation: FrozenMutation,
    val outcome: SendOutcome,
    val receipt: InteractionReceipt? = null,
    val problem: String? = null,
    val updateRequired: Boolean = false,
    /** A `422` field problem names this field; shown there when it is in the form, else at form level. */
    val fieldId: String? = null,
) {
    val request: InteractionMutateRequest get() = mutation.request
}

/** Text for the one status live region; a new [id] means announce it, even when the text repeats. */
data class Notice(val id: Int, val text: String)

data class FormSendState(
    val attempt: SendAttempt? = null,
    val status: Notice? = null,
    val feedback: Notice? = null,
    val reading: Boolean = false,
    /** The latest reread, or null before any. */
    val reread: FormRead? = null,
    /** The one automatic read after a confirmed send failed or showed no submission. */
    val followUpFailed: Boolean = false,
) {
    val unresolved: Boolean
        get() = attempt?.outcome.let { it == SendOutcome.Sending || it == SendOutcome.Unknown || it == SendOutcome.Checking || it == SendOutcome.NotReceived }

    /** A new mutation (and a new request ID) is allowed only after a conclusive outcome and while nothing reads. */
    val canStart: Boolean
        get() {
            val a = attempt
            val closed = a != null && a.outcome == SendOutcome.Committed && a.request.operation != "save-draft"
            return !unresolved && !reading && !closed
        }

    /** Latest of [status] and [feedback]: the single live region shows only this. */
    val announcement: Notice? get() = listOfNotNull(status, feedback).maxByOrNull { it.id }
}

/**
 * Sends one form's mutations with the chat-send recovery rules (docs/android/chat-forms.md): no automatic resend,
 * no polling, no new request ID while an outcome is unresolved, and every check or retry is an explicit tap. The
 * only automatic call is one follow-up read per confirmed submit for the lifetime of this object, which is the screen
 * visit: keep it across recreation and pane changes, and call [reset] when the signed-in account changes.
 */
class FormSubmitter(
    private val api: FormApi,
    private val wire: ProposedFormWire,
    private val scope: CoroutineScope,
    private val newId: () -> String = ::newRequestId,
) {
    private val _state = MutableStateFlow(FormSendState())
    val state: StateFlow<FormSendState> = _state.asStateFlow()

    private var generation = 0
    private var notices = 0
    private val announced = mutableSetOf<String>()
    private val followUps = mutableSetOf<String>()

    /** The newest answer revision a committed mutation confirmed; a later refusal does not lower it. */
    private var confirmedRevision = 0

    /** Starts `submit`, `save-draft` or `cancel` on [form]. Returns false, sending nothing, when not allowed. */
    fun start(form: FormRead.Form, operation: String, answers: JsonObject?): Boolean {
        val s = _state.value
        if (!s.canStart || form.state != FormState.Open) return false
        if (operation != "cancel" && answers == null) return false
        val revision = maxOf(form.answerRevision, s.attempt?.receipt?.answerRevision ?: 0, confirmedRevision)
        val mutation = try {
            api.freeze(form.copy(answerRevision = revision).mutation(newId(), operation, answers))
        } catch (_: IllegalArgumentException) {
            return false
        }
        _state.update { it.copy(attempt = SendAttempt(mutation, SendOutcome.Sending), feedback = null, followUpFailed = false) }
        send(mutation)
        return true
    }

    /** Explicit tap after [SendOutcome.Unknown]: reads the receipt for the original request ID. */
    fun checkAgain() {
        val attempt = _state.value.attempt?.takeIf { it.outcome == SendOutcome.Unknown } ?: return
        _state.update { it.copy(attempt = attempt.copy(outcome = SendOutcome.Checking)) }
        val g = generation
        scope.launch {
            if (g != generation) return@launch
            val receipt = try {
                api.receipt(attempt.request.requestId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (g != generation) return@launch
                if (e is MobileApiException.NotFound) {
                    _state.update { it.copy(attempt = attempt.copy(outcome = SendOutcome.NotReceived)) }
                } else {
                    _state.update { it.copy(attempt = attempt.copy(outcome = SendOutcome.Unknown), feedback = notice("Still couldn't check. Try again.")) }
                }
                return@launch
            }
            if (g == generation) settle(attempt.mutation, receipt)
        }
    }

    /** Explicit tap after [SendOutcome.NotReceived]: sends the identical request again. */
    fun sendAgain() {
        val attempt = _state.value.attempt?.takeIf { it.outcome == SendOutcome.NotReceived } ?: return
        _state.update { it.copy(attempt = attempt.copy(outcome = SendOutcome.Sending), feedback = null) }
        send(attempt.mutation)
    }

    /** Explicit tap: rereads the form. Ignored while another read runs. */
    fun reload(interactionId: String) = read(interactionId, automatic = false)

    /** The signed-in account changed: drop every attempt and ignore replies still in flight. */
    fun reset() {
        generation += 1
        conflictRead = null
        confirmedRevision = 0
        announced.clear()
        followUps.clear()
        _state.value = FormSendState()
    }

    private fun send(mutation: FrozenMutation) {
        val g = generation
        scope.launch {
            // A call queued before [reset] never reaches the API for the new account.
            if (g != generation) return@launch
            val receipt = try {
                api.mutate(mutation)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (g == generation) fail(mutation, e)
                return@launch
            }
            if (g == generation) settle(mutation, receipt)
        }
    }

    /**
     * Only contract errors that are refused before any write ([Rejected][SendOutcome.Rejected]) allow a new request
     * ID. Everything else, including `unavailable`, `rate_limited`, a code at the wrong status, an unknown code or
     * a malformed reply, leaves the outcome unknown until a tapped receipt check settles it.
     */
    private fun fail(mutation: FrozenMutation, e: Exception) {
        val notSent = "Your answers weren't sent. Check them and try again."
        val attempt = when (e) {
            is MobileApiException.Transport ->
                SendAttempt(mutation, if (e.outcomeUnknown) SendOutcome.Unknown else SendOutcome.NotReceived)
            is MobileApiException.ClientUpdateRequired ->
                SendAttempt(mutation, SendOutcome.Rejected, problem = "Update Whumpo to answer this form.", updateRequired = true)
            is MobileApiException.StaleRevision ->
                SendAttempt(mutation, SendOutcome.Rejected, problem = "This form changed. Reload it to see the latest version.")
            is MobileApiException.NotFound ->
                SendAttempt(mutation, SendOutcome.Rejected, problem = "This form is no longer available.")
            is MobileApiException.ProofRequired, is MobileApiException.IdentityIncomplete ->
                SendAttempt(mutation, SendOutcome.Rejected, problem = "Sign in again, then send your answers.")
            is MobileApiException.FieldProblem ->
                SendAttempt(mutation, SendOutcome.Rejected, problem = e.message, fieldId = e.fieldId)
            // An unmapped interaction 409: reread to explain it, but only a receipt check settles the write.
            is MobileApiException.Unrecognized -> SendAttempt(mutation, SendOutcome.Unknown).also {
                if (e.status == 409 && e.code != "unrecognized") conflictRead = mutation.request.expectedAnswerRevision
            }
            is MobileApiException.ValidationFailed, is MobileApiException.OnboardingRequired,
            is MobileApiException.PayloadTooLarge, is MobileApiException.UnsupportedMediaType ->
                SendAttempt(mutation, SendOutcome.Rejected, problem = notSent)
            else -> SendAttempt(mutation, SendOutcome.Unknown)
        }
        _state.update { it.copy(attempt = attempt) }
        conflictRead?.let { expected ->
            conflictRead = null
            read(mutation.request.interactionId, automatic = true, conflict = expected)
        }
    }

    private fun settle(mutation: FrozenMutation, receipt: InteractionReceipt) {
        val request = mutation.request
        val matches = receipt.requestId == request.requestId && receipt.interactionId == request.interactionId &&
            receipt.operation == request.operation
        if (!matches) {
            _state.update { it.copy(attempt = SendAttempt(mutation, SendOutcome.Unknown)) }
            return
        }
        val key = "${request.operation}:${request.requestId}"
        val text = when (request.operation) { "submit" -> "Sent"; "save-draft" -> "Saved"; else -> "Canceled" }
        val first = announced.add(key)
        confirmedRevision = maxOf(confirmedRevision, receipt.answerRevision)
        _state.update {
            it.copy(
                attempt = SendAttempt(mutation, SendOutcome.Committed, receipt),
                status = if (first) notice(text) else it.status,
            )
        }
        if (request.operation == "submit" && followUps.add(key)) read(request.interactionId, automatic = true)
    }

    private var conflictRead: Int? = null

    private fun read(interactionId: String, automatic: Boolean, conflict: Int? = null) {
        if (_state.value.reading) return
        val a = _state.value.attempt
        val nextStep = a != null && a.outcome == SendOutcome.Committed && a.request.operation == "submit"
        val savedDraft = a != null && a.outcome == SendOutcome.Committed && a.request.operation == "save-draft"
        _state.update { it.copy(reading = true) }
        val g = generation
        scope.launch {
            if (g != generation) return@launch
            val decoded = try {
                wire.decode(api.read(interactionId))
            } catch (e: CancellationException) {
                throw e
            } catch (_: MobileApiException.ClientUpdateRequired) {
                FormRead.UpdateRequired
            } catch (_: Exception) {
                null
            }
            if (g != generation) return@launch
            // A read for another interaction is a failed read: never stored, shown or used to settle anything.
            val read = decoded.takeUnless { it is FormRead.Form && it.interactionId != interactionId }
            val ok = read != null && (!nextStep || read is FormRead.UpdateRequired || (read as? FormRead.Form)?.submission != null)
            val text = when {
                conflict != null -> conflictText(read, conflict)
                nextStep -> if (ok) "Next step loaded" else "Couldn't load the next step"
                savedDraft -> if (ok) "Saved draft reloaded" else "Couldn't reload the saved draft"
                else -> if (ok) "Form reloaded" else "Couldn't reload the form"
            }
            _state.update { s ->
                s.copy(
                    reading = false,
                    reread = read ?: s.reread,
                    followUpFailed = nextStep && !ok,
                    feedback = if (automatic && conflict == null) s.feedback else notice(text),
                )
            }
        }
    }

    /** Classifies a reread after an unmapped interaction `409` (docs/mobile-api-forms-proposal.md). */
    private fun conflictText(read: FormRead?, expectedRevision: Int): String {
        val form = read as? FormRead.Form
        return when {
            form == null -> "This form changed. Check whether your answers arrived."
            form.replacementInteractionId != null -> "This form was replaced by a newer one."
            form.state != FormState.Open -> "This form is no longer open."
            form.answerRevision > expectedRevision -> "These answers changed on another device. Check whether yours arrived."
            else -> "This form changed. Check whether your answers arrived."
        }
    }

    private fun notice(text: String): Notice {
        notices += 1
        return Notice(notices, text)
    }
}
