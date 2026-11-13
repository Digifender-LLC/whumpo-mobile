package com.whumpo.android.ui.chat.forms

import com.whumpo.android.ui.chat.forms.ProposedFormsFixture.form
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import java.util.Locale
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

    @Test
    fun localDecimalsAndDigitsEncodeCanonicalNumbersWithoutAcceptingGrouping() {
        val before = Locale.getDefault()
        val number = FormDefinition("Synthetic", "", listOf(FormField("n", "Number", "", true, FieldKind.Number(-100.0, 100.0, 0.5))))
        try {
            for ((locale, text) in listOf(Locale.FRANCE to "12,5", Locale.forLanguageTag("ar-EG") to "١٢٫٥")) {
                Locale.setDefault(locale)
                val answers = mapOf("n" to Answer.Number(text))
                assertTrue(number.validate(answers, draft = false).isEmpty())
                assertEquals(JsonPrimitive(12.5), number.encodeAnswers(answers)["n"])
                assertTrue(number.validate(mapOf("n" to Answer.Number("12.5")), draft = false).isEmpty())
                assertTrue(number.validate(mapOf("n" to Answer.Number("1 234,5")), draft = false).isNotEmpty())
            }
            Locale.setDefault(Locale.US)
            assertTrue(number.validate(mapOf("n" to Answer.Number("12,5")), draft = false).isNotEmpty())
        } finally {
            Locale.setDefault(before)
        }
    }

    @Test
    fun partialDateTimeCannotBeSavedOrEncodedAsAWireValue() {
        val answers = mapOf("datetime" to Answer.DateTimeDraft("2031-05-0", "09:00"))
        assertEquals("Enter a date and time.", definition.validate(answers, draft = true)["datetime"])
        assertFailsWith<IllegalArgumentException> { definition.encodeAnswers(answers) }
    }

    @Test
    fun numbersBetweenStepsAreRejectedAtLargeMagnitudes() {
        val def = FormDefinition("Synthetic", "", listOf(FormField("n", "Synthetic number", "", true, FieldKind.Number(0.0, 1e9, 0.0005))))
        fun problem(text: String) = def.validate(mapOf("n" to Answer.Number(text)), draft = false)["n"]
        for (bad in listOf("1000000.00025", "0.00025", "12345678.50025", "999999999.99975")) assertTrue(problem(bad) != null, bad)
        for (good in listOf("1000000.0005", "0.0005", "12345678.5", "10000000.0005", "999999999.9995", "1000000000")) {
            assertEquals(null, problem(good), good)
        }
    }

    @Test
    fun tinyStepsAtLargeOffsetsKeepTheirExactDecimalGrid() {
        for ((min, max, good, bad) in listOf(
            listOf("999999999", "1000000000", "999999999.000001", "999999999.0000005"),
            listOf("-1000000000", "-999999999", "-999999999.999999", "-999999999.9999995"),
        )) {
            val def = FormDefinition("Synthetic", "", listOf(FormField("n", "Number", "", true,
                FieldKind.Number(min.toDouble(), max.toDouble(), 0.000001))))
            fun problem(text: String) = def.validate(mapOf("n" to Answer.Number(text)), draft = false)["n"]
            assertEquals(null, problem(good), good)
            assertTrue(problem(bad)?.startsWith("Use steps") == true, bad)
        }
    }

    @Test
    fun decimalGridAllowsOnlyATinyFixedFractionOfAStepForCanonicalRounding() {
        val def = FormDefinition("Synthetic", "", listOf(FormField("n", "Number", "", true,
            FieldKind.Number(0.1, 1.0, 0.2))))
        for (text in listOf("0.3", "0.30000000000000004", "0.3000000001", "0.2999999999")) {
            assertTrue(def.validate(mapOf("n" to Answer.Number(text)), draft = false).isEmpty(), text)
        }
        for (text in listOf("0.300000001", "0.299999999", "0.4")) {
            assertTrue(def.validate(mapOf("n" to Answer.Number(text)), draft = false).isNotEmpty(), text)
        }
    }

    @Test
    fun aDecimalOutsideTheRangeCannotRoundBackInside() {
        val def = FormDefinition("Synthetic", "", listOf(FormField("n", "Number", "", true,
            FieldKind.Number(-1e9, 1e9, 0.00000001))))
        for (text in listOf("1000000000.00000001", "-1000000000.00000001")) {
            assertTrue(def.validate(mapOf("n" to Answer.Number(text)), draft = false)["n"]?.startsWith("Enter a number from") == true, text)
        }
    }

    @Test
    fun typedPrecisionCannotBeSilentlyLostByTheNumberWireFormat() {
        val def = FormDefinition("Synthetic", "", listOf(FormField("n", "Number", "", true,
            FieldKind.Number(999999999.0, 1e9, 0.00000001))))
        for (text in listOf("999999999.00000001", "999999999.00000012")) {
            assertEquals("Use fewer decimal places.", def.validate(mapOf("n" to Answer.Number(text)), draft = false)["n"], text)
        }
        for (text in listOf("999999999", "999999999.0000001", "999999999.000001", "999999999.0000010")) {
            val answers = mapOf("n" to Answer.Number(text))
            assertTrue(def.validate(answers, draft = false).isEmpty(), text)
            assertTrue(ProposedFormsFixture.wire.answersFit(def, def.encodeAnswers(answers), draft = false), text)
        }
    }
}
