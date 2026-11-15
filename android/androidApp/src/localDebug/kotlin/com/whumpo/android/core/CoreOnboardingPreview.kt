package com.whumpo.android.core

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.whumpo.android.ui.theme.WhumpoPrimaryButton
import com.whumpo.android.ui.theme.WhumpoQuietButton
import com.whumpo.android.ui.theme.WhumpoSecondaryButton
import com.whumpo.android.ui.theme.WhumpoTheme
import com.whumpo.core.onboarding.CoreOnboardingFlow
import com.whumpo.core.onboarding.CoreOnboardingInputError
import com.whumpo.core.onboarding.CoreOnboardingInputProblem
import com.whumpo.core.onboarding.CoreOnboardingOrganization
import com.whumpo.core.onboarding.CoreOnboardingPlan
import com.whumpo.core.onboarding.CoreOnboardingQuestion
import com.whumpo.core.onboarding.CoreOnboardingQuickChoices
import com.whumpo.core.onboarding.CoreOnboardingSecurityMethod
import com.whumpo.core.onboarding.CoreOnboardingSecurityTask
import com.whumpo.core.onboarding.CoreOnboardingSheet
import com.whumpo.core.onboarding.CoreOnboardingSnapshot
import com.whumpo.core.onboarding.CoreOnboardingStep
import com.whumpo.core.onboarding.CoreOnboardingTeamConflict
import com.whumpo.core.onboarding.CoreOnboardingTeamIntent

private data class PreviewActions(
    val start: () -> Boolean,
    val selectPlan: (CoreOnboardingPlan) -> Boolean,
    val back: () -> Boolean,
    val keepJustMe: () -> Boolean,
    val switchToTeamPlan: (CoreOnboardingPlan) -> Boolean,
    val proceedFromCompletedStep: () -> Boolean,
    val confirmPayment: () -> Boolean,
    val failPayment: () -> Boolean,
    val openPaymentSheet: () -> Boolean,
    val cancelPayment: () -> Boolean,
    val selectSecurityMethod: (CoreOnboardingSecurityMethod) -> Boolean,
    val editSecurity: () -> Boolean,
    val confirmSecurityTask: (CoreOnboardingSecurityTask) -> Boolean,
    val failSecurityTask: (CoreOnboardingSecurityTask) -> Boolean,
    val openSecuritySheet: (CoreOnboardingSecurityTask) -> Boolean,
    val cancelSecurityTask: (CoreOnboardingSecurityTask) -> Boolean,
    val chooseTeamIntent: (CoreOnboardingTeamIntent) -> Boolean,
    val answer: (String) -> Boolean,
    val editPlan: () -> Boolean,
    val submitEdit: (String) -> Boolean,
    val cancelEdit: () -> Boolean,
    val startEdit: (CoreOnboardingQuestion) -> Boolean,
    val createOrganization: () -> Unit,
)

/**
 * Offline onboarding walkthrough for the signed-out debug harness. It never touches the session controller,
 * network, credential manager or storage; the flow lives in `remember` only and is discarded on exit.
 */
@Composable
internal fun CoreOnboardingPreview(close: () -> Unit) {
    val flow = remember { CoreOnboardingFlow() }
    val state by flow.state.collectAsStateWithLifecycle()
    val exit: () -> Unit = {
        flow.reset()
        close()
    }
    DisposableEffect(flow) { onDispose { flow.reset() } }
    BackHandler { if (!flow.back()) exit() }
    val actions = remember(flow) {
        PreviewActions(
            start = flow::start,
            selectPlan = flow::selectPlan,
            back = flow::back,
            keepJustMe = flow::keepJustMe,
            switchToTeamPlan = flow::switchToTeamPlan,
            proceedFromCompletedStep = flow::proceedFromCompletedStep,
            confirmPayment = flow::confirmPayment,
            failPayment = flow::failPayment,
            openPaymentSheet = flow::openPaymentSheet,
            cancelPayment = flow::cancelPayment,
            selectSecurityMethod = flow::selectSecurityMethod,
            editSecurity = flow::editSecurity,
            confirmSecurityTask = flow::confirmSecurityTask,
            failSecurityTask = flow::failSecurityTask,
            openSecuritySheet = flow::openSecuritySheet,
            cancelSecurityTask = flow::cancelSecurityTask,
            chooseTeamIntent = flow::chooseTeamIntent,
            answer = flow::answer,
            editPlan = flow::editPlan,
            submitEdit = flow::submitEdit,
            cancelEdit = flow::cancelEdit,
            startEdit = flow::startEdit,
            createOrganization = { flow.createOrganization() },
        )
    }
    OnboardingPreviewTheme {
        Surface { CoreOnboardingPreviewContent(state, actions, exit) }
    }
}

