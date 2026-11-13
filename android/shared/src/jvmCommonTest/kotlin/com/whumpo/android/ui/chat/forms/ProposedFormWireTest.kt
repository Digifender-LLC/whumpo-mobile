package com.whumpo.android.ui.chat.forms

import com.whumpo.android.ui.chat.forms.ProposedFormsFixture.form
import com.whumpo.android.ui.chat.forms.ProposedFormsFixture.group
import com.whumpo.android.ui.chat.forms.ProposedFormsFixture.read
import com.whumpo.android.ui.chat.forms.ProposedFormsFixture.wire
import com.whumpo.core.api.InteractionMutateRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** The adopted native form decoder against tests/fixtures/proposed/forms.json. */
class ProposedFormWireTest {
    private val clientJson = Json { encodeDefaults = true; explicitNulls = true }

    @Test
    fun everyReadFixtureDecodesWithItsState() {
        val reads = group("reads")
        assertEquals(12, reads.size)
        for ((name, r) in reads) {
            val decoded = assertIs<FormRead.Form>(wire.decode(r.jsonObject), name)
            assertEquals(r.jsonObject.getValue("state").jsonPrimitive.content, decoded.state.wire, name)
        }
    }

    @Test
    fun everyUpdateRequiredFixtureFailsClosed() {
        val cases = group("updateRequired")
        assertEquals(9, cases.size)
        for ((name, r) in cases) assertEquals(FormRead.UpdateRequired, wire.decode(r.jsonObject), name)
    }

    @Test
    fun openFormKeepsFieldOrderTypesAndConditions() {
        val f = form()
        assertEquals(
            listOf("short-text", "long-text", "choice", "multi-choice", "include-details", "details", "quantity", "date", "datetime", "project", "projects"),
            f.definition.fields.map { it.id },
        )
        val kinds = f.definition.fields.map { it.kind::class.simpleName }
        assertEquals(listOf("Text", "Text", "Choice", "MultiChoice", "YesNo", "Text", "Number", "Date", "DateTime", "Resource", "Resource"), kinds)
        val choice = f.definition.fields.single { it.id == "choice" }
        assertEquals(Answer.One("option-a"), choice.default)
        assertTrue(choice.suggested)
        assertEquals(ShowWhen("include-details", Answer.Flag(true)), f.definition.fields.single { it.id == "details" }.showWhen)
        assertEquals("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", f.definitionDigest)
        assertNull(f.draft)
        assertNull(f.submission)
    }

    @Test
    fun extraReadAndEnvelopeMetadataIsIgnored() {
        assertEquals(form("open").definition, form("openWithMetadata").definition)
    }

    @Test
    fun draftsSubmissionsAndReplacementsDecode() {
        assertEquals(
            mapOf("short-text" to Answer.Text("Synthetic draft"), "include-details" to Answer.Flag(true), "details" to Answer.Text("Synthetic conditional answer")),
            form("draft").draft,
        )
        assertEquals(emptyMap(), form("emptyDraft").draft)
        val submitted = form("submitted").submission!!
        assertEquals(Answer.Flag(false), submitted.answers["include-details"])
        assertEquals(Answer.Number("25"), submitted.answers["quantity"])
        assertEquals(Answer.Text("2030-03-04T15:30:00.000Z"), submitted.answers["datetime"])
        assertNull(submitted.continuation)
        val continuation = form("submittedWithContinuation").submission!!.continuation!!
        assertEquals("pending", continuation.state)
        assertEquals(form("submittedWithContinuation").submission!!.id, continuation.id)
        assertEquals("66666666-6666-4666-8666-000000000030", form("replaced").replacementInteractionId)
        assertNull(form("superseded").replacementInteractionId)
    }

