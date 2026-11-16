package com.whumpo.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.whumpo.android.ui.onboarding.DiscoveryHandoffContent
import com.whumpo.android.ui.onboarding.BusinessDiscoveryContent
import com.whumpo.android.ui.theme.WhumpoEntryTheme
import com.whumpo.core.api.CoreDiscoveryActionId
import com.whumpo.core.api.CoreDiscoveryHandoff
import com.whumpo.core.api.CoreDiscoveryPlan
import com.whumpo.core.api.CoreDiscoveryPlanStatus
import com.whumpo.core.api.CoreDiscoveryProposal
import com.whumpo.core.api.CoreDiscoverySnapshot
import com.whumpo.core.api.CoreHandoffDependency
import com.whumpo.core.api.CoreHandoffLineage
import com.whumpo.core.api.CoreHandoffState
import com.whumpo.core.api.CoreHandoffTask
import com.whumpo.core.api.CoreTaskStatus
import com.whumpo.core.api.CoreWorkTask
import com.whumpo.core.auth.DiscoveryHandoffNotice
import com.whumpo.core.auth.CoreConnectedDiscoveryState
import com.whumpo.core.auth.DiscoveryHandoffReview
import com.whumpo.core.auth.DiscoveryHandoffState
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DiscoveryHandoffContentTest : RobolectricUiTest() {
    @Test
    fun suggestedNextStepRequiresAnExplicitWorkReviewTap() {
        val proposal = CoreDiscoveryProposal(CoreDiscoveryActionId.Goal, "First goal", "Review a step",
            emptyList(), emptyList(), deferred = false, titleOverride = null)
        val plan = CoreDiscoveryPlan(4, 3, "Review the plan", listOf(proposal), null, proposal.id)
        val discovery = CoreDiscoverySnapshot("org-demo", 4, 3, emptyMap(), emptyList(), CoreDiscoveryPlanStatus.Current, plan)
        var reviewed: Pair<CoreDiscoveryActionId, Long>? = null
        launch(Screen.Medium) {
            BusinessDiscoveryContent(CoreConnectedDiscoveryState(snapshot = discovery), true,
                onCommand = {}, onRefresh = {}, onRetry = {}, onSignIn = {}, onLater = {},
                reviewForWork = { action, revision -> reviewed = action to revision })
        }.use {
            compose.onNodeWithText("Suggested next").assertIsDisplayed()
            assertEquals(null, reviewed)
            compose.onNodeWithText("Review First goal for Work").performScrollTo().performClick()
            assertEquals(proposal.id to 4L, reviewed)
        }
    }

    @Test
    fun largeTextReviewRequiresPrerequisiteBeforeAnExplicitRequest() {
        var request: DiscoveryHandoffReview? = null
        val action = CoreDiscoveryActionId.Migration
        var state by mutableStateOf(DiscoveryHandoffState(review = review(listOf(action))))
        launch(Screen.Compact, dark = true, fontScale = 2f) {
            WhumpoEntryTheme(darkTheme = true) { content(state, onSubmit = { request = it }) }
        }.use {
            compose.onNodeWithText("Workspace project: project-demo").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Required in this project: Website migration").performScrollTo().assertIsDisplayed()
            val submit = compose.onNodeWithText("Request one Work item")
            submit.performScrollTo().assertIsNotEnabled().assertHeightIsAtLeast(48.dp)
            assertEquals(null, request)
            val ready = review(emptyList()).copy(dependencies = listOf(CoreHandoffDependency(action, "task-migration")))
            compose.runOnIdle { state = state.copy(review = ready) }
            submit.performScrollTo().assertIsDisplayed().performClick()
            assertEquals(ready, request)
        }
    }

    @Test
    fun queuedAndUnverifiedConfirmationHaveNoWorkLink() {
        var linked: CoreWorkTask? = null
        val queued = CoreDiscoveryHandoff("org-demo", "effect-demo", "project-demo", CoreDiscoveryActionId.Goal,
            4, 3, 4, emptyList(), CoreHandoffState.Queued, null, 2, "a".repeat(64), null,
            CoreHandoffLineage.Current, null)
        var state by mutableStateOf(DiscoveryHandoffState(detail = queued))
        launch(Screen.Compact, fontScale = 2f) {
            WhumpoEntryTheme(darkTheme = false) { content(state, onOpen = { linked = it }) }
        }.use {
            compose.onNodeWithText("Queued").assertIsDisplayed()
            compose.onNodeWithText("Open verified Work item").assertDoesNotExist()
            compose.runOnIdle {
                state = state.copy(detail = queued.copy(state = CoreHandoffState.Confirmed, effectVersion = 3,
                    task = CoreHandoffTask("task-demo", "receipt-demo", 1)))
            }
            compose.onNodeWithText("Handoff confirmed").assertIsDisplayed()
            compose.onNodeWithText("Open verified Work item").assertDoesNotExist()
            val task = CoreWorkTask("task-demo", "project-demo", "First goal", null, null, null, CoreTaskStatus.Backlog, 1)
            compose.runOnIdle { state = state.copy(verifiedTask = task) }
            compose.onNodeWithText("Open verified Work item").performScrollTo().performClick()
            assertEquals(task, linked)
            compose.runOnIdle { state = DiscoveryHandoffState(pending = true, notice = DiscoveryHandoffNotice.Denied) }
            compose.onNodeWithText("Open verified Work item").assertDoesNotExist()
            compose.onNodeWithText("First goal").assertDoesNotExist()
            compose.onNodeWithText("Retry original request").assertIsDisplayed()
        }
    }

    private fun review(missing: List<CoreDiscoveryActionId>) = DiscoveryHandoffReview(
        CoreDiscoveryActionId.Goal, "First goal", "Review your first step", 4, 3, 4,
        "project-demo", null, listOf(CoreDiscoveryActionId.Migration), missing, emptyList(), null,
    )

    @Composable
    private fun content(
        state: DiscoveryHandoffState,
        onSubmit: (DiscoveryHandoffReview) -> Unit = {},
        onOpen: (CoreWorkTask) -> Unit = {},
    ) = DiscoveryHandoffContent(state, onSubmit, onChooseWorkspace = {}, onMoreWorkspaces = {},
        onChooseProject = {}, onMoreProjects = {}, onInspect = {}, onReviewApproval = {}, onDecision = { _, _ -> },
        onRetry = {}, onOpenWork = onOpen, onSignIn = {}, onBack = {})
}
