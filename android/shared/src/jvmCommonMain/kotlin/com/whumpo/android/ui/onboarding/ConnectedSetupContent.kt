package com.whumpo.android.ui.onboarding

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.whumpo.core.auth.CoreConnectedSetupState
import com.whumpo.core.auth.CoreSetupNotice
import com.whumpo.core.auth.CoreSetupQuestion
import java.time.ZoneId

@Composable
fun ConnectedSetupNextStepContent(
    onReview: () -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Your workspace is ready", style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() })
        Text("You can review your starter plan now or continue to your workspace.")
        WhumpoPrimaryButton(onClick = onReview, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("Review your next step")
        }
        WhumpoQuietButton(onClick = onContinue, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("Continue to workspace")
        }
    }
}

@Composable
fun ConnectedSetupContent(
    state: CoreConnectedSetupState,
    isOwner: Boolean,
    onAnswer: (CoreSetupQuestion, String?) -> Unit,
    onEdit: (CoreSetupQuestion) -> Unit,
    onSkipRemaining: () -> Unit,
    onCreate: () -> Unit,
    onRetry: () -> Unit,
    onRefresh: () -> Unit,
    onSignIn: () -> Unit,
    organizationActions: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    onDiscovery: (() -> Unit)? = null,
) {
    val question = state.question
    val profile = state.profile
    val editable = isOwner && state.canEdit && !state.busy && !state.pending && !state.reviewRequired
    var answer by remember(question, profile?.version) {
        mutableStateOf(question?.let { field -> profile?.let(field::answer) }.orEmpty())
    }
    val deviceZone = remember { ZoneId.systemDefault().id }
    val scroll = rememberLazyListState()
    LaunchedEffect(question, profile?.version) {
        if (question != null && profile != null) {
            val earlierAnswers = CoreSetupQuestion.entries.count { it.answer(profile) != null || it in state.deferred }
            scroll.scrollToItem(2 + earlierAnswers)
        }
    }

    Column(modifier.imePadding().padding(horizontal = 20.dp)) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = scroll, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("Workspace setup", style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.semantics { heading() }.padding(top = 12.dp))
            }
            item {
                val prompt = when {
                    state.readiness == null -> "I’m checking whether your workspace is ready."
                    !isOwner -> "Your workspace is waiting for an organization owner to finish setup."
                    profile == null -> "I’m loading your saved setup answers."
                    !state.canEdit -> "Finish verifying your account to continue setting up this workspace."
                    else -> null
                }
                prompt?.let { SetupMessage("Whumpo", it) }
                state.notice?.let { notice ->
                    Text(notice.label(), modifier = Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite })
                }
                if (state.busy) {
                    Text("Working…", modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
            }
            if (profile != null && isOwner && state.canEdit) {
                CoreSetupQuestion.entries.forEach { field ->
                    val saved = field.answer(profile)
                    if (saved != null || field in state.deferred) {
                        item(key = field.name) {
                            SetupMessage("You · ${field.title()}", saved ?: "Skipped for now")
                            if (question == null && editable) {
                                SetupQuietButton("Change ${field.title().lowercase()}") { onEdit(field) }
                            }
                        }
                    } else if (question == null && editable) {
                        item(key = field.name) {
                            SetupQuietButton("Add ${field.title().lowercase()}") { onEdit(field) }
                        }
                    }
                }
                if (question != null) {
                    item {
                        SetupMessage("Whumpo", question.prompt())
                        if (question == CoreSetupQuestion.TimeZone && deviceZone in ZoneId.getAvailableZoneIds()) {
                            SetupQuietButton("Use $deviceZone", editable) { onAnswer(question, deviceZone) }
                        }
                        if (question == CoreSetupQuestion.Cadence) {
                            listOf("Daily" to "daily", "Weekly" to "weekly", "No briefings" to "none").forEach { (label, value) ->
                                SetupQuietButton(label, editable) { onAnswer(question, value) }
                            }
                        }
                        SetupQuietButton("Skip this question", editable) { onAnswer(question, null) }
                        SetupQuietButton("Skip remaining questions", editable, onClick = onSkipRemaining)
                    }
                } else {
                    item {
                        SetupMessage("Whumpo", "When you’re ready, I’ll create your first project and Partner conversation.")
                        if (onDiscovery != null && editable) {
                            SetupMessage("Whumpo", "You can also tell me more about your business to review a starter plan.")
                            SetupQuietButton("Add business details", onClick = onDiscovery)
                        }
                    }
                }
            }
            item {
                if (state.notice == CoreSetupNotice.SignInAgain || isOwner && profile != null && !state.canEdit) {
                    SetupQuietButton("Sign in again", !state.busy, onClick = onSignIn)
                }
                SetupQuietButton("Refresh workspace status", !state.busy, onClick = onRefresh)
                organizationActions()
            }
        }
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when {
                isOwner && state.pending -> {
                    SetupPrimaryButton(if (state.acknowledged) "Check saved setup" else "Retry original request",
                        !state.busy, onClick = onRetry)
                }
                isOwner && state.canEdit && question == null -> {
                    SetupPrimaryButton("Create workspace", editable, onClick = onCreate)
                }
                isOwner && state.canEdit && question != null && question != CoreSetupQuestion.Cadence -> {
                    OutlinedTextField(
                        value = answer,
                        onValueChange = { if (it.length <= 8000) answer = it },
                        enabled = !state.busy,
                        label = { Text(question.title()) },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = if (question == CoreSetupQuestion.Goal) 3 else 1,
                        singleLine = question != CoreSetupQuestion.Goal,
                    )
                    SetupPrimaryButton("Save answer", editable && answer.isNotBlank()) { onAnswer(question, answer) }
                }
            }
        }
    }
}

