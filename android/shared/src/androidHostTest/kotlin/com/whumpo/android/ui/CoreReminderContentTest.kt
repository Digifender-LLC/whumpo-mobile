package com.whumpo.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.whumpo.android.ui.theme.WhumpoEntryTheme
import com.whumpo.android.ui.work.CoreReminderInboxContent
import com.whumpo.android.ui.work.CoreTaskReminderContent
import com.whumpo.core.api.CoreReminderNotice
import com.whumpo.core.api.CoreReminderState
import com.whumpo.core.api.CoreTaskStatus
import com.whumpo.core.api.CoreWorkTask
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CoreReminderContentTest : RobolectricUiTest() {
    private val due = "2026-12-01T11:22:33.119Z"

    @Test fun pendingRetryAndIndependentStatusCheckReplaceFreshArm() {
        var pending by mutableStateOf<String?>(null)
        var state by mutableStateOf<CoreReminderState?>(null)
        var busy by mutableStateOf(false)
        val actions = mutableListOf<String>()
        launch(Screen.Compact, dark = true, fontScale = 2f) {
            WhumpoEntryTheme(darkTheme = true) {
                Column {
                    CoreTaskReminderContent(due, state, verified = true, canArm = true, busy, pending, null,
                        onArm = { actions += "arm"; pending = "Retry scheduling reminder" },
                        onCancel = { actions += "cancel" },
                        onRetry = { actions += "retry"; busy = true },
                        onCheck = { actions += "check" },
                    )
                }
            }
        }.use {
            compose.onNodeWithText("Remind me at the due time").assertHeightIsAtLeast(48.dp).performClick()
            compose.onNodeWithText("Remind me at the due time").assertDoesNotExist()
            compose.onNodeWithText("Check reminder status").performClick()
            compose.onNodeWithText("Retry scheduling reminder").performClick()
            compose.onNodeWithText("Retry scheduling reminder").assertIsNotEnabled()
            assertEquals(listOf("arm", "check", "retry"), actions)
            compose.runOnIdle { busy = false; pending = null; state = CoreReminderState.Armed }
            compose.onNodeWithText("Reminder scheduled.").assertIsDisplayed()
            compose.onNodeWithText("Cancel reminder").performClick()
            assertEquals(listOf("arm", "check", "retry", "cancel"), actions)
        }
    }

    @Test fun showsOnlyRedactedNoticeUntilOpenAndKeepsMarkReadSeparate() {
        val item = CoreReminderNotice("notice-1", due, "2026-12-01T11:23:00.000Z", null)
        val target = CoreWorkTask("task-1", "project-1", "Review the quote", null, null, due, CoreTaskStatus.Ready, 4)
        var task by mutableStateOf<CoreWorkTask?>(null)
        var items by mutableStateOf(listOf(item))
        val acknowledgements = mutableListOf<CoreReminderNotice>()
        launch(Screen.Compact, dark = true, fontScale = 2f) {
            WhumpoEntryTheme(darkTheme = true) {
                CoreReminderInboxContent("Current organization", items, false, task, false, null,
                    onBack = { task = null }, onRefresh = {}, onMore = {},
                    onOpen = { task = target },
                    onRead = { acknowledgements += it; items = listOf(it.copy(acknowledgedAt = it.observedAt)) },
                )
            }
        }.use {
            compose.onNodeWithText(target.title).assertDoesNotExist()
            compose.onNodeWithText("Open reminded task").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
            compose.onNodeWithText(target.title).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Mark reminder as read").assertDoesNotExist()
            assertEquals(emptyList<CoreReminderNotice>(), acknowledgements)
            compose.onNodeWithText("Back to task reminders").performScrollTo().performClick()
            compose.onNodeWithText("Mark reminder as read").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
            assertEquals(listOf(item), acknowledgements)
            compose.onNodeWithText("Read").performScrollTo().assertIsDisplayed()
        }
    }
}
