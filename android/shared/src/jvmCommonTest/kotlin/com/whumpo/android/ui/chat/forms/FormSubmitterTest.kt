package com.whumpo.android.ui.chat.forms

import com.whumpo.android.ui.chat.forms.ProposedFormsFixture.form
import com.whumpo.android.ui.chat.forms.ProposedFormsFixture.read
import com.whumpo.android.ui.chat.forms.ProposedFormsFixture.wire
import com.whumpo.core.api.InteractionMutateRequest
import com.whumpo.core.api.InteractionReceipt
import com.whumpo.core.api.MobileApiException
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@OptIn(ExperimentalCoroutinesApi::class)
class FormSubmitterTest {
    private class FakeApi : FormApi {
        val mutations = mutableListOf<InteractionMutateRequest>()
        val receipts = mutableListOf<String>()
        var reads = 0
        var mutateGate: CompletableDeferred<Unit>? = null
        var receiptGate: CompletableDeferred<Unit>? = null
        var onMutate: (InteractionMutateRequest) -> InteractionReceipt = { receiptFor(it) }
        var onReceipt: (String) -> InteractionReceipt = { throw MobileApiException.NotFound("Not found.") }
        var onRead: () -> JsonObject = { read("submitted") }

        override suspend fun mutate(request: InteractionMutateRequest): InteractionReceipt {
            mutations += request
            mutateGate?.await()
            return onMutate(request)
        }

        override suspend fun receipt(requestId: String): InteractionReceipt {
            receipts += requestId
            receiptGate?.await()
            return onReceipt(requestId)
        }

        override suspend fun read(interactionId: String): JsonObject {
            reads += 1
            return onRead()
        }
    }

    private val open = form()
    private val answers = JsonObject(mapOf("short-text" to JsonPrimitive("Synthetic answer")))
    private val lost = MobileApiException.Transport(IOException("synthetic"), outcomeUnknown = true)

    private fun TestScope.submitter(api: FakeApi): FormSubmitter {
        var n = 0
        return FormSubmitter(api, wire, backgroundScope) { "77777777-7777-4777-8777-%012d".format(++n) }
    }

    private fun test(body: suspend TestScope.(FakeApi) -> Unit) = runTest(UnconfinedTestDispatcher()) { body(FakeApi()) }

    /** Launched work waits for [runCurrent], so a call can be queued but not yet made. */
    private fun queued(body: suspend TestScope.(FakeApi) -> Unit) = runTest(StandardTestDispatcher()) { body(FakeApi()) }

    @Test
    fun resetBeforeAQueuedSendMakesNoCall() = queued { api ->
        val s = submitter(api)
        assertTrue(s.start(open, "submit", answers))
        s.reset()
        runCurrent()
        assertTrue(api.mutations.isEmpty())
        assertEquals(FormSendState(), s.state.value)
    }

    @Test
    fun resetBeforeAQueuedReceiptCheckMakesNoCall() = queued { api ->
        val s = submitter(api)
        api.onMutate = { throw lost }
        s.start(open, "submit", answers)
        runCurrent()
        assertEquals(SendOutcome.Unknown, s.state.value.attempt?.outcome)
        s.checkAgain()
        s.reset()
        runCurrent()
        assertTrue(api.receipts.isEmpty())
        assertEquals(1, api.mutations.size)
        assertEquals(FormSendState(), s.state.value)
    }

    @Test
    fun resetBeforeAQueuedRereadMakesNoCall() = queued { api ->
        val s = submitter(api)
        s.start(open, "save-draft", answers)
        runCurrent()
        assertEquals(SendOutcome.Committed, s.state.value.attempt?.outcome)
        s.reload(open.interactionId)
        s.reset()
        runCurrent()
        assertEquals(0, api.reads)
        assertEquals(FormSendState(), s.state.value)
    }

