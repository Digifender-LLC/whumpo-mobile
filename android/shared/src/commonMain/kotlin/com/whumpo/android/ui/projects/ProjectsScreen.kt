package com.whumpo.android.ui.projects

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.whumpo.android.ui.common.AppIcons
import com.whumpo.android.ui.common.AppLinks
import com.whumpo.android.ui.common.InfoRow
import com.whumpo.android.ui.common.PageTitle
import com.whumpo.android.ui.common.ScrollingColumn
import com.whumpo.android.ui.common.SectionHeading
import com.whumpo.android.ui.common.ServiceTile
import com.whumpo.android.ui.common.StateHost
import com.whumpo.android.ui.common.StatusBadge
import com.whumpo.android.ui.photos.PhotosSection
import com.whumpo.android.ui.shell.AdaptivePanes
import com.whumpo.android.ui.shell.DetailPlaceholder
import com.whumpo.android.ui.shell.LocalNavigate
import com.whumpo.android.ui.shell.TopDestination
import com.whumpo.android.ui.shell.rememberBackProgress
import com.whumpo.android.ui.shell.WidthClass
import com.whumpo.android.ui.state.UiState
import com.whumpo.android.ui.theme.WhumpoQuietButton
import com.whumpo.android.ui.theme.WhumpoSecondaryButton
import com.whumpo.android.ui.theme.WhumpoTheme
import com.whumpo.android.ui.theme.WhumpoTokens

/** What the Projects screen can do. [onPickPhoto] is null where the platform has no photo picker. */
class ProjectsActions(
    val onRefresh: () -> Unit = {},
    val onLoadMore: () -> Unit = {},
    val onSelect: (String?) -> Unit = {},
    val onRetryDetail: () -> Unit = {},
    val onPickPhoto: (() -> Unit)? = null,
    val onRefreshPhotos: () -> Unit = {},
    val onRetryUpload: (String) -> Unit = {},
    val onDismissUpload: (String) -> Unit = {},
    val onGoToChat: () -> Unit = {},
    /** Opens Chat with a draft that mentions the project. It never sends. */
    val onAskAboutProject: (ProjectRow) -> Unit = {},
)

@Composable
fun ProjectsScreen(
    viewModel: ProjectsViewModel,
    width: WidthClass,
    links: AppLinks,
    onPickPhoto: (() -> Unit)?,
    onAskAboutProject: (ProjectRow) -> Unit = {},
) {
    val navigate = LocalNavigate.current
    val list by viewModel.list.collectAsState()
    val selected by viewModel.selected.collectAsState()
    val detail by viewModel.detail.collectAsState()
    val photos by viewModel.photos.collectAsState()
    ProjectsContent(
        list, selected, detail, photos, width, links,
        ProjectsActions(
            onRefresh = viewModel::refresh,
            onLoadMore = viewModel::loadMore,
            onSelect = viewModel::select,
            onRetryDetail = viewModel::retryDetail,
            onPickPhoto = onPickPhoto,
            onRefreshPhotos = viewModel::refreshPhotos,
            onRetryUpload = viewModel::retryUpload,
            onDismissUpload = viewModel::dismissUpload,
            onGoToChat = { navigate(TopDestination.Chat) },
            onAskAboutProject = { project ->
                onAskAboutProject(project)
                navigate(TopDestination.Chat)
            },
        ),
    )
}

/** Stateless Projects: a list, then a detail. One pane below Expanded (list, then detail with a back button), two on Expanded. */
@Composable
fun ProjectsContent(
    list: UiState<ProjectList>,
    selectedId: String?,
    detail: UiState<ProjectDetail>,
    photos: PhotosState,
    width: WidthClass,
    links: AppLinks,
    actions: ProjectsActions,
    modifier: Modifier = Modifier,
) {
    val single = width != WidthClass.Expanded
    // Inside the shell's own back handler, so back closes the detail first and only then returns to Chat.
    rememberBackProgress(enabled = single && selectedId != null) { actions.onSelect(null) }
    AdaptivePanes(
        width = width,
        modifier = modifier,
        list = {
            if (single && selectedId != null) {
                DetailPane(detail, photos, showBack = true, links, actions)
            } else {
                ListPane(list, selectedId, links, actions)
            }
        },
        detail = {
            if (selectedId != null) {
                DetailPane(detail, photos, showBack = false, links, actions)
            } else {
                DetailPlaceholder(hint = "Pick a project to see its versions and photos.")
            }
        },
    )
}

