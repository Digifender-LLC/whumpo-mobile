package com.whumpo.android.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.whumpo.android.ui.state.EmptyState
import com.whumpo.android.ui.state.ErrorCard
import com.whumpo.android.ui.state.Skeleton
import com.whumpo.android.ui.state.UiState
import com.whumpo.android.ui.state.UpdateRequiredScreen
import com.whumpo.android.ui.theme.WhumpoTheme
import com.whumpo.android.ui.theme.WhumpoTokens

/** Vertically scrolling page so large font scales never clip content. */
@Composable
fun ScrollingColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content,
    )
}

/**
 * Maps a [UiState] onto the WP3 state components: skeleton, empty, error card, update-required and content.
 * [emptyMessage] and [emptyAction] fill the Empty state; [loading] is what TalkBack hears while loading.
 */
@Composable
fun <T> StateHost(
    state: UiState<T>,
    loading: String,
    emptyMessage: String,
    emptyAction: String,
    onEmptyAction: () -> Unit,
    onRetry: () -> Unit,
    links: AppLinks,
    modifier: Modifier = Modifier,
    skeletonRows: Int = 4,
    content: @Composable (T) -> Unit,
) {
    val uri = LocalUriHandler.current
    when (state) {
        UiState.Loading -> Skeleton(loading, modifier, rows = skeletonRows)
        UiState.Empty -> EmptyState(emptyMessage, emptyAction, onEmptyAction, modifier)
        is UiState.Error -> ErrorCard(state.what, state.safe, onRetry, modifier)
        UiState.UpdateRequired -> UpdateRequiredScreen(
            onUpdate = { uri.openUri(links.playStore) },
            onHelp = { uri.openUri(links.support) },
            modifier = modifier,
        )
        is UiState.Content -> content(state.value)
    }
}

/** One list row: icon tile, title, a subtitle whose spoken form carries the absolute time, and an optional trailing slot. */
@Composable
fun InfoRow(
    title: String,
    subtitle: String,
    subtitleDescription: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(WhumpoTokens.RadiusMdDp.dp),
        color = scheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, WhumpoTheme.colors.line),
    ) {
        Row(
            Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .heightIn(min = 48.dp)
                // One spoken line: the title, then the time with its absolute form.
                .clearAndSetSemantics { contentDescription = "$title. $subtitleDescription" },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(icon, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
            trailing?.invoke()
        }
    }
}
