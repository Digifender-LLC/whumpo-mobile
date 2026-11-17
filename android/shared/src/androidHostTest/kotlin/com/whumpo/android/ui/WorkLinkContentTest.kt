package com.whumpo.android.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.whumpo.android.ui.theme.WhumpoEntryTheme
import com.whumpo.android.ui.work.WorkLinkContent
import com.whumpo.android.ui.work.WorkLinkResult
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
class WorkLinkContentTest : RobolectricUiTest() {
    @Test
    fun userMustOpenThePastedLinkInTheDisplayedOrganization() {
        var link by mutableStateOf("")
        var state by mutableStateOf<WorkLinkResult>(WorkLinkResult.EnterLink)
        var opens = 0
        var signedOut = 0
        val current = CoreWorkTask("task-1", "project-2", "Current task", "Current detail", null, null, CoreTaskStatus.Ready, 2)
        launch(Screen.Compact, dark = true, fontScale = 2f) {
            WhumpoEntryTheme(darkTheme = true) {
                WorkLinkContent("Selected organization", link, state,
                    onLinkChange = { link = it; state = WorkLinkResult.EnterLink },
                    onOpen = { opens++; state = WorkLinkResult.Checking },
                    onBack = {},
                    onSignOut = { signedOut++ },
                )
            }
        }.use {
            compose.onNodeWithText("Selected organization").performScrollTo().assertIsDisplayed()
            val open = compose.onNodeWithText("Open link in this organization")
            open.performScrollTo().assertIsNotEnabled()
            compose.onNodeWithText("Work link").performScrollTo().performTextInput(
                "https://work.example.test/#work/task/v1/task-1/" + "a".repeat(32)
            )
            assertEquals(0, opens)
            compose.onNodeWithText(current.title).assertDoesNotExist()
            open.performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
            assertEquals(1, opens)
            open.assertIsNotEnabled()
            compose.onNodeWithText("Checking Work link…").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(current.title).assertDoesNotExist()

            compose.runOnIdle { state = WorkLinkResult.Open(current) }
            compose.onNodeWithText(current.title).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(current.description!!).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("View website receipts").assertDoesNotExist()
            compose.runOnIdle { state = WorkLinkResult.Unavailable }
            compose.onNodeWithText(current.title).assertDoesNotExist()
            compose.onNodeWithText("This Work link is unavailable.").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Sign out").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
            assertEquals(1, signedOut)
        }
    }
}
