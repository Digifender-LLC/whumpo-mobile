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
import com.whumpo.android.ui.ownership.CoreOwnerInvitationsActions
import com.whumpo.android.ui.ownership.CoreOwnerInvitationsContent
import com.whumpo.core.api.CoreEntitlementStatus
import com.whumpo.core.api.CoreMembershipMode
import com.whumpo.core.api.CoreOrganizationEntitlements
import com.whumpo.core.api.CoreRole
import com.whumpo.core.auth.CoreOwnerInvitationState
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CoreOwnerInvitationsContentTest : RobolectricUiTest() {
    private val seats = CoreOrganizationEntitlements("org-1", CoreEntitlementStatus.active, CoreMembershipMode.business_team,
        4, 1, 1, 2, true, false, 1, 1, null)

    @Test
    fun narrowLargeTextIssueAndClipboardRequireExplicitActionsWithoutHistory() {
        val issued = mutableListOf<CoreRole>()
        var history = 0
        var copies = 0
        var state by mutableStateOf(CoreOwnerInvitationState(seats = seats))
        launch(Screen.Compact, dark = true, fontScale = 2f) {
            CoreOwnerInvitationsContent(state, CoreOwnerInvitationsActions(
                refreshSeats = {}, history = { history++ }, issue = { issued.add(it) }, revoke = {},
                retry = {}, copy = { copies++ }, clearCode = {}, close = {},
            ))
        }.use {
            compose.onNodeWithText("Available seats: 2").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Issue invitation").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithText("Copy invitation code").assertDoesNotExist()
            compose.onNodeWithText("Choose Viewer").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
            compose.onNodeWithText("Issue invitation").performScrollTo().performClick()
            assertEquals(listOf(CoreRole.viewer), issued)
            assertEquals(0, history)
            assertEquals(0, copies)
            compose.runOnIdle { state = state.copy(codeExpiresAt = 123) }
            compose.onNodeWithText("Copy puts the code on the system clipboard.", substring = true)
                .performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Copy invitation code").performScrollTo().performClick()
            assertEquals(1, copies)
            compose.runOnIdle { state = state.copy(codeExpiresAt = null, canRetry = true) }
            compose.onNodeWithText("Copy invitation code").assertDoesNotExist()
            compose.onNodeWithText("Issue invitation").assertDoesNotExist()
            compose.onNodeWithText("Retry original request").performScrollTo().assertIsDisplayed()
        }
    }
}
