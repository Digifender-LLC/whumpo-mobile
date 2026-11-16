package com.whumpo.android.ui

import android.graphics.Insets
import android.view.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.whumpo.android.ui.onboarding.BusinessDiscoveryContent
import com.whumpo.android.ui.theme.WhumpoEntryTheme
import com.whumpo.core.api.CoreDiscoveryActionId
import com.whumpo.core.api.CoreDiscoveryCommand
import com.whumpo.core.api.CoreDiscoveryEdit
import com.whumpo.core.api.CoreDiscoveryKey
import com.whumpo.core.api.CoreDiscoveryPlan
import com.whumpo.core.api.CoreDiscoveryPlanStatus
import com.whumpo.core.api.CoreDiscoveryProposal
import com.whumpo.core.api.CoreDiscoverySavedAnswer
import com.whumpo.core.api.CoreDiscoverySnapshot
import com.whumpo.core.api.CoreDiscoveryValue
import com.whumpo.core.auth.CoreConnectedDiscoveryState
import com.whumpo.core.auth.CoreDiscoveryNotice
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
class BusinessDiscoveryContentTest : RobolectricUiTest() {
    @Test
    fun darkConversationAtDoubleFontKeepsAnImeSafeSaveAndAnOptionalExit() {
        val commands = mutableListOf<CoreDiscoveryCommand>()
        var left = 0
        var panel: Color? = null
        launch(Screen.Compact, dark = true, fontScale = 2f) {
            WhumpoEntryTheme(darkTheme = true) {
                val color = MaterialTheme.colorScheme.background
                SideEffect { panel = color }
                Surface { screen(snapshot(CoreDiscoveryKey.Offer), false, commands::add, { left++ }) }
            }
        }.use { activity ->
            assertEquals(Color(0xFF1C1A17), panel)
            val editor = compose.onNodeWithTag("discovery-editor")
            editor.performTextInput("Cabinet restoration")
            editor.assertIsFocused()
            val windowBottom = compose.onRoot().fetchSemanticsNode().boundsInRoot.bottom
            activity.onActivity {
                WindowCompat.setDecorFitsSystemWindows(it.window, false)
                it.window.decorView.dispatchApplyWindowInsets(WindowInsets.Builder()
                    .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, 440))
                    .setVisible(WindowInsets.Type.ime(), true).build())
            }
            compose.waitForIdle()
            val save = compose.onNodeWithText("Save answer").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            assertTrue(save.fetchSemanticsNode().boundsInRoot.bottom <= windowBottom - 439)
            editor.performImeAction()
            compose.onNodeWithText("Continue later").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
            assertEquals(listOf(CoreDiscoveryCommand.Answer(CoreDiscoveryKey.Offer,
                CoreDiscoveryValue.Declared("Cabinet restoration"))), commands)
            assertEquals(1, left)
        }
    }

    @Test
    fun lightThemeDisplaysServerReviewAndSelectionOnlyUpdatesDraft() {
        val commands = mutableListOf<CoreDiscoveryCommand>()
        var accent: Color? = null
        val proposal = CoreDiscoveryProposal(CoreDiscoveryActionId.Goal, "Review the goal", "Start with your stated goal",
            emptyList(), emptyList(), deferred = false, titleOverride = null)
        val plan = CoreDiscoveryPlan(4, 1, "Your next step is ready for review.",
            listOf(proposal), selectedNext = null, recommendedNext = proposal.id)
        launch(Screen.Medium, fontScale = 2f) {
            WhumpoEntryTheme(darkTheme = false) {
                val color = MaterialTheme.colorScheme.primary
                SideEffect { accent = color }
                Surface { screen(snapshot(CoreDiscoveryKey.Stage, plan = plan), true, commands::add) }
            }
        }.use {
            assertEquals(Color(0xFF68470C), accent)
            compose.onNodeWithText(plan.summary).assertIsDisplayed()
            compose.onNodeWithText("Select as next step").performScrollTo().assertIsDisplayed().performClick()
            assertEquals(listOf(CoreDiscoveryCommand.EditPlan(4, CoreDiscoveryEdit.Select(proposal.id))), commands)
            compose.onNodeWithText("Continue later").assertIsDisplayed()
        }
    }

    @Test
    fun stalePlanCanRefreshAndAReadyOwnerCanResumeCountryQuestions() {
        val commands = mutableListOf<CoreDiscoveryCommand>()
        launch(Screen.Medium) { screen(snapshot(CoreDiscoveryKey.Country, stale = true), true, commands::add) }.use {
            compose.onNodeWithText("Refresh starter plan").performClick()
            compose.onNodeWithText("Rename step").assertDoesNotExist()
            compose.onNodeWithText("Continue questions").performClick()
            compose.onNodeWithTag("discovery-country").performTextInput("TW")
            compose.onNodeWithText("(TW)", substring = true).performClick()
            assertEquals(listOf(CoreDiscoveryCommand.RefreshPlan,
                CoreDiscoveryCommand.Answer(CoreDiscoveryKey.Country, CoreDiscoveryValue.Declared("TW"))), commands)
        }
    }

    @Test
    fun deniedReadRemovesSavedAnswersFromQuestionAndReviewViews() {
        val saved = "Synthetic private summary"
        val original = snapshot(CoreDiscoveryKey.Offer).copy(
            activeQuestions = listOf(CoreDiscoveryKey.Offer, CoreDiscoveryKey.Region),
            answers = mapOf(CoreDiscoveryKey.Offer to CoreDiscoverySavedAnswer(CoreDiscoveryValue.Declared(saved), 1)),
        )
        for (review in listOf(false, true)) {
            var state by mutableStateOf(CoreConnectedDiscoveryState(snapshot = original))
            launch(Screen.Medium) {
                BusinessDiscoveryContent(state, review, onCommand = {}, onRefresh = {}, onRetry = {},
                    onSignIn = {}, onLater = {}, modifier = Modifier.fillMaxSize())
            }.use {
                val key = if (review) "review-offer" else "answered-offer"
                compose.onNodeWithTag("discovery-chat").performScrollToKey(key)
                compose.onNodeWithText(saved).assertIsDisplayed()
                compose.runOnIdle {
                    state = state.copy(snapshot = null, notice = CoreDiscoveryNotice.PolicyUnavailable, requiresRefresh = true)
                }
                compose.onNodeWithText(saved).assertDoesNotExist()
                compose.onNodeWithTag("discovery-editor").assertDoesNotExist()
                compose.onNodeWithText("Continue later").assertIsDisplayed()
            }
        }
    }

    private fun snapshot(question: CoreDiscoveryKey, plan: CoreDiscoveryPlan? = null, stale: Boolean = false) =
        CoreDiscoverySnapshot("example-org", plan?.revision ?: 1, 1, emptyMap(), listOf(question),
            when {
                stale -> CoreDiscoveryPlanStatus.Stale
                plan != null -> CoreDiscoveryPlanStatus.Current
                else -> CoreDiscoveryPlanStatus.Missing
            }, plan)

    @Composable
    private fun screen(
        snapshot: CoreDiscoverySnapshot,
        review: Boolean,
        onCommand: (CoreDiscoveryCommand) -> Unit,
        onLater: () -> Unit = {},
    ) = BusinessDiscoveryContent(CoreConnectedDiscoveryState(snapshot = snapshot), review, onCommand,
        onRefresh = {}, onRetry = {}, onSignIn = {}, onLater = onLater, modifier = Modifier.fillMaxSize())
}
