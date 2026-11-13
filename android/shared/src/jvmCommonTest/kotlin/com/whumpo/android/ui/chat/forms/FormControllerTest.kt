package com.whumpo.android.ui.chat.forms

import com.whumpo.android.ui.chat.forms.ProposedFormsFixture.read
import com.whumpo.android.ui.chat.forms.ProposedFormsFixture.wire
import com.whumpo.core.api.FrozenMutation
import com.whumpo.core.api.InteractionMutateRequest
import com.whumpo.core.api.InteractionReceipt
import com.whumpo.core.api.MobileApiException
import com.whumpo.core.api.ContinuationResponse
import com.whumpo.core.api.Continuation
import kotlin.time.Instant
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

@OptIn(ExperimentalCoroutinesApi::class)
class FormControllerTest {
    private class FakeApi : FormPagesApi {
        val sent = mutableListOf<FrozenMutation>()
        val reads = mutableListOf<String>()
        val pages = mutableListOf<Pair<String, JsonElement?>>()
        val receipts = mutableListOf<String>()
        val digests = mutableListOf<String>()
        val continued = mutableListOf<Pair<String, Int>>()
        var continuationGate: CompletableDeferred<Unit>? = null
        var snapshotReadBeforeGate = false
        var onContinue: (String, Int) -> ContinuationResponse = { _, _ -> error("Unexpected continuation") }
        var readGate: CompletableDeferred<Unit>? = null
        var mutateGate: CompletableDeferred<Unit>? = null
        var onPageAt: ((String, String, JsonElement?) -> JsonObject)? = null
        var pageGate: CompletableDeferred<Unit>? = null
        var onRead: (String) -> JsonObject = { ProposedFormsFixture.read("draft") }
        var onPage: (String, JsonElement?) -> JsonObject = { field, _ -> page(field, listOf(PROJECT_ONE), JsonNull) }
        var onMutate: (InteractionMutateRequest) -> InteractionReceipt = {
            InteractionReceipt(1, it.requestId, it.interactionId, it.operation, it.expectedAnswerRevision + 1)
        }

        override fun freeze(request: InteractionMutateRequest) =
            FrozenMutation(request, Json.encodeToString(InteractionMutateRequest.serializer(), request).encodeToByteArray())

        override suspend fun mutate(mutation: FrozenMutation): InteractionReceipt {
            sent += mutation
            mutateGate?.await()
            return onMutate(mutation.request)
        }

        override suspend fun receipt(requestId: String): InteractionReceipt {
            receipts += requestId
            throw MobileApiException.NotFound("Not found.")
        }

        override suspend fun read(interactionId: String): JsonObject {
            reads += interactionId
            val captured = if (snapshotReadBeforeGate) onRead(interactionId) else null
            readGate?.await()
            return captured ?: onRead(interactionId)
        }

        override suspend fun continueSubmission(submissionId: String, expectedChatRevision: Int): ContinuationResponse {
            continued += submissionId to expectedChatRevision
            continuationGate?.await()
            return onContinue(submissionId, expectedChatRevision)
        }
        override suspend fun stopSubmission(submissionId: String): ContinuationResponse = error("Unexpected stop")
        override suspend fun reconcileSubmission(submissionId: String): ContinuationResponse = error("Unexpected reconciliation")

        override suspend fun resources(interactionId: String, definitionDigest: String, fieldId: String, before: JsonElement?): JsonObject {
            pages += fieldId to before
            digests += definitionDigest
            pageGate?.await()
            return onPageAt?.invoke(definitionDigest, fieldId, before) ?: onPage(fieldId, before)
        }
    }

    private companion object {
        const val DRAFT_ID = "66666666-6666-4666-8666-000000000002"
        const val PROJECT_ONE = "11111111-1111-4111-8111-00000000a001"
        const val PROJECT_TWO = "11111111-1111-4111-8111-00000000a002"
        val draft = ProposedFormsFixture.form("draft")

        fun page(
            fieldId: String,
            ids: List<String>,
            next: JsonElement,
            digest: String = draft.definitionDigest,
            interactionId: String = draft.interactionId,
        ) = buildJsonObject {
            put("version", 1)
            put("interactionId", interactionId)
            put("definitionDigest", digest)
            put("fieldId", fieldId)
            put("collection", "projects")
            put("items", JsonArray(ids.map { buildJsonObject { put("id", it); put("name", "Synthetic $it"); put("photo", JsonNull) } }))
            put("nextBefore", next)
        }
    }

    private val lost = MobileApiException.Transport(IOException("synthetic"), outcomeUnknown = true)

