package com.whumpo.android.ui.chat.forms

import com.whumpo.core.api.Continuation
import com.whumpo.core.api.ContinuationResponse
import com.whumpo.core.api.FrozenMutation
import com.whumpo.core.api.InteractionMutateRequest
import com.whumpo.core.api.InteractionReceipt
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@OptIn(ExperimentalCoroutinesApi::class)
class FormContinuationTest {
    private val form = ProposedFormsFixture.form("submittedWithContinuation")
    private val submission = checkNotNull(form.submission)
    private val pending = checkNotNull(submission.continuation)

    private inner class Api : FormPagesApi {
        val calls = mutableListOf<String>()
        var gate: CompletableDeferred<Unit>? = null
        var reply = ContinuationResponse(1, submission.id, pending)
        var fail = false
        var read = ProposedFormsFixture.read("submittedWithContinuation")

        private suspend fun result(call: String): ContinuationResponse {
            calls += call
            gate?.await()
            if (fail) throw IOException("Synthetic lost reply")
            return reply
        }

        override suspend fun continueSubmission(submissionId: String, expectedChatRevision: Int) =
            result("continue:$submissionId:$expectedChatRevision")
        override suspend fun stopSubmission(submissionId: String) = result("stop:$submissionId")
        override suspend fun reconcileSubmission(submissionId: String) = result("reconcile:$submissionId")
        override suspend fun read(interactionId: String): JsonObject {
            calls += "read:$interactionId"
            gate?.await()
            return read
        }
        override fun freeze(request: InteractionMutateRequest): FrozenMutation = error("No form mutation")
        override suspend fun mutate(mutation: FrozenMutation): InteractionReceipt = error("No form mutation")
        override suspend fun receipt(requestId: String): InteractionReceipt = error("No receipt")
        override suspend fun resources(interactionId: String, definitionDigest: String, fieldId: String, before: JsonElement?): JsonObject =
            error("No resource request")
    }

    @Test
    fun continueIsExplicitUsesSubmissionIdAndCapturesCurrentRevision() = runTest(UnconfinedTestDispatcher()) {
        val api = Api()
        var revision = 4
        val flow = FormContinuation(api, ProposedFormsFixture.wire, backgroundScope, form.interactionId, submission.id, pending.copy(state = "not-started"), { revision })
        assertTrue(api.calls.isEmpty())
        assertTrue(flow.state.value.canContinue)
        revision = 7
        flow.continueNow()
        assertEquals(listOf("continue:${submission.id}:7"), api.calls)
        assertEquals(FormContinuationPhase.Pending, flow.state.value.phase)
        flow.continueNow()
        assertEquals(1, api.calls.size)
    }

    @Test
    fun uncertainContinueOnlyAllowsReconciliationNeverAnotherContinueOrStop() = runTest(UnconfinedTestDispatcher()) {
        val api = Api().apply { fail = true }
        val flow = FormContinuation(api, ProposedFormsFixture.wire, backgroundScope, form.interactionId, submission.id, pending.copy(state = "not-started"), { 4 })
        flow.continueNow()
        assertEquals(FormContinuationPhase.Unknown, flow.state.value.phase)
        flow.continueNow()
        flow.stop()
        flow.observe(null)
        assertFalse(flow.state.value.canContinue)
        api.fail = false
        api.reply = api.reply.copy(continuation = pending.copy(state = "unknown"))
        flow.check()
        assertEquals(listOf("continue:${submission.id}:4", "reconcile:${submission.id}"), api.calls)
        assertTrue(flow.state.value.canCheck)
        assertFalse(flow.state.value.canContinue)
        assertFalse(flow.state.value.canStop)
    }

    @Test
    fun pendingChecksReadAndOnlyNotStartedCanStop() = runTest(UnconfinedTestDispatcher()) {
        val api = Api()
        val flow = FormContinuation(api, ProposedFormsFixture.wire, backgroundScope, form.interactionId, submission.id, pending, { 4 })
        flow.check()
        assertEquals(listOf("read:${form.interactionId}"), api.calls)
        flow.stop()
        assertEquals(1, api.calls.size)
        flow.observe(pending.copy(state = "not-started"))
        api.reply = api.reply.copy(continuation = pending.copy(state = "failed", reason = "stopped"))
        flow.stop()
        assertEquals("stop:${submission.id}", api.calls.last())
        assertEquals(submission.id, pending.id)
        assertEquals(FormContinuationPhase.Failed, flow.state.value.phase)
        assertFalse(flow.state.value.canContinue)
        assertFalse(flow.state.value.canStop)
        assertFalse(flow.state.value.canCheck)
    }

    @Test
    fun mismatchedMutationReplyRemainsUnknownAndDuplicateTapsDoNothing() = runTest(UnconfinedTestDispatcher()) {
        val api = Api().apply { gate = CompletableDeferred() }
        val flow = FormContinuation(api, ProposedFormsFixture.wire, backgroundScope, form.interactionId, submission.id, pending.copy(state = "not-started"), { 4 })
        flow.continueNow()
        flow.continueNow()
        flow.check()
        assertEquals(1, api.calls.size)
        api.reply = api.reply.copy(submissionId = "88888888-8888-4888-8888-000000000099")
        api.gate!!.complete(Unit)
        assertEquals(FormContinuationPhase.Unknown, flow.state.value.phase)
        assertFalse(flow.state.value.canContinue)
    }

