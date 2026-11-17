package com.whumpo.android.ui.work

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.whumpo.android.ui.theme.WhumpoQuietButton
import com.whumpo.core.api.CoreInboxCategory
import com.whumpo.core.api.CoreInboxDetail
import com.whumpo.core.api.CoreInboxItem
import com.whumpo.core.api.CoreInboxPresentation
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun CoreInboxContent(
    organizationName: String,
    items: List<CoreInboxItem>?,
    hasMore: Boolean,
    detail: CoreInboxDetail?,
    busy: Boolean,
    notice: String?,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onMore: () -> Unit,
    onOpen: (CoreInboxItem) -> Unit,
    onAcknowledge: (CoreInboxItem) -> Unit,
) {
    key(detail) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (detail == null) "Back" else "Back to task notifications")
            }
            Text(organizationName, style = MaterialTheme.typography.labelLarge)
            val heading = when (detail) {
                null -> "Task notifications"
                is CoreInboxDetail.Approval -> "Task approval"
                is CoreInboxDetail.ConfirmedResult -> "Verified task result"
            }
            Text(heading, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
            if (notice != null || busy) {
                Text(notice ?: "Checking task notifications…", modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            when (detail) {
                is CoreInboxDetail.Approval -> {
                    Text(detail.operation, style = MaterialTheme.typography.titleLarge)
                    Text("Waiting for approval")
                    Text("Expires ${inboxTime(detail.expiresAt)}")
                    if (detail.needsSignIn) Text("Sign in again before making a decision.")
                }
                is CoreInboxDetail.ConfirmedResult -> {
                    Text("Confirmed ${inboxTime(detail.verifiedAt)}")
                    Text(if (detail.taskCount == 1L) "1 task verified" else "${detail.taskCount} tasks verified")
                }
                null -> {
                    WhumpoQuietButton(onClick = onRefresh, modifier = Modifier.heightIn(min = 48.dp), enabled = !busy) {
                        Text("Refresh notifications")
                    }
                    if (items?.isEmpty() == true) Text("No task notifications on this page.")
                    items?.forEach { item ->
                        key(item.id) { InboxRow(item, busy, { onOpen(item) }, { onAcknowledge(item) }) }
                    }
                    if (hasMore) WhumpoQuietButton(onClick = onMore, modifier = Modifier.heightIn(min = 48.dp), enabled = !busy) {
                        Text("More notifications")
                    }
                }
            }
        }
    }
}

@Composable
private fun InboxRow(item: CoreInboxItem, busy: Boolean, open: () -> Unit, acknowledge: () -> Unit) {
    val label = when (item.category) {
        CoreInboxCategory.Approval -> "Task approval"
        CoreInboxCategory.ConfirmedResult -> "Verified task result"
    }
    val observed = inboxTime(item.observedAt)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Text(observed)
            when (item.presentation) {
                CoreInboxPresentation.Eligible -> Unit
                CoreInboxPresentation.CategoryDisabled -> Text("Delivery disabled")
                CoreInboxPresentation.QuietHours -> Text("Delivery muted during quiet hours")
            }
            WhumpoQuietButton(onClick = open, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (item.category == CoreInboxCategory.Approval) "Open approval" else "Open verified result")
            }
            if (item.acknowledgedAt == null) {
                TextButton(onClick = acknowledge, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Acknowledge notification")
                }
            } else {
                Text("Acknowledged ${inboxTime(item.acknowledgedAt)}")
            }
        }
    }
}

private val inboxTimeFormat = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
private fun inboxTime(value: String): String = Instant.parse(value).atZone(ZoneId.systemDefault()).format(inboxTimeFormat)
