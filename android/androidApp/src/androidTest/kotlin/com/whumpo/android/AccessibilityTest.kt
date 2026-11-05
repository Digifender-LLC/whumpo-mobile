package com.whumpo.android

import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Accessibility Test Framework checks (touch target size, contrast, labels, duplicate bounds) on every top-level screen:
 * the sign-in screen and the four shell destinations. `enableAccessibilityChecks` also checks after each click.
 * They run against whatever the screen shows with no server reachable (error and empty states), which is also what a
 * first-time owner can see. TalkBack speech itself is still manual device evidence (docs/android-build.md).
 */
@RunWith(AndroidJUnit4::class)
class AccessibilityTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Before
    fun setUp() {
        Fixtures.reset()
        compose.enableAccessibilityChecks()
    }

    @After
    fun tearDown() = Fixtures.reset()

    @Test
    fun signInScreenPasses() {
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("auth-sign-in").fetchSemanticsNodes().isNotEmpty() }
        compose.onRoot().tryPerformAccessibilityChecks()
    }

    @Test
    fun everyShellDestinationPasses() {
        Fixtures.container.accountState.activate(Fixtures.ownerA)
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("nav-chat").fetchSemanticsNodes().isNotEmpty() }
        listOf("chat", "projects", "activity", "settings").forEach { route ->
            compose.onNodeWithTag("nav-$route").performClick()
            compose.waitForIdle()
            compose.onRoot().tryPerformAccessibilityChecks()
        }
    }
}
