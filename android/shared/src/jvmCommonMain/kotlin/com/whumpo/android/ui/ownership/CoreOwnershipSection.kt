package com.whumpo.android.ui.ownership

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.whumpo.core.api.CoreOwnershipProvenance
import com.whumpo.core.api.CoreRole
import com.whumpo.core.auth.CoreOwnershipAction
import com.whumpo.core.auth.CoreOwnershipController
import com.whumpo.core.auth.CoreOwnershipNotice
import kotlinx.coroutines.launch

@Composable
fun CoreOwnershipSection(
    controller: CoreOwnershipController,
    userId: String,
    role: CoreRole,
    onSignInReview: (Boolean) -> Unit = {},
    onReviewOpened: () -> Unit = {},
    onInteraction: () -> Unit = {},
    signIn: () -> Unit,
) {
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var signInReview by remember { mutableStateOf(false) }
    fun setSignInReview(present: Boolean) {
        if (present) onReviewOpened()
        signInReview = present
        onSignInReview(present)
    }
    val designation = state.snapshot?.designation
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Primary owner", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        OwnershipButton(onInteraction, if (expanded) "Hide primary owner details" else "Show primary owner details") {
            expanded = !expanded
            if (!expanded) setSignInReview(false)
            if (expanded && designation == null) scope.launch { controller.refresh() }
        }
        if (expanded) {
            state.notice?.let {
                Text(noticeText(it), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            if (state.busy) Text("Checking primary owner…")
            designation?.let {
                OwnershipUser("Primary owner", it.primaryOwnerUserId, userId)
                it.version?.let { version -> Text("Designation version: $version") }
                it.provenance?.let { source -> Text("Designation source: ${provenanceText(source)}") }
                if (role != CoreRole.owner) Text("Transfer details are available to owners.")
            }
            state.snapshot?.transfer?.let { offer ->
                Text("Transfer offer: ${offer.status}")
                OwnershipUser("Sender", offer.sourceUserId, userId)
                OwnershipUser("Recipient", offer.targetUserId, userId)
                Text("Expires: ${offer.expiresAt}")
            }
            OwnershipButton(onInteraction, "Refresh primary owner", enabled = !state.busy) { scope.launch { controller.refresh() } }
            if (state.retry) {
                OwnershipButton(onInteraction, "Retry exact request", enabled = !state.busy) { scope.launch { controller.retry() } }
            }
            if (state.notice == CoreOwnershipNotice.SignInAgain) {
                OwnershipButton(onInteraction, "Sign in again", enabled = !state.busy) { setSignInReview(true) }
            }
            for ((action, label) in listOf(
                CoreOwnershipAction.Claim to "Review primary owner claim",
                CoreOwnershipAction.Accept to "Review acceptance",
                CoreOwnershipAction.Cancel to "Review cancellation",
            )) {
                if (controller.canReview(action)) OwnershipButton(onInteraction, label) {
                    onReviewOpened()
                    controller.review(action)
                }
            }
            if (!state.retry && role == CoreRole.owner && designation?.primaryOwnerUserId == userId &&
                designation.pendingTransfer == null
            ) {
                OwnershipButton(onInteraction, "Load eligible owners", enabled = !state.busy) {
                    scope.launch { controller.loadCandidates() }
                }
                state.candidates?.let { candidates ->
                    if (candidates.isEmpty()) Text("No other active owners are eligible.")
                    var offset by remember(candidates) { mutableIntStateOf(0) }
                    candidates.drop(offset).take(20).forEach { candidate ->
                        OwnershipButton(onInteraction, "Review offer to ${candidate.userId}",
                            enabled = controller.canReview(CoreOwnershipAction.Offer, candidate),
                        ) {
                            onReviewOpened()
                            controller.review(CoreOwnershipAction.Offer, candidate)
                        }
                    }
                    if (offset > 0) OwnershipButton(onInteraction, "Previous owners") { offset -= 20 }
                    if (offset + 20 < candidates.size) OwnershipButton(onInteraction, "More owners") { offset += 20 }
                }
            }
        }
    }

    state.review?.takeIf { expanded }?.let { review ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(reviewTitle(review.action), style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() })
            OwnershipUser("Current primary owner", review.designation.primaryOwnerUserId, userId)
            review.designation.version?.let { Text("Reviewed designation version: $it") }
            if (review.action == CoreOwnershipAction.Claim) OwnershipUser("New primary owner", userId, userId)
            review.target?.let { candidate ->
                OwnershipUser("Recipient", candidate.userId, userId)
                Text("Recipient membership version: ${candidate.membershipVersion}")
            }
            review.transfer?.let { offer ->
                OwnershipUser("Sender", offer.sourceUserId, userId)
                OwnershipUser("Recipient", offer.targetUserId, userId)
                Text("Expires: ${offer.expiresAt}")
            }
            Text("This action leaves roles, sessions, billing and seats unchanged.")
            OwnershipButton(onInteraction, confirmLabel(review.action), enabled = !state.busy) { scope.launch { controller.submit() } }
            OwnershipButton(onInteraction, "Dismiss review", enabled = !state.busy) { controller.dismissReview() }
        }
    }
    if (signInReview && expanded) {
        AlertDialog(
            onDismissRequest = { setSignInReview(false) },
            title = { Text("Sign in again?") },
            text = { Text("This leaves the workspace on this device. Use your account’s sign-in method, then select the workspace and review the change again.") },
            confirmButton = { OwnershipButton(onInteraction, "Continue to sign-in") {
                setSignInReview(false)
                signIn()
            } },
            dismissButton = { OwnershipButton(onInteraction, "Stay signed in") { setSignInReview(false) } },
        )
    }
}

