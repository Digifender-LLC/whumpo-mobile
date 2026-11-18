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
import androidx.compose.ui.unit.dp
import com.whumpo.android.ui.work.WorkAttachmentContent
import com.whumpo.core.auth.CoreAttachmentNotice
import com.whumpo.core.auth.CoreWorkAttachmentState
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WorkAttachmentContentTest : RobolectricUiTest() {
    @Test
    fun confirmsOnlyAfterVisibleReviewAndDistinguishesSavedFromAttached() {
        var state by mutableStateOf(CoreWorkAttachmentState())
        var choices = 0
        var confirmations = 0
        var retries = 0
        launch(Screen.Compact, dark = true, fontScale = 2f) {
            WorkAttachmentContent("Install sign", state, false,
                onChoose = { choices++ }, onConfirm = { confirmations++ }, onRetry = { retries++ }, onClose = {})
        }.use {
            compose.onNodeWithText("Save and attach").assertDoesNotExist()
            compose.onNodeWithText("Choose file").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
            assertEquals(1, choices)
            assertEquals(0, confirmations)
            compose.runOnIdle { state = CoreWorkAttachmentState("measurements.txt", 120) }
            compose.onNodeWithText("Task: Install sign").assertIsDisplayed()
            compose.onNodeWithText("measurements.txt").assertIsDisplayed()
            compose.onNodeWithText("120 bytes").assertIsDisplayed()
            compose.onNodeWithText("Save and attach").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
            assertEquals(1, confirmations)
            compose.runOnIdle { state = state.copy(saved = true, canRetry = true) }
            compose.onNodeWithText("Attached to task.").assertDoesNotExist()
            compose.onNodeWithText("Saved private file. Attachment not yet verified.").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Retry original request").performScrollTo().performClick()
            assertEquals(1, retries)
            compose.runOnIdle { state = state.copy(attached = true, canRetry = false) }
            compose.onNodeWithText("Attached to task.").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Retry original request").assertDoesNotExist()
        }
    }

    @Test
    fun cameraIsExplicitAndKeepsTheExistingFileFallbackAndReview() {
        var state by mutableStateOf(CoreWorkAttachmentState())
        var capturing by mutableStateOf(false)
        var unavailable by mutableStateOf(false)
        var launches = 0
        var confirmations = 0
        launch(Screen.Compact, dark = true, fontScale = 2f) {
            WorkAttachmentContent("Install sign", state, false, onChoose = {}, onConfirm = { confirmations++ },
                onRetry = {}, onClose = {}, onCapture = { launches++; capturing = true },
                capturing = capturing, cameraUnavailable = unavailable)
        }.use {
            compose.onNodeWithText("Capture document photo").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
            assertEquals(1, launches)
            compose.onNodeWithText("Choose file").assertIsNotEnabled()
            compose.onNodeWithText("Save and attach").assertDoesNotExist()
            compose.runOnIdle { capturing = false; unavailable = true }
            compose.onNodeWithText("Camera unavailable. Choose a file instead.").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Choose file").performScrollTo().assertIsDisplayed()
            compose.runOnIdle { unavailable = false; state = CoreWorkAttachmentState("Document photo.jpg", 642) }
            compose.onNodeWithText("Task: Install sign").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Document photo.jpg").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("642 bytes").performScrollTo().assertIsDisplayed()
            assertEquals(0, confirmations)
            compose.onNodeWithText("Save and attach").performScrollTo().performClick()
            assertEquals(1, confirmations)
        }
    }

    @Test
    fun changedTaskHidesReviewAndOffersReturn() {
        var closes = 0
        launch(Screen.Compact) {
            WorkAttachmentContent("Former task", CoreWorkAttachmentState(notice = CoreAttachmentNotice.DestinationChanged),
                false, onChoose = {}, onConfirm = {}, onRetry = {}, onClose = { closes++ })
        }.use {
            compose.onNodeWithText("Task: Former task").assertDoesNotExist()
            compose.onNodeWithText("Choose file").assertDoesNotExist()
            compose.onNodeWithText("Back to task").performClick()
            assertEquals(1, closes)
        }
    }
}