    private fun TestScope.controller(api: FakeApi, onResult: (String) -> Unit = {}): FormController {
        var n = 0
        return FormController(api, wire, backgroundScope, currentChatRevision = { 4 }, onResultRequested = onResult,
            now = { Instant.parse("2029-01-01T00:00:00Z") }) { "77777777-7777-4777-8777-%012d".format(++n) }
    }

    private fun test(body: suspend TestScope.(FakeApi) -> Unit) = runTest(UnconfinedTestDispatcher()) { body(FakeApi()) }

    private fun queued(body: suspend TestScope.(FakeApi) -> Unit) = runTest(StandardTestDispatcher()) { body(FakeApi()) }

    private fun FormController.screen() = (pane.value as FormPane.Shown).screen

    @Test
    fun openingReadsOnceAndWritesNothing() = test { api ->
        val c = controller(api)
        c.open(DRAFT_ID)
        assertEquals(listOf(DRAFT_ID), api.reads)
        assertTrue(api.sent.isEmpty())
        assertEquals(draft, c.screen().read)
        assertEquals(draft.draft.orEmpty(), c.screen().answers)
        // The first page of each resource field loads; that is a read, never a write.
        assertEquals(listOf<Pair<String, JsonElement?>>("project" to null, "projects" to null), api.pages)
    }

    private val submittedRead get() = read("submittedWithContinuation").edit("submission.continuation.state", JsonPrimitive("not-started"))
    private val submittedForm get() = wire.decode(submittedRead) as FormRead.Form

    @Test
    fun freshSameFormReloadUpdatesContinuationAndResultOnlyRefreshesExistingChat() = test { api ->
        val result = "44444444-4444-4444-8444-000000000001"
        api.onRead = { submittedRead }
        val opened = mutableListOf<String>()
        val c = controller(api, opened::add)
        c.open(submittedForm.interactionId)
        assertEquals(FormContinuationPhase.NotStarted, c.screen().continuation?.phase)
        api.onRead = { submittedRead.edit("submission.continuation.state", JsonPrimitive("succeeded"))
            .edit("submission.continuation.resultTurnId", JsonPrimitive(result)) }
        c.onReload()
        assertEquals(FormContinuationPhase.Succeeded, c.screen().continuation?.phase)
        c.onContinue()
        assertTrue(api.continued.isEmpty())
        c.onOpenContinuationResult()
        assertEquals(listOf(result), opened)
        assertNull(c.pane.value)
        assertTrue(api.sent.isEmpty())
    }

    @Test
    fun lostContinueStaysUnknownThroughReloadStartAndFailureUntilFreshSameContentRead() = test { api ->
        api.onRead = { submittedRead }
        api.onContinue = { _, _ -> throw lost }
        val c = controller(api)
        c.open(submittedForm.interactionId)
        c.onContinue()
        assertEquals(FormContinuationPhase.Unknown, c.screen().continuation?.phase)
        api.readGate = CompletableDeferred()
        api.onRead = { throw lost }
        c.onReload()
        assertEquals(FormContinuationPhase.Unknown, c.screen().continuation?.phase)
        api.readGate!!.complete(Unit)
        assertEquals(FormContinuationPhase.Unknown, c.screen().continuation?.phase)
        api.readGate = null
        api.onRead = { submittedRead }
        c.onReload()
        assertEquals(FormContinuationPhase.NotStarted, c.screen().continuation?.phase)
        assertEquals(1, api.continued.size, "a fresh status read never dispatches another continue")
    }

    @Test
    fun reopenReadOvertakenByContinuationSettlementCannotRestoreNotStarted() = test { api ->
        api.onRead = { submittedRead }
        api.onContinue = { id, _ -> ContinuationResponse(1, id, Continuation(id, "pending", null, null)) }
        api.continuationGate = CompletableDeferred()
        val c = controller(api)
        c.open(submittedForm.interactionId)
        c.onContinue()
        c.close()
        api.snapshotReadBeforeGate = true
        api.readGate = CompletableDeferred()
        c.open(submittedForm.interactionId)
        api.continuationGate!!.complete(Unit)
        api.readGate!!.complete(Unit)
        assertEquals(FormContinuationPhase.Pending, c.screen().continuation?.phase)
        c.onContinue()
        assertEquals(1, api.continued.size)
    }

    @Test
    fun pendingFormReadBlocksContinuationAndRemovingFollowupClearsControls() = test { api ->
        api.onRead = { submittedRead }
        val c = controller(api)
        c.open(submittedForm.interactionId)
        api.readGate = CompletableDeferred()
        c.onReload()
        c.onContinue()
        assertTrue(api.continued.isEmpty())
        api.onRead = { submittedRead.edit("submission.continuation", JsonNull)
            .edit("envelope.definition.continuationBinding", JsonNull) }
        api.readGate!!.complete(Unit)
        assertNull(c.screen().continuation)
        c.onContinue()
        c.onStopContinuation()
        assertTrue(api.continued.isEmpty())
    }

