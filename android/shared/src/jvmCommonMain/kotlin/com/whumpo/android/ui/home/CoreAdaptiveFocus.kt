package com.whumpo.android.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.whumpo.android.ui.theme.WhumpoQuietButton
import com.whumpo.core.api.CoreAdaptiveCategory
import com.whumpo.core.auth.CoreAdaptiveHomeState
import com.whumpo.core.auth.CoreAdaptiveNotice
import com.whumpo.core.auth.CoreAdaptiveReview

@Composable
fun CoreAdaptiveFocus(
    state: CoreAdaptiveHomeState,
    open: (CoreAdaptiveCategory) -> Unit,
    save: (CoreAdaptiveReview, Boolean, List<CoreAdaptiveCategory>) -> Unit,
    reset: (CoreAdaptiveReview) -> Unit,
    retry: () -> Unit,
) {
    val review = state.review
    var settings by remember(review) { mutableStateOf(false) }
    var resetRequested by remember(review) { mutableStateOf(false) }
    val control = Modifier.heightIn(min = 48.dp)
    Text("Your focus", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
    state.shortcuts.forEachIndexed { index, shortcut ->
        key(index) {
            if (shortcut == null) Spacer(control)
            else WhumpoQuietButton({ open(shortcut) }, modifier = control) { Text(shortcut.label()) }
        }
    }
    if (review != null) WhumpoQuietButton({ settings = !settings; resetRequested = false }, modifier = control) {
        Text(if (settings) "Close focus settings" else "Focus settings")
    }
    state.notice?.let {
        Text(when (it) {
            CoreAdaptiveNotice.Unavailable -> "Focus is temporarily unavailable."
            CoreAdaptiveNotice.Retry -> "The focus change could not be confirmed."
            CoreAdaptiveNotice.Changed -> "Focus changed elsewhere. Review your current settings."
        }, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (it == CoreAdaptiveNotice.Retry) WhumpoQuietButton(retry, modifier = control) { Text("Retry focus change") }
    }
    if (settings && review != null) {
        var enabled by remember(state.learningEnabled) { mutableStateOf(state.learningEnabled) }
        var pins by remember(state.pins) { mutableStateOf(state.pins) }
        Text("Learn from navigation")
        Switch(enabled, { enabled = it }, enabled = !state.saving,
            modifier = Modifier.semantics { contentDescription = "Learn from navigation" })
        Column {
            for (category in CoreAdaptiveCategory.entries) {
                val index = pins.indexOf(category)
                WhumpoQuietButton({
                    pins = if (index >= 0) pins - category else pins + category
                }, enabled = !state.saving && (index >= 0 || pins.size < 2), modifier = control) {
                    Text(if (index >= 0) "Unpin ${category.label()} · ${index + 1}" else "Pin ${category.label()}")
                }
            }
        }
        WhumpoQuietButton({ save(review, enabled, pins) }, enabled = !state.saving, modifier = control) { Text("Save focus settings") }
        if (resetRequested) {
            Text("Clear learned navigation and pinned shortcuts?")
            WhumpoQuietButton({ resetRequested = false; reset(review) }, enabled = !state.saving, modifier = control) {
                Text("Confirm reset focus")
            }
            WhumpoQuietButton({ resetRequested = false }, modifier = control) { Text("Keep focus history") }
        } else WhumpoQuietButton({ resetRequested = true }, enabled = !state.saving, modifier = control) {
            Text("Reset focus history")
        }
    }
}

private fun CoreAdaptiveCategory.label(): String = when (this) {
    CoreAdaptiveCategory.Work -> "Work"
    CoreAdaptiveCategory.Partner -> "Partner"
    CoreAdaptiveCategory.WebsiteActivity -> "Website activity"
    CoreAdaptiveCategory.VerifiedResults -> "Verified results"
}
