package com.whumpo.wear

import java.time.Instant
import com.whumpo.core.wear.CoreWearTodaySnapshot

// Core Today returns up to ten rows per section and a separate truncation bit.
internal const val TODAY_SECTION_LIMIT = 10
internal const val MAX_AGE_MILLIS = CoreWearTodaySnapshot.MAX_AGE_MILLIS

internal data class TodayCount(val count: Int, val truncated: Boolean = false) {
    val valid: Boolean
        get() = count in 0..TODAY_SECTION_LIMIT && (!truncated || count == TODAY_SECTION_LIMIT)

    fun display(): String = if (truncated) "$count+" else count.toString()
}

internal data class TodayCounts(val approvals: TodayCount, val blocked: TodayCount, val deadlines: TodayCount) {
    val valid: Boolean get() = approvals.valid && blocked.valid && deadlines.valid
    val empty: Boolean get() = approvals.count == 0 && blocked.count == 0 && deadlines.count == 0
}

/** Contains only aggregate section counts and the canonical Today generation time. */
internal data class TodayTileSnapshot(
    val generatedAt: Instant,
    val counts: TodayCounts,
    val expiresAtMillis: Long = generatedAt.toEpochMilli() + MAX_AGE_MILLIS,
)

internal sealed interface TodayTileState {
    data object Unavailable : TodayTileState
    data class Available(
        val generatedAt: Instant,
        val expiresAtMillis: Long,
        val counts: TodayCounts,
    ) : TodayTileState
}

internal fun todayTileState(snapshot: TodayTileSnapshot?, nowMillis: Long): TodayTileState {
    if (snapshot == null || !snapshot.counts.valid || nowMillis < MAX_AGE_MILLIS) return TodayTileState.Unavailable
    val earliest = Instant.ofEpochMilli(nowMillis - MAX_AGE_MILLIS)
    if (snapshot.generatedAt <= earliest || snapshot.generatedAt > Instant.ofEpochMilli(nowMillis)) {
        return TodayTileState.Unavailable
    }
    val generatedAtMillis = snapshot.generatedAt.toEpochMilli()
    if (generatedAtMillis > Long.MAX_VALUE - MAX_AGE_MILLIS ||
        snapshot.expiresAtMillis <= nowMillis || snapshot.expiresAtMillis <= generatedAtMillis ||
        snapshot.expiresAtMillis - generatedAtMillis > MAX_AGE_MILLIS
    ) return TodayTileState.Unavailable
    return TodayTileState.Available(snapshot.generatedAt, snapshot.expiresAtMillis, snapshot.counts)
}