@Composable
private fun OwnershipButton(onInteraction: () -> Unit, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(onClick = { onInteraction(); onClick() }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(label)
    }
}

@Composable
private fun OwnershipUser(label: String, id: String?, userId: String) {
    Text("$label: ${when (id) {
        null -> "Undesignated"
        userId -> "$id (You)"
        else -> id
    }}")
}

private fun noticeText(notice: CoreOwnershipNotice) = when (notice) {
    CoreOwnershipNotice.Unavailable -> "Could not load primary owner details."
    CoreOwnershipNotice.ReviewChanged -> "The primary owner or transfer changed. Review the current details again."
    CoreOwnershipNotice.SignInAgain -> "This change requires an eligible owner who verified their sign-in within the last 15 minutes."
    CoreOwnershipNotice.Forbidden -> "Only the sender or recipient can cancel while they are still a member."
    CoreOwnershipNotice.Rejected -> "The service refused this request. Refresh before reviewing another change."
    CoreOwnershipNotice.Unconfirmed -> "The service may have applied this request. Retry sends the original request. Signing out or restarting clears it from this device."
    CoreOwnershipNotice.Confirmed -> "The service confirmed the request."
}

private fun reviewTitle(action: CoreOwnershipAction) = when (action) {
    CoreOwnershipAction.Claim -> "Review claim"
    CoreOwnershipAction.Offer -> "Review transfer offer"
    CoreOwnershipAction.Accept -> "Review transfer acceptance"
    CoreOwnershipAction.Cancel -> "Review transfer cancellation"
}

private fun confirmLabel(action: CoreOwnershipAction) = when (action) {
    CoreOwnershipAction.Claim -> "Confirm primary owner claim"
    CoreOwnershipAction.Offer -> "Send transfer offer"
    CoreOwnershipAction.Accept -> "Accept transfer"
    CoreOwnershipAction.Cancel -> "Cancel transfer"
}

private fun provenanceText(source: CoreOwnershipProvenance) = when (source) {
    CoreOwnershipProvenance.onboarding -> "Organization setup"
    CoreOwnershipProvenance.local_bootstrap -> "Local setup"
    CoreOwnershipProvenance.backfill_onboarding_creator -> "Organization creator"
    CoreOwnershipProvenance.backfill_sole_owner -> "Sole owner"
    CoreOwnershipProvenance.owner_claim -> "Owner claim"
    CoreOwnershipProvenance.transfer -> "Transfer"
}
