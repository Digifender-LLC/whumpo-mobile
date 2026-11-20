package com.whumpo.android.ui.security

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.whumpo.android.ui.theme.WhumpoQuietButton
import com.whumpo.core.api.SecurityNoticeDelivery
import com.whumpo.core.api.SecurityNoticePage
import java.time.Instant

/** Plain account-only content; membership selection remains owned by the parent. */
@Composable
fun CoreAccountNoticesContent(page: SecurityNoticePage?, busy: Boolean, refresh: () -> Unit, more: () -> Unit, hide: () -> Unit) {
    Column {
        Text("Account security notices", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        WhumpoQuietButton(refresh, enabled = !busy) { Text(if (page == null) "Show security notices" else "Refresh security notices") }
        if (page != null) {
            if (page.items.isEmpty()) Text("No account security notices.")
            page.items.forEach { notice ->
                Text("Security event · ${Instant.ofEpochMilli(notice.createdAt)}")
                Text(when (notice.delivery) {
                    SecurityNoticeDelivery.Sent -> "External notification sent"
                    SecurityNoticeDelivery.Unknown -> "External delivery is unconfirmed"
                    SecurityNoticeDelivery.NotConfigured -> "External notifications are not configured"
                })
            }
            if (page.nextCursor != null) WhumpoQuietButton(more, enabled = !busy) { Text("More security notices") }
            WhumpoQuietButton(hide) { Text("Hide security notices") }
        }
    }
}
