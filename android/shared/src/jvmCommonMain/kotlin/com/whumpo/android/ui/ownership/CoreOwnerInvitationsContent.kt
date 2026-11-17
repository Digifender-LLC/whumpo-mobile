package com.whumpo.android.ui.ownership

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.whumpo.android.ui.theme.WhumpoPrimaryButton
import com.whumpo.android.ui.theme.WhumpoQuietButton
import com.whumpo.core.api.CoreInvitationStatus
import com.whumpo.core.api.CorePersonInvitation
import com.whumpo.core.api.CoreRole
import com.whumpo.core.auth.CoreInvitationNotice
import com.whumpo.core.auth.CoreOwnerInvitationState
import java.time.Instant

class CoreOwnerInvitationsActions(
    val refreshSeats: () -> Unit,
    val history: (Boolean) -> Unit,
    val issue: (CoreRole) -> Unit,
    val revoke: (CorePersonInvitation) -> Unit,
    val retry: () -> Unit,
    val copy: () -> Unit,
    val clearCode: () -> Unit,
    val close: () -> Unit,
)

@Composable
fun CoreOwnerInvitationsContent(state: CoreOwnerInvitationState, actions: CoreOwnerInvitationsActions) {
    var role by remember { mutableStateOf<CoreRole?>(null) }
    var reviewingRevoke by remember { mutableStateOf<CorePersonInvitation?>(null) }
    val button = Modifier.fillMaxWidth().heightIn(min = 48.dp)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        WhumpoQuietButton(actions.close, modifier = button) { Text("Back to organization") }
        Text("Team invitations", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        val seats = state.seats
        if (seats != null) {
            Text("Coverage: ${seats.status}")
            Text("Active seats: ${seats.activeHumanSeats}")
            Text("Reserved seats: ${seats.reservedHumanSeats}")
            Text("Available seats: ${seats.availableHumanSeats}")
            seats.humanSeatLimit?.let { Text("Seat limit: $it") }
            seats.expiresAt?.let { Text("Coverage ends: ${Instant.ofEpochMilli(it)}") }
        } else {
            Text("Current seats unavailable")
        }
        WhumpoQuietButton(actions.refreshSeats, enabled = !state.busy, modifier = button) { Text("Refresh seats") }
        if (seats?.teamAdmissionAvailable == true && !state.canRetry) {
            Text("Choose what the invitee can do", style = MaterialTheme.typography.titleMedium)
            WhumpoQuietButton({ role = CoreRole.operator }, enabled = !state.busy, modifier = button) {
                Text(if (role == CoreRole.operator) "Operator selected" else "Choose Operator")
            }
            WhumpoQuietButton({ role = CoreRole.viewer }, enabled = !state.busy, modifier = button) {
                Text(if (role == CoreRole.viewer) "Viewer selected" else "Choose Viewer")
            }
            WhumpoPrimaryButton(onClick = { role?.let(actions.issue) },
                enabled = role != null && !state.busy, modifier = button) { Text("Issue invitation") }
        }
        if (state.codeExpiresAt != null) {
            Text("One-use invitation code is ready. Share it privately with the intended person.",
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            Text("Copy puts the code on the system clipboard. Android or other apps may retain it.")
            WhumpoPrimaryButton(actions.copy, enabled = !state.busy, modifier = button) { Text("Copy invitation code") }
            WhumpoQuietButton(actions.clearCode, modifier = button) { Text("Dismiss invitation code") }
        }
        if (state.canRetry) {
            WhumpoQuietButton(actions.retry, enabled = !state.busy, modifier = button) { Text("Retry original request") }
        }
        state.notice?.let {
            Text(when (it) {
                CoreInvitationNotice.IssuedWithoutCode -> "Invitation recorded. Its one-use code is unavailable."
                CoreInvitationNotice.Revoked -> "Invitation revoked."
                CoreInvitationNotice.Copied -> "Invitation code copied."
                CoreInvitationNotice.Unavailable -> "Could not verify current invitations or seats."
                CoreInvitationNotice.ReviewChanged -> "No seat is available. Refresh before trying again."
                CoreInvitationNotice.Unknown -> "Could not confirm the result. Retry the original request."
            }, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        Text("Invitation history", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        WhumpoQuietButton({ actions.history(false) }, enabled = !state.busy, modifier = button) {
            Text(if (state.historyLoaded) "Refresh invitation history" else "Load invitation history")
        }
        if (state.historyLoaded && state.invitations.isEmpty()) Text("No invitations")
        state.invitations.forEach { invite ->
            Text("${invite.role.name.replaceFirstChar(Char::uppercaseChar)} · ${invite.status}")
            Text("Expires: ${invite.expiresAt}")
            if (invite.status == CoreInvitationStatus.pending && Instant.parse(invite.expiresAt).toEpochMilli() > System.currentTimeMillis()
                && !state.canRetry) {
                if (reviewingRevoke == invite) {
                    Text("Revoking releases the seat and prevents use of this code.")
                    WhumpoPrimaryButton(onClick = {
                        reviewingRevoke = null
                        actions.revoke(invite)
                    }, enabled = !state.busy, modifier = button) { Text("Confirm revoke invitation") }
                    WhumpoQuietButton({ reviewingRevoke = null }, modifier = button) { Text("Keep invitation") }
                } else {
                    WhumpoQuietButton({ reviewingRevoke = invite }, enabled = !state.busy, modifier = button) {
                        Text("Review revoke invitation")
                    }
                }
            }
        }
        if (state.nextCursor != null) {
            WhumpoQuietButton({ actions.history(true) }, enabled = !state.busy, modifier = button) {
                Text("More invitations")
            }
        }
    }
}
