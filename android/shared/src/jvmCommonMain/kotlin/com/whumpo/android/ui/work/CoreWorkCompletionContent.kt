package com.whumpo.android.ui.work

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.whumpo.android.ui.partner.PartnerApprovalDetails
import com.whumpo.android.ui.theme.WhumpoPrimaryButton
import com.whumpo.android.ui.theme.WhumpoQuietButton
import com.whumpo.core.api.CoreWorkTask
import com.whumpo.core.api.PartnerApprovalStatus
import com.whumpo.core.auth.CoreWorkCompletionNotice
import com.whumpo.core.auth.CoreWorkCompletionState
import com.whumpo.core.auth.PartnerTaskProof

@Composable
fun CoreWorkCompletionContent(
    task: CoreWorkTask,
    state: CoreWorkCompletionState,
    submit: () -> Unit,
    refresh: () -> Unit,
    approve: () -> Unit,
    close: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Task completion", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        Text(task.title, style = MaterialTheme.typography.titleLarge)
        Text("Completing records your owner assertion that this task is finished. Queued or pending approval is not completion.")
        if (state.busy) Text("Checking the task change…", modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        state.notice?.let { notice -> Text(when (notice) {
            CoreWorkCompletionNotice.Changed -> "This task changed. Refresh Work before trying again."
            CoreWorkCompletionNotice.FreshOwnerRequired -> "Completion needs a fresh owner sign-in. Your Work reads remain available."
            CoreWorkCompletionNotice.Rejected -> "The task completion was not accepted. Refresh its current state."
            CoreWorkCompletionNotice.ReviewChanged -> "The approval changed or expired. Review its latest state before deciding."
            CoreWorkCompletionNotice.Unconfirmed -> "The outcome is unconfirmed. Retry the same request or check its verified result."
        }, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }) }
        if (!state.accepted && state.notice !in setOf(CoreWorkCompletionNotice.Changed, CoreWorkCompletionNotice.Rejected)) {
            WhumpoPrimaryButton(onClick = submit, enabled = !state.busy) {
                Text(if (state.started) "Retry same completion" else "Complete task")
            }
        }
        state.approval?.let { approval ->
            PartnerApprovalDetails(approval, requesterLabel = "You", taskLabel = task.title)
            if (approval.status == PartnerApprovalStatus.pending) WhumpoPrimaryButton(onClick = approve, enabled = !state.busy) { Text("Approve task change") }
        }
        state.proof?.let { proof ->
            Text(when (proof) {
                PartnerTaskProof.Queued -> "Completion is queued."
                PartnerTaskProof.WaitingApproval -> "Completion is waiting for approval."
                PartnerTaskProof.Failed -> "The task change failed."
                PartnerTaskProof.Canceled -> "The task change was canceled."
                is PartnerTaskProof.Verified -> "Task completion is verified at version ${proof.verifiedVersion}."
            }, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            if (proof is PartnerTaskProof.Verified) {
                Text("Verified task result", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                Text("Current task: ${proof.task.status.label} · version ${proof.task.version}")
                Text("The matching receipt records the owner assertion. Refresh Work for a new list snapshot.")
            }
        }
        if (state.accepted) WhumpoQuietButton(onClick = refresh, enabled = !state.busy) { Text("Check task result") }
        WhumpoQuietButton(onClick = close) { Text("Back to Work") }
    }
}
