package com.whumpo.android.ui.photos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.whumpo.android.ui.common.AppIcons
import com.whumpo.android.ui.common.AppLinks
import com.whumpo.android.ui.common.InfoRow
import com.whumpo.android.ui.common.SectionHeading
import com.whumpo.android.ui.common.StateHost
import com.whumpo.android.ui.common.StatusBadge
import com.whumpo.android.ui.projects.PhotosState
import com.whumpo.android.ui.projects.UploadPhase
import com.whumpo.android.ui.state.ErrorCard
import com.whumpo.android.ui.theme.WhumpoPrimaryButton
import com.whumpo.android.ui.theme.WhumpoQuietButton

/**
 * A project's photos: add one through the system photo picker (no storage permission), watch uploads, see each photo's status.
 * A photo is only usable once it is ready and approved; "Checking" and "Not approved" are shown as such.
 */
@Composable
fun PhotosSection(
    state: PhotosState,
    onPick: (() -> Unit)?,
    onRefresh: () -> Unit,
    onRetryUpload: (String) -> Unit,
    onDismissUpload: (String) -> Unit,
    links: AppLinks,
    modifier: Modifier = Modifier,
) {
    Column(modifier.testTag("photos"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeading("Photos")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onPick != null) {
                WhumpoPrimaryButton(onClick = onPick, modifier = Modifier.heightIn(min = 48.dp).testTag("photo-add")) {
                    Icon(AppIcons.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Add photo")
                }
            }
            WhumpoQuietButton(onClick = onRefresh, modifier = Modifier.heightIn(min = 48.dp)) { Text("Check status") }
        }
        state.uploads.forEach { upload ->
            when (upload.phase) {
                UploadPhase.Preparing, UploadPhase.Uploading -> {
                    val what = if (upload.phase == UploadPhase.Preparing) "Preparing photo" else "Uploading photo"
                    InfoRow(
                        what, "Keep this screen open. This can take a moment.", "Keep this screen open. This can take a moment.",
                        AppIcons.Clock, Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("upload-${upload.id}"),
                    )
                }
                UploadPhase.Failed -> ErrorCard(
                    what = upload.message ?: "The photo wasn't added.",
                    safe = if (upload.canRetry) "Try again to finish. It won't be added twice." else "Nothing was added.",
                    onRetry = { if (upload.canRetry) onRetryUpload(upload.id) else onDismissUpload(upload.id) },
                    modifier = Modifier.testTag("upload-${upload.id}"),
                    retryLabel = if (upload.canRetry) "Try again" else "Dismiss",
                )
            }
        }
        StateHost(
            state = state.photos,
            loading = "Loading photos",
            emptyMessage = "No photos yet. Add one to use it on your site.",
            emptyAction = "Add photo",
            onEmptyAction = onPick ?: {},
            onRetry = onRefresh,
            links = links,
            skeletonRows = 2,
        ) { photos ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                photos.forEach { p ->
                    InfoRow(
                        title = p.name,
                        subtitle = p.summary,
                        subtitleDescription = "${p.summary}, ${p.status.label}",
                        icon = AppIcons.Photo,
                        modifier = Modifier.testTag("photo-${p.id}"),
                        trailing = { StatusBadge(p.status) },
                    )
                }
            }
        }
        Text(
            "Photos are resized and stripped of location and camera details before they leave this phone.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
