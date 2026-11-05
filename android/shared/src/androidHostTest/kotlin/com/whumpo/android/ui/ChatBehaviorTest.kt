package com.whumpo.android.ui

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.whumpo.android.ui.chat.ChatActions
import com.whumpo.android.ui.chat.ChatLayout
import com.whumpo.android.ui.chat.ChatState
import com.whumpo.android.ui.chat.Outbound
import com.whumpo.android.ui.chat.Phase
import com.whumpo.android.ui.chat.ReviewSheetContent
import com.whumpo.android.ui.chat.ReviewStep
import com.whumpo.android.ui.chat.SendStatus
import com.whumpo.android.ui.chat.StarterPrompts
import com.whumpo.android.ui.shell.WidthClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ChatBehaviorTest : RobolectricUiTest() {
    private class Recorder : ChatActions {
        val drafts = mutableListOf<String>()
        var sends = 0
        var confirms = 0
        var dismisses = 0
        var opened: String? = null
        var checks = 0
        var resends = 0

        override fun onDraftChanged(text: String) {
            drafts += text
        }

        override fun onSend() {
            sends += 1
        }

        override fun onConfirmReview() {
            confirms += 1
        }

        override fun onDismissReview() {
            dismisses += 1
        }

        override fun onOpenReview(turnId: String) {
            opened = turnId
        }

        override fun onCheckSend() {
            checks += 1
        }

        override fun onSendAgain() {
            resends += 1
        }
    }

    private fun polite() = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)

    @Test
    fun starterPromptsFillTheDraftAndNeverSend() {
        val actions = Recorder()
        launch(Screen.Compact) {
            ChatLayout(ChatScenes.screen(ChatState(phase = Phase.Ready)), WidthClass.Compact, actions, animated = false)
        }.use {
            compose.onNodeWithTag("chat-starter-1").performClick()
        }
        assertEquals(listOf(StarterPrompts[1]), actions.drafts)
        assertEquals(0, actions.sends)
    }

    @Test
    fun turnsShowRelativeTimesAndSpeakTheAbsoluteTime() {
        launch(Screen.Compact) {
            ChatLayout(ChatScenes.screen(ChatScenes.ready.copy(turns = ChatScenes.turns.take(1))), WidthClass.Compact, Recorder(), animated = false)
        }.use {
            val time = compose.onNodeWithTag("turn-time-${ChatScenes.turns[0].id}")
            time.assertContentDescriptionEquals("5 min ago, 4 Oct 2026, 12:00 UTC")
        }
    }

    @Test
    fun sendIsDisabledUntilThereIsADraftAndExplainsWhy() {
        launch(Screen.Compact) {
            ChatLayout(ChatScenes.screen(ChatScenes.ready), WidthClass.Compact, Recorder(), animated = false)
        }.use {
            compose.onNodeWithTag("chat-send").assertIsNotEnabled()
            compose.onNodeWithTag("chat-hint").assert(hasText("Write a message to send it."))
        }
        launch(Screen.Compact) {
            ChatLayout(ChatScenes.screen(ChatScenes.ready, draft = "Hello"), WidthClass.Compact, Recorder(), animated = false)
        }.use {
            compose.onNodeWithTag("chat-send").assertIsEnabled()
        }
    }

    @Test
    fun theTypingIndicatorAndAnnouncementsArePoliteLiveRegions() {
        val pending = ChatScenes.turns.last().copy(status = "pending", assistantText = null, review = null)
        val state = ChatScenes.ready.copy(turns = ChatScenes.turns.dropLast(1) + pending, announcement = "Whumpo replied: Hello")
        launch(Screen.Compact) {
            ChatLayout(ChatScenes.screen(state), WidthClass.Compact, Recorder(), animated = false)
        }.use {
            compose.onNodeWithTag("chat-typing").assert(polite()).assert(hasText("Whumpo is working on it."))
            compose.onNodeWithTag("chat-announcement").assert(polite())
        }
    }

    @Test
    fun anInteractionWithRawJsonGetsASafeGenericCard() {
        launch(Screen.Compact) {
            // Only the first turn, so the list is not scrolled past the card.
            ChatLayout(ChatScenes.screen(ChatScenes.ready.copy(turns = ChatScenes.turns.take(1))), WidthClass.Compact, Recorder(), animated = false)
        }.use {
            compose.onNodeWithTag("interaction-generic").assertExists()
            compose.onNode(hasText("Whumpo has a form for you")).assertExists()
        }
    }

    @Test
    fun aLostReplyOffersCheckingNotSending() {
        val actions = Recorder()
        val lost = Outbound("55555555-5555-4555-8555-000000000001", "Add a booking page", 4, SendStatus.Unknown)
        launch(Screen.Compact) {
            ChatLayout(ChatScenes.screen(ChatScenes.ready.copy(outbound = lost)), WidthClass.Compact, actions, animated = false)
        }.use {
            compose.onNodeWithTag("outbound-check").performClick()
            compose.onNodeWithTag("outbound-resend").assertDoesNotExist()
        }
        assertEquals(1, actions.checks)
        assertEquals(0, actions.resends)
        assertEquals(0, actions.sends)
    }

    @Test
    fun theReviewCardOffersReviewChangeAsThePrimaryAction() {
        val actions = Recorder()
        launch(Screen.Compact) {
            ChatLayout(ChatScenes.screen(ChatScenes.ready), WidthClass.Compact, actions, animated = false)
        }.use {
            compose.onAllNodes(hasText("Review change"))[0].performClick()
        }
        assertTrue(actions.opened != null)
    }

    @Test
    fun theSheetHasOnePrimaryOneSecondaryAndAQuietDismiss() {
        val actions = Recorder()
        launch(Screen.Compact) {
            ReviewSheetContent(ChatScenes.flow(ReviewStep.Ready), actions, {})
        }.use {
            compose.onNodeWithTag("review-confirm").assertIsEnabled().assertHeightIsAtLeast(48.dp).assert(hasText("Publish website"))
            compose.onNodeWithTag("review-inspect").assert(hasText("Open preview"))
            compose.onNodeWithTag("review-dismiss").assert(hasText("Not now"))
            compose.onNodeWithTag("review-lines").assertExists()
            compose.onNodeWithTag("review-confirm").performClick()
            compose.onNodeWithTag("review-dismiss").performClick()
        }
        assertEquals(1, actions.confirms)
        assertEquals(1, actions.dismisses)
    }

    @Test
    fun aStaleReviewHasNoEnabledConfirm() {
        launch(Screen.Compact) {
            ReviewSheetContent(ChatScenes.flow(ReviewStep.NoLongerCurrent, "This review is no longer current."), Recorder(), {})
        }.use {
            compose.onNodeWithTag("review-confirm").assertIsNotEnabled()
            compose.onNodeWithTag("review-outcome").assert(polite())
        }
    }

    @Test
    fun aRunningConfirmIgnoresTaps() {
        launch(Screen.Compact) {
            ReviewSheetContent(ChatScenes.flow(ReviewStep.Confirming), Recorder(), {})
        }.use {
            compose.onNodeWithTag("review-confirm").assertIsNotEnabled().assert(hasText("Confirming..."))
        }
    }
}
