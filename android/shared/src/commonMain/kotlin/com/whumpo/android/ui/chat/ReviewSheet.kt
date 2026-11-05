package com.whumpo.android.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.whumpo.android.ui.state.WhumpoIcons
import com.whumpo.android.ui.theme.WhumpoPrimaryButton
import com.whumpo.android.ui.theme.WhumpoQuietButton
import com.whumpo.android.ui.theme.WhumpoSecondaryButton
import com.whumpo.android.ui.theme.WhumpoTheme
import com.whumpo.core.api.ChatTurn
import com.whumpo.core.api.PreviewRef
import com.whumpo.core.api.Review

private val Check: ImageVector = ImageVector.Builder("check", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(
        addPathNodes("M5,12l5,5L19,7"),
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 2f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    )
}.build()

/** The button names the effect, as the contract's four controls do. */
internal fun confirmLabel(kind: String): String = when (kind) {
    "site" -> "Publish website"
    "specification" -> "Save private website"
    "application" -> "Turn on app"
    "app-restore" -> "Restore app data"
    else -> "Confirm"
}

/** `2026-10-04T12:30:00.000Z` as `2026-10-04 12:30 UTC`; text only, no clock is needed. */
internal fun expiryText(iso: String): String = iso.replace('T', ' ').take(16) + " UTC"

/** The in-chat card for a review: status, then Review change (primary) and Open preview (secondary). Order and labels are fixed (D8). */
@Composable
fun ReviewCard(turn: ChatTurn, actions: ChatActions, onOpenPreview: (PreviewRef) -> Unit, modifier: Modifier = Modifier) {
    val review = turn.review ?: return
    val actionable = review.canApprove && review.state == "pending"
    val badge = when {
        review.kind !in setOf("specification", "site", "application", "app-restore") || review.unavailableReason == "client_update_required" -> "Update required"
        actionable -> "Waiting for you"
        review.state == "applied" -> "Applied"
        review.state == "expired" -> "Expired"
        else -> "No longer current"
    }
    Card(
        modifier.fillMaxWidth().padding(start = 36.dp).testTag("review-card"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            review.project?.let { Text(it.name, style = MaterialTheme.typography.labelLarge, color = WhumpoTheme.colors.inkMuted) }
            Text(review.disclosure.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            StatusBadge(badge, good = review.state == "applied")
            if (actionable) {
                WhumpoPrimaryButton({ actions.onOpenReview(turn.id) }, Modifier.heightIn(min = 48.dp).testTag("review-open")) { Text("Review change") }
            }
            review.preview?.takeIf { actionable }?.let { preview ->
                WhumpoSecondaryButton({ onOpenPreview(preview) }, Modifier.heightIn(min = 48.dp).testTag("review-card-preview")) { Text("Open preview") }
            }
        }
    }
}

/** Icon, word and colour together: status is never colour alone. */
@Composable
private fun StatusBadge(text: String, good: Boolean) {
    val colors = WhumpoTheme.colors
    Row(
        Modifier
            .background(if (good) colors.readySoft else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val tint = if (good) colors.readyInk else MaterialTheme.colorScheme.onSurfaceVariant
        Icon(if (good) Check else WhumpoIcons.Activity, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = tint)
    }
}

/** The review summary: the server's exact disclosure, then one primary action, one secondary, one quiet. */
@Composable
fun ReviewSheetContent(flow: ReviewFlow, actions: ChatActions, onOpenPreview: (PreviewRef) -> Unit, modifier: Modifier = Modifier) {
    val review: Review = flow.review
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp).testTag("review-sheet"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        review.project?.let { Text(it.name, style = MaterialTheme.typography.labelLarge, color = WhumpoTheme.colors.inkMuted) }
        Text(review.disclosure.title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.semantics { heading() })
        Column(Modifier.testTag("review-lines"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            review.disclosure.lines.forEach { line ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("-", style = MaterialTheme.typography.bodyLarge, color = WhumpoTheme.colors.inkMuted)
                    Text(line, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        Text("Expires ${expiryText(review.expiresAt)}", style = MaterialTheme.typography.bodyMedium, color = WhumpoTheme.colors.inkMuted)
        if (review.requiresFreshPasskey && flow.step == ReviewStep.Ready) {
            Text("You'll be asked to confirm with your passkey.", style = MaterialTheme.typography.bodyMedium, color = WhumpoTheme.colors.inkMuted)
        }
        flow.message?.let { Outcome(flow.step, it) }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (flow.step) {
                ReviewStep.Done -> WhumpoPrimaryButton(actions::onDismissReview, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("review-done")) { Text("Done") }
                else -> {
                    val label = when (flow.step) {
                        ReviewStep.Reauthenticating -> "Checking your passkey..."
                        ReviewStep.Confirming -> "Confirming..."
                        ReviewStep.Failed -> "Try again"
                        else -> confirmLabel(review.kind)
                    }
                    WhumpoPrimaryButton(
                        actions::onConfirmReview,
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("review-confirm"),
                        enabled = flow.canConfirm,
                    ) { Text(label) }
                    review.preview?.takeIf { flow.step == ReviewStep.Ready || flow.step == ReviewStep.Failed }?.let { preview ->
                        WhumpoSecondaryButton({ onOpenPreview(preview) }, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("review-inspect")) { Text("Open preview") }
                    }
                    WhumpoQuietButton(actions::onDismissReview, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("review-dismiss")) { Text("Not now") }
                }
            }
        }
    }
}

/** The receipt or the failure, with what is safe. Announced politely. */
@Composable
private fun Outcome(step: ReviewStep, message: String) {
    val colors = WhumpoTheme.colors
    val scheme = MaterialTheme.colorScheme
    val (container, content, icon) = when (step) {
        ReviewStep.Done -> Triple(colors.readySoft, colors.readyInk, Check)
        ReviewStep.Failed, ReviewStep.NoLongerCurrent, ReviewStep.UpdateRequired -> Triple(scheme.errorContainer, scheme.onErrorContainer, WhumpoIcons.Alert)
        else -> Triple(scheme.surfaceVariant, scheme.onSurfaceVariant, WhumpoIcons.Activity)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(container, RoundedCornerShape(16.dp))
            .padding(16.dp)
            .testTag("review-outcome")
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(24.dp))
        Text(message, style = MaterialTheme.typography.bodyLarge, color = content)
    }
}

/** Expanded widths: the sheet is a side pane, so the transcript stays visible. */
@Composable
fun ReviewPane(flow: ReviewFlow, actions: ChatActions, onOpenPreview: (PreviewRef) -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxHeight().fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        ReviewSheetContent(flow, actions, onOpenPreview)
    }
}

/** Compact and medium widths: a modal bottom sheet. System back closes it without confirming anything. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewBottomSheet(flow: ReviewFlow, actions: ChatActions, onOpenPreview: (PreviewRef) -> Unit) {
    ModalBottomSheet(
        onDismissRequest = actions::onDismissReview,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        ReviewSheetContent(flow, actions, onOpenPreview)
    }
}