    @Test
    fun cardMetadataFollowsConfirmedReceiptsAndIsErasedOnAccountChange() = test { api ->
        val open = ProposedFormsFixture.form()
        api.onRead = { read("open") }
        val c = controller(api)
        c.open(open.interactionId)
        assertEquals(false, c.cards.value[open.interactionId]?.hasDraft)
        c.onSaveDraft()
        assertEquals(true, c.cards.value[open.interactionId]?.hasDraft)
        c.onCancel()
        assertEquals("canceled", c.cards.value[open.interactionId]?.state)
        c.close()
        assertEquals(open.definition.title, c.cards.value[open.interactionId]?.title)
        c.reset()
        assertTrue(c.cards.value.isEmpty())
    }

    @Test
    fun confirmedCancelSendsNoAnswersAndClosesEvenAnInvalidDraft() = test { api ->
        val c = controller(api)
        c.open(DRAFT_ID)
        c.onAnswer("datetime", Answer.DateTimeDraft("2031-05-0", "09:00"))
        c.onCancel()
        val request = api.sent.single().request
        assertEquals("cancel", request.operation)
        assertNull(request.answers)
        assertEquals(FormState.Canceled, (c.screen().read as FormRead.Form).state)
        assertTrue(c.screen().answers.isEmpty())
        c.onSubmit()
        c.onCancel()
        assertEquals(1, api.sent.size)
    }

    @Test
    fun uncertainCancelIsReceiptGatedAndRetryKeepsTheSameBytes() = test { api ->
        api.onMutate = { throw lost }
        val c = controller(api)
        c.open(DRAFT_ID)
        c.onCancel()
        val original = api.sent.single()
        c.onCancel()
        c.onSubmit()
        assertEquals(1, api.sent.size)
        c.onCheckAgain()
        c.onSendAgain()
        assertEquals(2, api.sent.size)
        assertSame(original, api.sent.last())
        assertTrue(original.bytes().contentEquals(api.sent.last().bytes()))
        assertEquals(listOf(original.request.requestId), api.receipts)
        assertNull(api.sent.last().request.answers)
    }

    @Test
    fun blurValidatesOnlyTheVisitedFieldAndHiddenErrorsAreRemoved() = test { api ->
        val c = controller(api)
        c.open(DRAFT_ID)
        c.onAnswer("details", Answer.Text("x".repeat(201)))
        assertTrue(c.screen().errors.isEmpty())
        c.onFieldBlur("details")
        assertEquals(setOf("details"), c.screen().errors.keys)
        c.onAnswer("include-details", Answer.Flag(false))
        assertTrue(c.screen().errors.isEmpty())
        assertTrue(api.sent.isEmpty())
    }

    @Test
    fun pagingEchoesTheOpaqueCursorUnchanged() = test { api ->
        val cursor = buildJsonObject { put("opaque", JsonArray(listOf(JsonPrimitive(3), JsonPrimitive("x")))) }
        api.onPage = { field, before -> if (before == null) page(field, listOf(PROJECT_ONE), cursor) else page(field, listOf(PROJECT_TWO), JsonNull) }
        val c = controller(api)
        c.open(DRAFT_ID)
        assertTrue("project" in c.screen().moreResources)
        c.onLoadMoreResources("project")
        assertEquals("project" to cursor, api.pages.last())
        assertTrue("project" !in c.screen().moreResources)
        c.onLoadMoreResources("project")
        assertEquals(3, api.pages.size, "no request after the last page")
        val names = c.screen().resourceNames
        assertEquals("Synthetic $PROJECT_TWO", resourceName(PROJECT_TWO, names))
        assertEquals(NAME_NOT_LOADED, resourceName("11111111-1111-4111-8111-00000000a099", names))
    }

    @Test
    fun aPageForAnotherDigestFormOrFieldIsIgnoredAndCanBeRetried() = test { api ->
        val wrong: List<(String) -> JsonObject> = listOf(
            { field -> page(field, listOf(PROJECT_ONE), JsonNull, digest = "b".repeat(64)) },
            { field -> page(field, listOf(PROJECT_ONE), JsonNull, interactionId = "66666666-6666-4666-8666-000000000001") },
            { field -> page(if (field == "project") "projects" else "project", listOf(PROJECT_ONE), JsonNull) },
        )
        for (bad in wrong) {
            api.onPage = { field, _ -> bad(field) }
            val c = controller(api)
            c.open(DRAFT_ID)
            assertEquals(NAME_NOT_LOADED, resourceName(PROJECT_ONE, c.screen().resourceNames))
            assertTrue("project" in c.screen().moreResources, "a failed page can be retried")
            c.close()
        }
    }

