package com.whumpo.android.ui.share

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.whumpo.android.ui.projects.ProjectList
import com.whumpo.android.ui.state.UiState
import com.whumpo.android.ui.theme.WhumpoPrimaryButton
import com.whumpo.android.ui.theme.WhumpoQuietButton

/**
 * Asks what to do with a shared item. Nothing is sent, uploaded or fetched until the owner confirms here: a photo is
 * added to the project they pick, text lands in the Chat draft (still unsent). The decision lives in [ShareFlow].
 *
 * [protect] runs inside the dialog window; the Android app uses it to refuse touches while another window covers the dialog
 * (tap-jacking). The photo preview is made on this phone from the shared file and held in memory only; nothing leaves the
 * phone, and nothing is stored, before the owner confirms.
 */
@Composable
fun ShareDialog(
    flow: ShareFlow,
    projects: UiState<ProjectList>,
    preview: PreviewState,
    onLoadMore: () -> Unit,
    onRetryProjects: () -> Unit,
    onDismiss: () -> Unit,
    protect: @Composable () -> Unit = {},
) {
    val item = flow.item
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("share-dialog"),
        title = { Text(if (item is SharedItem.Photo) "Add this photo to a project?" else "Add this to your Chat draft?") },
        text = {
            protect()
            // Scrolls as a whole, so a long text, many projects or the largest font size never push the buttons off screen.
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (item) {
                    is SharedItem.Text -> {
                        Text(item.text, modifier = Modifier.testTag("share-text"))
                        Text(
                            "It goes into your draft only. Nothing is sent until you send it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    is SharedItem.Photo -> {
                        PhotoPreview(preview)
                        ProjectChoice(projects, flow, onLoadMore, onRetryProjects)
                    }
                }
            }
        },
        confirmButton = {
            WhumpoPrimaryButton(
                onClick = { flow.confirm() },
                enabled = flow.canConfirm,
                modifier = Modifier.heightIn(min = 48.dp).testTag("share-confirm"),
            ) { Text(if (item is SharedItem.Photo) "Add photo" else "Add to draft") }
        },
        dismissButton = {
            WhumpoQuietButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp).testTag("share-cancel")) { Text("Cancel") }
        },
    )
}

/** A fixed-height frame, so the dialog does not jump when the thumbnail arrives or fails. */
@Composable
private fun PhotoPreview(preview: PreviewState) {
    Box(
        Modifier.fillMaxWidth().height(PREVIEW_HEIGHT).clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant).testTag("share-preview"),
        contentAlignment = Alignment.Center,
    ) {
        when (preview) {
            is PreviewState.Ready -> Image(
                bitmap = preview.image,
                contentDescription = "Preview of the photo you're sharing.",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().testTag("share-preview-image"),
            )
            PreviewState.Unavailable -> Text(
                "Preview unavailable. You can still add the photo.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(12.dp).testTag("share-preview-unavailable"),
            )
            else -> Text(
                "Loading preview",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

private val PREVIEW_HEIGHT = 160.dp

@Composable
private fun ProjectChoice(projects: UiState<ProjectList>, flow: ShareFlow, onLoadMore: () -> Unit, onRetry: () -> Unit) {
    when (projects) {
        is UiState.Content -> Column(Modifier.selectableGroup().testTag("share-projects")) {
            Text(
                "Pick the project. This preview is made on your phone; nothing leaves it until you tap Add photo, which resizes the photo and strips its location details first.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            projects.value.rows.forEach { row ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .selectable(selected = flow.chosen == row.id, role = Role.RadioButton, onClick = { flow.choose(row.id) })
                        .testTag("share-project-${row.id}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = flow.chosen == row.id, onClick = null)
                    Text(row.name, modifier = Modifier.padding(start = 12.dp))
                }
            }
            if (projects.value.moreFailed) {
                Text("We couldn't load more projects.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (projects.value.nextCursor != null) {
                WhumpoQuietButton(
                    onClick = onLoadMore,
                    enabled = !projects.value.loadingMore,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("share-more"),
                ) { Text(if (projects.value.loadingMore) "Loading" else "Show more projects") }
            }
        }
        UiState.Loading -> Text("Loading your projects")
        UiState.Empty -> Text("You don't have a project to add this to yet. Create one in Chat first.")
        else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("We couldn't load your projects. Nothing was added.")
            WhumpoQuietButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp).testTag("share-retry")) { Text("Try again") }
        }
    }
}
