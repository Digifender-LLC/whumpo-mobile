package com.whumpo.wear

import android.text.format.DateFormat
import androidx.wear.protolayout.LayoutElementBuilders.Column
import androidx.wear.protolayout.LayoutElementBuilders.Layout
import androidx.wear.protolayout.TimelineBuilders.TimeInterval
import androidx.wear.protolayout.TimelineBuilders.Timeline
import androidx.wear.protolayout.TimelineBuilders.TimelineEntry
import androidx.wear.protolayout.material3.MaterialScope
import androidx.wear.protolayout.material3.Typography.BODY_MEDIUM
import androidx.wear.protolayout.material3.primaryLayout
import androidx.wear.protolayout.material3.text
import androidx.wear.protolayout.types.LayoutString
import androidx.wear.tiles.Material3TileService
import androidx.wear.tiles.RequestBuilders.TileRequest
import androidx.wear.tiles.TileBuilders.Tile
import androidx.wear.tiles.tile
import java.util.Date

class TodayTileService : Material3TileService() {
    override suspend fun MaterialScope.tileResponse(requestParams: TileRequest): Tile {
        val snapshot = TodayTileSource.snapshot()
        val state = todayTileState(snapshot, System.currentTimeMillis())
        val timeline = Timeline.Builder().addTimelineEntry(TimelineEntry.Builder()
            .setLayout(Layout.Builder().setRoot(primaryLayout(
                titleSlot = { text(LayoutString(getString(R.string.tile_name))) },
                mainSlot = { text(LayoutString(getString(R.string.today_unavailable)), typography = BODY_MEDIUM) },
            )).build()).build())
        if (state is TodayTileState.Available) {
            val counts = state.counts
            val summary = if (counts.empty) getString(R.string.today_empty) else getString(
                R.string.today_counts, counts.approvals.display(), counts.blocked.display(), counts.deadlines.display(),
            )
            val time = DateFormat.getTimeFormat(this@TodayTileService).format(Date.from(state.generatedAt))
            val visible = primaryLayout(
                titleSlot = { text(LayoutString(getString(R.string.tile_name))) },
                mainSlot = { Column.Builder()
                    .addContent(text(LayoutString(summary), typography = BODY_MEDIUM, maxLines = 3))
                    .addContent(text(LayoutString(getString(R.string.today_updated, time))))
                    .build()
                },
            )
            timeline.addTimelineEntry(TimelineEntry.Builder()
                .setValidity(TimeInterval.Builder()
                    .setStartMillis(state.generatedAt.toEpochMilli()).setEndMillis(state.expiresAtMillis).build())
                .setLayout(Layout.Builder().setRoot(visible).build())
                .build())
        }
        return tile(timeline = timeline.build())
    }
}