    @Test
    fun mismatchedReadCannotResolvePendingSubmission() = runTest(UnconfinedTestDispatcher()) {
        val api = Api().apply {
            read = read.edit("submission.id", JsonPrimitive("88888888-8888-4888-8888-000000000099"))
        }
        val flow = FormContinuation(api, ProposedFormsFixture.wire, backgroundScope, form.interactionId, submission.id, pending, { 4 })
        flow.check()
        assertEquals(FormContinuationPhase.Unknown, flow.state.value.phase)
    }

    @Test
    fun mismatchedNestedIdentityAndMalformedResultRemainUnconfirmed() = runTest(UnconfinedTestDispatcher()) {
        for (bad in listOf(
            pending.copy(id = "88888888-8888-4888-8888-000000000099"),
            pending.copy(state = "succeeded", resultTurnId = "not-a-turn-id"),
        )) {
            val api = Api().apply { reply = reply.copy(continuation = bad) }
            val flow = FormContinuation(api, ProposedFormsFixture.wire, backgroundScope, form.interactionId, submission.id, pending.copy(state = "not-started"), { 4 })
            flow.continueNow()
            assertEquals(FormContinuationPhase.Unknown, flow.state.value.phase)
            assertFalse(flow.state.value.canContinue)
        }
    }

    @Test
    fun succeededShowsExistingResultAndAuthoritativeNotStartedMayBeActedOn() = runTest(UnconfinedTestDispatcher()) {
        val api = Api()
        val flow = FormContinuation(api, ProposedFormsFixture.wire, backgroundScope, form.interactionId, submission.id, pending.copy(state = "unknown"), { 4 })
        api.reply = api.reply.copy(continuation = pending.copy(state = "not-started"))
        flow.check()
        assertEquals(listOf("reconcile:${submission.id}"), api.calls)
        assertTrue(flow.state.value.canContinue)
        assertTrue(flow.state.value.canStop)
        val turn = "44444444-4444-4444-8444-000000000001"
        api.reply = api.reply.copy(continuation = pending.copy(state = "succeeded", resultTurnId = turn))
        flow.continueNow()
        assertEquals(FormContinuationPhase.Succeeded, flow.state.value.phase)
        assertEquals(turn, flow.state.value.resultTurnId)
        assertFalse(flow.state.value.canContinue)
        assertFalse(flow.state.value.canStop)
        assertFalse(flow.state.value.canCheck)
    }

    @Test
    fun expiredOrReplacedFormCannotStartButCanStopNeverStartedWork() = runTest(UnconfinedTestDispatcher()) {
        val api = Api()
        var eligible = true
        val flow = FormContinuation(api, ProposedFormsFixture.wire, backgroundScope, form.interactionId, submission.id, pending.copy(state = "not-started"), { 4 }, { eligible })
        eligible = false
        flow.continueNow()
        assertTrue(api.calls.isEmpty())
        assertFalse(flow.state.value.canContinue)
        assertTrue(flow.state.value.canStop)
        flow.stop()
        assertEquals(listOf("stop:${submission.id}"), api.calls)
    }

    @Test
    fun unsupportedServerStatesAreReadOnlyAndMissingChatRevisionSendsNothing() = runTest(UnconfinedTestDispatcher()) {
        val api = Api()
        val unsupported = FormContinuation(api, ProposedFormsFixture.wire, backgroundScope, form.interactionId, submission.id, pending.copy(state = "future-state"), { 4 })
        unsupported.continueNow()
        unsupported.stop()
        unsupported.check()
        assertEquals(FormContinuationPhase.Unavailable, unsupported.state.value.phase)
        val unavailableRevision = FormContinuation(api, ProposedFormsFixture.wire, backgroundScope, form.interactionId, submission.id, pending.copy(state = "not-started"), { null })
        unavailableRevision.continueNow()
        assertTrue(api.calls.isEmpty())
        assertEquals("Refresh Chat before continuing.", unavailableRevision.state.value.message)
    }

    @Test
    fun accountResetPreventsQueuedWritesAndLateReplies() = runTest(StandardTestDispatcher()) {
        val api = Api()
        val queued = FormContinuation(api, ProposedFormsFixture.wire, backgroundScope, form.interactionId, submission.id, pending.copy(state = "not-started"), { 4 })
        queued.continueNow()
        queued.reset()
        runCurrent()
        assertTrue(api.calls.isEmpty())
        val running = FormContinuation(api, ProposedFormsFixture.wire, backgroundScope, form.interactionId, submission.id, pending.copy(state = "not-started"), { 4 })
        api.gate = CompletableDeferred()
        running.continueNow()
        runCurrent()
        running.reset()
        api.gate!!.complete(Unit)
        runCurrent()
        assertEquals(FormContinuationPhase.Unavailable, running.state.value.phase)
    }
}
