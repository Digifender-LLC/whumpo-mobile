package com.whumpo.android

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val destinations = listOf(
        "chat" to "Chat",
        "projects" to "Projects",
        "activity" to "Activity",
        "settings" to "Settings",
    )

    // The shell only shows for a signed-in account (WP5 sign-in gate), so the tests sign a fixture account in.
    @Before
    fun signedIn() {
        Fixtures.reset()
        Fixtures.container.accountState.activate(Fixtures.ownerA)
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("nav-chat").fetchSemanticsNodes().isNotEmpty() }
    }

    @After
    fun signedOut() = Fixtures.reset()

    @Test
    fun chatIsTheFirstDestination() {
        compose.onNodeWithTag("nav-chat").assertIsSelected()
        compose.onNode(isHeading() and hasText("Chat")).assertIsDisplayed()
        compose.onNodeWithTag("chat-composer").assertIsDisplayed()
    }

    @Test
    fun signedOutShowsWelcomeInsteadOfTheShell() {
        Fixtures.reset()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("auth-sign-in").fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(isHeading() and hasText("Welcome back to Whumpo.")).assertIsDisplayed()
        compose.onNodeWithTag("nav-chat").assertDoesNotExist()
    }

    @Test
    fun everyDestinationIsReachableAndAnnouncedAsATab() {
        destinations.forEach { (route, title) ->
            val item = compose.onNodeWithTag("nav-$route")
            item.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
            item.performClick()
            item.assertIsSelected()
            compose.onNode(isHeading() and hasText(title)).assertIsDisplayed()
            destinations.filter { it.first != route }.forEach { (other, _) ->
                compose.onNodeWithTag("nav-$other").assertIsNotSelected()
            }
        }
    }

    @Test
    fun selectedDestinationSurvivesConfigurationChange() {
        compose.onNodeWithTag("nav-activity").performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("nav-activity").assertIsSelected()
        compose.onNode(isHeading() and hasText("Activity")).assertIsDisplayed()
    }

    @Test
    fun settingsShowsTheBuildEnvironment() {
        compose.onNodeWithTag("nav-settings").performClick()
        val expected = when (BuildConfig.WHUMPO_ENVIRONMENT) {
            "local" -> "Local development (this device only): http://127.0.0.1:8787"
            else -> "Whumpo test platform: https://whumpo.com"
        }
        compose.onNodeWithTag("settings-environment").assert(hasText(expected))
    }
}
