package com.whumpo.wear

import android.content.ComponentName
import android.content.Context
import android.text.format.DateFormat
import androidx.wear.protolayout.ActionBuilders.launchAction
import androidx.wear.protolayout.LayoutElementBuilders.Layout
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.TimelineBuilders.TimeInterval
import androidx.wear.protolayout.TimelineBuilders.Timeline
import androidx.wear.protolayout.TimelineBuilders.TimelineEntry
import androidx.wear.protolayout.material3.DataCardStyle.Companion.defaultDataCardStyle
import androidx.wear.protolayout.material3.MaterialScope
import androidx.wear.protolayout.material3.Typography.BODY_MEDIUM
import androidx.wear.protolayout.material3.Typography.BODY_SMALL
import androidx.wear.protolayout.material3.primaryLayout
import androidx.wear.protolayout.material3.text
import androidx.wear.protolayout.material3.textDataCard
import androidx.wear.protolayout.modifiers.LayoutModifier
import androidx.wear.protolayout.modifiers.clickable
import androidx.wear.protolayout.modifiers.contentDescription
import androidx.wear.protolayout.types.LayoutString
import androidx.wear.tiles.Material3TileService
import androidx.wear.tiles.RequestBuilders.TileRequest
import androidx.wear.tiles.TileBuilders.Tile
import androidx.wear.tiles.tile
import java.util.Date

class TodayTileService : Material3TileService() {
    override suspend fun MaterialScope.tileResponse(requestParams: TileRequest): Tile {
        TodayTileSource.refresh(this@TodayTileService)
        val snapshot = TodayTileSource.snapshot(this@TodayTileService)
        val state = todayTileState(snapshot, System.currentTimeMillis())
        return tile(timeline = todayTileTimeline(this@TodayTileService, state))
    }
}

internal fun MaterialScope.todayTileTimeline(context: Context, state: TodayTileState): Timeline {
    val timeline = Timeline.Builder()
        .addTimelineEntry(todayTileEntry(context, TodayTileState.Unavailable))
    if (state is TodayTileState.Available) {
        timeline.addTimelineEntry(todayTileEntry(context, state))
    }
    return timeline.build()
}

private fun MaterialScope.todayTileEntry(context: Context, state: TodayTileState): TimelineEntry {
    val root = todayTileRoot(context, state)
    val builder = TimelineEntry.Builder().setLayout(Layout.Builder().setRoot(root).build())
    if (state is TodayTileState.Available) {
        builder.setValidity(TimeInterval.Builder()
            .setStartMillis(state.generatedAt.toEpochMilli())
            .setEndMillis(state.expiresAtMillis)
            .build())
    }
    return builder.build()
}

internal fun MaterialScope.todayTileRoot(context: Context, state: TodayTileState): LayoutElement {
    val openToday = clickable(
        action = launchAction(ComponentName(context.packageName, TodayActivity::class.java.name)),
    )
    val bottomSlot: (MaterialScope.() -> LayoutElement)? = when (state) {
        TodayTileState.Unavailable -> null
        is TodayTileState.Available -> {
            {
                val time = DateFormat.getTimeFormat(context).format(Date.from(state.generatedAt))
                text(
                    LayoutString(context.getString(R.string.today_updated, time)),
                    modifier = LayoutModifier.contentDescription(context.getString(R.string.today_updated_accessibility, time)),
                    typography = BODY_SMALL,
                )
            }
        }
    }
    val root = primaryLayout(
        titleSlot = { text(LayoutString(context.getString(R.string.tile_name))) },
        mainSlot = {
            when (state) {
                TodayTileState.Unavailable -> text(
                    LayoutString(context.getString(R.string.today_unavailable)),
                    typography = BODY_MEDIUM,
                )
                is TodayTileState.Available -> {
                    if (state.counts.empty) {
                        text(
                            LayoutString(context.getString(R.string.today_empty)),
                            modifier = LayoutModifier.contentDescription(context.getString(R.string.today_empty_accessibility)),
                            typography = BODY_MEDIUM,
                        )
                    } else {
                        val countsDescription = context.getString(
                            R.string.today_counts_accessibility,
                            state.counts.approvals.display(),
                            state.counts.blocked.display(),
                            state.counts.deadlines.display(),
                        )
                        textDataCard(
                            onClick = openToday,
                            modifier = LayoutModifier.contentDescription(countsDescription),
                            style = defaultDataCardStyle(),
                            title = {
                                text(LayoutString(context.getString(R.string.today_blocked_count, state.counts.blocked.display())))
                            },
                            content = {
                                text(LayoutString(context.getString(R.string.today_deadlines_count, state.counts.deadlines.display())))
                            },
                            secondaryText = {
                                text(LayoutString(context.getString(R.string.today_approvals_count, state.counts.approvals.display())))
                            },
                        )
                    }
                }
            }
        },
        bottomSlot = bottomSlot,
        onClick = openToday,
    )
    return root
}