    @Test
    fun aLatePageAfterCloseOrAnotherFormIsIgnored() = test { api ->
        val gate = CompletableDeferred<Unit>()
        api.pageGate = gate
        val c = controller(api)
        c.open(DRAFT_ID)
        api.pageGate = null
        api.onPage = { field, _ -> page(field, emptyList(), JsonNull) }
        c.open(DRAFT_ID)
        gate.complete(Unit)
        assertEquals(NAME_NOT_LOADED, resourceName(PROJECT_ONE, c.screen().resourceNames))
    }

    @Test
    fun resetBeforeAQueuedReadMakesNoCall() = queued { api ->
        val c = controller(api)
        c.open(DRAFT_ID)
        c.reset()
        runCurrent()
        assertTrue(api.reads.isEmpty())
        assertNull(c.pane.value)
    }

    @Test
    fun aLateReadAfterAccountChangeIsIgnored() = test { api ->
        val gate = CompletableDeferred<Unit>()
        api.readGate = gate
        val c = controller(api)
        c.open(DRAFT_ID)
        c.reset()
        gate.complete(Unit)
        assertNull(c.pane.value)
        assertTrue(api.pages.isEmpty())
    }

    @Test
    fun aFailedReadOffersOnlyAnExplicitRetry() = test { api ->
        api.onRead = { throw lost }
        val c = controller(api)
        c.open(DRAFT_ID)
        assertEquals(FormPane.Failed(DRAFT_ID), c.pane.value)
        assertEquals(1, api.reads.size)
        api.onRead = { read("draft") }
        c.onReload()
        assertEquals(2, api.reads.size)
        assertTrue(c.pane.value is FormPane.Shown)
        assertTrue(api.sent.isEmpty())
    }

    @Test
    fun anUnknownFormFailsClosedWithNothingToSend() = test { api ->
        api.onRead = { JsonObject(read("draft") + ("state" to JsonPrimitive("archived"))) }
        val c = controller(api)
        c.open(DRAFT_ID)
        assertEquals(FormRead.UpdateRequired, c.screen().read)
        c.onSaveDraft()
        c.onSubmit()
        assertTrue(api.sent.isEmpty())
        assertTrue(api.pages.isEmpty())
    }

    @Test
    fun aFieldProblemShowsAtAKnownFieldAndAtFormLevelOtherwise() = test { api ->
        val field = draft.definition.visibleFields(draft.draft.orEmpty()).first().id
        for ((fieldId, atField) in listOf(field to true, "not-a-field" to false, null to false)) {
            api.onMutate = { throw MobileApiException.FieldProblem("synthetic_open_code", "Synthetic problem.", fieldId) }
            val c = controller(api)
            c.open(DRAFT_ID)
            c.onSaveDraft()
            val screen = c.screen()
            assertEquals(SendOutcome.Rejected, screen.send.attempt?.outcome)
            assertEquals(if (atField) setOf(field) else emptySet(), screen.errors.keys)
            c.close()
        }
    }

    @Test
    fun anUnresolvedSendBlocksEditsAndNewWrites() = test { api ->
        api.onMutate = { throw lost }
        val c = controller(api)
        c.open(DRAFT_ID)
        c.onSaveDraft()
        assertEquals(1, api.sent.size)
        val before = c.screen().answers
        c.onAnswer(draft.definition.fields.first().id, null)
        c.onSaveDraft()
        c.onSubmit()
        assertEquals(1, api.sent.size)
        assertEquals(before, c.screen().answers)
        c.onSendAgain()
        assertEquals(1, api.sent.size, "send again waits for a conclusive receipt check")
    }

    @Test
    fun noSavedDraftPrefillsDefaultsAnEmptyDraftStaysEmptyAndASavedDraftWins() = test { api ->
        val openId = ProposedFormsFixture.form("open").interactionId
        val suggestion = buildJsonObject { put("value", "Synthetic suggestion"); put("provenance", "suggested") }
        val saved = buildJsonObject { put("short-text", "Synthetic saved") }
        for ((draftValue, expected) in listOf<Pair<JsonElement, Map<String, Answer>>>(
            // The fixture also declares a suggested default for `choice`.
            JsonNull to mapOf("short-text" to Answer.Text("Synthetic suggestion"), "choice" to Answer.One("option-a")),
            JsonObject(emptyMap()) to emptyMap(),
            saved to mapOf("short-text" to Answer.Text("Synthetic saved")),
        )) {
            api.onRead = { read("open").edit("envelope.definition.fields.0.default", suggestion).edit("draft", draftValue) }
            val c = controller(api)
            c.open(openId)
            assertEquals(expected, c.screen().answers, "draft $draftValue")
            assertTrue((c.screen().read as FormRead.Form).definition.fields.first().suggested)
            c.reset()
        }
        assertTrue(api.sent.isEmpty())
    }

