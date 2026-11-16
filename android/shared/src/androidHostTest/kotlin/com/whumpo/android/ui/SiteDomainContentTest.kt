package com.whumpo.android.ui

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.whumpo.android.ui.domains.SiteDomainActions
import com.whumpo.android.ui.domains.SiteDomainContent
import com.whumpo.android.ui.domains.rememberDomainTime
import com.whumpo.android.ui.theme.WhumpoEntryTheme
import com.whumpo.core.api.CoreDomainClaim
import com.whumpo.core.api.CoreDomainStatus
import com.whumpo.core.api.CoreDomainTxt
import com.whumpo.core.auth.CoreSiteDomainState
import com.whumpo.core.auth.DomainNotice
import com.whumpo.core.auth.DomainProject
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SiteDomainContentTest : RobolectricUiTest() {
    private val start = Instant.parse("2026-11-16T10:00:00Z")
    private val project = DomainProject("project-demo", null, true)
    private val claim = CoreDomainClaim(
        "12345678-1234-4234-8234-123456789abc", project.id, "synthetic-example.com", 1,
        CoreDomainStatus.OwnershipVerified,
        CoreDomainTxt("_whumpo.synthetic-example.com", "whumpo-verification=" + "1".repeat(64)),
        Instant.parse("2026-11-16T10:00:02Z"), start, Instant.parse("2026-11-16T10:00:01Z"),
    )

    @Test
    fun compactLargeTextShowsExactInstructionsAndCheckControl() {
        var checked: CoreDomainClaim? = null
        launch(Screen.Compact, dark = true, fontScale = 2f) {
            WhumpoEntryTheme(darkTheme = true) {
                SiteDomainContent(CoreSiteDomainState(project = project, claims = listOf(claim), claim = claim),
                    start, actions(check = { checked = it }))
            }
        }.use {
            compose.onNodeWithText("Ownership verified").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(claim.record.name).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(claim.record.value).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Check TXT record").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
            assertEquals(claim, checked)
        }
    }

    @Test
    fun screenAgesOutEvidenceAndChallengeWithoutAnotherRead() {
        var clock = start
        compose.mainClock.autoAdvance = false
        launch(Screen.Compact) {
            WhumpoEntryTheme(darkTheme = true) {
                SiteDomainContent(CoreSiteDomainState(project = project, claims = listOf(claim), claim = claim),
                    rememberDomainTime(listOf(claim)) { clock }, actions())
            }
        }.use {
            compose.onNodeWithText("Ownership verified").assertExists()
            compose.runOnIdle { clock = checkNotNull(claim.evidenceValidUntil) }
            compose.mainClock.advanceTimeBy(1_100)
            compose.onNodeWithText("Ownership verified").assertDoesNotExist()
            compose.onNodeWithText("Ownership evidence expired. Check the TXT record again.").assertExists()
            compose.runOnIdle { clock = claim.expiresAt }
            compose.mainClock.advanceTimeBy(1_100)
            compose.onNodeWithText(claim.record.value).assertDoesNotExist()
            compose.onNodeWithText("Check TXT record").assertDoesNotExist()
            compose.onNodeWithText("Renew TXT challenge").assertExists()
        }
    }

    @Test
    fun compactLightFormKeepsInputEditableAfterDefinitiveValidation() {
        val rejected = "reserved-example.test"
        val submitted = mutableListOf<String>()
        var state by mutableStateOf(CoreSiteDomainState(project = project))
        launch(Screen.Compact, fontScale = 2f) {
            WhumpoEntryTheme(darkTheme = false) {
                SiteDomainContent(state, start, actions(prepare = {
                    submitted += it
                    state = state.copy(pending = true)
                }))
            }
        }.use {
            val submit = compose.onNodeWithText("Get TXT instructions")
            val field = compose.onNodeWithText("Domain name")
            submit.performScrollTo().assertIsNotEnabled()
            field.performScrollTo().performTextInput(rejected)
            assertEquals(emptyList<String>(), submitted)
            submit.performScrollTo().performClick()
            compose.onNodeWithText("Retry original request").performScrollTo().assertIsDisplayed()

            compose.runOnIdle { state = state.copy(pending = false, notice = DomainNotice.InvalidHostname) }
            compose.onNodeWithText(rejected).assertExists()
            compose.onNodeWithText("Retry original request").assertDoesNotExist()
            field.performScrollTo().performTextReplacement(claim.hostname)
            submit.performScrollTo().performClick()
            assertEquals(listOf(rejected, claim.hostname), submitted)
        }
    }

    @Test
    fun deniedReadOffersReadbackWithoutRevealingClaim() {
        var retried = false
        launch(Screen.Compact) {
            WhumpoEntryTheme(darkTheme = false) {
                SiteDomainContent(CoreSiteDomainState(pending = true, readbackOnly = true, notice = DomainNotice.Denied),
                    start, actions(retry = { retried = true }))
            }
        }.use {
            compose.onNodeWithText(claim.record.value).assertDoesNotExist()
            compose.onNodeWithText("Ownership verified").assertDoesNotExist()
            compose.onNodeWithText("Retry original request").assertDoesNotExist()
            compose.onNodeWithText("Sign in again").assertIsDisplayed()
            compose.onNodeWithText("Read saved claim").performScrollTo().performClick()
            assertEquals(true, retried)
        }
    }

    private fun actions(
        prepare: (String) -> Unit = {},
        check: (CoreDomainClaim) -> Unit = {},
        retry: () -> Unit = {},
    ) = SiteDomainActions(refresh = {}, chooseDifferentProject = {}, chooseWorkspace = {}, moreWorkspaces = {},
        chooseProject = {}, moreProjects = {}, inspect = {}, prepare = prepare, check = check, renew = {},
        retry = retry, signIn = {}, back = {})
}
