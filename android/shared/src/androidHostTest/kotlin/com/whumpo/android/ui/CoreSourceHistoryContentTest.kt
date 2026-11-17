package com.whumpo.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.whumpo.android.ui.home.CoreSourceHistoryContent
import com.whumpo.core.api.CoreSourceHistoryOrigin
import com.whumpo.core.api.CoreSourceHistoryVersion
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CoreSourceHistoryContentTest : RobolectricUiTest() {
    @Test
    fun pagingAndScopeChangesReplaceTheVisibleRowsAndExposeOnlyCurrentControls() {
        var entries by mutableStateOf<List<CoreSourceHistoryVersion>?>(listOf(
            CoreSourceHistoryVersion(22, 1_700_000_000_000, CoreSourceHistoryOrigin.Legacy),
        ))
        var hasOlder by mutableStateOf(true)
        var loading by mutableStateOf(false)
        var notice by mutableStateOf<String?>(null)
        var older = 0
        var latest = 0
        launch(Screen.Compact, fontScale = 2f) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                CoreSourceHistoryContent(entries, hasOlder, loading, notice,
                    onOlder = { older++; loading = true }, onLatest = { latest++ })
            }
        }.use {
            compose.onNodeWithText("Version 22").assertIsDisplayed()
            compose.onNodeWithText("Original save").assertIsDisplayed()
            compose.onNodeWithText("Older versions").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
            assertEquals(1, older)
            compose.onNodeWithText("Version 22").assertDoesNotExist()
            compose.onNodeWithText("Latest versions").assertDoesNotExist()
            compose.runOnIdle {
                entries = null
                hasOlder = false
                loading = false
                notice = "Source history changed. Load the latest versions."
            }
            compose.onNodeWithText("Source history changed. Load the latest versions.").assertIsDisplayed()
            compose.onNodeWithText("Older versions").assertDoesNotExist()
            compose.onNodeWithText("Latest versions").performScrollTo().performClick()
            assertEquals(1, latest)
            compose.runOnIdle { entries = emptyList(); notice = null }
            compose.onNodeWithText("No saved source versions.").assertIsDisplayed()
            compose.onNodeWithText("Version 22").assertDoesNotExist()
        }
    }
}
