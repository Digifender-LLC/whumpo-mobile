package com.whumpo.android.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.whumpo.android.ui.security.CoreAccountNoticesContent
import com.whumpo.core.api.SecurityNotice
import com.whumpo.core.api.SecurityNoticeDelivery
import com.whumpo.core.api.SecurityNoticePage
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CoreAccountNoticesContentTest : RobolectricUiTest() {
    @Test fun accountOnlyControlsShowPageRefreshMoreAndHideWithoutWorkspaceAction() {
        var refreshes = 0
        var pages = 0
        var page by mutableStateOf<SecurityNoticePage?>(null)
        launch(Screen.Compact, dark = true) {
            CoreAccountNoticesContent(page, false,
                refresh = { refreshes++; page = SecurityNoticePage(listOf(SecurityNotice("notice:1", "sign_in", 1, SecurityNoticeDelivery.Unknown)), "cursor:1") },
                more = { pages++ }, hide = { page = null })
        }.use {
            compose.onNodeWithText("Show security notices").performClick()
            compose.onNodeWithText("External delivery is unconfirmed").assertIsDisplayed()
            compose.onNodeWithText("More security notices").performClick()
            compose.onNodeWithText("Refresh security notices").performClick()
            assertEquals(2, refreshes); assertEquals(1, pages)
            compose.onNodeWithText("Hide security notices").performClick()
            compose.onNodeWithText("External delivery is unconfirmed").assertDoesNotExist()
            compose.onNodeWithText("More security notices").assertDoesNotExist()
        }
    }

    @Test fun busyReadCanStillHideAndAuthorityClearingRemovesPrivateRows() {
        var page by mutableStateOf<SecurityNoticePage?>(SecurityNoticePage(listOf(SecurityNotice("notice:1", "recovery", 1, SecurityNoticeDelivery.NotConfigured)), "cursor:1"))
        launch(Screen.Compact, dark = true) {
            CoreAccountNoticesContent(page, true, refresh = { error("Disabled") }, more = { error("Disabled") }, hide = { page = null })
        }.use {
            compose.onNodeWithText("Refresh security notices").assertIsNotEnabled()
            compose.onNodeWithText("More security notices").assertIsNotEnabled()
            compose.onNodeWithText("Hide security notices").performClick()
            compose.onNodeWithText("External notifications are not configured").assertDoesNotExist()
            compose.onNodeWithText("Show security notices").assertIsNotEnabled()
        }
    }
}