    @Test
    fun aRereadWithANewDigestLoadsFreshFirstPagesOnceAndIgnoresTheOldPage() = test { api ->
        val newDigest = "c".repeat(64)
        val cursor = JsonPrimitive("synthetic-opaque")
        api.onPageAt = { digest, field, before ->
            if (digest == draft.definitionDigest) page(field, listOf(PROJECT_ONE), JsonNull)
            else page(field, listOf(PROJECT_TWO), if (before == null) cursor else JsonNull, digest = newDigest)
        }
        val oldPage = CompletableDeferred<Unit>()
        api.pageGate = oldPage
        val c = controller(api)
        c.open(DRAFT_ID)
        api.pageGate = null
        api.onRead = { read("draft").edit("envelope.definitionDigest", JsonPrimitive(newDigest)) }
        c.onReload()
        assertEquals(newDigest, (c.screen().read as FormRead.Form).definitionDigest)
        assertEquals(listOf(draft.definitionDigest, draft.definitionDigest, newDigest, newDigest), api.digests)
        assertEquals("Synthetic $PROJECT_TWO", resourceName(PROJECT_TWO, c.screen().resourceNames))
        c.onReload()
        assertEquals(4, api.pages.size, "the same reread does not load the first pages again")
        c.onLoadMoreResources("project")
        assertEquals("project" to cursor, api.pages.last())
        assertEquals(newDigest, api.digests.last())
        oldPage.complete(Unit)
        assertEquals(NAME_NOT_LOADED, resourceName(PROJECT_ONE, c.screen().resourceNames))
        api.onRead = { JsonObject(read("draft") + ("state" to JsonPrimitive("archived"))) }
        c.onReload()
        assertEquals(FormRead.UpdateRequired, c.screen().read)
        c.onSubmit()
        assertEquals(5, api.pages.size)
        assertTrue(api.sent.isEmpty())
    }

    @Test
    fun aRereadForAnotherInteractionIsNotShown() = test { api ->
        val c = controller(api)
        c.open(DRAFT_ID)
        api.onRead = { read("submitted").edit("envelope.id", JsonPrimitive("66666666-6666-4666-8666-0000000000f1")) }
        c.onReload()
        assertEquals(draft, c.screen().read)
        assertNull(c.screen().send.reread)
        assertTrue(api.sent.isEmpty())
    }

    @Test
    fun closingWhileSendingKeepsTheRequestAndAnswersForReopen() = test { api ->
        val gate = CompletableDeferred<Unit>()
        api.mutateGate = gate
        val c = controller(api)
        c.open(DRAFT_ID)
        c.onAnswer("short-text", Answer.Text("Synthetic unsent"))
        c.onSaveDraft()
        val attempt = c.screen().send.attempt!!
        assertEquals(SendOutcome.Sending, attempt.outcome)
        val answers = c.screen().answers
        c.onClose()
        assertNull(c.pane.value)
        c.open(DRAFT_ID)
        assertEquals(2, api.reads.size, "reopening only reads")
        assertSame(attempt.mutation, c.screen().send.attempt?.mutation)
        assertEquals(SendOutcome.Sending, c.screen().send.attempt?.outcome)
        assertEquals(answers, c.screen().answers)
        c.onSubmit()
        c.onSaveDraft()
        assertEquals(1, api.sent.size)
        gate.complete(Unit)
        assertEquals(SendOutcome.Committed, c.screen().send.attempt?.outcome)
        assertEquals(1, api.sent.size)
        c.onClose()
        c.open(DRAFT_ID)
        assertNull(c.screen().send.attempt, "a conclusive outcome is not kept")
    }

