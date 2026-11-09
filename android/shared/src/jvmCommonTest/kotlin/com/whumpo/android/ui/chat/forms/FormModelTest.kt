package com.whumpo.android.ui.chat.forms

import com.whumpo.android.ui.chat.forms.ProposedFormsFixture.form
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class FormModelTest {
    private val definition = form().definition
    private val complete = mapOf(
        "short-text" to Answer.Text("Synthetic answer"),
        "long-text" to Answer.Text("Synthetic long answer"),
        "choice" to Answer.One("option-a"),
        "multi-choice" to Answer.Many(listOf("option-a", "option-b")),
        "include-details" to Answer.Flag(false),
        "quantity" to Answer.Number("25"),
        "date" to Answer.Text("2030-03-04"),
        "datetime" to Answer.Text("2030-03-04T15:30:00.000Z"),
        "project" to Answer.One("11111111-1111-4111-8111-000000000001"),
    )

    @Test
    fun conditionalFieldShowsOnlyWhileItsControlMatches() {
        fun shown(a: Map<String, Answer>) = "details" in definition.visibleFields(a).map { it.id }
        assertFalse(shown(emptyMap()))
        assertTrue(shown(mapOf("include-details" to Answer.Flag(true))))
        assertFalse(shown(mapOf("include-details" to Answer.Flag(false), "details" to Answer.Text("kept on device"))))
    }

    @Test
    fun initialAnswersUseDefaultsUnlessADraftIsSaved() {
        assertEquals(mapOf("short-text" to Answer.Text("Synthetic default"), "choice" to Answer.One("option-a")), definition.initialAnswers(null))
        assertEquals(emptyMap(), definition.initialAnswers(emptyMap()))
    }

    @Test
    fun completeAnswersValidateAndEncodeLikeTheFixture() {
        assertEquals(emptyMap(), definition.validate(complete, draft = false))
        val encoded = definition.encodeAnswers(complete)
        assertEquals(JsonPrimitive(25), encoded["quantity"])
        assertEquals(JsonArray(listOf(JsonPrimitive("option-a"), JsonPrimitive("option-b"))), encoded["multi-choice"])
        assertFalse("details" in encoded)
    }

    @Test
    fun hiddenAndEmptyAnswersAreNeverSent() {
        val a = complete + ("details" to Answer.Text("hidden")) + ("projects" to Answer.Many(emptyList()))
        val encoded = definition.encodeAnswers(a)
        assertFalse("details" in encoded)
        assertFalse("projects" in encoded)
    }

    @Test
    fun requiredFieldsAreCheckedOnSubmitButNotOnDraft() {
        val partial = mapOf("short-text" to Answer.Text("Synthetic partial answer"))
        assertEquals(emptyMap(), definition.validate(partial, draft = true))
        val problems = definition.validate(partial + ("short-text" to Answer.Text("")), draft = false)
        assertEquals("Enter an answer.", problems["short-text"])
        assertEquals("Choose at least one.", problems["multi-choice"])
        assertEquals("Choose an answer.", problems["include-details"])
        assertEquals("Choose a project.", problems["project"])
        assertEquals(definition.fields.map { it.id }.filter { it in problems }, problems.keys.toList())
    }

    @Test
    fun constraintsAreCheckedEvenOnDrafts() {
        fun problem(id: String, a: Answer) = definition.validate(mapOf(id to a), draft = true)[id]
        assertEquals("Use at most 120 characters.", problem("short-text", Answer.Text("x".repeat(121))))
        assertEquals(null, problem("short-text", Answer.Text("x".repeat(120))))
        assertEquals("Use steps of 5, starting at 0.", problem("quantity", Answer.Number("7")))
        assertEquals("Enter a number from 0 to 100.", problem("quantity", Answer.Number("105")))
        assertEquals("Enter a number from 0 to 100.", problem("quantity", Answer.Number("1e1")))
        assertEquals("Enter a number from 0 to 100.", problem("quantity", Answer.Number("NaN")))
        assertEquals("Enter a date as YYYY-MM-DD.", problem("date", Answer.Text("2031-02-29")))
        assertEquals(null, problem("date", Answer.Text("2032-02-29")))
        assertEquals("Enter a date and time.", problem("datetime", Answer.Text("2030-03-04T15:30:00Z")))
        assertEquals("Choose at most 3.", problem("projects", Answer.Many((1..4).map { "11111111-1111-4111-8111-00000000000$it" })))
        assertEquals("This answer can't be used.", problem("multi-choice", Answer.Many(listOf("option-a", "option-a"))))
        assertEquals("This answer can't be used.", problem("project", Answer.One("not-a-uuid")))
    }

    @Test
    fun unloadedResourceNamesAreLabelled() {
        val names = mapOf("11111111-1111-4111-8111-000000000001" to "Synthetic project one")
        assertEquals("Synthetic project one", resourceName("11111111-1111-4111-8111-000000000001", names))
        assertEquals(NAME_NOT_LOADED, resourceName("11111111-1111-4111-8111-000000000002", names))
        assertEquals(NAME_NOT_LOADED, resourceName("11111111-1111-4111-8111-000000000003", mapOf("11111111-1111-4111-8111-000000000003" to " ")))
    }

    @Test
    fun encodedAnswersAlwaysFitTheDecoder() {
        assertTrue(ProposedFormsFixture.wire.answersFit(definition, definition.encodeAnswers(complete), draft = false))
        assertFalse(ProposedFormsFixture.wire.answersFit(definition, JsonObject(mapOf("details" to JsonPrimitive("hidden"))), draft = true))
    }
}
