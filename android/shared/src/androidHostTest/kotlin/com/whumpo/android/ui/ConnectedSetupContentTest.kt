package com.whumpo.android.ui

import android.graphics.Insets
import android.view.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.whumpo.android.ui.onboarding.ConnectedSetupContent
import com.whumpo.core.api.CoreOnboardingProfile
import com.whumpo.core.api.CoreOnboardingProfileStatus
import com.whumpo.core.api.CoreOnboardingStage
import com.whumpo.core.api.CoreOrganizationReadiness
import com.whumpo.core.auth.CoreConnectedSetupState
import com.whumpo.core.auth.CoreSetupQuestion
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
class ConnectedSetupContentTest : RobolectricUiTest() {
    private val readiness = CoreOrganizationReadiness("organization", false)
    private val profile = CoreOnboardingProfile(1, CoreOnboardingProfileStatus.draft, null, null, "Workshop", null, null)

    @Test
    fun retainsVisibleComposerAtDoubleFontWithImeAndCreationRemainsExplicit() {
        var state by mutableStateOf(CoreConnectedSetupState(
            readiness = readiness, profile = profile, stage = CoreOnboardingStage.verified, question = CoreSetupQuestion.Owner,
        ))
        var submitted: Pair<CoreSetupQuestion, String?>? = null
        var creates = 0
        launch(Screen.Compact, dark = true, fontScale = 2f) {
            content(state, owner = true, onAnswer = { question, value -> submitted = question to value }, onCreate = { creates++ })
        }.use { activity ->
            val text = compose.onNodeWithText("Owner name")
            val save = compose.onNodeWithText("Save answer")
            text.performTextInput("Leslie")
            save.assertHeightIsAtLeast(48.dp)
            val before = save.fetchSemanticsNode().boundsInRoot.bottom
            activity.onActivity {
                WindowCompat.setDecorFitsSystemWindows(it.window, false)
                it.window.decorView.dispatchApplyWindowInsets(WindowInsets.Builder()
                    .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, 440))
                    .setVisible(WindowInsets.Type.ime(), true).build())
            }
            compose.waitForIdle()
            assertTrue(before - save.fetchSemanticsNode().boundsInRoot.bottom >= 439)
            save.assertIsDisplayed().performClick()
            assertEquals(CoreSetupQuestion.Owner to "Leslie", submitted)
            assertEquals(0, creates)
            compose.runOnIdle {
                state = state.copy(profile = profile.copy(ownerName = "Leslie"), question = null)
            }
            compose.onNodeWithText("Create workspace").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
            assertEquals(1, creates)
        }
    }

    @Test
    fun waitingMemberCanRefreshAndNeverSeesOwnerControls() {
        var refreshes = 0
        launch(Screen.Medium) {
            content(CoreConnectedSetupState(readiness = readiness), owner = false, onRefresh = { refreshes++ })
        }.use {
            compose.onNodeWithText("Your workspace is waiting for an organization owner", substring = true).assertIsDisplayed()
            compose.onNodeWithText("Organization actions").assertIsDisplayed()
            compose.onNodeWithText("Create workspace").assertDoesNotExist()
            compose.onNodeWithText("Save answer").assertDoesNotExist()
            compose.onNodeWithText("Refresh workspace status").performClick()
            assertEquals(1, refreshes)
        }
    }

    @Composable
    private fun content(
        state: CoreConnectedSetupState,
        owner: Boolean,
        onAnswer: (CoreSetupQuestion, String?) -> Unit = { _, _ -> },
        onCreate: () -> Unit = {},
        onRefresh: () -> Unit = {},
    ) = ConnectedSetupContent(
        state = state,
        isOwner = owner,
        onAnswer = onAnswer,
        onEdit = {},
        onSkipRemaining = {},
        onCreate = onCreate,
        onRetry = {},
        onRefresh = onRefresh,
        onSignIn = {},
        organizationActions = { Text("Organization actions") },
        modifier = Modifier.fillMaxSize(),
    )
}
