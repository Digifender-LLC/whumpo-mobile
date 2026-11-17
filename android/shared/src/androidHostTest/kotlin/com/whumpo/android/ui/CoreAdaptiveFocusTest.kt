package com.whumpo.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.whumpo.android.ui.home.CoreAdaptiveFocus
import com.whumpo.android.ui.home.CoreTodayFocusPlacement
import com.whumpo.core.api.CoreAdaptiveCategory
import com.whumpo.core.api.CoreTodayDecisionAccess
import com.whumpo.core.api.CoreTodaySection
import com.whumpo.core.api.CoreTodaySiteActivity
import com.whumpo.core.api.CoreTodaySiteKind
import com.whumpo.core.api.CoreTodaySiteSource
import com.whumpo.core.auth.CoreAdaptiveHomeState
import com.whumpo.core.auth.CoreAdaptiveReview
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CoreAdaptiveFocusTest : RobolectricUiTest() {
    @Test
    fun largeTextSettingsRequireExplicitSaveAndReset() {
        val work = CoreAdaptiveCategory.Work
        val partner = CoreAdaptiveCategory.Partner
        var open: CoreAdaptiveCategory? = null
        var saved: Pair<Boolean, List<CoreAdaptiveCategory>>? = null
        var resets = 0
        val review = CoreAdaptiveReview(UUID.randomUUID(), UUID.randomUUID().toString(), 1)
        var state by mutableStateOf(CoreAdaptiveHomeState(listOf(work, partner), learningEnabled = true, review = review))
        launch(Screen.Compact, dark = true, fontScale = 2f) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                CoreAdaptiveFocus(state, { open = it }, { presented, enabled, pins ->
                    assertEquals(review, presented)
                    saved = enabled to pins
                }, { resets++ }, {})
            }
        }.use {
            assertNull(open)
            compose.onNodeWithText("Work").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
            assertEquals(work, open)
            compose.onNodeWithText("Focus settings").performScrollTo().performClick()
            compose.onNodeWithContentDescription("Learn from navigation").performScrollTo().performClick()
            compose.onNodeWithText("Pin Partner").performScrollTo().performClick()
            compose.onNodeWithText("Pin Work").performScrollTo().performClick()
            assertNull(saved)
            compose.onNodeWithText("Save focus settings").performScrollTo().performClick()
            assertEquals(false to listOf(partner, work), saved)
            compose.onNodeWithText("Reset focus history").performScrollTo().performClick()
            assertEquals(0, resets)
            compose.onNodeWithText("Confirm reset focus").performScrollTo().performClick()
            assertEquals(1, resets)
            compose.runOnIdle { state = state.copy(shortcuts = listOf(work, null)) }
            compose.onNodeWithText("Partner").assertDoesNotExist()
            compose.onNodeWithText("Reset focus history").performScrollTo().performClick()
            compose.runOnIdle { state = state.copy(review = review.copy(preferenceRevision = 2)) }
            compose.onNodeWithText("Confirm reset focus").assertDoesNotExist()
            compose.onNodeWithText("Save focus settings").assertDoesNotExist()
            assertEquals(1, resets)
        }
    }

    @Test
    fun mixedWebsiteActivityKeepsReviewAboveFocusAndReceiptsBelow() {
        val reviewed = CoreTodaySiteKind.PublicationReview
        val confirmed = CoreTodaySiteKind.WithdrawalReceipt
        fun site(kind: CoreTodaySiteKind, access: CoreTodayDecisionAccess?) = CoreTodaySiteActivity(
            "fixture", "2026-01-02T00:00:00.000Z",
            CoreTodaySiteSource(kind, kind.operation, "effect-1", "site-1", "project-1", "task-1", "build-1", 1, "0".repeat(64)),
            decisionAccess = access,
        )
        val review = site(reviewed, CoreTodayDecisionAccess.ReauthenticationRequired)
        val receipt = site(confirmed, null)
        launch(Screen.Compact) {
            Column {
                CoreTodayFocusPlacement(CoreTodaySection(listOf(receipt, review), true),
                    websiteDecisions = { section ->
                        assertEquals(listOf(review), section.items)
                        Text("Pending website review")
                    },
                    focus = { Text("Your focus") },
                    verifiedResults = { Text("Verified results") },
                    websiteReceipts = { section ->
                        assertEquals(CoreTodaySection(listOf(receipt), true), section)
                        Text("Confirmed website receipt")
                    },
                )
            }
        }.use {
            val tops = listOf("Pending website review", "Your focus", "Verified results", "Confirmed website receipt")
                .map { compose.onNodeWithText(it).fetchSemanticsNode().boundsInRoot.top }
            assertTrue(tops.zipWithNext().all { (first, second) -> first < second })
        }
    }
}