/** The preview's neutral surfaces and existing gold token do not change the connected harness theme. */
@Composable
private fun OnboardingPreviewTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val surface = if (dark) Color.Black else Color.White
    val ink = if (dark) Color.White else Color.Black
    val raised = ink.copy(alpha = if (dark) 0.08f else 0.04f).compositeOver(surface)
    val gold = WhumpoTheme.colors.sunshine
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme.copy(
            primary = ink,
            onPrimary = surface,
            primaryContainer = gold,
            onPrimaryContainer = Color.Black,
            secondary = ink,
            onSecondary = surface,
            secondaryContainer = raised,
            onSecondaryContainer = ink,
            background = surface,
            onBackground = ink,
            surface = surface,
            onSurface = ink,
            surfaceVariant = raised,
            onSurfaceVariant = ink.copy(alpha = 0.72f).compositeOver(surface),
            surfaceTint = gold,
            surfaceContainerLowest = surface,
            surfaceContainerLow = raised,
            surfaceContainer = raised,
            surfaceContainerHigh = raised,
            surfaceContainerHighest = raised,
            outline = ink.copy(alpha = 0.5f).compositeOver(surface),
            outlineVariant = ink.copy(alpha = 0.2f).compositeOver(surface),
        ),
        content = content,
    )
}

/** Rendering receives a snapshot and callbacks, with no session, service, or credential dependencies. */
@Composable
private fun CoreOnboardingPreviewContent(state: CoreOnboardingSnapshot, actions: PreviewActions, exit: () -> Unit) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
        PreviewBar(state.step, exit)
        HorizontalDivider()
        Box(Modifier.weight(1f)) {
            key(state.step) {
                when (state.step) {
                    CoreOnboardingStep.Welcome -> WelcomeStep(start = { actions.start() }, exit = exit)
                    CoreOnboardingStep.Plan -> PlanStep(state, actions)
                    CoreOnboardingStep.Payment -> PaymentStep(state, actions)
                    CoreOnboardingStep.Security -> SecurityStep(state, actions)
                    CoreOnboardingStep.Conversation -> ConversationStep(state, actions)
                    CoreOnboardingStep.Review -> ReviewStep(state, actions)
                    CoreOnboardingStep.Workspace -> state.organization?.let { OfflineWorkspace(it, exit) }
                }
            }
        }
    }
}

@Composable
private fun PreviewBar(step: CoreOnboardingStep, exit: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.semantics {
                contentDescription = "Onboarding preview. Demo only: nothing is charged, registered, sent or saved."
            },
        ) {
            Text("Onboarding preview", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stepPosition(step).orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            WhumpoQuietButton(onClick = exit) { Text("Exit preview") }
        }
    }
}

private fun stepPosition(step: CoreOnboardingStep): String? = when (step) {
    CoreOnboardingStep.Welcome, CoreOnboardingStep.Workspace -> null
    else -> "Step ${step.ordinal} of 5"
}

@Composable
private fun StepPage(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        content()
    }
}

