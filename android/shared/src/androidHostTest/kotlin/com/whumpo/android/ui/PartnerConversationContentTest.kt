package com.whumpo.android.ui

import android.graphics.Insets
import android.view.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.whumpo.android.ui.partner.PartnerConversationContent
import com.whumpo.core.api.PartnerAgentKind
import com.whumpo.core.api.PartnerMessage
import com.whumpo.core.api.PartnerMessageRole
import com.whumpo.core.api.PartnerRetention
import com.whumpo.core.api.PartnerTopic
import com.whumpo.core.api.PartnerTopicKind
import com.whumpo.core.api.PartnerTopicState
import com.whumpo.core.auth.PartnerNotice
import com.whumpo.core.auth.PartnerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PartnerConversationContentTest : RobolectricUiTest() {
    private val timestamp = "2027-01-02T03:04:05.000Z"
    private val topic = PartnerTopic(
        "conversation", "agent", PartnerAgentKind.partner, PartnerTopicKind.temporary, "Planning", false, 1,
        PartnerTopicState.open, PartnerRetention.retained, timestamp, timestamp,
    )

    @Test
    fun composerIsAccessibleAtDoubleTextAndMovesAboveImeInsets() {
        var state by mutableStateOf(PartnerState(topic = topic))
        var saves = 0
        launch(Screen.Compact, dark = true, fontScale = 2f) {
            conversation(state, onDraft = { state = state.copy(messageDraft = it) }, onSave = { saves++ })
        }.use { activity ->
            compose.onNodeWithText("Planning").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            val save = compose.onNodeWithText("Save message")
            save.assertHeightIsAtLeast(48.dp).assertIsNotEnabled()
            val field = compose.onNodeWithText("Message")
            field.performTextInput("Save just this message")
            save.assertIsEnabled().assertIsDisplayed()
            val before = save.fetchSemanticsNode().boundsInRoot.bottom
            val keyboardPixels = 440
            activity.onActivity {
                WindowCompat.setDecorFitsSystemWindows(it.window, false)
                val insets = WindowInsets.Builder()
                    .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, keyboardPixels))
                    .setVisible(WindowInsets.Type.ime(), true)
                    .build()
                it.window.decorView.dispatchApplyWindowInsets(insets)
            }
            compose.waitForIdle()
            val after = save.fetchSemanticsNode().boundsInRoot.bottom
            assertTrue("Save control moved by ${before - after}px for ${keyboardPixels}px IME", before - after >= keyboardPixels - 1)
            save.assertIsDisplayed().performClick()
            assertEquals(1, saves)
        }
    }

    @Test
    fun closedHistoryKeepsAcknowledgmentActionAndDisablesUnacknowledgedReplay() {
        val retained = topic.copy(state = PartnerTopicState.closed, closedAt = timestamp)
        val item = PartnerMessage("item", "actor", 1, PartnerMessageRole.user, "Retained message", timestamp)
        var state by mutableStateOf(PartnerState(topic = retained, messages = listOf(item),
            savePending = true, saveAcknowledged = true, notice = PartnerNotice.SaveAwaitingHistory))
        var checks = 0
        launch(Screen.Compact, dark = true, fontScale = 2f) {
            conversation(state, onSave = { checks++ })
        }.use {
            compose.onNodeWithText("Closed conversation", substring = true).assertIsDisplayed()
            compose.onNodeWithText("The service acknowledged the message", substring = true).assert(
                SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite),
            )
            compose.onNodeWithText("Message").assertDoesNotExist()
            compose.onNodeWithText("Request task run").assertDoesNotExist()
            compose.onNodeWithText("Check saved message").assertHeightIsAtLeast(48.dp).performClick()
            assertEquals(1, checks)
            compose.runOnIdle {
                state = state.copy(saveAcknowledged = false, notice = PartnerNotice.ClosedSaveUnresolved)
            }
            compose.onNodeWithText("Retry exact save").assertIsNotEnabled()
        }
    }

    @Test
    fun requestRunIsSeparateAndOnlyShownForOwnCanonicalMessage() {
        val own = PartnerMessage("own", "actor", 1, PartnerMessageRole.user, "My saved message", timestamp)
        val other = own.copy(id = "other", userId = "other-actor", sequence = 2, content = "Other message")
        var state by mutableStateOf(PartnerState(topic = topic, messages = listOf(own, other),
            notice = PartnerNotice.RunDisabled, messageDraft = "Draft"))
        var saves = 0
        var requested: PartnerMessage? = null
        launch(Screen.Medium, dark = true) {
            conversation(state, onSave = { saves++ }, onRequestRun = { requested = it })
        }.use {
            compose.onNodeWithText("Task execution is disabled", substring = true).assertIsDisplayed()
            compose.onNodeWithText("Save message").performClick()
            assertEquals(1, saves)
            assertEquals(null, requested)
            compose.onNodeWithText("Request task run").performClick()
            assertEquals(own, requested)
            assertEquals(1, saves)
            compose.runOnIdle { state = state.copy(runPending = true) }
            compose.onNodeWithText("Request task run").assertDoesNotExist()
            compose.onNodeWithText("Retry exact run request").assertIsEnabled()
        }
    }

    @Test
    fun routingOnlyComposerRequiresProvenReadiness() {
        var state by mutableStateOf(PartnerState())
        var saves = 0
        launch(Screen.Compact, dark = false, fontScale = 1f) {
            conversation(state, onDraft = { state = state.copy(messageDraft = it) }, onSave = { saves++ })
        }.use { activity ->
            compose.onNodeWithText("Message").assertDoesNotExist()
            activity.onActivity { state = state.copy(routingOnlyReady = true) }
            compose.onNodeWithText("Message").performTextInput("A primary conversation message")
            compose.onNodeWithText("Save message").assertIsEnabled().performClick()
            assertEquals(1, saves)
            activity.onActivity { state = state.copy(routingOnlyReady = false, messageDraft = "") }
            compose.onNodeWithText("Message").assertDoesNotExist()
        }
    }

    @androidx.compose.runtime.Composable
    private fun conversation(
        state: PartnerState,
        onDraft: (String) -> Unit = {},
        onSave: () -> Unit = {},
        onRequestRun: (PartnerMessage) -> Unit = {},
    ) = PartnerConversationContent(
        organization = "Example organization",
        title = "Planning",
        state = state,
        canWrite = true,
        userId = "actor",
        onBack = {},
        onRefresh = {},
        onMore = {},
        onDraft = onDraft,
        onSave = onSave,
        onRequestRun = onRequestRun,
        onRetryRun = {},
        onOpenRun = {},
        onRefreshRun = {},
        onReview = {},
        onVerify = {},
        modifier = Modifier.fillMaxSize(),
    )
}
