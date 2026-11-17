package com.whumpo.android.ui.home

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.whumpo.android.ui.theme.WhumpoQuietButton
import com.whumpo.core.api.CoreSourceHistoryOrigin
import com.whumpo.core.api.CoreSourceHistoryVersion
import java.text.DateFormat
import java.util.Date

@Composable
fun CoreSourceHistoryContent(
    entries: List<CoreSourceHistoryVersion>?,
    hasOlder: Boolean,
    loading: Boolean,
    notice: String?,
    onOlder: () -> Unit,
    onLatest: () -> Unit,
) {
    val control = Modifier.heightIn(min = 48.dp)
    if (loading) {
        CircularProgressIndicator()
        return
    }
    if (entries != null) {
        if (entries.isEmpty()) Text("No saved source versions.")
        for (row in entries) {
            Text("Version ${row.version}", style = MaterialTheme.typography.titleMedium)
            Text(when (row.origin) {
                CoreSourceHistoryOrigin.Legacy -> "Original save"
                CoreSourceHistoryOrigin.Save -> "Source save"
                CoreSourceHistoryOrigin.Composition -> "Combined source"
                CoreSourceHistoryOrigin.Restore -> "Source restore"
                CoreSourceHistoryOrigin.SlotRestore -> "Partial source restore"
            })
            Text(DateFormat.getDateInstance().format(Date(row.confirmedAt)), style = MaterialTheme.typography.bodySmall)
        }
    }
    if (notice != null) Text(notice, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    if (hasOlder) WhumpoQuietButton(onOlder, modifier = control) { Text("Older versions") }
    WhumpoQuietButton(onLatest, modifier = control) { Text("Latest versions") }
}
