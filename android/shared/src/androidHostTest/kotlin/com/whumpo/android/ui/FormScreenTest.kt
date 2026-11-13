package com.whumpo.android.ui

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.input.key.Key
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DatePicker
import androidx.compose.material3.TimePicker
import androidx.compose.material3.DisplayMode
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import com.whumpo.android.ui.chat.forms.FormMemoryOnlyContent
import org.junit.Assert.assertNull
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import com.whumpo.android.ui.chat.forms.FieldKind
import com.whumpo.android.ui.chat.forms.FormField
import com.whumpo.android.ui.chat.forms.FormOption
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.whumpo.android.ui.chat.forms.edit
import kotlinx.serialization.json.JsonPrimitive
import androidx.compose.ui.unit.dp
import com.whumpo.android.ui.chat.forms.Answer
import com.whumpo.android.ui.chat.forms.FormActions
import com.whumpo.android.ui.chat.forms.FormContinuationState
import com.whumpo.android.ui.chat.forms.FormContinuationPhase
import com.whumpo.android.ui.chat.forms.FormRead
import com.whumpo.android.ui.chat.forms.FormReview
import com.whumpo.android.ui.chat.forms.FormScreen
import com.whumpo.android.ui.chat.forms.FormScreenState
import com.whumpo.android.ui.chat.forms.FormSendState
import com.whumpo.android.ui.chat.forms.JavaZoneTime
import com.whumpo.android.ui.chat.forms.Notice
import com.whumpo.android.ui.chat.forms.ProposedFormsFixture
import com.whumpo.android.ui.chat.forms.SendAttempt
import com.whumpo.android.ui.chat.forms.SendOutcome
import com.whumpo.android.ui.chat.forms.initialAnswers
import com.whumpo.android.ui.chat.forms.mutation
import com.whumpo.core.api.FrozenMutation
import com.whumpo.core.api.InteractionReceipt
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Native form screen semantics, from the pending-adoption synthetic fixture (tests/fixtures/proposed/forms.json). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class FormScreenTest : RobolectricUiTest() {
    private class Recorder : FormActions {
        val calls = mutableListOf<String>()
        override fun onAnswer(fieldId: String, answer: Answer?) { calls += "answer:$fieldId" }
        override fun onSubmit() { calls += "submit" }
        override fun onSaveDraft() { calls += "save" }
        override fun onCheckAgain() { calls += "check" }
        override fun onSendAgain() { calls += "send-again" }
        override fun onReload() { calls += "reload" }
        override fun onLoadMoreResources(fieldId: String) { calls += "more:$fieldId" }
        override fun onUseNewerAnswers() { calls += "use-newer" }
        override fun onKeepMyAnswers() { calls += "keep-mine" }
        override fun onClose() { calls += "close" }
        override fun onUpdate() { calls += "update" }
        override fun onCancel() { calls += "cancel" }
        override fun onFieldBlur(fieldId: String) { calls += "blur:$fieldId" }
        override fun onContinue() { calls += "continue" }
        override fun onCheckContinuation() { calls += "continuation-check" }
        override fun onStopContinuation() { calls += "continuation-stop" }
        override fun onOpenContinuationResult() { calls += "continuation-result" }
    }

    private val open = ProposedFormsFixture.form()
    private val request = FrozenMutation(open.mutation("77777777-7777-4777-8777-000000000002", "submit", JsonObject(emptyMap())), byteArrayOf())

    private fun openState(send: FormSendState = FormSendState(), errors: Map<String, String> = emptyMap(), focus: Int = 0) =
        FormScreenState(open, open.definition.initialAnswers(null), errors, focus, send = send)

    private fun committed(operation: String = "submit") = open.mutation("77777777-7777-4777-8777-000000000002", operation, JsonObject(emptyMap())).let {
        SendAttempt(FrozenMutation(it, byteArrayOf()), SendOutcome.Committed, InteractionReceipt(1, it.requestId, it.interactionId, it.operation, 1))
    }

    private val noClick = SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick)
    private val fieldNodes = SemanticsMatcher("form field") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("form-field-") == true }

    @Test
    fun updateRequiredShowsNoFieldsNoHelpAndOnlyUpdate() {
        val actions = Recorder()
        launch(Screen.Compact) { FormScreen(FormScreenState(FormRead.UpdateRequired), JavaZoneTime, actions) }.use {
            compose.onNodeWithText("Update Whumpo to answer this form.").assertIsDisplayed()
            compose.onAllNodes(fieldNodes).assertCountEquals(0)
            compose.onAllNodesWithText("Get help").assertCountEquals(0)
            compose.onNodeWithTag("form-actions").assertDoesNotExist()
            compose.onNodeWithTag("form-update").performClick()
        }
        assertEquals(listOf("update"), actions.calls)
    }

    @Test
    fun openFormShowsVisibleFieldsWithRequiredLabelsAndLargeTargets() {
        launch(Screen.Compact) { FormScreen(openState(), JavaZoneTime, Recorder()) }.use {
            for (id in listOf("short-text", "long-text", "choice", "multi-choice", "include-details", "quantity", "date", "datetime", "project", "projects")) {
                compose.onNodeWithTag("form-field-$id").performScrollTo().assertIsDisplayed()
            }
            compose.onNodeWithTag("form-field-details").assertDoesNotExist()
            compose.onNodeWithText("Text example (required)").assertExists()
            compose.onNodeWithTag("form-option-choice-option-b").performScrollTo().assertHeightIsAtLeast(48.dp)
            compose.onNodeWithTag("form-boolean-include-details").performScrollTo().assertHeightIsAtLeast(48.dp)
            compose.onNodeWithTag("form-send").assertIsDisplayed().assertIsEnabled().assertHeightIsAtLeast(48.dp)
            compose.onNodeWithTag("form-close").assertHeightIsAtLeast(48.dp)
            compose.onNodeWithTag("form-status").assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        }
    }

    @Test
    fun firstProblemTakesFocusAndTheSummaryCountsThem() {
        val errors = linkedMapOf("long-text" to "Enter an answer.", "quantity" to "Enter an answer.")
        launch(Screen.Compact) { FormScreen(openState(errors = errors, focus = 1), JavaZoneTime, Recorder()) }.use {
            compose.onNodeWithText("Fix 2 answers to continue.").assertIsDisplayed()
            compose.onNodeWithTag("form-field-long-text").assertIsFocused()
        }
    }

    @Test
    fun aRequiredResourceWithoutLoadedProjectsCanReceiveValidationFocus() {
        val field = open.definition.fields.first { it.id == "project" }.copy(required = true)
        val form = open.copy(definition = open.definition.copy(fields = listOf(field)))
        val state = FormScreenState(form, errors = mapOf(field.id to "Choose a project."), focusFirstError = 1)
        launch(Screen.Compact) { FormScreen(state, JavaZoneTime, Recorder()) }.use {
            compose.onNodeWithTag("form-search-project").assertIsFocused()
            compose.onNodeWithText("Choose a project.").assertIsDisplayed()
        }
    }

    @Test
    fun sentIsStatusTextAndNothingCanBeSentAgain() {
        val send = FormSendState(attempt = committed(), status = Notice(1, "Sent"))
        launch(Screen.Compact) { FormScreen(openState(send), JavaZoneTime, Recorder()) }.use {
            compose.waitUntil(2_000) { compose.onAllNodes(hasText("Sent")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("form-notice", useUnmergedTree = true).assert(noClick)
            compose.onNodeWithTag("form-send").assertDoesNotExist()
            compose.onNodeWithTag("form-save").assertDoesNotExist()
            compose.onNodeWithTag("form-reload").assertDoesNotExist()
        }
    }

    @Test
    fun failedFollowUpOffersOnlyTryLoadingAgain() {
        val actions = Recorder()
        val send = FormSendState(attempt = committed(), status = Notice(1, "Sent"), followUpFailed = true)
        launch(Screen.Compact) { FormScreen(openState(send), JavaZoneTime, actions) }.use {
            compose.onNodeWithText("Try loading again").assertIsDisplayed().performClick()
            compose.onNodeWithTag("form-send").assertDoesNotExist()
        }
        assertEquals(listOf("reload"), actions.calls)
    }

    @Test
    fun unconfirmedSendFreezesAnswersAndOffersOnlyCheckAgain() {
        val actions = Recorder()
        val send = FormSendState(attempt = SendAttempt(request, SendOutcome.Unknown))
        launch(Screen.Compact) { FormScreen(openState(send), JavaZoneTime, actions) }.use {
            compose.onNodeWithTag("form-check").assertIsEnabled().performClick()
            compose.onNodeWithTag("form-send").assertDoesNotExist()
            compose.onNodeWithTag("form-save").assertDoesNotExist()
            compose.onNodeWithTag("form-field-short-text").assertIsNotEnabled()
        }
        assertEquals(listOf("check"), actions.calls)
    }

    @Test
    fun notReceivedOffersOnlySendAgain() {
        val actions = Recorder()
        val send = FormSendState(attempt = SendAttempt(request, SendOutcome.NotReceived))
        launch(Screen.Compact) { FormScreen(openState(send), JavaZoneTime, actions) }.use {
            compose.onNodeWithTag("form-send-again").performClick()
            compose.onNodeWithTag("form-check").assertDoesNotExist()
            compose.onNodeWithTag("form-save").assertDoesNotExist()
        }
        assertEquals(listOf("send-again"), actions.calls)
    }

    @Test
    fun actionsFollowTheFieldsAtDoubleTextSoNothingIsCovered() {
        launch(Screen.Compact, fontScale = 2f) { FormScreen(openState(), JavaZoneTime, Recorder()) }.use {
            compose.onNodeWithTag("form-field-projects").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("form-send").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("form-save").performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun actionsArePinnedOnWideDarkScreens() {
        launch(Screen.Expanded, dark = true) { FormScreen(openState(), JavaZoneTime, Recorder()) }.use {
            compose.onNodeWithTag("form-send").assertIsDisplayed()
        }
    }

    @Test
    fun aNewerDraftReviewOffersBothChoicesAndHoldsSending() {
        val actions = Recorder()
        val review = FormReview(mapOf("short-text" to Answer.Text("Synthetic server")), setOf("short-text"), listOf("Number example"))
        launch(Screen.Compact) { FormScreen(openState().copy(review = review), JavaZoneTime, actions) }.use {
            compose.onNodeWithTag("form-review").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Newer answers were saved for: Text example. Yours are shown.").assertExists()
            compose.onNodeWithText("No longer in this form, so they won't be sent: Number example.").assertExists()
            compose.onNodeWithTag("form-send").assertIsNotEnabled()
            compose.onNodeWithTag("form-save").assertIsNotEnabled()
            compose.onNodeWithTag("form-review-newer").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
            compose.onNodeWithTag("form-review-mine").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        }
        assertEquals(listOf("use-newer", "keep-mine"), actions.calls)
    }

    /** Owns the answers like the real caller: every edit is fed back into the screen state. */
    private class Owner(initial: FormScreenState) : FormActions {
        var state by mutableStateOf(initial)
        override fun onAnswer(fieldId: String, answer: Answer?) {
            state = state.copy(answers = if (answer == null) state.answers - fieldId else state.answers + (fieldId to answer))
        }
        override fun onSubmit() {}
        override fun onSaveDraft() {}
        override fun onCheckAgain() {}
        override fun onSendAgain() {}
        override fun onReload() {}
        override fun onLoadMoreResources(fieldId: String) {}
        override fun onUseNewerAnswers() {}
        override fun onKeepMyAnswers() {}
        override fun onClose() {}
        override fun onUpdate() {}
        override fun onCancel() {}
        override fun onFieldBlur(fieldId: String) {}
        override fun onContinue() {}
        override fun onCheckContinuation() {}
        override fun onStopContinuation() {}
        override fun onOpenContinuationResult() {}
    }

    private fun shows(tag: String, text: String) =
        compose.onNodeWithTag(tag).performScrollTo().assert(hasAnyDescendant(hasText(text.ifEmpty { if (tag.endsWith("-time")) "Choose time" else "Choose date" })))

    @Test
    fun dateTimeFollowsReplacedAnswersAndPickerConfirmsExplicitly() {
        val a = mapOf("datetime" to Answer.Text("2030-03-04T15:30:00.000Z"))
        val owner = Owner(FormScreenState(open, a))
        launch(Screen.Compact) { FormScreen(owner.state, JavaZoneTime, owner) }.use {
            shows("form-field-datetime-date", "2030-03-04")
            shows("form-field-datetime-time", "15:30")
            compose.runOnIdle { owner.state = owner.state.copy(answers = mapOf("datetime" to Answer.Text("2031-05-06T07:08:00.000Z"))) }
            shows("form-field-datetime-date", "2031-05-06")
            shows("form-field-datetime-time", "07:08")
            compose.onNodeWithText("07:08").performClick()
            compose.onNodeWithTag("form-time-picker").assertExists()
            compose.runOnIdle { assertEquals(Answer.Text("2031-05-06T07:08:00.000Z"), owner.state.answers["datetime"]) }
            compose.onNodeWithTag("form-time-confirm").performClick()
            compose.runOnIdle { owner.state = owner.state.copy(answers = mapOf("datetime" to Answer.DateTimeDraft("2031-05-0", "09:00"))) }
            shows("form-field-datetime-date", "2031-05-0")
            shows("form-field-datetime-time", "09:00")
            compose.runOnIdle { assertEquals(Answer.DateTimeDraft("2031-05-0", "09:00"), owner.state.answers["datetime"]) }
        }
    }

    @Test
    fun partialDateTimeSurvivesFormRemountWithoutSavedInstanceState() {
        val owner = Owner(FormScreenState(open, mapOf("datetime" to Answer.Text("2031-05-06T09:00:00.000Z"))))
        var visible by mutableStateOf(true)
        launch(Screen.Compact) { if (visible) FormScreen(owner.state, JavaZoneTime, owner) }.use {
            compose.runOnIdle { owner.state = owner.state.copy(answers = mapOf("datetime" to Answer.DateTimeDraft("2031-05-0", "09:00"))) }
            compose.runOnIdle { visible = false }
            compose.onNodeWithTag("form-screen").assertDoesNotExist()
            compose.runOnIdle { visible = true }
            shows("form-field-datetime-date", "2031-05-0")
            shows("form-field-datetime-time", "09:00")
        }
    }

    @Test
    fun confirmingAnOutOfRangeUtcTimePreservesBothLocalInputs() {
        val cases = listOf(
            Triple("0001-01-01", "00:00", "Etc/GMT-1"),
            Triple("9999-12-31", "23:59", "America/Chicago"),
        )
        for ((date, time, zone) in cases) {
            val field = open.definition.fields.single { it.id == "datetime" }.copy(kind = FieldKind.DateTime(zone))
            val form = open.copy(definition = open.definition.copy(fields = listOf(field)))
            val draft = Answer.DateTimeDraft(date, time)
            val owner = Owner(FormScreenState(form, mapOf(field.id to draft)))
            launch(Screen.Compact) { FormScreen(owner.state, JavaZoneTime, owner) }.use {
                compose.onNodeWithText(time).performClick()
                compose.onNodeWithTag("form-time-confirm").performClick()
                compose.runOnIdle { assertEquals(draft, owner.state.answers[field.id]) }
                shows("form-field-datetime-date", date)
                shows("form-field-datetime-time", time)
            }
        }
    }

    @Test
    fun sameFieldIdInAnotherFormShowsThatFormsAnswer() {
        val other = ProposedFormsFixture.wire.decode(
            ProposedFormsFixture.read("open").edit("envelope.id", JsonPrimitive("66666666-6666-4666-8666-000000000099")),
        ) as FormRead.Form
        val owner = Owner(FormScreenState(open, mapOf("datetime" to Answer.Text("2030-03-04T15:30:00.000Z"))))
        launch(Screen.Compact) { FormScreen(owner.state, JavaZoneTime, owner) }.use {
            shows("form-field-datetime-time", "15:30")
            compose.runOnIdle { owner.state = FormScreenState(other, mapOf("datetime" to Answer.Text("2031-05-06T07:08:00.000Z"))) }
            shows("form-field-datetime-date", "2031-05-06")
            shows("form-field-datetime-time", "07:08")
            compose.runOnIdle { owner.state = FormScreenState(open, emptyMap()) }
            shows("form-field-datetime-date", "")
            shows("form-field-datetime-time", "")
        }
    }

    @Test
    fun submittedAnswersAreReadOnlyAndUnloadedNamesAreLabelled() {
        val submitted = ProposedFormsFixture.form("submitted")
        launch(Screen.Compact) { FormScreen(FormScreenState(submitted), JavaZoneTime, Recorder()) }.use {
            compose.onNodeWithTag("form-answer-project").performScrollTo().assert(hasText("Name not loaded", substring = true))
            compose.onNodeWithTag("form-answer-include-details").performScrollTo().assert(hasText("No", substring = true))
            compose.onAllNodes(fieldNodes).assertCountEquals(0)
            compose.onNodeWithTag("form-send").assertDoesNotExist()
        }
    }
    @Test
    fun cancelRequiresConfirmationAndDismissalSendsNothing() {
        val actions = Recorder()
        launch(Screen.Compact) { FormScreen(openState(), JavaZoneTime, actions) }.use {
            compose.onNodeWithTag("form-cancel").performClick()
            assertTrue(actions.calls.isEmpty())
            compose.onNodeWithTag("form-cancel-dismiss").performClick()
            assertTrue(actions.calls.isEmpty())
            compose.onNodeWithTag("form-cancel").performClick()
            compose.onNodeWithTag("form-cancel-confirm").performClick()
        }
        assertEquals(listOf("cancel"), actions.calls)
    }

    @Test
    fun searchableChoicesFilterOnlyTheDisplayAndRetainSelection() {
        val field = FormField("choose", "Choose an option", "", false, FieldKind.Choice((1..8).map { FormOption("option-$it", "Option $it") }))
        val form = open.copy(definition = open.definition.copy(fields = listOf(field)))
        val owner = Owner(FormScreenState(form, mapOf("choose" to Answer.One("option-2"))))
        launch(Screen.Compact) { FormScreen(owner.state, JavaZoneTime, owner) }.use {
            compose.onNodeWithTag("form-search-choose").performTextReplacement("8")
            compose.onNodeWithTag("form-option-choose-option-8").assertExists()
            compose.onNodeWithTag("form-option-choose-option-2").assertDoesNotExist()
            compose.runOnIdle { assertEquals(Answer.One("option-2"), owner.state.answers["choose"]) }
            compose.onNodeWithTag("form-option-choose-option-8").performClick()
            compose.runOnIdle { assertEquals(Answer.One("option-8"), owner.state.answers["choose"]) }
        }
    }

    @Test
    fun resourceSearchPreservesChosenItemsAndExplainsFailedLoad() {
        val id = "11111111-1111-4111-8111-000000000001"
        val field = FormField("project", "Project", "", false, FieldKind.Resource(false, 1))
        val form = open.copy(definition = open.definition.copy(fields = listOf(field)))
        val state = FormScreenState(form, mapOf("project" to Answer.One(id)), resourceNames = mapOf(id to "Saved project"), moreResources = setOf("project"), resourceFailures = setOf("project"))
        val actions = Recorder()
        launch(Screen.Compact) { FormScreen(state, JavaZoneTime, actions) }.use {
            compose.onNodeWithTag("form-search-project").performTextReplacement("unmatched")
            compose.onNodeWithText("No matching options.").assertExists()
            compose.onNodeWithText("Couldn't load projects. Your selections are kept.").assertExists()
            compose.onNodeWithTag("form-more-project").performScrollTo().performClick()
        }
        assertEquals(listOf("more:project"), actions.calls.filterNot { it.startsWith("blur:") })
    }

    @Test
    fun keyboardBlurValidatesOnlyTheVisitedField() {
        val actions = Recorder()
        launch(Screen.Compact) { FormScreen(openState(), JavaZoneTime, actions) }.use {
            assertTrue(actions.calls.isEmpty())
            compose.onNodeWithTag("form-field-short-text").performClick().performKeyInput { pressKey(Key.Tab) }
            compose.runOnIdle { assertTrue("blur:short-text" in actions.calls) }
            assertTrue(actions.calls.none { it == "blur:long-text" })
        }
    }

    @Test
    fun datePickerDoesNotPersistAnswersInSavedInstanceState() {
        val field = FormField("date", "Date", "", true, FieldKind.Date)
        val form = open.copy(definition = open.definition.copy(fields = listOf(field)))
        val owner = Owner(FormScreenState(form, mapOf("date" to Answer.Text("2031-05-06"))))
        val registry = SaveableStateRegistry(null) { true }
        launch(Screen.Compact) {
            CompositionLocalProvider(LocalSaveableStateRegistry provides registry) { FormScreen(owner.state, JavaZoneTime, owner) }
        }.use {
            compose.onNodeWithText("2031-05-06").performClick()
            compose.onNodeWithTag("form-date-picker").assertExists()
            compose.runOnIdle { assertTrue(registry.performSave().isEmpty()) }
            compose.onNodeWithTag("form-date-dismiss").performClick()
            compose.runOnIdle { assertEquals(Answer.Text("2031-05-06"), owner.state.answers["date"]) }
            compose.onNodeWithText("2031-05-06").performClick()
            compose.onNodeWithTag("form-date-confirm").performClick()
            compose.runOnIdle { assertEquals(Answer.Text("2031-05-06"), owner.state.answers["date"]) }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun pickerDialogRootDoesNotSaveDateOrTimeAnswers() {
        var showTime by mutableStateOf(false)
        var dialogRegistry: androidx.compose.runtime.saveable.SaveableStateRegistry? = null
        var pickerRegistry: androidx.compose.runtime.saveable.SaveableStateRegistry? = SaveableStateRegistry(null) { true }
        launch(Screen.Compact) {
            FormMemoryOnlyContent {
                DatePickerDialog(onDismissRequest = {}, confirmButton = {}) {
                    dialogRegistry = LocalSaveableStateRegistry.current
                    FormMemoryOnlyContent {
                        pickerRegistry = LocalSaveableStateRegistry.current
                        val date = rememberDatePickerState(initialSelectedDateMillis = 1935792000000L, initialDisplayMode = DisplayMode.Input)
                        val time = rememberTimePickerState(initialHour = 7, initialMinute = 8, is24Hour = true)
                        if (showTime) TimePicker(time) else DatePicker(date)
                    }
                }
            }
        }.use {
            compose.runOnIdle {
                assertNull(pickerRegistry)
                assertTrue(dialogRegistry?.performSave().orEmpty().isEmpty())
                showTime = true
            }
            compose.runOnIdle {
                assertNull(pickerRegistry)
                assertTrue(dialogRegistry?.performSave().orEmpty().isEmpty())
            }
        }
    }

    @Test
    fun pickerAnswersCanBeClearedWithoutSending() {
        val fields = open.definition.fields.filter { it.id in setOf("date", "datetime") }
        val owner = Owner(FormScreenState(open.copy(definition = open.definition.copy(fields = fields)), mapOf("date" to Answer.Text("2031-05-06"), "datetime" to Answer.Text("2031-05-06T07:08:00.000Z"))))
        launch(Screen.Compact) { FormScreen(owner.state, JavaZoneTime, owner) }.use {
            compose.onAllNodesWithText("Clear date")[0].performScrollTo().performClick()
            compose.runOnIdle { assertFalse("date" in owner.state.answers) }
            compose.onNodeWithText("Clear time").performScrollTo().performClick()
            compose.runOnIdle { assertEquals(Answer.DateTimeDraft("2031-05-06", ""), owner.state.answers["datetime"]) }
            compose.onNodeWithText("Clear date").performScrollTo().performClick()
            compose.runOnIdle { assertFalse("datetime" in owner.state.answers) }
        }
    }

    @Test
    fun explicitFalseIsACompleteSwitchAnswerWithoutAnImplicitDefault() {
        val field = FormField("ready", "Ready", "", true, FieldKind.YesNo)
        val owner = Owner(FormScreenState(open.copy(definition = open.definition.copy(fields = listOf(field)))))
        launch(Screen.Compact) { FormScreen(owner.state, JavaZoneTime, owner) }.use {
            compose.runOnIdle { assertTrue(owner.state.answers.isEmpty()) }
            compose.onNodeWithTag("form-boolean-ready").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Not answered"))
            compose.onNodeWithTag("form-boolean-no-ready").performClick()
            compose.runOnIdle { assertEquals(Answer.Flag(false), owner.state.answers["ready"]) }
            compose.onNodeWithTag("form-boolean-ready").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "No")).performClick()
            compose.runOnIdle { assertEquals(Answer.Flag(true), owner.state.answers["ready"]) }
        }
    }

    @Test
    fun continuationActionsFollowAuthoritativeStateAndStopIsConfirmed() {
        val actions = Recorder()
        val submitted = ProposedFormsFixture.form("submitted")
        var state by mutableStateOf(FormScreenState(submitted, continuation = FormContinuationState(FormContinuationPhase.NotStarted)))
        launch(Screen.Compact) { FormScreen(state, JavaZoneTime, actions) }.use {
            compose.onNodeWithTag("form-continue").assertIsEnabled()
            compose.onNodeWithTag("form-continuation-check").assertDoesNotExist()
            compose.onNodeWithTag("form-stop").performClick()
            assertTrue(actions.calls.isEmpty())
            compose.onNodeWithTag("form-stop-dismiss").performClick()
            assertTrue(actions.calls.isEmpty())
            compose.onNodeWithTag("form-stop").performClick()
            compose.onNodeWithTag("form-stop-confirm").performClick()
            assertEquals(listOf("continuation-stop"), actions.calls)
            for (phase in listOf(FormContinuationPhase.Pending, FormContinuationPhase.Unknown)) {
                compose.runOnIdle { state = state.copy(continuation = FormContinuationState(phase)) }
                compose.onNodeWithTag("form-continue").assertDoesNotExist()
                compose.onNodeWithTag("form-stop").assertDoesNotExist()
                compose.onNodeWithTag("form-continuation-check").assertIsEnabled().performClick()
            }
            compose.runOnIdle { state = state.copy(continuation = FormContinuationState(FormContinuationPhase.Failed)) }
            compose.onNodeWithTag("form-continue").assertDoesNotExist()
            compose.onNodeWithTag("form-stop").assertDoesNotExist()
            compose.onNodeWithTag("form-continuation-check").assertDoesNotExist()
            compose.runOnIdle { state = state.copy(continuation = FormContinuationState(FormContinuationPhase.Succeeded, resultTurnId = "55555555-5555-4555-8555-000000000001")) }
            compose.onNodeWithTag("form-continuation-result").performClick()
        }
        assertEquals(listOf("continuation-stop", "continuation-check", "continuation-check", "continuation-result"), actions.calls)
    }

    @Test
    fun unavailableContinuationDoesNotOfferMutationAndCancelRecoveryIsAccurate() {
        val actions = Recorder()
        launch(Screen.Compact) {
            FormScreen(FormScreenState(ProposedFormsFixture.form("submitted"), continuation = FormContinuationState(FormContinuationPhase.Unavailable)), JavaZoneTime, actions)
        }.use {
            compose.onNodeWithTag("form-continue").assertDoesNotExist()
            compose.onNodeWithTag("form-stop").assertDoesNotExist()
            compose.onNodeWithTag("form-continuation-update").performClick()
        }
        val mutation = FrozenMutation(open.mutation("77777777-7777-4777-8777-000000000002", "cancel", null), byteArrayOf())
        launch(Screen.Compact) { FormScreen(openState(FormSendState(attempt = SendAttempt(mutation, SendOutcome.Unknown))), JavaZoneTime, actions) }.use {
            compose.onNodeWithText("We couldn't confirm the form was canceled. Check before trying again.").assertExists()
            compose.onNodeWithTag("form-check").assertIsEnabled()
            compose.onNodeWithTag("form-send-again").assertDoesNotExist()
            compose.onNodeWithTag("form-cancel").assertDoesNotExist()
        }
    }

}
