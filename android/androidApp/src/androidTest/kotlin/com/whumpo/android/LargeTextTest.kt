package com.whumpo.android

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.whumpo.android.ui.WhumpoApp
import com.whumpo.android.ui.theme.WhumpoTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The shell stays navigable and readable at 200% font scale. */
@RunWith(AndroidJUnit4::class)
class LargeTextTest {
    @get:Rule
    val compose = createComposeRule()

    @Before
    fun setUp() {
        Fixtures.reset()
        Fixtures.container.accountState.activate(Fixtures.ownerA)
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale = 2f)) {
                WhumpoTheme { WhumpoApp(Fixtures.container) }
            }
        }
    }

    @org.junit.After
    fun tearDown() = Fixtures.reset()

    @Test
    fun navigationLabelsAndContentRemainReachable() {
        listOf("chat", "projects", "activity", "settings").forEach { route ->
            compose.onNodeWithTag("nav-$route").assertIsDisplayed()
        }
        compose.onNodeWithTag("chat-composer").assertIsDisplayed()

        compose.onNodeWithTag("nav-settings").performClick()
        compose.onNode(isHeading() and hasText("Settings")).assertIsDisplayed()
        compose.onNodeWithTag("settings-environment").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("settings-sign-out").performScrollTo().assertIsDisplayed()
    }
}
