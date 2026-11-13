package com.whumpo.android.ui

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.whumpo.android.ui.chat.InteractionCard
import com.whumpo.android.ui.chat.forms.FormCardSummary
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.RoborazziOptions
import com.whumpo.android.ui.chat.forms.FormContinuationPhase
import com.whumpo.android.ui.chat.forms.FormContinuationState
import com.whumpo.android.ui.chat.forms.Answer
import com.whumpo.android.ui.chat.forms.FormActions
import com.whumpo.android.ui.chat.forms.FormRead
import com.whumpo.android.ui.chat.forms.FormScreen
import com.whumpo.android.ui.chat.forms.FormScreenState
import com.whumpo.android.ui.chat.forms.FormSendState
import com.whumpo.android.ui.chat.forms.JavaZoneTime
import com.whumpo.android.ui.chat.forms.ProposedFormsFixture
import com.whumpo.android.ui.chat.forms.SendAttempt
import com.whumpo.android.ui.chat.forms.SendOutcome
import com.whumpo.android.ui.chat.forms.initialAnswers
import com.whumpo.android.ui.chat.forms.mutation
import com.whumpo.core.api.FrozenMutation
import kotlinx.serialization.json.JsonObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Synthetic form states only; local recording is inspected before committing these baselines. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class FormScreenshotTest : RobolectricUiTest() {
    private object Actions : FormActions {
        override fun onAnswer(fieldId: String, answer: Answer?) {}
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

    @Test
    fun openFormAndLargeText() {
        val form = ProposedFormsFixture.form()
        val state = FormScreenState(form, form.definition.initialAnswers(null))
        for (dark in listOf(false, true)) {
            for (screen in listOf(Screen.Compact, Screen.Expanded)) {
                capture("form-open", screen, dark) { FormScreen(state, JavaZoneTime, Actions) }
            }
            capture("form-open", Screen.Compact, dark, fontScale = 2f) { FormScreen(state, JavaZoneTime, Actions) }
        }
    }

    @Test
    fun dateAndTimeDialogsLight() = captureDateAndTimeDialogs(dark = false, scale = 1f)

    @Test
    fun dateAndTimeDialogsDark() = captureDateAndTimeDialogs(dark = true, scale = 1f)

    @Test
    fun dateAndTimeDialogsAtDoubleText() = captureDateAndTimeDialogs(dark = false, scale = 2f)

    private fun captureDateAndTimeDialogs(dark: Boolean, scale: Float) {
        val form = ProposedFormsFixture.form()
        val state = FormScreenState(form.copy(definition = form.definition.copy(fields = form.definition.fields.filter { it.id == "datetime" })), answers = mapOf("datetime" to Answer.Text("2031-05-06T07:08:00.000Z")))
        val theme = (if (dark) "dark" else "light") + if (scale == 2f) "-font2" else ""
        RuntimeEnvironment.setFontScale(scale)
        try {
            launch(Screen.Compact, dark, fontScale = scale) { FormScreen(state, JavaZoneTime, Actions) }.use {
                compose.onNodeWithText("2031-05-06").performScrollTo().performClick()
                compose.onNodeWithTag("form-date-dialog").captureRoboImage("src/androidHostTest/screenshots/form-date-picker-compact-$theme.png")
                compose.onNodeWithTag("form-date-dismiss").performClick()
                compose.onNodeWithText("07:08").performScrollTo().performClick()
                val options = if (scale == 1f) RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(
                    imageComparator = if (dark) TimePickerImageComparator.Dark else TimePickerImageComparator.Light,
                    resultValidator = { it.pixelDifferences == 0 },
                )) else RoborazziOptions()
                compose.onNodeWithTag("form-time-dialog").captureRoboImage("src/androidHostTest/screenshots/form-time-picker-compact-$theme.png", roborazziOptions = options)
                compose.onNodeWithTag("form-time-dismiss").performClick()
            }
        } finally {
            RuntimeEnvironment.setFontScale(1f)
        }
    }

    @Test
    fun resourceSearchAndDraftCard() {
        val form = ProposedFormsFixture.form()
        val resource = form.definition.fields.first { it.id == "project" }
        val state = FormScreenState(form.copy(definition = form.definition.copy(fields = listOf(resource))), resourceNames = mapOf("11111111-1111-4111-8111-000000000001" to "Example studio", "11111111-1111-4111-8111-000000000002" to "Example workshop"), resourceFailures = setOf("project"), moreResources = setOf("project"))
        val interaction = checkNotNull(ChatScenes.turns.first { it.interaction != null }.interaction)
        for (dark in listOf(false, true)) {
            capture("form-resource", Screen.Compact, dark) { FormScreen(state, JavaZoneTime, Actions) }
            capture("form-card", Screen.Compact, dark) { InteractionCard(interaction, {}, summary = FormCardSummary("Project brief", "open", true)) }
        }
    }

    @Test
    fun refusalRecoveryAndSubmittedStates() {
        val form = ProposedFormsFixture.form()
        val request = form.mutation("77777777-7777-4777-8777-000000000002", "submit", JsonObject(emptyMap()))
        val unknown = FormScreenState(form, form.definition.initialAnswers(null), send = FormSendState(attempt = SendAttempt(FrozenMutation(request, byteArrayOf()), SendOutcome.Unknown)))
        val errors = FormScreenState(form, errors = mapOf("short-text" to "Enter an answer.", "long-text" to "Use at most 500 characters."))
        for (dark in listOf(false, true)) {
            capture("form-unknown", Screen.Compact, dark) { FormScreen(unknown, JavaZoneTime, Actions) }
            capture("form-errors", Screen.Compact, dark) { FormScreen(errors, JavaZoneTime, Actions) }
            capture("form-submitted", Screen.Compact, dark) { FormScreen(FormScreenState(ProposedFormsFixture.form("submitted"), continuation = FormContinuationState(FormContinuationPhase.NotStarted)), JavaZoneTime, Actions) }
            capture("form-update", Screen.Compact, dark) { FormScreen(FormScreenState(FormRead.UpdateRequired), JavaZoneTime, Actions) }
        }
    }
}
