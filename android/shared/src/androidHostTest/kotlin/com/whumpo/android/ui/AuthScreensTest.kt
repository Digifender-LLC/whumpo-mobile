package com.whumpo.android.ui

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.whumpo.android.ui.auth.AuthFlow
import com.whumpo.core.auth.AuthActions
import com.whumpo.core.auth.AuthProblem
import com.whumpo.core.auth.AuthState
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Records which action a screen asked for. */
internal class RecordingActions : AuthActions {
    val calls = mutableListOf<String>()
    override fun signInWithPasskey() { calls += "passkey" }
    override fun signInWithBrowser() { calls += "browser" }
    override fun startInvite() { calls += "invite" }
    override fun cancelHandoff() { calls += "cancel" }
    override fun openSetup() { calls += "setup" }
    override fun checkSetupAgain() { calls += "check" }
    override fun signOut() { calls += "signOut" }
    override fun openHelp() { calls += "help" }
    override fun openStore() { calls += "store" }
}

/** Behaviour and TalkBack semantics of the sign-in screens. Real passkeys are not exercised here (or anywhere in CI). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class AuthScreensTest : RobolectricUiTest() {
    private val polite = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)

    private fun show(state: AuthState, actions: RecordingActions = RecordingActions(), screen: Screen = Screen.Compact, font: Float = 1f) =
        launch(screen, fontScale = font) { AuthFlow(state, actions) }

    private fun exists(tag: String) = compose.onNodeWithTag(tag).assertExists()
    private fun absent(tag: String) = compose.onNodeWithTag(tag).assertDoesNotExist()

    @Test
    fun welcomeHasOneHeadingOnePrimaryAndQuietLinks() {
        val actions = RecordingActions()
        show(AuthState.SignedOut(), actions).use {
            compose.onNode(isHeading() and hasText("Welcome back to Whumpo.")).assertIsDisplayed()
            compose.onNodeWithTag("auth-sign-in").assertHeightIsAtLeast(48.dp).assertIsEnabled().performClick()
            compose.onNodeWithTag("auth-invite").assertHeightIsAtLeast(48.dp).performClick()
            compose.onNodeWithTag("auth-help").assertHeightIsAtLeast(48.dp).performClick()
            absent("auth-problem"); absent("auth-browser")
        }
        assertEquals(listOf("passkey", "invite", "help"), actions.calls)
    }

    @Test
    fun noPasskeyExplainsAndOffersTheOtherWays() {
        val actions = RecordingActions()
        show(AuthState.SignedOut(AuthProblem.NoPasskey), actions).use {
            compose.onNodeWithTag("auth-problem").assert(polite)
            compose.onNode(hasText("There's no Whumpo passkey on this phone.")).assertIsDisplayed()
            compose.onNodeWithTag("auth-sign-in").assert(hasText("Use a passkey from another device")).performClick()
            compose.onNodeWithTag("auth-browser").assertHeightIsAtLeast(48.dp).performClick()
        }
        assertEquals(listOf("passkey", "browser"), actions.calls)
    }

    @Test
    fun everyProblemHasPlainWordsAndASafeLine() {
        for (problem in AuthProblem.entries) {
            show(AuthState.SignedOut(problem)).use {
                exists("auth-problem")
                compose.onNodeWithTag("auth-sign-in").assertIsEnabled()
            }
        }
    }

    @Test
    fun whileSigningInEveryActionIsDisabledAndTheStateIsAnnounced() {
        show(AuthState.SignedOut(busy = true)).use {
            compose.onNodeWithTag("auth-sign-in").assertIsNotEnabled().assert(polite).assert(hasText("Signing in…"))
            compose.onNodeWithTag("auth-invite").assertIsNotEnabled()
        }
    }

    @Test
    fun waitingForTheBrowserCanReopenOrCancel() {
        val actions = RecordingActions()
        show(AuthState.WaitingForBrowser(), actions).use {
            compose.onNode(isHeading() and hasText("Finish in your browser")).assertIsDisplayed()
            compose.onNodeWithTag("auth-reopen").assertHeightIsAtLeast(48.dp).performClick()
            compose.onNodeWithTag("auth-cancel").assertHeightIsAtLeast(48.dp).performClick()
        }
        assertEquals(listOf("setup", "cancel"), actions.calls)
    }

    @Test
    fun exchangingShowsProgressWithNoButtons() {
        show(AuthState.WaitingForBrowser(exchanging = true)).use {
            compose.onNode(isHeading() and hasText("Signing you in…")).assertIsDisplayed()
            absent("auth-reopen"); absent("auth-cancel")
        }
    }

    @Test
    fun finishSetupOffersCheckAndSignOutOnlyOnceSignedIn() {
        val actions = RecordingActions()
        show(AuthState.FinishSetup(canCheck = true), actions).use {
            compose.onNodeWithTag("auth-open-setup").assertHeightIsAtLeast(48.dp).performClick()
            compose.onNodeWithTag("auth-check-setup").assertHeightIsAtLeast(48.dp).performClick()
            compose.onNodeWithTag("auth-sign-out").performClick()
        }
        assertEquals(listOf("setup", "check", "signOut"), actions.calls)
        show(AuthState.FinishSetup(canCheck = false)).use {
            absent("auth-check-setup"); absent("auth-sign-out")
        }
    }

    @Test
    fun updateRequiredBlocksWithStoreAndHelp() {
        val actions = RecordingActions()
        show(AuthState.UpdateRequired, actions).use {
            exists("state-update-required")
            compose.onNode(hasText("Open Play Store")).performClick()
            compose.onNode(hasText("Get help")).performClick()
        }
        assertEquals(listOf("store", "help"), actions.calls)
    }

    @Test
    fun theWholeWelcomeStaysReachableAtLargeTextAndOnTablet() {
        for ((screen, font) in listOf(Screen.Compact to 2f, Screen.Expanded to 1f)) {
            show(AuthState.SignedOut(AuthProblem.NoPasskey), screen = screen, font = font).use {
                compose.onNodeWithTag("auth-sign-in").performScrollTo().assertIsDisplayed()
                compose.onNodeWithTag("auth-browser").performScrollTo().assertIsDisplayed()
                compose.onNodeWithTag("auth-invite").performScrollTo().assertIsDisplayed()
                compose.onNodeWithTag("auth-help").performScrollTo().assertIsDisplayed()
            }
        }
    }
}