    @Test
    fun closingWhileUnknownKeepsTheReceiptGateAcrossAnotherForm() = test { api ->
        val openId = ProposedFormsFixture.form("open").interactionId
        api.onRead = { id -> if (id == DRAFT_ID) read("draft") else read("open") }
        api.onMutate = { throw lost }
        val c = controller(api)
        c.open(DRAFT_ID)
        c.onAnswer("short-text", Answer.Text("Synthetic unsent"))
        c.onSaveDraft()
        val first = api.sent.single()
        val answers = c.screen().answers
        assertEquals(SendOutcome.Unknown, c.screen().send.attempt?.outcome)
        c.onClose()
        c.open(openId)
        assertNull(c.screen().send.attempt)
        c.open(DRAFT_ID)
        assertSame(first, c.screen().send.attempt?.mutation)
        assertEquals(SendOutcome.Unknown, c.screen().send.attempt?.outcome)
        assertEquals(answers, c.screen().answers)
        c.onSubmit()
        c.onSendAgain()
        assertEquals(1, api.sent.size, "nothing new or resent while unknown")
        c.onCheckAgain()
        assertEquals(listOf(first.request.requestId), api.receipts)
        assertEquals(SendOutcome.NotReceived, c.screen().send.attempt?.outcome)
        api.onMutate = { InteractionReceipt(1, it.requestId, it.interactionId, it.operation, it.expectedAnswerRevision + 1) }
        c.onSendAgain()
        assertEquals(2, api.sent.size)
        assertEquals(first.request.requestId, api.sent[1].request.requestId)
        assertTrue(first.bytes().contentEquals(api.sent[1].bytes()))
        assertEquals(SendOutcome.Committed, c.screen().send.attempt?.outcome)
    }

    @Test
    fun accountChangeDropsKeptSendsAnswersAndLateReplies() = test { api ->
        val gate = CompletableDeferred<Unit>()
        api.mutateGate = gate
        val c = controller(api)
        c.open(DRAFT_ID)
        c.onAnswer("short-text", Answer.Text("Synthetic unsent"))
        c.onSaveDraft()
        c.onClose()
        c.reset()
        gate.complete(Unit)
        c.open(DRAFT_ID)
        assertNull(c.screen().send.attempt)
        assertEquals(draft.definition.initialAnswers(draft.draft), c.screen().answers)
        assertEquals(1, api.sent.size)
    }

    private fun newerDraftRead() = read("draft").edit("draft.short-text", JsonPrimitive("Synthetic server")).edit("answerRevision", JsonPrimitive(2))

    @Test
    fun aNewerSavedDraftIsStagedForReviewNotAppliedOverEdits() = test { api ->
        val newer = wire.decode(newerDraftRead()) as FormRead.Form
        for (choice in listOf("newer", "mine")) {
            api.onRead = { read("draft") }
            val c = controller(api)
            c.open(DRAFT_ID)
            c.onAnswer("short-text", Answer.Text("Synthetic local"))
            val local = c.screen().answers
            api.onRead = { newerDraftRead() }
            c.onReload()
            assertEquals(local, c.screen().answers, "edits are not replaced")
            assertEquals(newer, c.screen().read)
            assertEquals(FormReview(newer.draft, setOf("short-text"), emptyList()), c.screen().review)
            c.onSubmit()
            c.onSaveDraft()
            assertTrue(api.sent.isEmpty(), "nothing is sent while the review waits")
            if (choice == "newer") {
                c.onUseNewerAnswers()
                assertEquals(newer.draft, c.screen().answers)
                assertNull(c.screen().review)
                assertTrue(api.sent.isEmpty())
            } else {
                c.onKeepMyAnswers()
                assertEquals(local, c.screen().answers)
                assertNull(c.screen().review)
                assertTrue(api.sent.isEmpty())
                c.onSaveDraft()
                assertEquals(2, api.sent.single().request.expectedAnswerRevision)
                assertTrue("Synthetic local" in api.sent.single().bytes().decodeToString())
            }
            c.reset()
            api.sent.clear()
        }
    }

    @Test
    fun unchangedAnswersFollowANewerSavedDraft() = test { api ->
        val c = controller(api)
        c.open(DRAFT_ID)
        api.onRead = { newerDraftRead() }
        c.onReload()
        assertEquals((wire.decode(newerDraftRead()) as FormRead.Form).draft, c.screen().answers)
        assertNull(c.screen().review)
    }

