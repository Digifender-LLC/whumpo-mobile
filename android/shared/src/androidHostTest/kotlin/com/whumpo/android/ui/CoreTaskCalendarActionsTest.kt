package com.whumpo.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.whumpo.android.ui.home.CoreTaskCalendarActions
import com.whumpo.core.api.CoreTaskStatus
import com.whumpo.core.api.CoreWorkTask
import com.whumpo.core.auth.CoreTaskCalendarReview
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CoreTaskCalendarActionsTest : RobolectricUiTest() {
    @Test
    fun reviewAndCancelDoNotOpenEditorAndBusyReviewDisablesConfirmation() {
        val draft = checkNotNull(CoreTaskCalendarReview.from(CoreWorkTask("task-a", "project-a",
            "Review deadline", null, null, "2026-11-18T14:30:00.000Z", CoreTaskStatus.Ready, 6L)))
        var review by mutableStateOf<CoreTaskCalendarReview?>(null)
        var busy by mutableStateOf(false)
        var opened = 0
        launch(Screen.Compact, fontScale = 2f) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                CoreTaskCalendarActions(review, busy, null,
                    onReview = { review = draft }, onOpen = { opened++ }, onCancel = { review = null })
            }
        }.use {
            compose.onNodeWithText("Open calendar editor").assertDoesNotExist()
            compose.onNodeWithText("Review calendar event").assertHeightIsAtLeast(48.dp).performClick()
            assertEquals(0, opened)
            compose.runOnIdle { busy = true }
            compose.onNodeWithText("Open calendar editor").performScrollTo().assertIsNotEnabled()
            compose.runOnIdle { busy = false }
            compose.onNodeWithText("Cancel calendar review").performScrollTo().performClick()
            assertEquals(0, opened)
            compose.onNodeWithText("Review calendar event").performScrollTo().performClick()
            compose.onNodeWithText("Open calendar editor").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
            assertEquals(1, opened)
        }
    }
}
