package com.whumpo.android.ui.partner

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.whumpo.android.ui.theme.WhumpoPrimaryButton
import com.whumpo.android.ui.theme.WhumpoQuietButton
import com.whumpo.core.api.PartnerDecision
import com.whumpo.core.api.PartnerMessage
import com.whumpo.core.api.PartnerMessageRole
import com.whumpo.core.api.PartnerRunStatus
import com.whumpo.core.api.PartnerRunStep
import com.whumpo.core.api.PartnerTool
import com.whumpo.core.auth.PartnerNotice
import com.whumpo.core.auth.PartnerState
import com.whumpo.core.auth.PartnerTaskProof

@Composable
fun PartnerHeading(text: String) {
    Text(text, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
}

@Composable
fun PartnerButton(label: String, enabled: Boolean = true, primary: Boolean = false, onClick: () -> Unit) {
    val modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
    if (primary) {
        WhumpoPrimaryButton(onClick = onClick, enabled = enabled, modifier = modifier) { Text(label) }
    } else {
        WhumpoQuietButton(onClick = onClick, enabled = enabled, modifier = modifier) { Text(label) }
    }
}

@Composable
fun PartnerStatus(text: String) {
    Text(text, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}

fun PartnerNotice.partnerText(): String = when (this) {
    PartnerNotice.Unavailable -> "Could not read Partner data. Refresh to try again."
    PartnerNotice.ReadOnly -> "This conversation is read-only."
    PartnerNotice.InvalidInput -> "Check the title or message length and try again."
    PartnerNotice.DraftLimit -> "Finish a saved draft before starting another conversation draft."
    PartnerNotice.Saved -> "Message saved."
    PartnerNotice.SaveUnconfirmed -> "The service may have saved this message. Retry sends the original request."
    PartnerNotice.SaveAwaitingHistory -> "The service acknowledged the message. Waiting for an exact match in conversation history."
    PartnerNotice.ClosedSaveUnresolved -> "The save is unresolved. Reopen this conversation in the web workspace to retry the original request."
    PartnerNotice.CreateUnconfirmed -> "The service may have created the conversation. Retry sends the original request."
    PartnerNotice.RunUnconfirmed -> "The service may have accepted the run. Retry sends the original request."
    PartnerNotice.RunDisabled -> "Task execution is disabled by organization policy. Your message is saved."
    PartnerNotice.RequestRejected -> "The service refused this request. Refresh before trying again."
    PartnerNotice.DecisionUnconfirmed -> "The service may have recorded this decision. Retry sends the original request."
    PartnerNotice.DecisionRecorded -> "Approval decision recorded. Check the task result to verify execution."
    PartnerNotice.ReviewChanged -> "The approval changed or expired. Review the latest approval before deciding."
    PartnerNotice.SignInAgain -> "This decision may require a recent verified sign-in. Check your access and sign in again if needed."
}

/**
 * The scrollable history leaves room for the composer when the keyboard is open.
 * Task runs and approvals have separate actions from saving a message.
 */
@Composable
fun PartnerConversationContent(
    organization: String,
    title: String,
    state: PartnerState,
    canWrite: Boolean,
    userId: String,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onMore: () -> Unit,
    onDraft: (String) -> Unit,
    onSave: () -> Unit,
    onRequestRun: (PartnerMessage) -> Unit,
    onRetryRun: () -> Unit,
    onOpenRun: (PartnerMessage) -> Unit,
    onRefreshRun: () -> Unit,
    onReview: (PartnerRunStep) -> Unit,
    onVerify: (PartnerRunStep) -> Unit,
    modifier: Modifier = Modifier,
) {
    val closed = state.topic?.closed == true
    val editable = state.topic != null && !closed && canWrite
    val canStart = editable && !state.runPending && !state.decisionPending &&
        state.run?.status?.shouldPoll != true && state.run?.status != PartnerRunStatus.waiting_approval

    Column(modifier.imePadding().padding(horizontal = 20.dp)) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                PartnerButton("Back to conversations", onClick = onBack)
                Text(organization, style = MaterialTheme.typography.labelLarge)
                PartnerHeading(title)
            }
            item {
                if (closed) Text("Closed conversation · retained history")
                if (!canWrite && !closed) Text("Read-only access")
                state.notice?.let { PartnerStatus(it.partnerText()) }
                if (state.busy) PartnerStatus("Working…")
                PartnerButton("Refresh conversation", !state.busy, onClick = onRefresh)
            }
            if (state.messages.isEmpty() && !state.busy && state.topic != null) {
                item { Text("No messages yet.") }
            }
            items(state.messages.size, key = { "message-${state.messages[it].id}" }) { index ->
                val message = state.messages[index]
                val own = message.role == PartnerMessageRole.user && message.userId == userId
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(when {
                            message.role == PartnerMessageRole.assistant -> "Agent outcome"
                            own -> "You"
                            else -> "Organization member"
                        }, style = MaterialTheme.typography.labelLarge)
                        Text(message.content)
                        if (message.runId != null) {
                            PartnerButton("Open run details", !state.busy && !state.runPending && !state.decisionPending) { onOpenRun(message) }
                        }
                        if (own && canStart) {
                            PartnerButton("Request task run", !state.busy) { onRequestRun(message) }
                        }
                    }
                }
            }
            if (state.moreMessages) item {
                PartnerButton("Load more messages", !state.busy, onClick = onMore)
            }
            val saved = state.savedForRun
            if (canStart && saved != null && saved !in state.messages) item {
                PartnerButton("Request task run from saved message", !state.busy) { onRequestRun(saved) }
            }
            if (state.runPending) item {
                PartnerButton("Retry exact run request", !state.busy && editable, onClick = onRetryRun)
            }
            state.run?.let { run ->
                item {
                    Text("Task run", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                    Text(when (run.status) {
                        PartnerRunStatus.queued -> "Queued"
                        PartnerRunStatus.planning -> "Planning"
                        PartnerRunStatus.tool -> "Processing task request"
                        PartnerRunStatus.waiting_approval -> "Waiting for approval"
                        PartnerRunStatus.completed -> "Run completed. Check the task result to verify the change."
                        PartnerRunStatus.failed -> "Run failed"
                        PartnerRunStatus.canceled -> "Run canceled"
                    })
                    PartnerButton("Refresh run", !state.busy && !state.decisionPending, onClick = onRefreshRun)
                    run.steps.forEach { step ->
                        if (step.approvalId != null) PartnerButton("Review task approval · step ${step.index}", !state.busy) { onReview(step) }
                        if (step.effectId != null && step.tool != PartnerTool.Read) PartnerButton("Check task result · step ${step.index}", !state.busy) { onVerify(step) }
                    }
                }
            }
            state.proof?.let { proof ->
                item {
                    PartnerStatus(when (proof) {
                        PartnerTaskProof.WaitingApproval -> "Task change is waiting for approval."
                        PartnerTaskProof.Queued -> "Task change is queued."
                        PartnerTaskProof.Failed -> "Task change failed."
                        PartnerTaskProof.Canceled -> "Task change was canceled."
                        is PartnerTaskProof.Verified -> "Verified task change · ${proof.task.title}\nReceipt ${proof.receiptId} · version ${proof.verifiedVersion}"
                    })
                }
            }
        }
        if (editable || state.savePending) {
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (editable) OutlinedTextField(
                    value = state.messageDraft,
                    onValueChange = onDraft,
                    label = { Text("Message") },
                    enabled = !state.savePending,
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 2,
                )
                val action = when {
                    state.saveAcknowledged -> "Check saved message"
                    state.savePending -> "Retry exact save"
                    else -> "Save message"
                }
                PartnerButton(action, !state.busy && (state.saveAcknowledged || editable &&
                    (state.savePending || state.messageDraft.isNotBlank())), primary = true, onClick = onSave)
            }
        }
    }
}

@Composable
fun PartnerDecisionButtons(enabled: Boolean, onDecision: (PartnerDecision) -> Unit) {
    PartnerButton("Approve reviewed change", enabled, primary = true) { onDecision(PartnerDecision.approve) }
    PartnerButton("Reject reviewed change", enabled) { onDecision(PartnerDecision.reject) }
}