@Composable
private fun SetupMessage(speaker: String, message: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(speaker, style = MaterialTheme.typography.labelLarge)
            Text(message)
        }
    }
}

@Composable
private fun SetupPrimaryButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    WhumpoPrimaryButton(onClick, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled) { Text(label) }
}

@Composable
private fun SetupQuietButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    WhumpoQuietButton(onClick, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled) { Text(label) }
}

private fun CoreSetupQuestion.title(): String = when (this) {
    CoreSetupQuestion.Organization -> "Organization name"
    CoreSetupQuestion.Owner -> "Owner name"
    CoreSetupQuestion.Goal -> "First goal"
    CoreSetupQuestion.TimeZone -> "Time zone"
    CoreSetupQuestion.Cadence -> "Briefing schedule"
}

private fun CoreSetupQuestion.prompt(): String = when (this) {
    CoreSetupQuestion.Organization -> "What should I call your organization?"
    CoreSetupQuestion.Owner -> "What name should I use for you?"
    CoreSetupQuestion.Goal -> "What would you like to accomplish first?"
    CoreSetupQuestion.TimeZone -> "Which time zone should I use for your briefings? You can enter a time zone such as America/Chicago."
    CoreSetupQuestion.Cadence -> "How often would you like a briefing?"
}

private fun CoreSetupNotice.label(): String = when (this) {
    CoreSetupNotice.Unavailable -> "I couldn’t read your workspace setup. Refresh to try again."
    CoreSetupNotice.InvalidAnswer -> "Check the answer and try again."
    CoreSetupNotice.RequestUnconfirmed -> "I couldn’t confirm that request. Retry sends the original request."
    CoreSetupNotice.ConfirmationPending -> "The service acknowledged your request. I’m waiting for its current setup record."
    CoreSetupNotice.ProfileChanged -> "The saved setup may have changed. Refresh to review it before trying again."
    CoreSetupNotice.SignInAgain -> "This request requires verified owner access. Sign in again to refresh your access."
    CoreSetupNotice.Rejected -> "The service refused this request. Refresh to review your saved setup."
}
