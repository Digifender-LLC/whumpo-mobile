package com.whumpo.android

import android.app.UiAutomation
import android.content.res.Configuration
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.whumpo.core.account.StateSlot
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Configuration changes the system makes, not simulated ones: a real display rotation (UiAutomation) and a real system
 * font-scale change (the `font_scale` setting) while the app is open. `recreate()` and a density override are covered by
 * NavigationTest, AccountStateTest and LargeTextTest. Emulator evidence only.
 */
@RunWith(AndroidJUnit4::class)
class SystemConfigurationTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val automation get() = InstrumentationRegistry.getInstrumentation().uiAutomation
    private val store get() = Fixtures.container.accountState
    private var previousFontScale: String? = null

    @Before
    fun setUp() {
        Fixtures.reset()
        previousFontScale = DeviceShell.run("settings get system font_scale").trim()
        automation.setRotation(UiAutomation.ROTATION_FREEZE_0)
        Fixtures.signIn()
    }

    @After
    fun tearDown() {
        automation.setRotation(UiAutomation.ROTATION_FREEZE_0)
        automation.setRotation(UiAutomation.ROTATION_UNFREEZE)
        val previous = previousFontScale
        if (previous.isNullOrEmpty() || previous == "null") {
            DeviceShell.run("settings delete system font_scale")
        } else {
            DeviceShell.run("settings put system font_scale $previous")
        }
        Fixtures.reset()
    }

    private fun configuration(scenario: ActivityScenario<MainActivity>): Configuration {
        lateinit var current: Configuration
        scenario.onActivity { current = Configuration(it.resources.configuration) }
        return current
    }

    private fun typeDraft(text: String) {
        compose.onNodeWithTag("chat-composer").performTextInput(text)
        compose.waitUntil(5_000) { store.read(Fixtures.ownerA, StateSlot.CHAT_DRAFT) == text }
    }

    private fun assertDraftShown(text: String) {
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasTestTag("chat-composer") and hasText(text)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("chat-composer").assertIsDisplayed()
    }

    @Test
    fun rotatingTheDisplayKeepsTheDestinationAndTheDraft() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(10_000) { configuration(scenario).orientation == Configuration.ORIENTATION_PORTRAIT }
        typeDraft("Add a booking page")
        compose.onNodeWithTag("nav-activity").performClick()

        automation.setRotation(UiAutomation.ROTATION_FREEZE_90)
        compose.waitUntil(10_000) { configuration(scenario).orientation == Configuration.ORIENTATION_LANDSCAPE }
        compose.waitForIdle()
        compose.onNodeWithTag("nav-activity").assertIsSelected()
        compose.onNode(isHeading() and hasText("Activity")).assertIsDisplayed()
        Shots.take("rotation-landscape-activity")
        compose.onNodeWithTag("nav-chat").performClick()
        assertDraftShown("Add a booking page")

        automation.setRotation(UiAutomation.ROTATION_FREEZE_0)
        compose.waitUntil(10_000) { configuration(scenario).orientation == Configuration.ORIENTATION_PORTRAIT }
        compose.waitForIdle()
        compose.onNodeWithTag("nav-chat").assertIsSelected()
        assertDraftShown("Add a booking page")
        scenario.close()
    }

    @Test
    fun raisingTheSystemFontScaleWhileOpenKeepsTheShellUsable() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        typeDraft("Change the opening hours")

        DeviceShell.run("settings put system font_scale 2.0")
        compose.waitUntil(10_000) { configuration(scenario).fontScale >= 2f }
        compose.waitForIdle()
        listOf("chat", "projects", "activity", "settings").forEach { compose.onNodeWithTag("nav-$it").assertIsDisplayed() }
        assertDraftShown("Change the opening hours")

        compose.onNodeWithTag("nav-settings").performClick()
        compose.onNode(isHeading() and hasText("Settings")).assertIsDisplayed()
        compose.onNodeWithTag("settings-environment").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("settings-sign-out").performScrollTo().assertIsDisplayed()
        Shots.take("system-font-scale-200-settings")
        scenario.close()
    }
}