    @Test
    fun removedAndRetypedFieldsAreTakenOutAndChangedConstraintsStillValidate() = test { api ->
        val base = read("draft")
        val fields = base.getValue("envelope").jsonObject.getValue("definition").jsonObject.getValue("fields").jsonArray
        val changed = JsonArray(
            fields.mapNotNull { f ->
                val o = f.jsonObject
                when (o.getValue("id").jsonPrimitive.content) {
                    "long-text" -> null
                    "quantity" -> JsonObject(o + ("type" to JsonPrimitive("text")) + ("constraints" to buildJsonObject { put("maxLength", 80) }))
                    "short-text" -> JsonObject(o - "default" + ("constraints" to buildJsonObject { put("maxLength", 5) }))
                    else -> o
                }
            },
        )
        val c = controller(api)
        c.open(DRAFT_ID)
        c.onAnswer("long-text", Answer.Text("Synthetic long"))
        c.onAnswer("quantity", Answer.Number("5"))
        c.onAnswer("short-text", Answer.Text("Synthetic local"))
        api.onRead = {
            base.edit("envelope.definition.fields", changed).edit("envelope.definitionDigest", JsonPrimitive("c".repeat(64)))
                .edit("draft", JsonNull).edit("answerRevision", JsonPrimitive(2))
        }
        c.onReload()
        assertEquals(setOf("short-text", "include-details", "details"), c.screen().answers.keys)
        assertEquals(FormReview(null, emptySet(), listOf("Long text example", "Number example")), c.screen().review)
        c.onSubmit()
        assertTrue(api.sent.isEmpty())
        c.onUseNewerAnswers()
        assertEquals(Answer.Text("Synthetic local"), c.screen().answers["short-text"], "no newer draft to use")
        c.onKeepMyAnswers()
        assertNull(c.screen().review)
        c.onSaveDraft()
        assertTrue("short-text" in c.screen().errors, "the new 5-character limit still applies")
        assertTrue(api.sent.isEmpty())
    }

    private fun savedRead(text: String) = read("draft").edit("draft.short-text", JsonPrimitive(text)).edit("answerRevision", JsonPrimitive(2))

    @Test
    fun editsAfterASuccessfulSaveSurviveCloseAndReopen() = test { api ->
        val c = controller(api)
        c.open(DRAFT_ID)
        c.onAnswer("short-text", Answer.Text("Synthetic A"))
        c.onSaveDraft()
        val saved = api.sent.single()
        val savedBytes = saved.bytes()
        assertEquals(SendOutcome.Committed, c.screen().send.attempt?.outcome)
        c.onAnswer("short-text", Answer.Text("Synthetic B"))
        val b = c.screen().answers
        c.onClose()
        api.onRead = { savedRead("Synthetic A") }
        c.open(DRAFT_ID)
        assertEquals(b, c.screen().answers)
        assertNull(c.screen().review)
        assertEquals(1, api.sent.size, "closing and reopening send nothing")
        c.onSaveDraft()
        val next = api.sent[1]
        assertNotEquals(saved.request.requestId, next.request.requestId)
        assertEquals(2, next.request.expectedAnswerRevision)
        assertTrue("Synthetic B" in next.bytes().decodeToString())
        assertTrue(savedBytes.contentEquals(saved.bytes()))
    }

    @Test
    fun aSaveWithoutLaterEditsReopensToTheSavedDraftAndResetStillClears() = test { api ->
        val c = controller(api)
        c.open(DRAFT_ID)
        c.onAnswer("short-text", Answer.Text("Synthetic A"))
        c.onSaveDraft()
        val a = c.screen().answers
        c.onClose()
        api.onRead = { savedRead("Synthetic A") }
        c.open(DRAFT_ID)
        assertEquals(a, c.screen().answers)
        assertNull(c.screen().review)
        c.onAnswer("short-text", Answer.Text("Synthetic B"))
        c.onClose()
        c.reset()
        c.open(DRAFT_ID)
        assertEquals(Answer.Text("Synthetic A"), c.screen().answers["short-text"], "an account change keeps nothing local")
        assertEquals(1, api.sent.size)
    }

    private fun newerRead(text: String, revision: Int) =
        read("draft").edit("draft.short-text", JsonPrimitive(text)).edit("answerRevision", JsonPrimitive(revision))

    @Test
    fun returningToTheOldDraftAfterASaveIsAnEditAgainstTheSave() = test { api ->
        val newer = wire.decode(newerRead("Synthetic C", 3)) as FormRead.Form
        for (choice in listOf("newer", "mine")) {
            api.onRead = { read("draft") }
            val c = controller(api)
            c.open(DRAFT_ID)
            val d = c.screen().answers
            c.onAnswer("short-text", Answer.Text("Synthetic A"))
            c.onSaveDraft()
            assertEquals(SendOutcome.Committed, c.screen().send.attempt?.outcome)
            c.onAnswer("short-text", d.getValue("short-text"))
            assertEquals(d, c.screen().answers)
            c.onClose()
            api.onRead = { newerRead("Synthetic C", 3) }
            c.open(DRAFT_ID)
            assertEquals(d, c.screen().answers, "D stays visible")
            assertEquals(FormReview(newer.draft, setOf("short-text"), emptyList()), c.screen().review)
            if (choice == "newer") c.onUseNewerAnswers() else c.onKeepMyAnswers()
            assertEquals(if (choice == "newer") newer.draft else d, c.screen().answers)
            assertEquals(1, api.sent.size, "choosing sends nothing")
            c.reset()
            api.sent.clear()
        }
    }

