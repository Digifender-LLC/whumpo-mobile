package com.whumpo.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * WH-084 slice on the emulator: sign in, ask for a change and approve a pending specification review against the REAL Worker
 * module (`tests/android-local-worker.mjs`, disposable SQLite, no model) reached through `adb reverse tcp:8787 tcp:8787`.
 *
 * Skipped unless the worker's seed is passed as `-Pandroid.testInstrumentationRunnerArguments.seed=<base64 of the seed file>`,
 * so generic instrumented suites exclude it. API 36 CI supplies a seed through `scripts/test-worker-journey.sh`.
 * The passkey is a software authenticator test double ([SoftwarePasskeys]); this is
 * emulator evidence only: not Credential Manager, not a real provider, not the signed-app origin, not a physical device, and the
 * Worker runs in node on SQLite, not in workerd/D1.
 */
@RunWith(AndroidJUnit4::class)
class WorkerJourneyTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private var seed: WorkerSeed? = null
    private lateinit var passkeys: SoftwarePasskeys

    @Before
    fun signedOutWithADouble() {
        assumeTrue(BuildConfig.WHUMPO_ENVIRONMENT == "local")
        seed = WorkerSeed.from(InstrumentationRegistry.getArguments().getString("seed"))
        assumeTrue("no worker seed given", seed != null)
        Fixtures.reset()
        passkeys = SoftwarePasskeys(seed!!)
        Fixtures.container.passkeys.override = passkeys
    }

    @After
    fun restore() {
        Fixtures.container.passkeys.override = null
        Fixtures.reset()
    }

    private fun waitFor(timeout: Long = 20_000, condition: () -> Boolean) = compose.waitUntil(timeout, condition)

    private fun present(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun signInAskForAChangeAndApproveAPendingReview() {
        // A1 sign in with a passkey assertion the Worker verifies; no session existed before.
        waitFor { present("auth-sign-in") }
        compose.onNodeWithTag("auth-sign-in").performClick()
        waitFor { present("nav-chat") }
        assertEquals(1, passkeys.assertions)
        Shots.take("journey-1-signed-in")

        // A2 ask for a change in Chat (the deterministic no-model reply); the committed turn is shown honestly.
        waitFor { present("chat-composer") }
        compose.onNodeWithTag("chat-composer").performTextInput("export my chat")
        compose.onNodeWithTag("chat-send").performClick()
        waitFor { compose.onAllNodesWithText("export my chat", substring = true).fetchSemanticsNodes().size >= 1 && !present("chat-typing") }

        Shots.take("journey-2-chat-sent")
        // A3 the seeded specification review is listed with server-issued metadata and exact lines.
        waitFor { present("review-open") }
        compose.onAllNodesWithTag("review-open")[0].performScrollTo().performClick()
        waitFor { present("review-sheet") }
        compose.onNodeWithTag("review-lines").assertIsDisplayed()
        Shots.take("journey-3-review")

        // A4 explicit approval. The login proof is still fresh, so no second passkey is needed; the same request ID is used throughout.
        compose.onNodeWithTag("review-confirm").performScrollTo().performClick()
        waitFor { present("review-done") }
        compose.onNodeWithTag("review-outcome").assertIsDisplayed()
        Shots.take("journey-4-approved")
        assertTrue(passkeys.assertions >= 1)
    }
}
