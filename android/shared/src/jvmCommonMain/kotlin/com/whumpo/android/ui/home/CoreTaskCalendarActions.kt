package com.whumpo.android.ui.home

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.whumpo.android.ui.theme.WhumpoQuietButton
import com.whumpo.core.auth.CoreTaskCalendarReview
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun CoreTaskCalendarActions(
    review: CoreTaskCalendarReview?,
    busy: Boolean,
    notice: String?,
    onReview: () -> Unit,
    onOpen: () -> Unit,
    onCancel: () -> Unit,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    val control = Modifier.heightIn(min = 48.dp)
    if (review == null) {
        WhumpoQuietButton(onReview, enabled = !busy, modifier = control) { Text("Review calendar event") }
    } else {
        val dateTime = calendarReviewTime.withZone(zone)
        Text(review.title)
        Text("Starts: ${dateTime.format(Instant.ofEpochMilli(review.startMillis))}")
        Text("Ends: ${dateTime.format(Instant.ofEpochMilli(review.endMillis))}")
        Text("You can edit, save, or cancel in your calendar app.")
        WhumpoQuietButton(onOpen, enabled = !busy, modifier = control) { Text("Open calendar editor") }
        WhumpoQuietButton(onCancel, enabled = !busy, modifier = control) { Text("Cancel calendar review") }
    }
    notice?.let { Text(it, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
}

private val calendarReviewTime = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSS xxx", Locale.ROOT)
