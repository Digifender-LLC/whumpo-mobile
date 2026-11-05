package com.whumpo.android.ui.state

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.whumpo.android.ui.theme.WhumpoMotion
import com.whumpo.android.ui.theme.WhumpoPrimaryButton
import com.whumpo.android.ui.theme.WhumpoQuietButton
import com.whumpo.android.ui.theme.WhumpoTheme

private val MinTarget = 48.dp

/**
 * Loading with the layout known: skeleton rows with a 1.2 s opacity shimmer. [label] is what TalkBack
 * hears ("Loading projects"). Pass `animated = false` for the static tint used under reduced motion
 * (Compose also stops the shimmer when the system animator scale is 0).
 */
@Composable
fun Skeleton(label: String, modifier: Modifier = Modifier, rows: Int = 4, animated: Boolean = true) {
    val alpha = if (animated) {
        val pulse by rememberInfiniteTransition(label = "skeleton").animateFloat(
            initialValue = 0.45f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(600, easing = WhumpoMotion.Standard), RepeatMode.Reverse),
            label = "skeleton-alpha",
        )
        pulse
    } else {
        0.7f
    }
    val tint = WhumpoTheme.colors.line
    Column(
        modifier
            .fillMaxWidth()
            .testTag("state-loading")
            .semantics(mergeDescendants = true) { contentDescription = label }
            .alpha(alpha),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        repeat(rows) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.size(40.dp).background(tint, CircleShape))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.fillMaxWidth(0.6f).height(14.dp).background(tint, RoundedCornerShape(8.dp)))
                    Box(Modifier.fillMaxWidth(0.9f).height(12.dp).background(tint, RoundedCornerShape(8.dp)))
                }
            }
        }
    }
}

/** Empty: the mascot, one sentence and one action. */
@Composable
fun EmptyState(
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().padding(24.dp).testTag("state-empty"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        WhumpoMascot()
        Text(
            message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        WhumpoPrimaryButton(onClick = onAction, modifier = Modifier.heightIn(min = MinTarget)) { Text(actionLabel) }
    }
}

/** Recoverable error: what happened, what is safe, and a retry. Announced politely to TalkBack. */
@Composable
fun ErrorCard(
    what: String,
    safe: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    retryLabel: String = "Try again",
) {
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier.fillMaxWidth().testTag("state-error").semantics { liveRegion = LiveRegionMode.Polite },
        colors = CardDefaults.cardColors(containerColor = scheme.errorContainer, contentColor = scheme.onErrorContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // Icon plus words: status is never colour alone.
                Icon(WhumpoIcons.Alert, contentDescription = null, modifier = Modifier.size(24.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(what, style = MaterialTheme.typography.titleMedium)
                    Text(safe, style = MaterialTheme.typography.bodyLarge)
                }
            }
            WhumpoPrimaryButton(onClick = onRetry, modifier = Modifier.heightIn(min = MinTarget)) { Text(retryLabel) }
        }
    }
}

/** Slim banner above the content. Content already shown stays visible; the caller disables sending. */
@Composable
fun OfflineBanner(message: String, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .background(scheme.surfaceVariant)
            .heightIn(min = 40.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("state-offline")
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(WhumpoIcons.Offline, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
    }
}

/** Blocks the app on `client_update_required`. Help stays reachable. */
@Composable
fun UpdateRequiredScreen(
    onUpdate: () -> Unit,
    onHelp: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = "This version of Whumpo is out of date.",
    detail: String = "Update to keep going. Your work is safe.",
    updateLabel: String = "Open Play Store",
    helpLabel: String = "Get help",
) {
    Column(
        modifier.fillMaxSize().padding(24.dp).testTag("state-update-required"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        WhumpoMascot(size = 160.dp)
        Text(
            title,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            detail,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        WhumpoPrimaryButton(onClick = onUpdate, modifier = Modifier.heightIn(min = MinTarget)) { Text(updateLabel) }
        WhumpoQuietButton(onClick = onHelp, modifier = Modifier.heightIn(min = MinTarget)) { Text(helpLabel) }
    }
}
