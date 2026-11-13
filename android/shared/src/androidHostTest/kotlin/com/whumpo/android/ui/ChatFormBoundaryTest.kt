package com.whumpo.android.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import com.whumpo.android.ui.chat.ChatActions
import com.whumpo.android.ui.chat.ChatLayout
import com.whumpo.android.ui.chat.HapticCue
import com.whumpo.android.ui.chat.HapticKind
import com.whumpo.android.ui.chat.forms.Answer
import com.whumpo.android.ui.chat.forms.FormActions
import com.whumpo.android.ui.chat.forms.FormPane
import com.whumpo.android.ui.chat.forms.FormScreenState
import com.whumpo.android.ui.chat.forms.JavaZoneTime
import com.whumpo.android.ui.chat.forms.ProposedFormsFixture
import com.whumpo.android.ui.shell.WidthClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ChatFormBoundaryTest : RobolectricUiTest() {
    private class Host(private val startLoading: Boolean = false) : ChatActions, FormActions {
        private val form = ProposedFormsFixture.form().let { read ->
            read.copy(definition = read.definition.copy(fields = read.definition.fields.take(1)))
        }
        private val first = ChatScenes.turns.first()
        private val turns = List(20) { index ->
            first.copy(id = "synthetic-turn-$index", userText = "Synthetic message $index", assistantText = "Synthetic reply $index",
                interaction = first.interaction.takeIf { index == 2 })
        }
        var screen by mutableStateOf(ChatScenes.screen(ChatScenes.ready.copy(turns = turns, haptic = HapticCue(HapticKind.Confirm, 1))))
        var sends = 0
        override fun onDraftChanged(text: String) { screen = screen.copy(draft = text) }
        override fun onSend() { sends++ }
        override fun onOpenForm(interactionId: String) {
            if (startLoading) screen = screen.copy(form = FormPane.Loading(form.interactionId)) else completeRead()
        }
        fun completeRead() { screen = screen.copy(form = FormPane.Shown(form.interactionId, FormScreenState(form))) }
        override fun onClose() { screen = screen.copy(form = null) }
        override fun onAnswer(fieldId: String, answer: Answer?) {}
        override fun onSubmit() {}
        override fun onSaveDraft() {}
        override fun onCheckAgain() {}
        override fun onSendAgain() {}
        override fun onReload() {}
        override fun onLoadMoreResources(fieldId: String) {}
        override fun onUseNewerAnswers() {}
        override fun onKeepMyAnswers() {}
        override fun onUpdate() {}
        override fun onCancel() {}
        override fun onFieldBlur(fieldId: String) {}
        override fun onContinue() {}
        override fun onCheckContinuation() {}
        override fun onStopContinuation() {}
        override fun onOpenContinuationResult() {}
    }

    @Test
    fun compactFormOwnsKeyboardAndSemanticsThenRestoresChatState() {
        for ((size, width) in listOf(Screen.Compact to WidthClass.Compact, Screen.Medium to WidthClass.Medium)) {
            val host = Host()
            lateinit var inputMode: InputModeManager
            val cues = mutableListOf<HapticFeedbackType>()
            val haptics = object : HapticFeedback { override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) { cues += hapticFeedbackType } }
            launch(size) {
                CompositionLocalProvider(LocalHapticFeedback provides haptics) {
                    val mode = LocalInputModeManager.current
                    SideEffect { inputMode = mode }
                    ChatLayout(host.screen, width, host, animated = false, formActions = host, zones = JavaZoneTime)
                }
            }.use {
                compose.runOnIdle {
                    assertTrue(inputMode.requestInputMode(InputMode.Keyboard))
                    assertEquals(InputMode.Keyboard, inputMode.inputMode)
                }
                compose.onNodeWithTag("chat-composer").performTextInput("Retained synthetic draft")
                compose.onNodeWithTag("chat-transcript").performScrollToNode(hasTestTag("interaction-open"))
                val position = compose.onNodeWithTag("chat-transcript").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
                compose.onNodeWithTag("interaction-open").assertIsDisplayed().performClick()
                compose.onNodeWithTag("chat-composer", useUnmergedTree = true).assertDoesNotExist()
                compose.onNodeWithTag("chat-transcript", useUnmergedTree = true).assertDoesNotExist()
                compose.onNodeWithTag("chat-send", useUnmergedTree = true).assertDoesNotExist()
                compose.onNode(isFocused()).assert(hasAnyAncestor(hasTestTag("form-screen")))
                repeat(2) {
                    val before = compose.onNode(isFocused()).fetchSemanticsNode().id
                    compose.onNodeWithTag("form-screen").performKeyInput { pressKey(Key.Tab) }
                    compose.onNode(isFocused()).assert(hasAnyAncestor(hasTestTag("form-screen")))
                    assertNotEquals(before, compose.onNode(isFocused()).fetchSemanticsNode().id)
                }
                compose.onNodeWithTag("form-close").performClick()
                compose.onNodeWithTag("form-screen", useUnmergedTree = true).assertDoesNotExist()
                val returned = compose.onNode(isFocused()).assertIsDisplayed()
                    .assert(hasTestTag("interaction-open") or hasTestTag("chat-composer"))
                val returnedId = returned.fetchSemanticsNode().id
                println("Form return focus ($width): " + returned.fetchSemanticsNode().config[SemanticsProperties.TestTag])
                returned.performKeyInput { pressKey(Key.Tab) }
                val next = compose.onNode(isFocused()).assertIsDisplayed()
                    .assert(hasTestTag("interaction-open") or hasTestTag("chat-composer") or hasTestTag("chat-send"))
                assertNotEquals(returnedId, next.fetchSemanticsNode().id)
                compose.onNodeWithTag("chat-composer").assert(hasText("Retained synthetic draft"))
                val restored = compose.onNodeWithTag("chat-transcript").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
                assertEquals(position, restored, 1f)
                compose.onNodeWithTag("interaction-open").performClick()
                compose.onNodeWithTag("form-close").performClick()
                assertEquals(listOf(HapticFeedbackType.Confirm), cues)
                assertEquals(0, host.sends)
            }
        }
    }

    @Test
    fun keyboardFocusEntersTheLoadedFormAfterItsLoadingPaneIsReplaced() {
        val host = Host(startLoading = true)
        lateinit var inputMode: InputModeManager
        launch(Screen.Compact) {
            val mode = LocalInputModeManager.current
            SideEffect { inputMode = mode }
            ChatLayout(host.screen, WidthClass.Compact, host, animated = false, formActions = host, zones = JavaZoneTime)
        }.use {
            compose.runOnIdle {
                assertTrue(inputMode.requestInputMode(InputMode.Keyboard))
                assertEquals(InputMode.Keyboard, inputMode.inputMode)
            }
            compose.onNodeWithTag("chat-transcript").performScrollToNode(hasTestTag("interaction-open"))
            compose.onNodeWithTag("interaction-open").performClick()
            compose.onNodeWithTag("form-loading").assertIsDisplayed()
            // Traverse the loading pane after explicitly establishing the host's keyboard input mode.
            compose.onNodeWithTag("form-loading").performKeyInput { pressKey(Key.Tab) }
            compose.onNodeWithTag("form-close").assertIsFocused()
            compose.runOnIdle { host.completeRead() }
            compose.onNode(isFocused()).assert(hasAnyAncestor(hasTestTag("form-screen")))
            compose.onNodeWithTag("form-screen").performKeyInput { pressKey(Key.Tab) }
            compose.onNode(isFocused()).assert(hasAnyAncestor(hasTestTag("form-screen")))
        }
    }

    @Test
    fun expandedFormKeepsChatReachableBesideIt() {
        val host = Host()
        launch(Screen.Expanded) {
            ChatLayout(host.screen, WidthClass.Expanded, host, animated = false, formActions = host, zones = JavaZoneTime)
        }.use {
            compose.onNodeWithTag("chat-transcript").performScrollToNode(hasTestTag("interaction-open"))
            compose.onNodeWithTag("interaction-open").performClick()
            compose.onNodeWithTag("form-screen").assertIsDisplayed()
            compose.onNodeWithTag("chat-transcript").assertIsDisplayed()
            compose.onNodeWithTag("chat-composer").performTextInput("Side by side draft")
            compose.onNodeWithTag("chat-composer").assert(hasText("Side by side draft"))
            compose.onNodeWithTag("form-screen").assertIsDisplayed()
            assertEquals(0, host.sends)
        }
    }
}
