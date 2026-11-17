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
import com.whumpo.android.ui.work.OfflineWorkActions
import com.whumpo.android.ui.work.OfflineWorkContent
import com.whumpo.core.auth.CoreOfflineWorkState
import com.whumpo.core.auth.OfflineWorkNotice
import com.whumpo.core.auth.OfflineWorkReview
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OfflineWorkContentTest : RobolectricUiTest() {
    @Test
    fun compactOfflineCaptureSavesBeforeOfferingExplicitReconnect() {
        var state by mutableStateOf(CoreOfflineWorkState(loaded = true))
        var reconnections = 0
        var signOuts = 0
        val saved = mutableListOf<Pair<String, String>>()
        launch(Screen.Compact, dark = true, fontScale = 2f) {
            WhumpoEntryTheme(darkTheme = true) {
                OfflineWorkContent(state, actions(
                    save = { title, description ->
                        saved += title to description
                        state = state.copy(title = title, description = description, saved = true)
                    },
                    reconnect = { reconnections++ },
                    signOut = { signOuts++ },
                ))
            }
        }.use {
            compose.onNodeWithText("Reconnect to review").assertDoesNotExist()
            compose.onNodeWithText("Save on this device").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithText("Task title").performScrollTo().performTextInput("Review a brief")
            compose.onNodeWithText("Description (optional)").performScrollTo().performTextInput("Synthetic notes")
            compose.onNodeWithText("Save on this device").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
            assertEquals(listOf("Review a brief" to "Synthetic notes"), saved)
            assertEquals(0, reconnections)
            compose.onNodeWithText("Reconnect to review").performScrollTo().performClick()
            assertEquals(1, reconnections)
            compose.onNodeWithText("Sign out").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
            assertEquals(1, signOuts)
        }
    }

    @Test
    fun frozenDraftCannotBeEditedAndRetryRequiresItsReview() {
        val review = OfflineWorkReview("Synthetic project", "Review a brief", "Synthetic notes", retry = true)
        val saved = CoreOfflineWorkState(loaded = true, title = review.title, description = review.description,
            saved = true, frozen = true, notice = OfflineWorkNotice.Unconfirmed)
        var state by mutableStateOf(saved)
        val sent = mutableListOf<OfflineWorkReview>()
        launch(Screen.Compact, fontScale = 2f) {
            WhumpoEntryTheme(darkTheme = false) {
                OfflineWorkContent(state, actions(
                    reconnect = { state = saved.copy(connected = true, review = review) },
                    submit = { sent += it },
                ))
            }
        }.use {
            compose.onNodeWithText("Task title").assertDoesNotExist()
            compose.onNodeWithText("Discard saved draft").assertDoesNotExist()
            compose.onNodeWithText("Retry original request").assertDoesNotExist()
            compose.onNodeWithText("Reconnect to review").performScrollTo().performClick()
            compose.onNodeWithText(review.projectName).performScrollTo().assertIsDisplayed()
            assertEquals(emptyList<OfflineWorkReview>(), sent)
            compose.onNodeWithText("Retry original request").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
            assertEquals(listOf(review), sent)
            compose.runOnIdle { state = CoreOfflineWorkState(loaded = true, accessLost = true, notice = OfflineWorkNotice.AccessDenied) }
            compose.onNodeWithText(review.title).assertDoesNotExist()
            compose.onNodeWithText("Sign out").performScrollTo().assertIsDisplayed()
            compose.runOnIdle { state = CoreOfflineWorkState(loaded = true, notice = OfflineWorkNotice.Unreadable) }
            compose.onNodeWithText("The saved draft could not be opened.").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Discard saved draft").assertDoesNotExist()
            compose.onNodeWithText("Save on this device").assertDoesNotExist()
        }
    }

    private fun actions(
        save: (String, String) -> Unit = { _, _ -> },
        reconnect: () -> Unit = {},
        submit: (OfflineWorkReview) -> Unit = {},
        signOut: () -> Unit = {},
    ) = OfflineWorkActions(
        save = save, discard = {}, reconnect = reconnect, chooseWorkspace = {}, changeWorkspace = {},
        moreWorkspaces = {}, chooseProject = {}, moreProjects = {}, submit = submit, cancelReview = {},
        checkTask = {}, openTask = {}, back = {}, signOut = signOut,
    )
}