    @Test
    fun submittedAnswersCannotOmitOrEmptyVisibleRequiredFields() {
        val submitted = read("submitted")
        val required = form("submitted").definition.fields.filter { it.required && it.showWhen == null }
        for (field in required) {
            assertEquals(FormRead.UpdateRequired, wire.decode(submitted.edit("submission.answers.${field.id}", null)), field.id)
        }
        for ((path, value) in listOf(
            "submission.answers" to JsonObject(emptyMap()),
            "submission.answers.short-text" to JsonPrimitive(""),
            "submission.answers.multi-choice" to JsonArray(emptyList()),
        )) {
            assertEquals(FormRead.UpdateRequired, wire.decode(submitted.edit(path, value)), path)
        }
        // Partial and explicitly empty drafts remain valid.
        assertIs<FormRead.Form>(wire.decode(read("draft")))
        assertIs<FormRead.Form>(wire.decode(read("emptyDraft")))
    }

    @Test
    fun submittedConditionalRequiredAnswersAreCheckedOnlyWhenVisible() {
        val submitted = read("submitted").edit("envelope.definition.fields.5.required", JsonPrimitive(true))
        assertIs<FormRead.Form>(wire.decode(submitted))
        assertEquals(
            FormRead.UpdateRequired,
            wire.decode(submitted.edit("submission.answers.include-details", JsonPrimitive(true))),
        )
        assertIs<FormRead.Form>(wire.decode(
            submitted.edit("submission.answers.include-details", JsonPrimitive(true))
                .edit("submission.answers.details", JsonPrimitive("Synthetic required detail")),
        ))
    }

    @Test
    fun continuationLifecycleAndIdentityFollowThePublishedContract() {
        val base = read("submittedWithContinuation")
        for (state in listOf("not-started", "pending", "succeeded", "failed", "unknown", "future-state")) {
            val decoded = assertIs<FormRead.Form>(wire.decode(base.edit("submission.continuation.state", JsonPrimitive(state))))
            assertEquals(state, decoded.submission!!.continuation!!.state)
            assertEquals(decoded.submission.id, decoded.submission.continuation.id)
        }
        assertNull(form("submitted").definition.continuationBinding)
        assertNull(form("submitted").submission!!.continuation)
        for ((path, value) in listOf(
            "submission.continuation.id" to JsonPrimitive("88888888-8888-4888-8888-000000000099"),
            "submission.continuation.resultTurnId" to JsonPrimitive("not-a-turn-id"),
            "submission.continuation" to kotlinx.serialization.json.JsonNull,
            "envelope.definition.continuationBinding" to kotlinx.serialization.json.JsonNull,
            "envelope.expiresAt" to JsonPrimitive("not-a-date"),
        )) assertEquals(FormRead.UpdateRequired, wire.decode(base.edit(path, value)), path)
    }

