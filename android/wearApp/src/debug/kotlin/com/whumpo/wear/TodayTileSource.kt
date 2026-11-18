package com.whumpo.wear

import java.time.Instant

internal object TodayTileSource {
    private val fixture: TodayTileSnapshot? by lazy {
        val now = Instant.ofEpochMilli(System.currentTimeMillis())
        val empty = TodayCounts(TodayCount(0), TodayCount(0), TodayCount(0))
        when (BuildConfig.TILE_FIXTURE) {
            "fresh" -> TodayTileSnapshot(now, TodayCounts(TodayCount(2), TodayCount(1), TodayCount(10, truncated = true)))
            "empty" -> TodayTileSnapshot(now, empty)
            "expired" -> TodayTileSnapshot(now.minusMillis(MAX_AGE_MILLIS), empty)
            else -> null
        }
    }

    fun snapshot(): TodayTileSnapshot? = fixture
}