@Composable
private fun ListPane(list: UiState<ProjectList>, selectedId: String?, links: AppLinks, actions: ProjectsActions) {
    ScrollingColumn(Modifier.testTag("screen-projects")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            PageTitle("Projects", Modifier.weight(1f))
            WhumpoQuietButton(onClick = actions.onRefresh, modifier = Modifier.heightIn(min = 48.dp)) { Text("Refresh") }
        }
        StateHost(
            state = list,
            loading = "Loading projects",
            emptyMessage = "No projects yet. Tell Whumpo what you need.",
            emptyAction = "Go to Chat",
            onEmptyAction = actions.onGoToChat,
            onRetry = actions.onRefresh,
            links = links,
        ) { projects ->
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                projects.rows.forEach { ProjectCard(it, it.id == selectedId) { actions.onSelect(it.id) } }
                if (projects.moreFailed) {
                    Text(
                        "Couldn't load more projects. Nothing was changed.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (projects.nextCursor != null) {
                    WhumpoQuietButton(
                        onClick = actions.onLoadMore,
                        enabled = !projects.loadingMore,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("projects-more"),
                    ) { Text(if (projects.loadingMore) "Loading more" else "Show more projects") }
                }
            }
        }
    }
}

/** The web ServiceTile as a card: icon, name, status, and when it was last updated (the absolute time is spoken). */
@Composable
private fun ProjectCard(row: ProjectRow, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().testTag("project-${row.id}"),
        shape = RoundedCornerShape(WhumpoTokens.RadiusMdDp.dp),
        color = scheme.surfaceContainerLowest,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) scheme.primary else WhumpoTheme.colors.line),
    ) {
        Row(
            Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .heightIn(min = 48.dp)
                .clearAndSetSemantics { contentDescription = "${row.name}, ${row.status.label}. ${row.updated.description}" },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier.size(40.dp).background(scheme.primaryContainer, RoundedCornerShape(WhumpoTokens.RadiusSmDp.dp)),
                contentAlignment = Alignment.Center,
            ) { Icon(AppIcons.Project, contentDescription = null, tint = scheme.onPrimaryContainer, modifier = Modifier.size(22.dp)) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(row.name, style = MaterialTheme.typography.titleSmall, color = scheme.onSurface)
                StatusBadge(row.status)
                Text(row.updated.text, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun DetailPane(
    detail: UiState<ProjectDetail>,
    photos: PhotosState,
    showBack: Boolean,
    links: AppLinks,
    actions: ProjectsActions,
) {
    ScrollingColumn(Modifier.testTag("project-detail")) {
        if (showBack) {
            IconButton(onClick = { actions.onSelect(null) }, modifier = Modifier.size(48.dp).testTag("project-back")) {
                Icon(AppIcons.Back, contentDescription = "Back to projects")
            }
        }
        StateHost(
            state = detail,
            loading = "Loading project",
            emptyMessage = "This project isn't available.",
            emptyAction = "Back to projects",
            onEmptyAction = { actions.onSelect(null) },
            onRetry = actions.onRetryDetail,
            links = links,
        ) { d ->
            PageTitle(d.project.name)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatusBadge(d.project.status)
                Text(
                    d.project.updated.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clearAndSetSemantics { contentDescription = d.project.updated.description },
                )
            }
            Text(d.project.purpose, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
            WhumpoSecondaryButton(
                onClick = { actions.onAskAboutProject(d.project) },
                modifier = Modifier.heightIn(min = 48.dp).testTag("project-ask"),
            ) { Text("Ask about this project") }
            SectionHeading("Services")
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ServiceTile("Website", AppIcons.Globe, d.project.website, Modifier.fillMaxWidth())
                ServiceTile("App", AppIcons.Database, d.project.app, Modifier.fillMaxWidth())
            }
            SectionHeading("Versions")
            if (d.versions.isEmpty()) {
                Text(
                    "No saved versions yet.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    d.versions.forEach { v ->
                        InfoRow(v.component, v.saved.text, v.saved.description, AppIcons.Globe, Modifier.testTag("version-${v.id}"))
                    }
                }
            }
            PhotosSection(
                state = photos,
                onPick = actions.onPickPhoto,
                onRefresh = actions.onRefreshPhotos,
                onRetryUpload = actions.onRetryUpload,
                onDismissUpload = actions.onDismissUpload,
                links = links,
            )
        }
    }
}
