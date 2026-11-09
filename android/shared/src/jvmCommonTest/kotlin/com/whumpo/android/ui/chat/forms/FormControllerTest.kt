package com.whumpo.android.ui.chat.forms

import com.whumpo.android.ui.chat.forms.ProposedFormsFixture.read
import com.whumpo.android.ui.chat.forms.ProposedFormsFixture.wire
import com.whumpo.core.api.FrozenMutation
import com.whumpo.core.api.InteractionMutateRequest
import com.whumpo.core.api.InteractionReceipt
import com.whumpo.core.api.MobileApiException
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
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
import kotlinx.serialization.json.put

@OptIn(ExperimentalCoroutinesApi::class)
class FormControllerTest {
    private class FakeApi : FormPagesApi {
        val sent = mutableListOf<FrozenMutation>()
        val reads = mutableListOf<String>()
        val pages = mutableListOf<Pair<String, JsonElement?>>()
        val receipts = mutableListOf<String>()
        val digests = mutableListOf<String>()
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
            readGate?.await()
            return onRead(interactionId)
        }

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

    private fun TestScope.controller(api: FakeApi): FormController {
        var n = 0
        return FormController(api, wire, backgroundScope) { "77777777-7777-4777-8777-%012d".format(++n) }
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
            JsonNull to mapOf("short-text" to Answer.Text("Synthetic suggestion")),
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
