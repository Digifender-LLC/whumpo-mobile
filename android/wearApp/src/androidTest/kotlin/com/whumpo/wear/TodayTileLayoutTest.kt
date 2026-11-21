package com.whumpo.wear

import android.content.ComponentName
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.wear.protolayout.DeviceParametersBuilders
import androidx.wear.protolayout.LayoutElementBuilders.Layout
import androidx.wear.protolayout.material3.materialScope
import java.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TodayTileLayoutTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val now = System.currentTimeMillis()
    private val deviceParameters = DeviceParametersBuilders.DeviceParameters.Builder()
        .setScreenWidthDp(192)
        .setScreenHeightDp(192)
        .setScreenDensity(2f)
        .setFontScale(1.4f)
        .setDevicePlatform(DeviceParametersBuilders.DEVICE_PLATFORM_WEAR_OS)
        .setScreenShape(DeviceParametersBuilders.SCREEN_SHAPE_ROUND)
        .build()

    @Test
    fun freshCountsBuildThreeAccessibleRowsAndNativeLaunchTarget() {
        val counts = TodayCounts(TodayCount(2), TodayCount(1), TodayCount(10, truncated = true))
        val state = todayTileState(TodayTileSnapshot(Instant.ofEpochMilli(now), counts), now)
        val serialized = serialize(state)

        assertTrue(serialized, serialized.contains("Approvals: 2"))
        assertTrue(serialized, serialized.contains("Blocked: 1"))
        assertTrue(serialized, serialized.contains("Deadlines: 10+"))
        assertTrue(serialized, serialized.contains("Approvals 2. Blocked 1. Deadlines 10+."))
        assertTrue(serialized, serialized.contains(ComponentName(context.packageName, TodayActivity::class.java.name).className))
        assertFalse(serialized, serialized.contains("Approvals 2\\nBlocked 1\\nDeadlines 10+"))
    }

    @Test
    fun emptyAndExpiredStatesKeepReadableMessageAndNativeLaunchTarget() {
        val empty = TodayCounts(TodayCount(0), TodayCount(0), TodayCount(0))
        val emptyLayout = serialize(todayTileState(TodayTileSnapshot(Instant.ofEpochMilli(now), empty), now))
        assertTrue(emptyLayout, emptyLayout.contains("All clear today"))
        assertTrue(emptyLayout, emptyLayout.contains("No approvals, blocked tasks, or deadlines today"))

        val old = TodayTileSnapshot(
            Instant.ofEpochMilli(now - MAX_AGE_MILLIS - 1),
            TodayCounts(TodayCount(2), TodayCount(1), TodayCount(3)),
        )
        val expiredLayout = serialize(todayTileState(old, now))
        assertTrue(expiredLayout, expiredLayout.contains("Today is unavailable"))
        assertFalse(expiredLayout, expiredLayout.contains("Approvals: 2"))
        assertTrue(expiredLayout, expiredLayout.contains(TodayActivity::class.java.name))
    }

    private fun serialize(state: TodayTileState): String {
        val root = materialScope(context, deviceParameters) {
            todayTileRoot(context, state)
        }
        val layout = Layout.Builder().setRoot(root).build()
        return layout.toString()
    }
}
