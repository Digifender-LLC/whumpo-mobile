package com.whumpo.android

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.whumpo.android.ui.chat.forms.Answer
import com.whumpo.android.ui.chat.forms.FieldKind
import com.whumpo.android.ui.chat.forms.FormActions
import com.whumpo.android.ui.chat.forms.FormDefinition
import com.whumpo.android.ui.chat.forms.FormField
import com.whumpo.android.ui.chat.forms.FormRead
import com.whumpo.android.ui.chat.forms.FormScreen
import com.whumpo.android.ui.chat.forms.FormScreenState
import com.whumpo.android.ui.chat.forms.FormState
import com.whumpo.android.ui.chat.forms.JavaZoneTime
import com.whumpo.android.ui.theme.WhumpoTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The native form on a device: typed input, 200% text, accessibility checks and the soft keyboard. Synthetic and
 * backend-free; semantics and ATF checks are not physical TalkBack acceptance.
 */
@RunWith(AndroidJUnit4::class)
class FormDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private class Actions : FormActions {
        var answers by mutableStateOf(emptyMap<String, Answer>())
        val calls = mutableListOf<String>()
        override fun onAnswer(fieldId: String, answer: Answer?) {
            answers = if (answer == null) answers - fieldId else answers + (fieldId to answer)
        }
        override fun onSubmit() { calls += "submit" }
        override fun onSaveDraft() { calls += "save" }
        override fun onCheckAgain() { calls += "check" }
        override fun onSendAgain() { calls += "send-again" }
        override fun onReload() { calls += "reload" }
        override fun onLoadMoreResources(fieldId: String) { calls += "more:$fieldId" }
        override fun onClose() { calls += "close" }
        override fun onUpdate() { calls += "update" }
    }

    private val fieldIds = listOf("name", "notes", "ready")
    private val form = FormRead.Form(
        interactionId = "66666666-6666-4666-8666-0000000000d1",
        definitionDigest = "d".repeat(64),
        definition = FormDefinition(
            "Synthetic form",
            "Synthetic summary",
            listOf(
                FormField("name", "Synthetic name", "Synthetic help", true, FieldKind.Text(80, long = false)),
                FormField("notes", "Synthetic notes", "", false, FieldKind.Text(500, long = true)),
                FormField("ready", "Synthetic yes or no", "", false, FieldKind.YesNo),
            ),
        ),
        state = FormState.Open,
        answerRevision = 0,
        draft = null,
        replacementInteractionId = null,
        submission = null,
    )

    private fun show(fontScale: Float = 1f): Actions {
        val actions = Actions()
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                WhumpoTheme { FormScreen(FormScreenState(form, actions.answers), JavaZoneTime, actions) }
            }
        }
        return actions
    }

    @Test
    fun typedAnswersSendNothingUntilSend() {
        val actions = show()
        compose.onNodeWithTag("form-field-name").performTextInput("Synthetic answer")
        compose.waitForIdle()
        assertEquals(Answer.Text("Synthetic answer"), actions.answers["name"])
        assertTrue(actions.calls.isEmpty())
        compose.onNodeWithTag("form-send").performClick()
        assertEquals(listOf("submit"), actions.calls)
    }

    @Test
    fun everyFieldAndActionIsReachableAtDoubleTextAndPassesAccessibilityChecks() {
        compose.enableAccessibilityChecks()
        show(fontScale = 2f)
        compose.onRoot().tryPerformAccessibilityChecks()
        fieldIds.forEach { compose.onNodeWithTag("form-field-$it").performScrollTo().assertIsDisplayed() }
        compose.onNodeWithTag("form-send").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("form-save").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onRoot().tryPerformAccessibilityChecks()
    }

    @Test
    fun theActionBarStaysAboveTheSoftKeyboard() {
        compose.runOnUiThread {
            WindowCompat.setDecorFitsSystemWindows(compose.activity.window, false)
            @Suppress("DEPRECATION")
            compose.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        show()
        compose.onNodeWithTag("form-field-name").performClick()

        var imeBottom = 0
        var windowHeight = 0
        val shown = runCatching {
            compose.waitUntil(10_000) {
                var visible = false
                compose.runOnUiThread {
                    val root = compose.activity.window.decorView
                    val insets = ViewCompat.getRootWindowInsets(root)
                    visible = insets?.isVisible(WindowInsetsCompat.Type.ime()) == true
                    imeBottom = insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
                    windowHeight = root.height
                }
                visible && imeBottom > 0
            }
        }.isSuccess
        // Some headless emulator images never show a soft keyboard; record that as skipped.
        assumeTrue("Soft keyboard did not appear on this device", shown)

        compose.waitForIdle()
        val barBottom = compose.onNodeWithTag("form-actions").fetchSemanticsNode().boundsInWindow.bottom
        assertTrue(
            "action bar bottom $barBottom must be above keyboard top ${windowHeight - imeBottom}",
            barBottom <= windowHeight - imeBottom + 1f,
        )
    }
}
