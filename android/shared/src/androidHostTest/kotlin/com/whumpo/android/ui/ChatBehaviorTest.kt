package com.whumpo.android.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import com.whumpo.android.ui.chat.ChatActions
import com.whumpo.android.ui.chat.forms.FormCardSummary
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
        var transcriptRetries = 0
        val reviewChecks = mutableListOf<String>()
        var resends = 0
        val forms = mutableListOf<String>()

        override fun onOpenForm(interactionId: String) {
            forms += interactionId
        }

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

        override fun onRetry() {
            transcriptRetries += 1
        }

        override fun onCheckReview(requestId: String) {
            reviewChecks += requestId
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
    fun anInteractionCardOpensTheNativeFormOnlyOnTap() {
        val recorder = Recorder()
        val turns = ChatScenes.turns.take(1)
        launch(Screen.Compact) {
            // Only the first turn, so the list is not scrolled past the card.
            ChatLayout(ChatScenes.screen(ChatScenes.ready.copy(turns = turns)), WidthClass.Compact, recorder, animated = false)
        }.use {
            compose.onNodeWithTag("interaction-open").assertContentDescriptionEquals("Whumpo has a form for you. Sent. View form.")
            kotlin.test.assertEquals(emptyList(), recorder.forms)
            compose.onNodeWithTag("interaction-open").assertHeightIsAtLeast(48.dp).performClick()
            kotlin.test.assertEquals(listOfNotNull(turns.single().interaction?.interactionId), recorder.forms)
        }
    }

    @Test
    fun aKnownFormCardDescribesItsTitleDraftAndActionAsOneNode() {
        val recorder = Recorder()
        val turn = ChatScenes.turns.first()
        val interaction = checkNotNull(turn.interaction)
        val state = ChatScenes.screen(ChatScenes.ready.copy(turns = listOf(turn))).copy(
            formCards = mapOf(interaction.interactionId to FormCardSummary("Project brief", "open", true)),
        )
        launch(Screen.Compact) { ChatLayout(state, WidthClass.Compact, recorder, animated = false) }.use {
            compose.onNodeWithTag("interaction-open").assertContentDescriptionEquals("Project brief. Draft saved. Continue form.").performClick()
        }
        assertEquals(listOf(interaction.interactionId), recorder.forms)
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
    fun pendingAndUncertainSheetsExposeAnAccessibleReadOnlyCheckInsteadOfConfirm() {
        for (step in listOf(ReviewStep.Pending, ReviewStep.Uncertain)) {
            val actions = Recorder()
            val flow = ChatScenes.flow(step, "The outcome is still unknown.").copy(retryAllowed = false)
            launch(Screen.Compact) {
                ReviewSheetContent(flow, actions, {})
            }.use {
                compose.onNodeWithTag("review-check").assertIsEnabled().assertHeightIsAtLeast(48.dp).assert(hasText("Check again")).performClick()
                compose.onNodeWithTag("review-confirm").assertDoesNotExist()
                compose.onNodeWithTag("review-outcome").assert(polite())
            }
            assertEquals(listOf(flow.requestId), actions.reviewChecks)
            assertEquals(0, actions.confirms)
        }
    }

    @Test
    fun anAppliedReviewWithARetainedRequestStillOffersItsReadOnlyReopenAction() {
        val actions = Recorder()
        val flow = ChatScenes.flow(ReviewStep.Uncertain).copy(retryAllowed = false)
        val turn = ChatScenes.siteTurn
        val applied = turn.copy(review = turn.review!!.copy(state = "applied", canApprove = false, control = null))
        launch(Screen.Compact) {
            ChatLayout(ChatScenes.screen(ChatScenes.ready.copy(turns = listOf(applied), reviewChecks = listOf(flow))), WidthClass.Compact, actions, animated = false)
        }.use {
            compose.onNodeWithTag("review-check-open").assertIsEnabled().assertHeightIsAtLeast(48.dp).assert(hasText("Check again")).performClick()
            compose.onNodeWithTag("review-open").assertDoesNotExist()
        }
        assertEquals(turn.id, actions.opened)
        assertEquals(0, actions.confirms)
    }

    @Test
    fun aRetainedCheckRemainsReachableWithoutCurrentReviewMetadataOrAVisibleTurn() {
        for (turns in listOf(listOf(ChatScenes.siteTurn.copy(review = null)), emptyList())) {
            val actions = Recorder()
            val flow = ChatScenes.flow(ReviewStep.Uncertain).copy(retryAllowed = false)
            launch(Screen.Compact) {
                ChatLayout(ChatScenes.screen(ChatScenes.ready.copy(turns = turns, reviewChecks = listOf(flow))), WidthClass.Compact, actions, animated = false)
            }.use {
                compose.onNodeWithTag("review-check-open").assertIsEnabled().assertHeightIsAtLeast(48.dp).assert(hasText("Check again")).performClick()
                compose.onNodeWithTag("review-open").assertDoesNotExist()
            }
            assertEquals(flow.turnId, actions.opened)
            assertEquals(0, actions.confirms)
        }
    }

    @Test
    fun anOffPageRetryableFailureReopensWithoutSendingThenOffersExplicitTryAgain() {
        val actions = Recorder()
        val flow = ChatScenes.flow(ReviewStep.Failed, "The request was not found. You can try again with the same request.")
        launch(Screen.Compact) {
            ChatLayout(ChatScenes.screen(ChatScenes.ready.copy(turns = emptyList(), reviewChecks = listOf(flow))), WidthClass.Compact, actions, animated = false)
        }.use {
            compose.onNodeWithTag("review-open").assertIsEnabled().assertHeightIsAtLeast(48.dp).assert(hasText("Review change")).performClick()
            compose.onNodeWithTag("review-check-open").assertDoesNotExist()
        }
        assertEquals(flow.turnId, actions.opened)
        assertEquals(0, actions.confirms)
        assertTrue(actions.reviewChecks.isEmpty())
        launch(Screen.Compact) {
            ReviewSheetContent(flow, actions, {})
        }.use {
            compose.onNodeWithTag("review-confirm").assertIsEnabled().assertHeightIsAtLeast(48.dp).assert(hasText("Try again")).performClick()
            compose.onNodeWithTag("review-check").assertDoesNotExist()
        }
        assertEquals(1, actions.confirms)
        assertTrue(actions.reviewChecks.isEmpty())
    }

    @Test
    fun transcriptFailuresAndRefreshesKeepRetainedReopenActionsAccessibleWithoutEnablingSend() {
        val failure = Phase.Failed("Couldn't load your conversation.", "Check the confirmation result before trying again.")
        for (phase in listOf(failure, Phase.Loading)) {
            for (step in listOf(ReviewStep.Pending, ReviewStep.Uncertain, ReviewStep.Failed)) {
                val actions = Recorder()
                val flow = ChatScenes.flow(step).copy(retryAllowed = step == ReviewStep.Failed)
                val notice = "Whumpo could not take that message. Nothing was sent."
                val chat = ChatScenes.ready.copy(phase = phase, turns = emptyList(), reviewChecks = listOf(flow), notice = notice)
                launch(Screen.Compact) {
                    ChatLayout(ChatScenes.screen(chat, draft = "Saved draft"), WidthClass.Compact, actions, animated = false)
                }.use {
                    val tag = if (step == ReviewStep.Failed) "review-open" else "review-check-open"
                    val label = if (step == ReviewStep.Failed) "Review change" else "Check again"
                    compose.onNodeWithTag("chat-notice").assertIsDisplayed().assert(polite()).assert(hasText(notice))
                    if (phase is Phase.Failed) {
                        compose.onNodeWithTag("state-error").assertIsDisplayed()
                        compose.onNode(hasText("Try again")).assertIsDisplayed()
                    }
                    compose.onNodeWithTag("chat-transcript").performScrollToNode(hasTestTag(tag))
                    compose.onNodeWithTag(tag).assertIsEnabled().assertHeightIsAtLeast(48.dp).assert(hasText(label)).performClick()
                    compose.onNodeWithTag("chat-send").assertIsNotEnabled()
                    if (phase is Phase.Failed) {
                        compose.onNodeWithTag("chat-transcript").performScrollToNode(hasTestTag("state-error"))
                        compose.onNodeWithTag("state-error").assert(polite())
                        compose.onNode(hasText(failure.what)).assertExists()
                        compose.onNode(hasText(failure.safe)).assertExists()
                        compose.onNode(hasText("Try again")).performClick()
                        compose.onNodeWithTag("chat-notice").assertIsDisplayed().assert(hasText(notice))
                    } else {
                        compose.onNodeWithTag("state-error").assertDoesNotExist()
                    }
                }
                assertEquals(flow.turnId, actions.opened)
                assertEquals(0, actions.confirms)
                assertEquals(0, actions.sends)
                assertEquals(if (phase is Phase.Failed) 1 else 0, actions.transcriptRetries)
            }
        }
    }

    @Test
    fun aSameCountTranscriptFailureShowsRetryAndPreservesManualAccessToRetainedChecks() {
        val actions = Recorder()
        val checks = (1..4).map { index ->
            val flow = ChatScenes.flow(ReviewStep.Uncertain).copy(turnId = "retained-$index", requestId = "request-$index", retryAllowed = false)
            flow.copy(review = flow.review.copy(disclosure = flow.review.disclosure.copy(title = "Retained confirmation $index")))
        }
        val state = mutableStateOf(ChatScenes.ready.copy(turns = emptyList(), reviewChecks = checks))
        launch(Screen.Compact) {
            ChatLayout(ChatScenes.screen(state.value, draft = "Saved draft"), WidthClass.Compact, actions, animated = false)
        }.use {
            val failure = Phase.Failed("Couldn't load your conversation.", "Check the confirmation result before trying again.")
            // Four retained rows become one error plus three retained rows: the item count does not change.
            compose.runOnIdle { state.value = state.value.copy(phase = failure, transcriptFailure = failure, reviewChecks = checks.take(3)) }
            compose.onNodeWithTag("state-error").assertIsDisplayed()
            compose.onNode(hasText("Try again")).assertIsDisplayed().performClick()
            assertEquals(1, actions.transcriptRetries)
            compose.onNodeWithTag("chat-send").assertIsNotEnabled()
            compose.onNodeWithTag("chat-transcript").performScrollToNode(hasText("Retained confirmation 3"))
            compose.onNode(hasText("Retained confirmation 3")).assertIsDisplayed()
            // A background list change during the same outage must not pull the owner back to the error.
            compose.runOnIdle { state.value = state.value.copy(reviewChecks = checks) }
            compose.onNode(hasText("Retained confirmation 3")).assertIsDisplayed()
            val newFailure = failure.copy(what = "Couldn't reach Whumpo.")
            compose.runOnIdle { state.value = state.value.copy(phase = newFailure, transcriptFailure = newFailure) }
            compose.onNodeWithTag("state-error").assertIsDisplayed()
            compose.onNode(hasText("Try again")).assertIsDisplayed()
        }
        assertEquals(0, actions.sends)
        assertEquals(0, actions.confirms)
        assertTrue(actions.reviewChecks.isEmpty())
    }

    @Test
    fun aCachedTranscriptReadFailureIsSeparateFromTheSendNoticeDuringRetry() {
        val actions = Recorder()
        val failure = Phase.Failed("Whumpo is temporarily unavailable.", "Your unsent message is kept on this device.")
        val state = mutableStateOf(ChatScenes.ready.copy(transcriptFailure = failure, notice = "Whumpo could not take that message. Nothing was sent."))
        launch(Screen.Compact) {
            ChatLayout(ChatScenes.screen(state.value), WidthClass.Compact, actions, animated = false)
        }.use {
            compose.onNodeWithTag("state-error").assertIsDisplayed()
            compose.onNodeWithTag("chat-notice").assertIsDisplayed().assert(hasText(state.value.notice!!))
            compose.onNode(hasText("Try again")).assertIsDisplayed().performClick()
            compose.runOnIdle { state.value = state.value.copy(transcriptFailure = null) }
            compose.onNodeWithTag("state-error").assertDoesNotExist()
            compose.onNodeWithTag("chat-notice").assertIsDisplayed().assert(hasText(state.value.notice!!))
        }
        assertEquals(1, actions.transcriptRetries)
        assertEquals(0, actions.sends)
    }

    @Test
    fun aFailedTranscriptWithoutRetainedActionsKeepsItsExistingErrorCard() {
        val failure = Phase.Failed("Couldn't load your conversation.", "Nothing was changed.")
        launch(Screen.Compact) {
            ChatLayout(ChatScenes.screen(ChatScenes.ready.copy(phase = failure, turns = emptyList()), draft = "Saved draft"), WidthClass.Compact, Recorder(), animated = false)
        }.use {
            compose.onNodeWithTag("state-error").assertExists()
            compose.onNodeWithTag("chat-transcript").assertDoesNotExist()
            compose.onNodeWithTag("review-check-open").assertDoesNotExist()
            compose.onNodeWithTag("review-open").assertDoesNotExist()
            compose.onNodeWithTag("chat-send").assertIsNotEnabled()
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
