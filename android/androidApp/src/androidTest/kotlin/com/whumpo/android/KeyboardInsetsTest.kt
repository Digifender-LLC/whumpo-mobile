package com.whumpo.android

import androidx.compose.ui.test.junit4.createEmptyComposeRule
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
        Fixtures.signIn()
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
        assertAboveKeyboard(compose, scenario, "chat-composer", "chat-composer")
    }
}