    @Test
    fun malformedOrUnsupportedReadsFailClosed() {
        val open = read("open")
        val f = "envelope.definition.fields"
        val cases: List<Pair<String, JsonElement?>> = listOf(
            "version" to JsonPrimitive(2),
            "state" to null,
            "draft" to null,
            "answerRevision" to JsonPrimitive(-1),
            "draft" to JsonPrimitive("text"),
            "draft" to JsonObject(mapOf("details" to JsonPrimitive("hidden while include-details is unanswered"))),
            "draft" to JsonObject(mapOf("unknown-field" to JsonPrimitive("x"))),
            "draft" to JsonObject(mapOf("quantity" to JsonPrimitive(7))),
            "state" to JsonPrimitive("submitted"),
            "envelope.id" to JsonPrimitive("not-a-uuid"),
            "envelope.definitionDigest" to JsonPrimitive(""),
            "envelope.definition" to null,
            "envelope.definition.title" to JsonPrimitive("x".repeat(101)),
            "envelope.definition.continuationBinding" to JsonPrimitive("example.other@1"),
            f to JsonArray(emptyList()),
            "$f.0.constraints.maxLength" to JsonPrimitive("120"),
            "$f.0.constraints.maxLength" to JsonPrimitive(201),
            "$f.0.label" to JsonPrimitive("x".repeat(81)),
            "$f.0.label" to JsonPrimitive(""),
            "$f.0.required" to JsonPrimitive("true"),
            "$f.0.binding" to null,
            "$f.1.id" to JsonPrimitive("short-text"),
            "$f.1.id" to JsonPrimitive("Long-Text"),
            "$f.2.constraints.options.1.id" to JsonPrimitive("option-a"),
            "$f.2.default.value" to JsonPrimitive("option-z"),
            "$f.2.default.provenance" to JsonPrimitive("guessed"),
            "$f.4.constraints.extra" to JsonPrimitive(true),
            "$f.5.when.fieldId" to JsonPrimitive("quantity"),
            "$f.5.when.fieldId" to JsonPrimitive("projects"),
            "$f.5.when.equals" to JsonPrimitive("yes"),
            "$f.6.constraints.min" to JsonPrimitive(200),
            "$f.6.constraints.step" to JsonPrimitive(0),
            "$f.6.constraints.max" to JsonPrimitive(2e9),
            "$f.7.type" to JsonPrimitive("Date"),
            "$f.8.constraints.zone" to JsonPrimitive("Example/Nowhere"),
            "$f.9.constraints.maxItems" to JsonPrimitive(2),
            "$f.9.constraints.collection" to JsonPrimitive("photos"),
            "$f.10.constraints.maxItems" to JsonPrimitive(6),
            "$f.10.group" to JsonPrimitive(3),
        )
        for ((path, value) in cases) {
            assertEquals(FormRead.UpdateRequired, wire.decode(open.edit(path, value)), "$path = $value")
        }
    }

    @Test
    fun mutationsMatchTheFixtureRequests() {
        val f = form()
        for ((name, operation) in listOf("saveDraft" to "save-draft", "submit" to "submit", "cancel" to "cancel")) {
            val expected = group("mutations").getValue(name).jsonObject
            val request = f.mutation(
                expected.getValue("requestId").jsonPrimitive.content,
                operation,
                expected["answers"]?.jsonObject,
            )
            assertEquals(expected, clientJson.encodeToJsonElement(InteractionMutateRequest.serializer(), request), name)
        }
        assertNull(f.mutation("77777777-7777-4777-8777-000000000009", "cancel", JsonObject(emptyMap())).answers)
    }

    @Test
    fun fixtureAnswersFitAndInvalidOnesDoNot() {
        val definition = form().definition
        val mutations = group("mutations")
        assertTrue(wire.answersFit(definition, mutations.getValue("saveDraft").jsonObject.getValue("answers").jsonObject, draft = true))
        assertTrue(wire.answersFit(definition, mutations.getValue("submit").jsonObject.getValue("answers").jsonObject, draft = false))
        val invalid = group("invalidMutations")
        assertEquals(7, invalid.size)
        for ((name, m) in invalid) {
            val body = m.jsonObject
            val draft = body.getValue("operation").jsonPrimitive.content == "save-draft"
            assertFalse(wire.answersFit(definition, body.getValue("answers").jsonObject, draft), name)
        }
    }

    @Test
    fun submittedAnswersReencodeToTheirWireForm() {
        val f = form("submitted")
        val wireAnswers = read("submitted").getValue("submission").jsonObject.getValue("answers").jsonObject
        assertEquals(wireAnswers, f.definition.encodeAnswers(f.submission!!.answers))
    }

    private fun numberRead(min: Double, max: Double, value: Double) = read("draft")
        .edit("envelope.definition.fields.6.constraints", buildJsonObject { put("min", min); put("max", max); put("step", 0.0005) })
        .edit("envelope.definition.fields.6.default", buildJsonObject { put("value", value); put("provenance", "suggested") })
        .edit("draft.quantity", JsonPrimitive(value))

