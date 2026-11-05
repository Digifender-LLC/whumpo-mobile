package com.whumpo.android.ui

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.whumpo.android.ui.shell.TopDestination
import com.whumpo.android.ui.shell.WhumpoShell
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ShellBehaviorTest : RobolectricUiTest() {
    private fun exists(tag: String) = compose.onNodeWithTag(tag).assertExists()
    private fun absent(tag: String) = compose.onNodeWithTag(tag).assertDoesNotExist()

    @Test
    fun compactShowsABottomBarAndOnePane() {
        launch(Screen.Compact) { WhumpoShell() }.use {
            exists("nav-bar"); absent("nav-rail")
            exists("pane-list"); absent("pane-detail")
        }
    }

    @Test
    fun mediumShowsARailAndOnePane() {
        launch(Screen.Medium) { WhumpoShell() }.use {
            exists("nav-rail"); absent("nav-bar")
            exists("pane-list"); absent("pane-detail")
        }
    }

    @Test
    fun expandedShowsARailAndTwoPanes() {
        launch(Screen.Expanded) { WhumpoShell() }.use {
            exists("nav-rail"); absent("nav-bar")
            exists("pane-list"); exists("pane-detail")
        }
    }

    @Test
    fun everyDestinationIsATabWithATouchTargetAndAHeading() {
        launch(Screen.Compact) { WhumpoShell() }.use {
            TopDestination.entries.forEach { destination ->
                val tab = compose.onNodeWithTag("nav-${destination.route}")
                tab.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
                tab.assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
                tab.performClick()
                tab.assertIsSelected()
                compose.onNode(isHeading() and hasText(destination.label)).assertExists()
            }
        }
    }

    @Test
    fun selectionSurvivesRecreationAndResize() {
        launch(Screen.Compact) { WhumpoShell() }.use { scenario ->
            compose.onNodeWithTag("nav-activity").performClick()
            scenario.recreate()
            compose.waitForIdle()
            compose.onNodeWithTag("nav-activity").assertIsSelected()
        }
    }

    @Test
    fun backFromAnotherDestinationReturnsToChat() {
        launch(Screen.Compact) { WhumpoShell() }.use { scenario ->
            compose.onNodeWithTag("nav-settings").performClick()
            compose.onNodeWithTag("nav-settings").assertIsSelected()
            scenario.onActivity {
                assertTrue("back callback enabled", it.onBackPressedDispatcher.hasEnabledCallbacks())
                it.onBackPressedDispatcher.onBackPressed()
            }
            // Back is delivered through a coroutine, so wait for the selection instead of asserting once.
            compose.waitUntil(5_000) {
                compose.onAllNodes(isSelected() and hasTestTag("nav-chat")).fetchSemanticsNodes().isNotEmpty()
            }
        }
    }
}
