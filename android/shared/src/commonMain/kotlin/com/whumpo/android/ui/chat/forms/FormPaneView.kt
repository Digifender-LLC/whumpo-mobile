package com.whumpo.android.ui.chat.forms

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.whumpo.android.ui.common.readable
import com.whumpo.android.ui.theme.WhumpoQuietButton

/** The open form: its screen once read, otherwise a loading or failed state with Close (and Try loading again). */
@Composable
fun FormPaneView(pane: FormPane, zones: ZoneTime, actions: FormActions, modifier: Modifier = Modifier) {
    if (pane is FormPane.Shown) {
        FormScreen(pane.screen, zones, actions, modifier)
        return
    }
    val failed = pane is FormPane.Failed
    Column(
        modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag(if (failed) "form-failed" else "form-loading"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.readable().padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Form",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            WhumpoQuietButton(actions::onClose, Modifier.heightIn(min = 48.dp).testTag("form-close")) { Text("Close") }
        }
        Column(Modifier.readable().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (!failed) CircularProgressIndicator()
            Text(
                if (failed) "We couldn't load this form. Nothing was sent." else "Loading the form",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (failed) {
                WhumpoQuietButton(actions::onReload, Modifier.heightIn(min = 48.dp).testTag("form-reload")) { Text("Try loading again") }
            }
        }
    }
}