    @Test
    fun fractionalNumbersDecodeAsPlainDecimalsAndEncodeTheSameValue() {
        val cases = listOf(
            Triple(0.0, 1e9, 12345678.5 to "12345678.5"),
            Triple(0.0, 1e9, 0.0005 to "0.0005"),
            Triple(0.0, 1e9, 0.001 to "0.001"),
            Triple(0.0, 1e9, 0.0015 to "0.0015"),
            Triple(0.0, 1e9, 9999999.9995 to "9999999.9995"),
            Triple(0.0, 1e9, 1e7 to "10000000"),
            Triple(0.0, 1e9, 10000000.0005 to "10000000.0005"),
            Triple(-1e9, 0.0, -0.0005 to "-0.0005"),
            Triple(-1e9, 0.0, -0.001 to "-0.001"),
            Triple(-1e9, 0.0, -12345678.5 to "-12345678.5"),
            Triple(-1e9, 0.0, -1e7 to "-10000000"),
        )
        for ((min, max, case) in cases) {
            val (value, text) = case
            val form = assertIs<FormRead.Form>(wire.decode(numberRead(min, max, value)), "$value must not fail closed")
            val quantity = form.definition.fields.first { it.id == "quantity" }
            assertEquals(Answer.Number(text), quantity.default, "default $value")
            assertEquals(Answer.Number(text), form.draft?.get("quantity"), "draft $value")
            assertEquals(value, form.definition.encodeAnswers(form.draft!!).getValue("quantity").jsonPrimitive.double)
        }
    }

    @Test
    fun canonicalFloatingPointArtifactsStillDecodeAndRoundTrip() {
        for (value in listOf(0.3, 0.30000000000000004, 0.300000001)) {
            val source = numberRead(0.1, 1.0, value).edit("envelope.definition.fields.6.constraints.step", JsonPrimitive(0.2))
            val form = assertIs<FormRead.Form>(wire.decode(source))
            assertEquals(Answer.Number(plain(value)), form.draft?.get("quantity"))
            assertEquals(value, form.definition.encodeAnswers(form.draft!!).getValue("quantity").jsonPrimitive.double)
        }
    }

    @Test
    fun subnormalJsonNumbersDecodeCanonicallyAndKeepTheirBinaryWireValue() {
        for ((literal, text) in listOf("5e-324" to "5e-324", "4.9e-324" to "5e-324", "1e-323" to "1e-323")) {
            val value = literal.toDouble()
            val source = numberRead(0.0, 1.0, value)
                .edit("envelope.definition.fields.6.constraints.step", JsonPrimitive(Double.MIN_VALUE))
                .edit("envelope.definition.fields.6.default.value", Json.parseToJsonElement(literal))
                .edit("draft.quantity", Json.parseToJsonElement(literal))
            assertEquals(literal, source.getValue("draft").jsonObject.getValue("quantity").jsonPrimitive.content)
            val form = assertIs<FormRead.Form>(wire.decode(source))
            val expected = Answer.Number(checkNotNull(plainDecimal(text)))
            assertEquals(expected, form.draft?.get("quantity"))
            assertEquals(expected, form.definition.fields.first { it.id == "quantity" }.default)
            assertEquals(value.toBits(), form.definition.encodeAnswers(form.draft!!).getValue("quantity").jsonPrimitive.double.toBits())
        }
    }

    @Test
    fun plainNeverUsesExponentNotation() {
        val cases = mapOf(
            12345678.5 to "12345678.5", 0.0005 to "0.0005", 0.001 to "0.001", 1e7 to "10000000", 1e9 to "1000000000",
            100.0 to "100", 0.0 to "0", -0.0 to "0", -0.0005 to "-0.0005", 1e-7 to "0.0000001", 1.5e21 to "1500000000000000000000",
        )
        for ((d, text) in cases) assertEquals(text, plain(d), "$d")
        assertEquals("0.0005", plainDecimal("5e-4"))
        assertNull(plainDecimal("NaN"))
    }
}
