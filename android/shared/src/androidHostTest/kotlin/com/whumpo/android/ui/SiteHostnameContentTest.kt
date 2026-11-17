package com.whumpo.android.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import com.whumpo.android.ui.domains.SiteHostnameActions
import com.whumpo.android.ui.domains.SiteHostnameContent
import com.whumpo.android.ui.theme.WhumpoEntryTheme
import com.whumpo.core.api.CoreHostnameAttachment
import com.whumpo.core.api.CoreHostnameStatus
import com.whumpo.core.auth.CoreSiteHostnameState
import com.whumpo.core.auth.DomainProject
import com.whumpo.core.auth.HostnameAction
import com.whumpo.core.auth.HostnameNotice
import com.whumpo.core.auth.HostnamePending
import com.whumpo.core.auth.HostnameReview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SiteHostnameContentTest : RobolectricUiTest() {
    private val now = Instant.parse("2026-11-16T10:00:00Z")
    private val host = "synthetic-example.com"
    private val project = DomainProject("project-1", null, true)

    @Test
    fun keyboardReviewAndCancelRestoreFocusAtCompactDoubleText() {
        val review = HostnameReview(HostnameAction.Disable, host)
        val current = CoreSiteHostnameState(project = project, loaded = true, policyUnavailable = true,
            attachment = attachment(CoreHostnameStatus.ReproofRequired))
        var state by mutableStateOf(current)
        val confirmed = mutableListOf<HostnameReview>()
        lateinit var inputMode: InputModeManager
        launch(Screen.Compact, dark = true, fontScale = 2f) {
            WhumpoEntryTheme(darkTheme = true) {
                val mode = LocalInputModeManager.current
                SideEffect { inputMode = mode }
                SiteHostnameContent(state, now, actions(
                    reviewDisable = { state = current.copy(review = review) },
                    cancel = { state = current },
                    confirm = { confirmed += it },
                ))
            }
        }.use {
            compose.runOnIdle {
                assertTrue(inputMode.requestInputMode(InputMode.Keyboard))
                assertEquals(InputMode.Keyboard, inputMode.inputMode)
            }
            compose.onNodeWithText("Load verified domains").assertDoesNotExist()
            val trigger = compose.onNodeWithText("Review disabling hostname")
            trigger.performScrollTo().assertHeightIsAtLeast(48.dp)
            trigger.performSemanticsAction(SemanticsActions.RequestFocus)
            trigger.assertIsFocused().performKeyInput { pressKey(Key.Enter) }
            compose.onNodeWithText("Review disabling hostname").assertIsFocused()
            compose.onNodeWithText("Close local serving. The hostname stays reserved until removal is confirmed.")
                .performScrollTo().assertIsDisplayed()
            val cancel = compose.onNodeWithText("Cancel review").performScrollTo()
            cancel.performSemanticsAction(SemanticsActions.RequestFocus)
            cancel.performKeyInput { pressKey(Key.Enter) }
            trigger.assertIsFocused()
            assertEquals(emptyList<HostnameReview>(), confirmed)
            trigger.performKeyInput { pressKey(Key.Enter) }
            compose.onNodeWithText("Disable hostname").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
            assertEquals(listOf(review), confirmed)
        }
    }

    @Test
    fun canonicalPhasesAndPendingReadbackStayDistinct() {
        var state by mutableStateOf(CoreSiteHostnameState(project = project, loaded = true,
            attachment = attachment(CoreHostnameStatus.Reserved)))
        var retries = 0
        var checks = 0
        launch(Screen.Compact) {
            WhumpoEntryTheme(darkTheme = false) {
                SiteHostnameContent(state, now, actions(retry = { retries++ }, check = { checks++ }))
            }
        }.use {
            compose.onNodeWithText("Reserved").assertIsDisplayed()
            compose.runOnIdle { state = state.copy(attachment = attachment(CoreHostnameStatus.ServingEligible)) }
            compose.onNodeWithText("Eligible at last check").assertIsDisplayed()
            compose.runOnIdle { state = state.copy(attachment = attachment(CoreHostnameStatus.ReproofRequired)) }
            compose.onNodeWithText("Reproof required").assertIsDisplayed()
            compose.runOnIdle { state = state.copy(attachment = attachment(CoreHostnameStatus.Detaching)) }
            compose.onNodeWithText("Detaching").assertIsDisplayed()
            compose.onNodeWithText("Review disabling hostname").assertDoesNotExist()
            compose.runOnIdle { state = state.copy(attachment = null, loaded = false, pending = HostnamePending.Retry) }
            compose.onNodeWithText("Retry original request").performScrollTo().performClick()
            compose.runOnIdle { state = state.copy(pending = HostnamePending.CheckStatus, notice = HostnameNotice.CheckStatus) }
            compose.onNodeWithText("Retry original request").assertDoesNotExist()
            compose.onNodeWithText(host).assertDoesNotExist()
            compose.onNodeWithText("Check current status").performScrollTo().performClick()
            assertEquals(1, retries)
            assertEquals(1, checks)
        }
    }

    @Test
    fun expiredResumeReviewShowsTheVersionAndDisablesConfirmation() {
        val review = HostnameReview(HostnameAction.Resume, host, 3, now)
        launch(Screen.Compact, fontScale = 2f) {
            WhumpoEntryTheme(darkTheme = false) {
                SiteHostnameContent(CoreSiteHostnameState(project = project, review = review), now, actions())
            }
        }.use {
            compose.onNodeWithText("Website publication version 3").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Resume hostname").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithText("Cancel review").performScrollTo().assertHeightIsAtLeast(48.dp)
        }
    }

    private fun attachment(status: CoreHostnameStatus) = CoreHostnameAttachment("route-1", host, 1, 1, status)

    private fun actions(
        reviewDisable: () -> Unit = {},
        confirm: (HostnameReview) -> Unit = {},
        cancel: () -> Unit = {},
        retry: () -> Unit = {},
        check: () -> Unit = {},
    ) = SiteHostnameActions(
        refresh = {}, chooseDifferentProject = {}, chooseWorkspace = {}, moreWorkspaces = {}, chooseProject = {},
        moreProjects = {}, loadClaims = {}, reviewClaim = {}, reviewDisable = reviewDisable, confirm = confirm,
        cancelReview = cancel, retry = retry, checkStatus = check, signIn = {}, back = {},
    )
}