    @Test
    fun confirmedSubmitAnnouncesSentOnceAndReadsOnce() = test { api ->
        val s = submitter(api)
        assertTrue(s.start(open, "submit", answers))
        val state = s.state.value
        assertEquals(SendOutcome.Committed, state.attempt?.outcome)
        assertEquals("Sent", state.status?.text)
        assertEquals(1, api.mutations.size)
        assertEquals(1, api.reads)
        assertFalse(state.followUpFailed)
        assertFalse(state.canStart)
        assertFalse(s.start(open, "submit", answers))
        assertEquals(1, api.mutations.size)
    }

    @Test
    fun lostReplyWaitsForATappedReceiptCheckThenResendsTheSameRequest() = test { api ->
        val s = submitter(api)
        api.onMutate = { throw lost }
        s.start(open, "submit", answers)
        assertEquals(SendOutcome.Unknown, s.state.value.attempt?.outcome)
        // Nothing runs on its own, and nothing new can start.
        assertEquals(listOf(1, 0, 0), listOf(api.mutations.size, api.receipts.size, api.reads))
        assertFalse(s.start(open, "submit", answers))
        assertFalse(s.start(open, "save-draft", answers))
        s.sendAgain()
        assertEquals(1, api.mutations.size)

        s.checkAgain()
        assertEquals(listOf(api.mutations[0].requestId), api.receipts)
        assertEquals(SendOutcome.NotReceived, s.state.value.attempt?.outcome)
        assertFalse(s.start(open, "submit", answers))

        api.onMutate = { receiptFor(it) }
        s.sendAgain()
        assertEquals(2, api.mutations.size)
        assertSame(api.mutations[0], api.mutations[1])
        assertEquals(SendOutcome.Committed, s.state.value.attempt?.outcome)
        assertEquals(1, api.reads)
    }

    @Test
    fun receiptFoundCommitsWithoutResending() = test { api ->
        val s = submitter(api)
        api.onMutate = { throw lost }
        s.start(open, "submit", answers)
        api.onReceipt = { receiptFor(api.mutations[0]) }
        s.checkAgain()
        assertEquals(SendOutcome.Committed, s.state.value.attempt?.outcome)
        assertEquals(1, api.mutations.size)
        assertEquals("Sent", s.state.value.status?.text)
    }

    @Test
    fun eachFailedReceiptCheckGetsItsOwnFeedback() = test { api ->
        val s = submitter(api)
        api.onMutate = { throw lost }
        api.onReceipt = { throw lost }
        s.start(open, "submit", answers)
        s.checkAgain()
        val first = s.state.value.feedback
        s.checkAgain()
        val second = s.state.value.feedback
        assertEquals("Still couldn't check. Try again.", second?.text)
        assertNotEquals(first?.id, second?.id)
        assertEquals(SendOutcome.Unknown, s.state.value.attempt?.outcome)
        assertEquals(1, api.mutations.size)
    }

    @Test
    fun doubleTapsStartOneMutationAndOneReceiptCheck() = test { api ->
        val s = submitter(api)
        val gate = CompletableDeferred<Unit>().also { api.mutateGate = it }
        assertTrue(s.start(open, "submit", answers))
        assertFalse(s.start(open, "submit", answers))
        assertEquals(1, api.mutations.size)
        api.onMutate = { throw lost }
        gate.complete(Unit)
        val receiptGate = CompletableDeferred<Unit>().also { api.receiptGate = it }
        s.checkAgain()
        s.checkAgain()
        assertEquals(1, api.receipts.size)
        receiptGate.complete(Unit)
        assertEquals(SendOutcome.NotReceived, s.state.value.attempt?.outcome)
    }

    @Test
    fun failedFollowUpReadRetriesOnlyOnTapWithDistinctFeedback() = test { api ->
        val s = submitter(api)
        api.onRead = { throw lost }
        s.start(open, "submit", answers)
        assertTrue(s.state.value.followUpFailed)
        assertNull(s.state.value.feedback)
        assertEquals(1, api.reads)
        s.reload(open.interactionId)
        val first = s.state.value.feedback
        s.reload(open.interactionId)
        val second = s.state.value.feedback
        assertEquals("Couldn't load the next step", second?.text)
        assertNotEquals(first?.id, second?.id)
        api.onRead = { read("submitted") }
        s.reload(open.interactionId)
        assertEquals("Next step loaded", s.state.value.feedback?.text)
        assertFalse(s.state.value.followUpFailed)
        assertEquals(listOf(4, 1), listOf(api.reads, api.mutations.size))
    }