@Composable
private fun WelcomeStep(start: () -> Unit, exit: () -> Unit) = StepPage("Set up Whumpo") {
    Text(
        "Choose a plan, confirm a demo checkout, choose a demo account security method, then answer a few questions to set up your first organization.",
        style = MaterialTheme.typography.bodyLarge,
    )
    Text(
        "This is a preview. Nothing is charged, no credentials are created, and nothing is sent or saved. Leaving the preview discards it.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    WhumpoPrimaryButton(onClick = start, modifier = Modifier.fillMaxWidth()) { Text("Start") }
    WhumpoQuietButton(onClick = exit) { Text("Not now") }
}

@Composable
private fun PlanStep(state: CoreOnboardingSnapshot, actions: PreviewActions) = StepPage("Choose a plan") {
    Text(
        "Billing belongs to the organization. Your account belongs to you, so you can join other organizations later.",
        style = MaterialTheme.typography.bodyMedium,
    )
    state.conflict?.let { TeamConflictPanel(it, actions) }
    CoreOnboardingPlan.entries.forEach { plan ->
        val current = plan == state.plan
        Card(
            onClick = { actions.selectPlan(plan) },
            enabled = state.conflict == null,
            modifier = Modifier.fillMaxWidth().semantics { selected = current },
            colors = if (current) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            else CardDefaults.cardColors(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(plan.label, style = MaterialTheme.typography.titleMedium)
                Text(planScope(plan))
                Text("Plan price to be confirmed", style = MaterialTheme.typography.bodySmall)
                if (current) {
                    Text(
                        if (state.paymentConfirmed) "Current plan · demo payment confirmed" else "Current plan",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
    WhumpoQuietButton(onClick = { actions.back() }) { Text("Back") }
}

private fun planScope(plan: CoreOnboardingPlan) =
    if (plan.allowsTeam) "For a team. You can invite people later." else "For one person across their devices."

@Composable
private fun TeamConflictPanel(conflict: CoreOnboardingTeamConflict, actions: PreviewActions) {
    OutlinedCard(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("A team needs a team plan", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text("${conflict.singlePersonPlan.label} is for one person. Business and Business Plus allow a team.")
            Text(
                "Switching plans means confirming the demo checkout again. Your answers are kept.",
                style = MaterialTheme.typography.bodySmall,
            )
            CoreOnboardingPlan.entries.filter { it.allowsTeam }.forEach { plan ->
                WhumpoSecondaryButton(onClick = { actions.switchToTeamPlan(plan) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Switch to ${plan.label}")
                }
            }
            WhumpoQuietButton(onClick = { actions.keepJustMe() }) { Text("Keep just me on ${conflict.singlePersonPlan.label}") }
        }
    }
}

@Composable
private fun PaymentStep(state: CoreOnboardingSnapshot, actions: PreviewActions) = StepPage("Demo checkout") {
    val plan = state.plan ?: return@StepPage
    SummaryCard {
        Text("Plan: ${plan.label}", style = MaterialTheme.typography.titleMedium)
        Text("Billed to the organization you're about to set up.")
        Text("Plan price to be confirmed", style = MaterialTheme.typography.bodySmall)
        Text("Demo total: $0.00", style = MaterialTheme.typography.bodyLarge)
    }
    if (state.repaymentRequired && !state.paymentConfirmed) {
        Notice("Your plan changed, so the earlier demo confirmation no longer applies. Confirm again; your answers are kept.")
    }
    when {
        state.paymentConfirmed -> {
            Notice("Demo payment confirmed for ${plan.label}. Nothing was charged.")
            WhumpoPrimaryButton(onClick = { actions.proceedFromCompletedStep() }, modifier = Modifier.fillMaxWidth()) { Text("Continue") }
        }
        state.payment == CoreOnboardingSheet.Open || state.payment == CoreOnboardingSheet.Failed -> DemoSheet(
            title = "Demo payment sheet",
            explanation = "Demo only. No payment method is collected and nothing is charged. Total $0.00.",
            failed = state.payment == CoreOnboardingSheet.Failed,
            failure = "The demo payment did not go through. Nothing was charged.",
            confirmLabel = "Confirm demo payment",
            confirm = { actions.confirmPayment() },
            fail = { actions.failPayment() },
            retry = { actions.openPaymentSheet() },
            cancel = { actions.cancelPayment() },
        )
        else -> {
            if (state.payment == CoreOnboardingSheet.Cancelled) Notice("Demo checkout cancelled. Nothing was charged.")
            WhumpoPrimaryButton(onClick = { actions.openPaymentSheet() }, modifier = Modifier.fillMaxWidth()) {
                Text("Open demo payment sheet")
            }
        }
    }
    WhumpoQuietButton(onClick = { actions.back() }) { Text("Change plan") }
}

@Composable
private fun SecurityStep(state: CoreOnboardingSnapshot, actions: PreviewActions) = StepPage("Secure your account") {
    Text("Choose a method for your own account. You can later join multiple organizations with that account.")
    Notice("Demo only. No passwords, authenticator secrets, passkeys or recovery files are collected, created or saved.")
    CoreOnboardingSecurityMethod.entries.forEach { method ->
        Card(
            onClick = { actions.selectSecurityMethod(method) },
            modifier = Modifier.fillMaxWidth().semantics { selected = method == state.security.method },
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    if (method == CoreOnboardingSecurityMethod.Passkeys) "Recommended: two passkeys"
                    else "Use a password and authenticator instead",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    if (method == CoreOnboardingSecurityMethod.Passkeys) {
                        "Register two distinct passkeys and save a recovery kit. Use either passkey to sign in; you do not need both each time."
                    } else "Set a password, verify an authenticator app, and save one-time recovery codes. No passkey is required.",
                )
                if (method == state.security.method) Text("Selected", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
    Text("Choosing a different method clears its demo setup progress. Your plan, payment and organization answers are kept.", style = MaterialTheme.typography.bodySmall)
    val security = state.security
    if (state.securitySetupComplete) {
        Notice("Demo security setup complete: ${security.method?.label}.")
        WhumpoPrimaryButton(onClick = { actions.proceedFromCompletedStep() }, modifier = Modifier.fillMaxWidth()) { Text("Continue") }
    } else security.nextTask?.let { task ->
        val details = securityTaskDetails(task, security.method)
        Text(details.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Text(details.explanation)
        if (security.firstPasskey) Notice("First demo passkey confirmed.")
        if (security.backupPasskey) Notice("Distinct backup demo passkey confirmed.")
        if (security.password) Notice("Demo password setup confirmed. No password was entered.")
        if (security.authenticator) Notice("Demo authenticator setup confirmed. No secret or code was entered.")
        when (security.sheet) {
            CoreOnboardingSheet.Open, CoreOnboardingSheet.Failed -> DemoSheet(
                title = details.title,
                explanation = details.explanation,
                failed = security.sheet == CoreOnboardingSheet.Failed,
                failure = "This demo step did not complete. Try again or cancel.",
                confirmLabel = details.confirmLabel,
                confirm = { actions.confirmSecurityTask(task) },
                fail = { actions.failSecurityTask(task) },
                retry = { actions.openSecuritySheet(task) },
                cancel = { actions.cancelSecurityTask(task) },
            )
            CoreOnboardingSheet.Idle, CoreOnboardingSheet.Cancelled -> {
                if (security.sheet == CoreOnboardingSheet.Cancelled) Notice("Demo step cancelled. This step still needs confirmation.")
                WhumpoPrimaryButton(onClick = { actions.openSecuritySheet(task) }, modifier = Modifier.fillMaxWidth()) {
                    Text(details.openLabel)
                }
            }
        }
    }
    WhumpoQuietButton(onClick = { actions.back() }) { Text("Back to checkout") }
}

private data class SecurityTaskDetails(val title: String, val explanation: String, val openLabel: String, val confirmLabel: String)

private fun securityTaskDetails(task: CoreOnboardingSecurityTask, method: CoreOnboardingSecurityMethod?) = when (task) {
    CoreOnboardingSecurityTask.FirstPasskey -> SecurityTaskDetails(
        "First demo passkey", "Simulate registering your first passkey. No device credential screen opens and no credential is created.",
        "Set up first demo passkey", "Confirm first demo passkey",
    )
    CoreOnboardingSecurityTask.BackupPasskey -> SecurityTaskDetails(
        "Backup demo passkey",
        "Simulate a second distinct passkey. A synced copy of the first is not a second credential. A separate authenticator or provider can reduce shared loss risk; this demo does not verify physical independence.",
        "Set up backup demo passkey", "Confirm distinct backup demo passkey",
    )
    CoreOnboardingSecurityTask.Password -> SecurityTaskDetails(
        "Demo password setup", "Simulate choosing a password. Do not enter a real password; this preview has no password field or password storage.",
        "Simulate password setup", "Confirm demo password setup",
    )
    CoreOnboardingSecurityTask.Authenticator -> SecurityTaskDetails(
        "Demo authenticator setup", "Simulate verifying an authenticator-app TOTP code. No QR code, authenticator secret or real code is requested or generated.",
        "Simulate authenticator setup", "Confirm demo authenticator setup",
    )
    CoreOnboardingSecurityTask.Recovery -> if (method == CoreOnboardingSecurityMethod.Passkeys) SecurityTaskDetails(
        "Demo recovery kit", "NOT A REAL RECOVERY KEY. In real setup you must save your recovery kit. Here, acknowledge the simulated save; no usable key or downloadable file exists.",
        "Review demo recovery kit", "Acknowledge demo kit saved",
    ) else SecurityTaskDetails(
        "Demo recovery codes", "NOT REAL RECOVERY CODES. In real setup you must save one-time recovery codes. Here, acknowledge the simulated save; no usable codes or downloadable file exists.",
        "Review demo recovery codes", "Acknowledge demo codes saved",
    )
}

/** An explicitly labelled app sheet; it never invokes system payment or credential UI. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DemoSheet(
    title: String,
    explanation: String,
    failed: Boolean,
    failure: String,
    confirmLabel: String,
    confirm: () -> Unit,
    fail: () -> Unit,
    retry: () -> Unit,
    cancel: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = cancel, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Onboarding preview · demo only", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text(explanation)
            if (failed) {
                Text(
                    failure,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                WhumpoPrimaryButton(onClick = retry, modifier = Modifier.fillMaxWidth()) { Text("Try again") }
            } else {
                WhumpoPrimaryButton(onClick = confirm, modifier = Modifier.fillMaxWidth()) { Text(confirmLabel) }
                WhumpoSecondaryButton(onClick = fail, modifier = Modifier.fillMaxWidth()) { Text("Simulate failure") }
            }
            WhumpoQuietButton(onClick = cancel) { Text("Cancel") }
        }
    }
}

private data class ChatLine(val fromGuide: Boolean, val text: String)

private fun transcript(state: CoreOnboardingSnapshot): List<ChatLine> {
    val answers = state.answers
    val lines = mutableListOf<ChatLine>()
    for (question in CoreOnboardingQuestion.entries) {
        val prompt = when (question) {
            CoreOnboardingQuestion.OwnerName -> "Hi! What should we call you?"
            CoreOnboardingQuestion.OrganizationName -> "Thanks, ${answers.ownerName}. What's the name of your organization?"
            CoreOnboardingQuestion.BusinessDescription -> "In a sentence or two, what does ${answers.organizationName} do?"
            CoreOnboardingQuestion.TeamIntent -> "Will it be just you, or will you work with a team?"
            CoreOnboardingQuestion.FirstPriority -> "What's the first thing you'd like to get done?"
        }
        lines += ChatLine(true, prompt)
        if (!answers.isAnswered(question)) return lines
        lines += ChatLine(false, if (question == CoreOnboardingQuestion.TeamIntent) teamLabel(answers.teamIntent) else answers.text(question).orEmpty())
    }
    lines += ChatLine(true, "That's everything. Review your setup before creating ${answers.organizationName}.")
    return lines
}

private fun teamLabel(intent: CoreOnboardingTeamIntent?) = when (intent) {
    CoreOnboardingTeamIntent.JustMe -> "Just me"
    CoreOnboardingTeamIntent.Team -> "With a team"
    null -> "Not answered"
}

@Composable
private fun ConversationStep(state: CoreOnboardingSnapshot, actions: PreviewActions) {
    val lines = transcript(state)
    val conflict = state.conflict
    val list = rememberLazyListState()
    // The composer shares the scrolling surface, so it remains reachable with a large font and the IME open.
    val lastIndex = lines.size + 1
    LaunchedEffect(lastIndex) { list.animateScrollToItem(lastIndex) }
    val question = state.currentQuestion
    LazyColumn(
        Modifier.fillMaxSize(),
        state = list,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                "Scripted setup questions. No AI or network is used in this preview.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(lines) { ChatBubble(it) }
        if (conflict != null) item { TeamConflictPanel(conflict, actions) }
        else item {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HorizontalDivider()
                when (question) {
                    null -> WhumpoPrimaryButton(onClick = { actions.proceedFromCompletedStep() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Review setup")
                    }
                    CoreOnboardingQuestion.TeamIntent -> TeamChoices { actions.chooseTeamIntent(it) }
                    else -> key(question) {
                        TextAnswer(question, null, state.inputError, submitLabel = "Send") { actions.answer(it) }
                    }
                }
                WhumpoQuietButton(onClick = { actions.back() }) { Text("Back to security") }
            }
        }
    }
}

@Composable
private fun ChatBubble(line: ChatLine) {
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
            contentDescription = (if (line.fromGuide) "Setup guide: " else "You: ") + line.text
        },
        horizontalArrangement = if (line.fromGuide) Arrangement.Start else Arrangement.End,
    ) {
        if (!line.fromGuide) Spacer(Modifier.weight(0.12f))
        Surface(
            color = if (line.fromGuide) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.primaryContainer,
            contentColor = if (line.fromGuide) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onPrimaryContainer,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.weight(0.88f, fill = false),
        ) {
            Text(line.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
        }
        if (line.fromGuide) Spacer(Modifier.weight(0.12f))
    }
}

@Composable
private fun TeamChoices(choose: (CoreOnboardingTeamIntent) -> Unit) {
    WhumpoSecondaryButton(onClick = { choose(CoreOnboardingTeamIntent.JustMe) }, modifier = Modifier.fillMaxWidth()) { Text("Just me") }
    WhumpoSecondaryButton(onClick = { choose(CoreOnboardingTeamIntent.Team) }, modifier = Modifier.fillMaxWidth()) { Text("With a team") }
}

private fun fieldLabel(question: CoreOnboardingQuestion) = when (question) {
    CoreOnboardingQuestion.OwnerName -> "Your name"
    CoreOnboardingQuestion.OrganizationName -> "Organization name"
    CoreOnboardingQuestion.BusinessDescription -> "What the business does"
    CoreOnboardingQuestion.TeamIntent -> "Team"
    CoreOnboardingQuestion.FirstPriority -> "First priority"
}

private fun errorMessage(error: CoreOnboardingInputError): String = when (error.problem) {
    CoreOnboardingInputProblem.Empty -> "Enter ${fieldLabel(error.question).lowercase()}."
    CoreOnboardingInputProblem.TooLong ->
        "Use ${error.question.maxLength} characters or fewer. This has ${error.rejected.trim().length}."
}

/**
 * Composer for one text answer. The draft stays in the field after a rejected submit so the person can correct
 * it; the error shows only while the field still holds the rejected text.
 */
@Composable
private fun TextAnswer(
    question: CoreOnboardingQuestion,
    initial: String?,
    inputError: CoreOnboardingInputError?,
    submitLabel: String,
    submit: (String) -> Unit,
) {
    val max = question.maxLength ?: return
    var draft by remember(question) {
        mutableStateOf(inputError?.takeIf { it.question == question }?.rejected ?: initial.orEmpty())
    }
    val error = inputError?.takeIf { it.question == question && it.rejected == draft }
    val quick = when (question) {
        CoreOnboardingQuestion.BusinessDescription -> CoreOnboardingQuickChoices.business
        CoreOnboardingQuestion.FirstPriority -> CoreOnboardingQuickChoices.firstPriority
        else -> emptyList()
    }
    if (quick.isNotEmpty()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            quick.forEach { choice -> SuggestionChip(onClick = { draft = choice }, label = { Text(choice) }) }
        }
    }
    val multiline = question == CoreOnboardingQuestion.BusinessDescription
    OutlinedTextField(
        value = draft,
        onValueChange = { draft = it },
        label = { Text(fieldLabel(question)) },
        isError = error != null,
        supportingText = {
            Text(
                error?.let(::errorMessage) ?: "${draft.trim().length} / $max",
                modifier = if (error != null) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier,
            )
        },
        singleLine = !multiline,
        maxLines = if (multiline) 4 else 1,
        keyboardOptions = KeyboardOptions(imeAction = if (multiline) ImeAction.Default else ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { submit(draft) }),
        modifier = Modifier.fillMaxWidth(),
    )
    WhumpoPrimaryButton(onClick = { submit(draft) }, modifier = Modifier.fillMaxWidth()) { Text(submitLabel) }
}

@Composable
private fun ReviewStep(state: CoreOnboardingSnapshot, actions: PreviewActions) = StepPage("Review your setup") {
    val answers = state.answers
    val plan = state.plan ?: return@StepPage
    state.conflict?.let { TeamConflictPanel(it, actions) }
    val idle = state.editing == null && state.conflict == null
    ReviewRow("Plan", "${plan.label} · Plan price to be confirmed", "Change plan", idle) { actions.editPlan() }
    ReviewRow("Account security", state.security.method?.label.orEmpty(), "Change security", idle) { actions.editSecurity() }
    CoreOnboardingQuestion.entries.forEach { question ->
        if (state.editing == question) {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (question == CoreOnboardingQuestion.TeamIntent) {
                        Text("Team", style = MaterialTheme.typography.titleMedium)
                        TeamChoices { actions.chooseTeamIntent(it) }
                    } else {
                        TextAnswer(question, answers.text(question), state.inputError, submitLabel = "Save") { actions.submitEdit(it) }
                    }
                    WhumpoQuietButton(onClick = { actions.cancelEdit() }) { Text("Cancel") }
                }
            }
        } else {
            val value = if (question == CoreOnboardingQuestion.TeamIntent) teamLabel(answers.teamIntent) else answers.text(question).orEmpty()
            ReviewRow(fieldLabel(question), value, "Edit", idle) { actions.startEdit(question) }
        }
    }
    SummaryCard {
        Text(if (state.paymentConfirmed) "Demo payment confirmed" else "Demo payment needed")
        Text(if (state.securitySetupComplete) "Demo security setup complete; recovery save acknowledged" else "Demo security setup needed")
    }
    Text(
        "Creates one organization in this preview only. Nothing is sent or saved.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    WhumpoPrimaryButton(onClick = { actions.createOrganization() }, enabled = state.canCreate, modifier = Modifier.fillMaxWidth()) {
        Text("Create ${answers.organizationName}")
    }
    WhumpoQuietButton(onClick = { actions.back() }) { Text("Back to conversation") }
}

@Composable
private fun ReviewRow(label: String, value: String, action: String, enabled: Boolean, edit: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyLarge)
        }
        WhumpoQuietButton(
            onClick = edit,
            enabled = enabled,
            modifier = Modifier.semantics { contentDescription = "$action: $label" },
        ) { Text(action) }
    }
}

private enum class WorkspaceTab(val label: String) { Today("Today"), Work("Work"), Account("Account") }

@Composable
private fun OfflineWorkspace(organization: CoreOnboardingOrganization, exit: () -> Unit) {
    var tab by remember { mutableStateOf(WorkspaceTab.Today) }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            WorkspaceTab.entries.forEach { entry ->
                FilterChip(selected = tab == entry, onClick = { tab = entry }, label = { Text(entry.label) })
            }
        }
        key(tab) {
            StepPage(tab.label) {
                Text(organization.name, style = MaterialTheme.typography.labelLarge)
                Notice("Offline preview workspace. It exists only until you exit.")
                when (tab) {
                    WorkspaceTab.Today -> {
                        Text("Welcome, ${organization.ownerName}.", style = MaterialTheme.typography.titleMedium)
                        SummaryCard {
                            Text("First priority · draft", style = MaterialTheme.typography.labelLarge)
                            Text(organization.firstGoalDraft, style = MaterialTheme.typography.bodyLarge)
                            Text("Kept on this screen only. Nothing has been scheduled or assigned.", style = MaterialTheme.typography.bodySmall)
                        }
                        Text("Reviews", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                        Text("No reviews yet. Decisions that need you will appear here.")
                        Text("Website", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                        Text("No website yet. Nothing has been built or published.")
                    }
                    WorkspaceTab.Work -> {
                        SummaryCard {
                            Text(organization.firstGoalDraft, style = MaterialTheme.typography.titleMedium)
                            Text("Draft · not started")
                        }
                        Text(
                            "This draft comes from your setup answers. No task has been created on a server.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    WorkspaceTab.Account -> {
                        SummaryCard {
                            Text("You", style = MaterialTheme.typography.labelLarge)
                            Text(organization.ownerName, style = MaterialTheme.typography.titleMedium)
                            Text("Security: ${organization.securityMethod.label}")
                            Text("Demo setup complete; recovery save acknowledged. No real credentials or recovery material exist. Your account can belong to more than one organization later.")
                        }
                        SummaryCard {
                            Text("Organization", style = MaterialTheme.typography.labelLarge)
                            Text(organization.name, style = MaterialTheme.typography.titleMedium)
                            Text(organization.businessDescription)
                            Text("Plan: ${organization.plan.label} · Plan price to be confirmed")
                            Text("Billing belongs to ${organization.name}.", style = MaterialTheme.typography.bodySmall)
                        }
                        SummaryCard {
                            Text("Team", style = MaterialTheme.typography.labelLarge)
                            Text(
                                if (organization.teamIntent == CoreOnboardingTeamIntent.Team) {
                                    "You plan to work with a team. Invitations aren't available in this preview, and no one has been invited."
                                } else "Just you for now.",
                            )
                        }
                        WhumpoSecondaryButton(onClick = exit, modifier = Modifier.fillMaxWidth()) { Text("Exit preview") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
    }
}

@Composable
private fun Notice(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}
