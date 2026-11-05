package com.whumpo.android.ui.activity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.whumpo.android.ui.common.PageTitle
import com.whumpo.android.ui.common.ScrollingColumn
import com.whumpo.android.ui.common.StateHost
import com.whumpo.android.ui.shell.AdaptivePanes
import com.whumpo.android.ui.shell.DetailPlaceholder
import com.whumpo.android.ui.shell.LocalNavigate
import com.whumpo.android.ui.shell.TopDestination
import com.whumpo.android.ui.shell.WidthClass
import com.whumpo.android.ui.state.UiState
import com.whumpo.android.ui.theme.WhumpoQuietButton

@Composable
fun ActivityScreen(viewModel: ActivityViewModel, width: WidthClass, links: AppLinks) {
    val navigate = LocalNavigate.current
    val state by viewModel.state.collectAsState()
    val notice by viewModel.notice.collectAsState()
    ActivityContent(
        state, notice, width, links,
        onRefresh = viewModel::refresh,
        onLoadMore = viewModel::loadMore,
        onDismissNotice = viewModel::dismissNotice,
        onGoToChat = { navigate(TopDestination.Chat) },
    )
}

/** The feed, newest first, with relative times (the absolute time is in each row's spoken description). */
@Composable
fun ActivityContent(
    state: UiState<ActivityList>,
    notice: EventNotice?,
    width: WidthClass,
    links: AppLinks,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onDismissNotice: () -> Unit,
    onGoToChat: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AdaptivePanes(
        width = width,
        modifier = modifier,
        list = {
            ScrollingColumn(Modifier.testTag("screen-activity")) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    PageTitle("Activity", Modifier.weight(1f))
                    WhumpoQuietButton(onClick = onRefresh, modifier = Modifier.heightIn(min = 48.dp)) { Text("Refresh") }
                }
                if (notice != null) NoticeCard(notice, onDismissNotice)
                StateHost(
                    state = state,
                    loading = "Loading activity",
                    emptyMessage = "Nothing yet. Work Whumpo does for you will show up here.",
                    emptyAction = "Go to Chat",
                    onEmptyAction = onGoToChat,
                    onRetry = onRefresh,
                    links = links,
                ) { feed ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        feed.rows.forEach { row ->
                            InfoRow(
                                title = row.summary,
                                subtitle = "${row.action} · ${row.time.text}",
                                subtitleDescription = "${row.action}, ${row.time.description}",
                                icon = AppIcons.Clock,
                                modifier = Modifier.testTag("activity-${row.id}"),
                            )
                        }
                        if (feed.moreFailed) {
                            Text(
                                "Couldn't load more activity. Nothing was changed.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (feed.nextCursor != null) {
                            WhumpoQuietButton(
                                onClick = onLoadMore,
                                enabled = !feed.loadingMore,
                                modifier = Modifier.heightIn(min = 48.dp).testTag("activity-more"),
                            ) { Text(if (feed.loadingMore) "Loading more" else "Show older activity") }
                        }
                    }
                }
            }
        },
        detail = { DetailPlaceholder(hint = "Activity is a feed. Open Projects to see what changed in each one.") },
    )
}

@Composable
private fun NoticeCard(notice: EventNotice, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("activity-notice").semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(12.dp),
        color = if (notice.available) scheme.primaryContainer else scheme.surfaceVariant,
    ) {
        Row(
            Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                if (notice.available) AppIcons.Bell else AppIcons.Alert,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (notice.available) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
            )
            Text(
                notice.text,
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                color = if (notice.available) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
            )
            WhumpoQuietButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Dismiss") }
        }
    }
}