    @Test
    fun followUpReadWithoutASubmissionIsNotTheNextStep() = test { api ->
        val s = submitter(api)
        api.onRead = { read("open") }
        s.start(open, "submit", answers)
        assertTrue(s.state.value.followUpFailed)
    }

    @Test
    fun twoSavesAreAnnouncedSeparatelyAndNeverReadAutomatically() = test { api ->
        val s = submitter(api)
        s.start(open, "save-draft", answers)
        val first = s.state.value.status
        assertTrue(s.state.value.canStart)
        s.start(open, "save-draft", answers)
        val second = s.state.value.status
        assertEquals(listOf("Saved", "Saved"), listOf(first?.text, second?.text))
        assertNotEquals(first?.id, second?.id)
        assertNotEquals(api.mutations[0].requestId, api.mutations[1].requestId)
        assertEquals(1, api.mutations[1].expectedAnswerRevision)
        assertEquals(0, api.reads)
        s.reload(open.interactionId)
        assertEquals("Saved draft reloaded", s.state.value.feedback?.text)
    }

    @Test
    fun accountChangeDropsPendingWorkAndLateReplies() = test { api ->
        val s = submitter(api)
        val gate = CompletableDeferred<Unit>().also { api.mutateGate = it }
        s.start(open, "submit", answers)
        s.reset()
        gate.complete(Unit)
        assertEquals(FormSendState(), s.state.value)
        assertEquals(0, api.reads)
    }

    @Test
    fun mismatchedReceiptIsNotTrusted() = test { api ->
        val s = submitter(api)
        api.onMutate = { receiptFor(it).copy(requestId = "77777777-7777-4777-8777-999999999999") }
        s.start(open, "submit", answers)
        assertEquals(SendOutcome.Unknown, s.state.value.attempt?.outcome)
        assertNull(s.state.value.status)
        assertEquals(0, api.reads)
    }

    @Test
    fun contractRejectionAllowsEditingAndAFreshRequestId() = test { api ->
        val s = submitter(api)
        api.onMutate = { throw MobileApiException.ValidationFailed("invalid_request", "Synthetic problem.") }
        s.start(open, "submit", answers)
        assertEquals(SendOutcome.Rejected, s.state.value.attempt?.outcome)
        assertTrue(s.state.value.canStart)
        api.onMutate = { receiptFor(it) }
        s.start(open, "submit", answers)
        assertNotEquals(api.mutations[0].requestId, api.mutations[1].requestId)
    }

    @Test
    fun updateRequiredAndUnrecognizedRepliesAreHandledSafely() = test { api ->
        val s = submitter(api)
        api.onMutate = { throw MobileApiException.ClientUpdateRequired("Update.", null, null) }
        s.start(open, "submit", answers)
        assertTrue(s.state.value.attempt!!.updateRequired)
        api.onMutate = { throw MobileApiException.Unrecognized(502, null) }
        s.start(open, "submit", answers)
        assertEquals(SendOutcome.Unknown, s.state.value.attempt?.outcome)
    }

    @Test
    fun closedFormsAndMissingAnswersNeverMutate() = test { api ->
        val s = submitter(api)
        for (name in listOf("submitted", "canceled", "expired", "superseded", "replaced")) assertFalse(s.start(form(name), "submit", answers), name)
        assertFalse(s.start(open, "submit", null))
        assertTrue(api.mutations.isEmpty())
    }

    @Test
    fun cancelCarriesNoAnswers() = test { api ->
        val s = submitter(api)
        s.start(open, "cancel", answers)
        assertNull(api.mutations.single().answers)
        assertEquals("Canceled", s.state.value.status?.text)
        assertEquals(0, api.reads)
    }

    private companion object {
        fun receiptFor(r: InteractionMutateRequest) = InteractionReceipt(1, r.requestId, r.interactionId, r.operation, r.expectedAnswerRevision + 1)
    }
}
