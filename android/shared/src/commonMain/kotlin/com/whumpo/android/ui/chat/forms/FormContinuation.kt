package com.whumpo.android.ui.chat.forms

import com.whumpo.core.api.Continuation
import com.whumpo.core.api.ContinuationResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Only states with published client semantics expose actions. Other server states remain read-only. */
enum class FormContinuationPhase { Ready, Pending, Unknown, Failed, Unavailable }

data class FormContinuationState(
    val phase: FormContinuationPhase,
    val busy: Boolean = false,
    val message: String? = null,
    val resultTurnId: String? = null,
) {
    val canContinue: Boolean get() = !busy && phase == FormContinuationPhase.Ready
    val canCheck: Boolean get() = !busy && phase in setOf(FormContinuationPhase.Pending, FormContinuationPhase.Unknown)
    val canStop: Boolean get() = !busy && phase == FormContinuationPhase.Pending
}

interface FormContinuationApi {
    suspend fun continueSubmission(submissionId: String, expectedChatRevision: Int): ContinuationResponse
    suspend fun stopSubmission(submissionId: String): ContinuationResponse
    suspend fun reconcileSubmission(submissionId: String): ContinuationResponse
}

/**
 * One submission's explicit next-step actions. No polling or automatic continue/retry. An uncertain response
 * remains reconciliation-only across pane dismissal; only reset on account change discards that knowledge.
 */
class FormContinuation(
    private val api: FormPagesApi,
    private val wire: ProposedFormWire,
    private val scope: CoroutineScope,
    private val interactionId: String,
    private val submissionId: String,
    initial: Continuation?,
    private val currentChatRevision: () -> Int?,
) {
    private val mutable = MutableStateFlow(initial?.toState() ?: FormContinuationState(FormContinuationPhase.Ready))
    val state: StateFlow<FormContinuationState> = mutable.asStateFlow()
    private var generation = 0

    fun observe(continuation: Continuation?) {
        if (!mutable.value.busy && continuation != null) mutable.value = continuation.toState()
    }

    fun continueNow() {
        if (!mutable.value.canContinue) return
        val revision = currentChatRevision()?.takeIf { it >= 0 }
        if (revision == null) {
            mutable.value = mutable.value.copy(message = "Refresh Chat before continuing.")
            return
        }
        request { api.continueSubmission(submissionId, revision) }
    }

    fun stop() {
        if (mutable.value.canStop) request { api.stopSubmission(submissionId) }
    }

    fun check() {
        val before = mutable.value
        if (!before.canCheck) return
        if (before.phase == FormContinuationPhase.Unknown) {
            request { api.reconcileSubmission(submissionId) }
            return
        }
        val g = generation
        mutable.value = before.copy(busy = true, message = null)
        scope.launch {
            if (g != generation) return@launch
            val next = try {
                val form = wire.decode(api.read(interactionId)) as? FormRead.Form
                val submission = form?.takeIf { it.interactionId == interactionId }?.submission
                submission?.takeIf { it.id == submissionId }?.continuation?.toState()
                    ?: FormContinuationState(FormContinuationPhase.Unknown, message = "Couldn't confirm the next step. Check again.")
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                before.copy(busy = false, message = "Couldn't load the next step. Check again.")
            }
            if (g == generation) mutable.value = next
        }
    }

    fun reset() {
        generation += 1
        mutable.value = FormContinuationState(FormContinuationPhase.Unavailable)
    }

    private fun request(call: suspend () -> ContinuationResponse) {
        val g = generation
        mutable.value = mutable.value.copy(busy = true, message = null)
        scope.launch {
            if (g != generation) return@launch
            val next = try {
                val response = call()
                if (response.version == 1 && response.submissionId == submissionId && isUuid(response.continuation.id)) {
                    response.continuation.toState()
                } else {
                    unknown()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                unknown()
            }
            if (g == generation) mutable.value = next
        }
    }

    private fun unknown() = FormContinuationState(
        FormContinuationPhase.Unknown, message = "Couldn't confirm the next step. Check before continuing.",
    )

    private fun Continuation.toState() = FormContinuationState(
        when (state) {
            "pending" -> FormContinuationPhase.Pending
            "unknown" -> FormContinuationPhase.Unknown
            "failed" -> FormContinuationPhase.Failed
            else -> FormContinuationPhase.Unavailable
        },
        message = reason,
        resultTurnId = resultTurnId,
    )
}
