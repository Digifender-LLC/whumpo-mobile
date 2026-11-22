package com.whumpo.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.whumpo.android.wear.coreWearTodayPutDataRequest
import com.whumpo.core.wear.CoreWearTodaySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CoreWearTodayTransportTest {
    @Test
    fun nativePutDataRequestBytesAreTheReceiverJsonPayload() {
        val snapshot = CoreWearTodaySnapshot(
            generation = 3,
            generatedAtMillis = 1_800_000_000_000L,
            publishedAtMillis = 1_800_000_000_100L,
            expiresAtMillis = 1_800_000_000_000L + CoreWearTodaySnapshot.MAX_AGE_MILLIS,
            available = true,
            approvals = 2,
            blocked = 1,
            deadlines = 4,
        )
        val request = coreWearTodayPutDataRequest(snapshot)

        assertEquals(CoreWearTodaySnapshot.DATA_PATH, request.uri.path)
        assertTrue(request.isUrgent)
        assertEquals(snapshot, CoreWearTodaySnapshot.decode(requireNotNull(request.data)))
    }
}
