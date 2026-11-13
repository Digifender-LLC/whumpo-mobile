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
            compose.onNodeWithTag("form-option-include-details-yes").performScrollTo().assertHeightIsAtLeast(48.dp)
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
            compose.onNodeWithText("No projects loaded yet.").assertIsFocused()
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
        compose.onNodeWithTag(tag).performScrollTo().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString(text)))

    @Test
    fun dateTimeFollowsReplacedAnswersButKeepsTypingInProgress() {
        val a = mapOf("datetime" to Answer.Text("2030-03-04T15:30:00.000Z"))
        val owner = Owner(FormScreenState(open, a))
        launch(Screen.Compact) { FormScreen(owner.state, JavaZoneTime, owner) }.use {
            shows("form-field-datetime-date", "2030-03-04")
            shows("form-field-datetime-time", "15:30")
            compose.runOnIdle { owner.state = owner.state.copy(answers = mapOf("datetime" to Answer.Text("2031-05-06T07:08:00.000Z"))) }
            shows("form-field-datetime-date", "2031-05-06")
            shows("form-field-datetime-time", "07:08")
            compose.onNodeWithTag("form-field-datetime-time").performTextReplacement("09:00")
            compose.runOnIdle { assertEquals(Answer.Text("2031-05-06T09:00:00.000Z"), owner.state.answers["datetime"]) }
            compose.onNodeWithTag("form-field-datetime-date").performTextReplacement("2031-05-0")
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
            compose.onNodeWithTag("form-field-datetime-date").performScrollTo().performTextReplacement("2031-05-0")
            compose.runOnIdle { visible = false }
            compose.onNodeWithTag("form-screen").assertDoesNotExist()
            compose.runOnIdle { visible = true }
            shows("form-field-datetime-date", "2031-05-0")
            shows("form-field-datetime-time", "09:00")
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
}
