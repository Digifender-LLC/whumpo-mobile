package com.whumpo.wear

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TodayTileStateTest {
    private val now = 1_800_000_000_000L
    private val empty = TodayCounts(TodayCount(0), TodayCount(0), TodayCount(0))

    @Test
    fun missingExpiredFutureAndInvalidSnapshotsStayUnavailable() {
        assertEquals(TodayTileState.Unavailable, todayTileState(null, now))
        for (timestamp in listOf(now - MAX_AGE_MILLIS, now - MAX_AGE_MILLIS - 1, now + 1)) {
            assertEquals(TodayTileState.Unavailable, todayTileState(TodayTileSnapshot(Instant.ofEpochMilli(timestamp), empty), now))
        }
        for (invalid in listOf(TodayCount(-1), TodayCount(11), TodayCount(2, truncated = true))) {
            val snapshot = TodayTileSnapshot(Instant.ofEpochMilli(now), empty.copy(approvals = invalid))
            assertEquals(TodayTileState.Unavailable, todayTileState(snapshot, now))
        }
    }

    @Test
    fun freshEmptyAndCappedCountsRemainDistinctUntilExactExpiry() {
        val generated = Instant.ofEpochMilli(now - MAX_AGE_MILLIS + 1)
        val snapshot = TodayTileSnapshot(generated, empty)
        val state = assertIs<TodayTileState.Available>(todayTileState(snapshot, now))
        assertTrue(state.counts.empty)
        assertEquals(generated, state.generatedAt)
        assertEquals(now + 1, state.expiresAtMillis)
        assertEquals(TodayTileState.Unavailable, todayTileState(snapshot, now + 1))

        val filled = empty.copy(deadlines = TodayCount(TODAY_SECTION_LIMIT, truncated = true))
        assertFalse(filled.empty)
        assertEquals("10+", assertIs<TodayTileState.Available>(
            todayTileState(snapshot.copy(counts = filled), now),
        ).counts.deadlines.display())
        assertEquals("10", TodayCount(TODAY_SECTION_LIMIT).display())
    }

    @Test
    fun verifiedWireExpiryControlsRecoveryEvenWhenBriefingTimestampIsNewer() {
        val snapshot = TodayTileSnapshot(
            generatedAt = Instant.ofEpochMilli(now - 1_000),
            counts = empty.copy(approvals = TodayCount(3)),
            expiresAtMillis = now + 1,
        )

        val fresh = assertIs<TodayTileState.Available>(todayTileState(snapshot, now))
        assertEquals(now + 1, fresh.expiresAtMillis)
        assertEquals(TodayTileState.Unavailable, todayTileState(snapshot, now + 1))
    }
}
