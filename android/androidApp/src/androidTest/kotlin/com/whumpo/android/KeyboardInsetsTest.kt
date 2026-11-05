package com.whumpo.android

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.AssumptionViolatedException
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The composer stays above the soft keyboard (edge-to-edge + imePadding). */
@RunWith(AndroidJUnit4::class)
class KeyboardInsetsTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @Before
    fun setUp() {
        Fixtures.reset()
        Fixtures.container.accountState.activate(Fixtures.ownerA)
    }

    @After
    fun tearDown() = Fixtures.reset()

    private fun withKeyboardActivity(block: (ActivityScenario<MainActivity>) -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use(block)
    }

    @Test
    fun anAbsentKeyboardClosesTheActivityBeforeSkipping() {
        var activity: MainActivity? = null
        assertThrows(AssumptionViolatedException::class.java) {
            withKeyboardActivity { scenario ->
                scenario.onActivity { activity = it }
                assumeTrue("Synthetic unavailable keyboard", false)
            }
        }
        assertTrue("the skipped scenario destroyed its activity", activity?.isDestroyed == true)
    }

    @Test
    fun composerIsNotCoveredByTheKeyboard() = withKeyboardActivity { scenario ->
        compose.onNodeWithTag("chat-composer").performClick()

        var imeBottom = 0
        var windowHeight = 0
        val shown = runCatching {
            compose.waitUntil(10_000) {
                var visible = false
                scenario.onActivity { activity ->
                    val root = activity.window.decorView
                    val insets = ViewCompat.getRootWindowInsets(root)
                    visible = insets?.isVisible(WindowInsetsCompat.Type.ime()) == true
                    imeBottom = insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
                    windowHeight = root.height
                }
                visible && imeBottom > 0
            }
        }.isSuccess
        // Some headless emulator images never show a soft keyboard; record that as skipped.
        assumeTrue("Soft keyboard did not appear on this device", shown)

        compose.waitForIdle()
        val composerBottom = compose.onNodeWithTag("chat-composer")
            .fetchSemanticsNode().boundsInWindow.bottom
        assertTrue(
            "composer bottom $composerBottom must be above keyboard top ${windowHeight - imeBottom}",
            composerBottom <= windowHeight - imeBottom + 1f,
        )
    }
}