    @Test
    fun eachCommittedSaveMovesTheBaseline() = test { api ->
        val c = controller(api)
        c.open(DRAFT_ID)
        c.onAnswer("short-text", Answer.Text("Synthetic A"))
        c.onSaveDraft()
        c.onAnswer("short-text", Answer.Text("Synthetic B"))
        c.onSaveDraft()
        assertEquals(listOf(1, 2), api.sent.map { it.request.expectedAnswerRevision })
        c.onAnswer("short-text", Answer.Text("Synthetic A"))
        c.onClose()
        api.onRead = { newerRead("Synthetic C", 4) }
        c.open(DRAFT_ID)
        assertEquals(Answer.Text("Synthetic A"), c.screen().answers["short-text"], "A is an edit against the later save B")
        assertEquals(setOf("short-text"), c.screen().review?.differs)
        // Without edits after the latest save, the newer read is simply followed.
        val d = controller(api)
        api.onRead = { read("draft") }
        d.open(DRAFT_ID)
        d.onAnswer("short-text", Answer.Text("Synthetic A"))
        d.onSaveDraft()
        d.onClose()
        api.onRead = { newerRead("Synthetic C", 3) }
        d.open(DRAFT_ID)
        assertEquals(Answer.Text("Synthetic C"), d.screen().answers["short-text"])
        assertNull(d.screen().review)
        assertEquals(3, api.sent.size)
    }

    @Test
    fun aSaveMovesTheBaselineOnlyOnceCommittedEvenWhenItsReplyIsLate() = test { api ->
        val gate = CompletableDeferred<Unit>()
        api.mutateGate = gate
        val c = controller(api)
        c.open(DRAFT_ID)
        val d = c.screen().answers
        c.onAnswer("short-text", Answer.Text("Synthetic A"))
        c.onSaveDraft()
        c.onClose()
        c.open(DRAFT_ID)
        assertEquals(SendOutcome.Sending, c.screen().send.attempt?.outcome)
        assertEquals(Answer.Text("Synthetic A"), c.screen().answers["short-text"])
        gate.complete(Unit)
        assertEquals(SendOutcome.Committed, c.screen().send.attempt?.outcome)
        c.onAnswer("short-text", d.getValue("short-text"))
        c.onClose()
        api.onRead = { newerRead("Synthetic C", 3) }
        c.open(DRAFT_ID)
        assertEquals(d, c.screen().answers, "D is an edit against the committed save A")
        assertEquals(setOf("short-text"), c.screen().review?.differs)
        assertEquals(1, api.sent.size)
    }

    @Test
    fun anUnknownSaveDoesNotMoveTheBaseline() = test { api ->
        api.onMutate = { throw lost }
        val c = controller(api)
        c.open(DRAFT_ID)
        c.onAnswer("short-text", Answer.Text("Synthetic A"))
        c.onSaveDraft()
        assertEquals(SendOutcome.Unknown, c.screen().send.attempt?.outcome)
        c.onClose()
        api.onRead = { newerRead("Synthetic C", 2) }
        c.open(DRAFT_ID)
        assertEquals(SendOutcome.Unknown, c.screen().send.attempt?.outcome, "the receipt gate survives")
        assertEquals(Answer.Text("Synthetic A"), c.screen().answers["short-text"], "A is still unconfirmed, so it is an edit")
        assertEquals(setOf("short-text"), c.screen().review?.differs)
        assertEquals(1, api.sent.size)
    }

    @Test
    fun aCommittedSubmitKeepsNoAnswers() = test { api ->
        // A complete saved draft, so Send passes validation.
        val complete = read("draft").edit("draft", read("submitted").getValue("submission").jsonObject.getValue("answers"))
        api.onRead = { complete }
        val c = controller(api)
        c.open(DRAFT_ID)
        c.onAnswer("short-text", Answer.Text("Synthetic C"))
        c.onSubmit()
        assertEquals(SendOutcome.Committed, c.screen().send.attempt?.outcome)
        c.onClose()
        c.open(DRAFT_ID)
        val server = wire.decode(complete) as FormRead.Form
        assertEquals(server.definition.initialAnswers(server.draft), c.screen().answers)
        assertNull(c.screen().send.attempt)
    }

    @Test
    fun accountChangeDropsAnswersAndNames() = test { api ->
        val c = controller(api)
        c.open(DRAFT_ID)
        c.reset()
        assertNull(c.pane.value)
        c.open(DRAFT_ID)
        assertEquals(draft.draft.orEmpty(), c.screen().answers)
    }
}
