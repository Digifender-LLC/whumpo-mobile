package com.whumpo.android.ui

import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import com.whumpo.android.ui.theme.WhumpoEntryTheme
import com.whumpo.android.ui.work.CoreInboxContent
import com.whumpo.core.api.CoreInboxCategory
import com.whumpo.core.api.CoreInboxDetail
import com.whumpo.core.api.CoreInboxItem
import com.whumpo.core.api.CoreInboxPresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CoreInboxContentTest : RobolectricUiTest() {
    private val date = "2026-11-16T11:00:00.000Z"
    private val item = CoreInboxItem("notice-1", CoreInboxCategory.Approval, date, null, CoreInboxPresentation.QuietHours)

    @Test
    fun compactLargeTextSeparatesKeyboardOpenFromAcknowledgmentAndPendingProof() {
        var items by mutableStateOf(listOf(item))
        var detail by mutableStateOf<CoreInboxDetail?>(null)
        var busy by mutableStateOf(false)
        var notice by mutableStateOf<String?>(null)
        val opened = mutableListOf<CoreInboxItem>()
        val acknowledged = mutableListOf<CoreInboxItem>()
        lateinit var inputMode: InputModeManager
        launch(Screen.Compact, dark = true, fontScale = 2f) {
            WhumpoEntryTheme(darkTheme = true) {
                val mode = LocalInputModeManager.current
                SideEffect { inputMode = mode }
                CoreInboxContent(
                    "Selected organization", items, false, detail, busy, notice,
                    onBack = { detail = null },
                    onRefresh = {},
                    onMore = {},
                    onOpen = { opened += it; busy = true },
                    onAcknowledge = { acknowledged += it; busy = true },
                )
            }
        }.use {
            compose.onNodeWithText("Selected organization").assertIsDisplayed()
            val open = compose.onNodeWithText("Open approval").performScrollTo().assertHeightIsAtLeast(48.dp)
            compose.runOnIdle { assertTrue(inputMode.requestInputMode(InputMode.Keyboard)) }
            open.performSemanticsAction(SemanticsActions.RequestFocus)
            open.assertIsFocused().performKeyInput { pressKey(Key.Enter) }
            assertEquals(listOf(item), opened)
            assertEquals(emptyList<CoreInboxItem>(), acknowledged)
            compose.onNodeWithText("Acknowledge notification").assertIsNotEnabled()
            compose.onNodeWithText("Waiting for approval").assertDoesNotExist()

            compose.runOnIdle {
                detail = CoreInboxDetail.Approval("Create task", date, true)
                busy = false
            }
            compose.onNodeWithText("Waiting for approval").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Sign in again before making a decision.").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Acknowledge notification").assertDoesNotExist()
            compose.onNodeWithText("Back to task notifications").performScrollTo().performClick()
            compose.onNodeWithText("Acknowledge notification").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
            assertEquals(listOf(item), acknowledged)
            compose.onNodeWithText("Acknowledged", substring = true).assertDoesNotExist()

            compose.runOnIdle {
                items = listOf(item.copy(acknowledgedAt = date))
                busy = false
                notice = "Notification acknowledged."
            }
            compose.onNodeWithText("Acknowledged", substring = true, ignoreCase = false).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Acknowledge notification").assertDoesNotExist()
            compose.onNodeWithText("Open approval").performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun readFailureRemovesVerifiedDetailAndOffersRefresh() {
        var items by mutableStateOf<List<CoreInboxItem>?>(listOf(item))
        var detail by mutableStateOf<CoreInboxDetail?>(CoreInboxDetail.ConfirmedResult(date, 3))
        var notice by mutableStateOf<String?>(null)
        var refreshes = 0
        launch(Screen.Compact, dark = false, fontScale = 1f) {
            WhumpoEntryTheme(darkTheme = false) {
                CoreInboxContent("Selected organization", items, false, detail, false, notice,
                    onBack = {}, onRefresh = { refreshes++ }, onMore = {}, onOpen = {}, onAcknowledge = {})
            }
        }.use {
            compose.onNodeWithText("3 tasks verified").assertIsDisplayed()
            compose.runOnIdle {
                items = null
                detail = null
                notice = "Task notifications are unavailable. Refresh to check again."
            }
            compose.onNodeWithText("3 tasks verified").assertDoesNotExist()
            compose.onNodeWithText("Open approval").assertDoesNotExist()
            compose.onNodeWithText(notice!!).assertIsDisplayed()
            compose.onNodeWithText("Refresh notifications").assertHeightIsAtLeast(48.dp).performClick()
            assertEquals(1, refreshes)
        }
    }
}
