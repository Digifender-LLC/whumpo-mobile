package com.whumpo.android.ui.work

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.whumpo.android.ui.theme.WhumpoPrimaryButton
import com.whumpo.android.ui.theme.WhumpoQuietButton
import com.whumpo.core.api.CoreWorkTask

sealed interface WorkLinkResult {
    data object EnterLink : WorkLinkResult
    data object Checking : WorkLinkResult
    data object Unavailable : WorkLinkResult
    data class Open(val task: CoreWorkTask) : WorkLinkResult
}

@Composable
fun WorkLinkContent(
    organizationName: String,
    link: String,
    result: WorkLinkResult,
    onLinkChange: (String) -> Unit,
    onOpen: () -> Unit,
    onBack: () -> Unit,
    onSignOut: () -> Unit,
) {
    val actionSize = Modifier.fillMaxWidth().heightIn(min = 48.dp)
    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        WhumpoQuietButton(onClick = onBack, modifier = actionSize) { Text("Back to Work") }
        Text("Open Work link", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        Text(organizationName, style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(link, onLinkChange, modifier = Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("Work link") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        WhumpoPrimaryButton(onClick = onOpen, enabled = link.isNotEmpty() && result != WorkLinkResult.Checking,
            modifier = actionSize) { Text("Open link in this organization") }
        when (result) {
            WorkLinkResult.EnterLink -> Unit
            WorkLinkResult.Checking -> WorkLinkStatus("Checking Work link…")
            WorkLinkResult.Unavailable -> WorkLinkStatus("This Work link is unavailable.")
            is WorkLinkResult.Open -> {
                val task = result.task
                Text(task.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics {
                    heading()
                    liveRegion = LiveRegionMode.Polite
                })
                Text(task.status.label)
                task.description?.let { Text(it) }
                task.dueAt?.let { Text("Due $it") }
                if (task.archivedAt != null) Text("Archived")
            }
        }
        WhumpoQuietButton(onClick = onSignOut, modifier = actionSize) { Text("Sign out") }
    }
}

@Composable
private fun WorkLinkStatus(label: String) =
    Text(label, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
