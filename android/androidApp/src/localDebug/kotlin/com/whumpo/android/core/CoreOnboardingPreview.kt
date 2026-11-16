package com.whumpo.android.core

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.whumpo.android.ui.theme.WhumpoPrimaryButton
import com.whumpo.android.ui.theme.WhumpoQuietButton
import com.whumpo.android.ui.theme.WhumpoSecondaryButton
import com.whumpo.android.ui.theme.WhumpoEntryTheme
import com.whumpo.core.onboarding.CoreDiscoveryAnswer
import com.whumpo.core.onboarding.CoreDiscoveryQuestion
import com.whumpo.core.onboarding.CoreStarterKind
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
    val acknowledgeRecovery: (CoreOnboardingSecurityMethod) -> Boolean,
    val confirmSecurityTask: (CoreOnboardingSecurityTask) -> Boolean,
    val failSecurityTask: (CoreOnboardingSecurityTask) -> Boolean,
    val openSecuritySheet: (CoreOnboardingSecurityTask) -> Boolean,
    val cancelSecurityTask: (CoreOnboardingSecurityTask) -> Boolean,
    val chooseTeamIntent: (CoreOnboardingTeamIntent) -> Boolean,
    val answer: (CoreOnboardingQuestion, String) -> Boolean,
    val editPlan: () -> Boolean,
    val submitEdit: (String) -> Boolean,
    val cancelEdit: () -> Boolean,
    val startEdit: (CoreOnboardingQuestion) -> Boolean,
    val answerDiscovery: (CoreDiscoveryQuestion, CoreDiscoveryAnswer) -> Boolean,
    val deferDiscovery: () -> Boolean,
    val deferCoreQuestion: () -> Boolean,
    val startDiscoveryEdit: (CoreDiscoveryQuestion) -> Boolean,
    val selectNextAction: (CoreStarterKind) -> Boolean,
    val editStarterTitle: (CoreStarterKind, String) -> Boolean,
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
            acknowledgeRecovery = flow::acknowledgeRecovery,
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
            answerDiscovery = flow::answerDiscovery,
            deferDiscovery = flow::deferDiscovery,
            deferCoreQuestion = flow::deferCoreQuestion,
            startDiscoveryEdit = flow::startDiscoveryEdit,
            selectNextAction = flow::selectNextAction,
            editStarterTitle = flow::editStarterTitle,
            createOrganization = { flow.createOrganization() },
        )
    }
    WhumpoEntryTheme {
        Surface { CoreOnboardingPreviewContent(state, actions, exit) }
    }
}

/** Rendering receives a snapshot and callbacks, with no session, service, or credential dependencies. */
@Composable
private fun CoreOnboardingPreviewContent(state: CoreOnboardingSnapshot, actions: PreviewActions, exit: () -> Unit) {
    Box(Modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 600.dp).fillMaxSize()) {
            PreviewBar(state.step, exit)
            HorizontalDivider()
            Box(Modifier.weight(1f)) {
                key(state.step) {
                    when (state.step) {
                        CoreOnboardingStep.Welcome -> WelcomeStep(start = { actions.start() }, exit = exit)
                        CoreOnboardingStep.Plan -> PlanStep(state, actions)
                        CoreOnboardingStep.Payment -> PaymentStep(state, actions)
                        CoreOnboardingStep.Security -> key(state.securityChoiceVisible, state.security.nextTask, state.security.recoverySaved) {
                            SecurityStep(state, actions)
                        }
                        CoreOnboardingStep.Conversation -> ConversationStep(state, actions)
                        CoreOnboardingStep.Review -> ReviewStep(state, actions)
                        CoreOnboardingStep.Workspace -> state.organization?.let { OfflineWorkspace(it, exit) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewBar(step: CoreOnboardingStep, exit: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Whumpo", style = MaterialTheme.typography.titleMedium)
            Text(stepPosition(step), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        WhumpoQuietButton(onClick = exit, modifier = Modifier.heightIn(min = 48.dp)) { Text("Exit preview") }
    }
}

private fun stepPosition(step: CoreOnboardingStep): String = when (step) {
    CoreOnboardingStep.Welcome -> "Offline preview"
    CoreOnboardingStep.Plan -> "Setup preview"
    CoreOnboardingStep.Payment -> "Checkout"
    CoreOnboardingStep.Security -> "Account security"
    CoreOnboardingStep.Conversation -> "Business details"
    CoreOnboardingStep.Review -> "Review"
    CoreOnboardingStep.Workspace -> "Preview workspace"
}

@Composable
private fun StepPage(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        content()
    }
}

@Composable
private fun WelcomeStep(start: () -> Unit, exit: () -> Unit) = StepPage("Let’s set up your workspace") {
    Guide("I’ll help you choose a plan, practice securing your account, and decide what to work on first.")
    Text(
        "This is a preview. Nothing is charged, no credentials are created, and nothing is sent or saved. Leaving the preview discards it.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    WhumpoPrimaryButton(onClick = start, modifier = Modifier.fillMaxWidth()) { Text("Start") }
    WhumpoQuietButton(onClick = exit) { Text("Not now") }
}

@Composable
private fun PlanStep(state: CoreOnboardingSnapshot, actions: PreviewActions) = StepPage("How will you work?") {
    var choice by remember(state.plan) { mutableStateOf(state.plan) }
    Text("Choose a plan for this organization. Billing stays with the organization.")
    state.conflict?.let { TeamConflictPanel(it, actions) }
    Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CoreOnboardingPlan.entries.forEach { plan ->
            val chosen = choice == plan
            Surface(
                color = if (chosen) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .selectable(chosen, enabled = state.conflict == null, role = Role.RadioButton, onClick = { choice = plan })
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = chosen, onClick = null)
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(plan.label, style = MaterialTheme.typography.titleMedium)
                        Text(planScope(plan), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
    Notice("Proposed plans. Prices are still to be confirmed.")
    WhumpoPrimaryButton(
        onClick = { choice?.let { actions.selectPlan(it) } },
        enabled = choice != null && state.conflict == null,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) { Text(choice?.let { "Continue with ${it.label}" } ?: "Continue") }
    WhumpoQuietButton(onClick = { actions.back() }) { Text("Back") }
}

private fun planScope(plan: CoreOnboardingPlan) = when (plan) {
    CoreOnboardingPlan.Solo -> "For one person managing tasks and small website projects."
    CoreOnboardingPlan.Growth -> "For one person with recurring work and more projects."
    CoreOnboardingPlan.Business -> "For a team that shares and assigns work."
    CoreOnboardingPlan.BusinessPlus -> "For larger teams planning staged releases and more oversight."
}

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
    Guide("Check the plan before we move on. This checkout is a practice run.")
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
    Guide(if (state.securityChoiceVisible) "Choose a method for your own account. You can later join multiple organizations with that account." else state.security.method?.label.orEmpty())
    Notice("Demo only. No passwords, authenticator secrets, passkeys or recovery files are collected, created or saved.")
    if (state.securityChoiceVisible) {
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
                        } else "Set a password, verify an authenticator app, and save ten one-time recovery codes. No passkey is required.",
                    )
                    if (method == state.security.method) Text("Continue with this method", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        Text("Choosing a different method clears its demo setup progress. Your plan, payment and organization answers are kept.", style = MaterialTheme.typography.bodySmall)
        WhumpoQuietButton(onClick = { actions.back() }) { Text("Back to checkout") }
        return@StepPage
    }
    val security = state.security
    if (state.securitySetupComplete) {
        Notice("Demo security setup complete: ${security.method?.label}.")
        WhumpoPrimaryButton(onClick = { actions.proceedFromCompletedStep() }, modifier = Modifier.fillMaxWidth()) { Text("Continue") }
    } else if (security.recoverySaved) {
        Text("Acknowledge the simulated save", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Text("The demo save step is complete. In real setup, check that you can access your saved recovery material before continuing. This preview has not created or saved a file.")
        val method = requireNotNull(security.method)
        Notice(if (method == CoreOnboardingSecurityMethod.Passkeys) "NOT A REAL RECOVERY KEY" else "NOT REAL RECOVERY CODES")
        WhumpoPrimaryButton(onClick = { actions.acknowledgeRecovery(method) }, modifier = Modifier.fillMaxWidth()) {
            Text(if (method == CoreOnboardingSecurityMethod.Passkeys) "Acknowledge demo kit saved" else "Acknowledge demo codes saved")
        }
    } else security.nextTask?.let { task ->
        val details = securityTaskDetails(task, security.method)
        Text(details.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Text(details.explanation)
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
    WhumpoQuietButton(onClick = { actions.back() }) { Text("Back to security choices") }
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
        "Demo recovery kit", "NOT A REAL RECOVERY KEY. In real setup you must save your recovery kit. Here, simulate saving, then acknowledge in a separate step; no usable key or downloadable file exists.",
        "Review demo recovery kit", "Simulate saving demo kit",
    ) else SecurityTaskDetails(
        "Demo recovery codes", "NOT REAL RECOVERY CODES. In real setup you save ten one-time recovery codes. Here, simulate saving, then acknowledge in a separate step; no usable codes or downloadable file exists.",
        "Review demo recovery codes", "Simulate saving demo codes",
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

private fun corePrompt(question: CoreOnboardingQuestion, state: CoreOnboardingSnapshot): String = when (question) {
    CoreOnboardingQuestion.OwnerName -> "What should I call you?"
    CoreOnboardingQuestion.OrganizationName -> "Thanks, ${state.answers.ownerName}. What name should we use for this workspace?"
    CoreOnboardingQuestion.BusinessDescription -> "What does the business do, or what are you thinking of starting?"
    CoreOnboardingQuestion.TeamIntent -> "Will it be just you, or will you work with a team?"
    CoreOnboardingQuestion.FirstPriority -> "What would you like to get done first?"
}

private fun teamLabel(intent: CoreOnboardingTeamIntent?) = when (intent) {
    CoreOnboardingTeamIntent.JustMe -> "Just me"
    CoreOnboardingTeamIntent.Team -> "With a team"
    null -> "Not answered"
}

private fun lastAnswer(state: CoreOnboardingSnapshot): String? {
    val next = state.discovery.next
    val discovery = state.discovery.questions().takeWhile { it != next }
        .lastOrNull { it in state.discovery.answers }
    if (discovery != null) return state.discovery.answers[discovery]?.summary()
    val question = CoreOnboardingQuestion.entries.lastOrNull(state.answers::isAnswered) ?: return null
    return when {
        question in state.answers.deferred -> "Left for later"
        question == CoreOnboardingQuestion.TeamIntent -> teamLabel(state.answers.teamIntent)
        else -> state.answers.text(question)
    }
}

@Composable
private fun RecentAnswer(value: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.85f),
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = MaterialTheme.shapes.large,
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text("You", style = MaterialTheme.typography.labelMedium)
                Text(value, style = MaterialTheme.typography.bodyLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ConversationStep(state: CoreOnboardingSnapshot, actions: PreviewActions) {
    val question = state.currentQuestion
    val discovery = if (question == null) state.discovery.next else null
    val keyboard = LocalSoftwareKeyboardController.current
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val conflict = state.conflict
    key(question, discovery, conflict) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (conflict == null && !imeVisible) lastAnswer(state)?.let { RecentAnswer(it) }
            when {
                conflict != null -> TeamConflictPanel(conflict, actions)
                question != null -> {
                    Guide(corePrompt(question, state))
                    if (question == CoreOnboardingQuestion.TeamIntent) {
                        TeamChoices { actions.chooseTeamIntent(it) }
                    } else {
                        TextAnswer(question, null, state.inputError, submitLabel = "Send") {
                            if (actions.answer(question, it)) keyboard?.hide()
                        }
                        if (question.optional) {
                            WhumpoQuietButton(onClick = {
                                if (actions.deferCoreQuestion()) keyboard?.hide()
                            }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Skip for now") }
                        }
                    }
                }
                discovery != null -> {
                    DiscoveryPrompt(discovery, state.discovery.prompt(discovery)) { answer ->
                        actions.answerDiscovery(discovery, answer).also { if (it) keyboard?.hide() }
                    }
                    WhumpoQuietButton(onClick = { actions.deferDiscovery() }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Skip remaining details")
                    }
                }
                else -> {
                    Guide("I’ve pulled together a starter plan from your answers. You can edit any detail before we finish.")
                    WhumpoPrimaryButton(onClick = { actions.proceedFromCompletedStep() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text("Review setup")
                    }
                }
            }
            WhumpoQuietButton(onClick = { actions.back() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Back to security") }
        }
    }
}

@Composable
private fun Guide(prompt: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Whumpo", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(prompt, style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun DiscoveryPrompt(
    question: CoreDiscoveryQuestion,
    prompt: String,
    initial: CoreDiscoveryAnswer? = null,
    submit: (CoreDiscoveryAnswer) -> Boolean,
) {
    var draft by remember(question) { mutableStateOf((initial as? CoreDiscoveryAnswer.Declared)?.value.orEmpty()) }
    var invalid by remember(question) { mutableStateOf(false) }
    val send: () -> Unit = { invalid = !submit(CoreDiscoveryAnswer.Declared(draft)) }
    Guide(prompt)
    if (question == CoreDiscoveryQuestion.Ein) {
        Notice("Choose a status only. Keep your EIN and other tax numbers private.")
    }
    if (question.options.isEmpty()) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it; invalid = false },
            label = { Text(question.label) },
            singleLine = true,
            isError = invalid,
            supportingText = if (invalid) {
                {
                    Text(
                        when (question) {
                            CoreDiscoveryQuestion.WebsiteUrl -> "Enter a public address like https://example.com."
                            CoreDiscoveryQuestion.Hostname -> "Enter just the domain, like example.com."
                            else -> "Use up to 160 characters. Leave out private numbers."
                        },
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            } else null,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (draft.isNotBlank()) send() }),
            modifier = Modifier.fillMaxWidth(),
        )
        WhumpoPrimaryButton(onClick = send, enabled = draft.isNotBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(if (initial == null) "Send" else "Save")
        }
    } else {
        question.options.forEach { option ->
            WhumpoSecondaryButton(
                onClick = { submit(CoreDiscoveryAnswer.Declared(option)) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text(option) }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        WhumpoQuietButton(onClick = { submit(CoreDiscoveryAnswer.Unknown) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
            Text("Not sure")
        }
        WhumpoQuietButton(onClick = { submit(CoreDiscoveryAnswer.Deferred) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
            Text("Skip for now")
        }
    }
}

@Composable
private fun TeamChoices(choose: (CoreOnboardingTeamIntent) -> Unit) {
    WhumpoSecondaryButton(onClick = { choose(CoreOnboardingTeamIntent.JustMe) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Just me") }
    WhumpoSecondaryButton(onClick = { choose(CoreOnboardingTeamIntent.Team) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("With a team") }
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
    if (question.maxLength == null) return
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
        supportingText = error?.let {
            { Text(errorMessage(it), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        },
        singleLine = !multiline,
        maxLines = if (multiline) 4 else 1,
        keyboardOptions = KeyboardOptions(imeAction = if (multiline) ImeAction.Default else ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { submit(draft) }),
        modifier = Modifier.fillMaxWidth(),
    )
    WhumpoPrimaryButton(onClick = { submit(draft) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(submitLabel) }
}

@Composable
private fun ReviewStep(state: CoreOnboardingSnapshot, actions: PreviewActions) = StepPage("Review your setup") {
    val editingDiscovery = state.discoveryEdit
    if (editingDiscovery != null) {
        DiscoveryPrompt(editingDiscovery, state.discovery.prompt(editingDiscovery), state.discovery.answers[editingDiscovery]) {
            actions.answerDiscovery(editingDiscovery, it)
        }
        WhumpoQuietButton(onClick = { actions.cancelEdit() }) { Text("Cancel") }
        return@StepPage
    }
    val answers = state.answers
    val plan = state.plan ?: return@StepPage
    state.conflict?.let { TeamConflictPanel(it, actions) }
    var starterEditing by remember { mutableStateOf<CoreStarterKind?>(null) }
    val idle = state.editing == null && state.conflict == null
    val detailsEditable = idle && starterEditing == null
    BackHandler(starterEditing != null) { starterEditing = null }
    Guide("Check what we’ve agreed on, then pick the first action to take into your workspace.")
    ReviewRow("Plan", "${plan.label} · Plan price to be confirmed", "Change plan", detailsEditable) { actions.editPlan() }
    ReviewRow("Account security", state.security.method?.label.orEmpty(), "Change security", detailsEditable) { actions.editSecurity() }
    CoreOnboardingQuestion.entries.forEach { question ->
        if (state.editing == question) {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (question == CoreOnboardingQuestion.TeamIntent) {
                        Text("Team", style = MaterialTheme.typography.titleMedium)
                        TeamChoices { actions.chooseTeamIntent(it) }
                    } else {
                        TextAnswer(question, answers.text(question), state.inputError, submitLabel = "Save") { actions.submitEdit(it) }
                        if (question.optional) {
                            WhumpoQuietButton(onClick = { actions.deferCoreQuestion() }) { Text("Leave for later") }
                        }
                    }
                    WhumpoQuietButton(onClick = { actions.cancelEdit() }) { Text("Cancel") }
                }
            }
        } else {
            val value = if (question == CoreOnboardingQuestion.TeamIntent) teamLabel(answers.teamIntent) else answers.text(question) ?: "Left for later"
            ReviewRow(fieldLabel(question), value, "Edit", detailsEditable) { actions.startEdit(question) }
        }
    }
    var detailsExpanded by remember { mutableStateOf(false) }
    HorizontalDivider()
    WhumpoSecondaryButton(onClick = { detailsExpanded = !detailsExpanded }, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(if (detailsExpanded) "Hide business details" else "Review business details")
    }
    if (detailsExpanded) {
        state.discovery.questions().forEach { question ->
            ReviewRow(question.label, state.discovery.answers[question]?.summary() ?: "Not answered", "Edit", detailsEditable) {
                actions.startDiscoveryEdit(question)
            }
        }
    }
    StarterPlan(state, actions, idle, starterEditing) { starterEditing = it }
    SummaryCard {
        Text(if (state.paymentConfirmed) "Demo payment confirmed" else "Demo payment needed")
        Text(if (state.securitySetupComplete) "Demo security setup complete; recovery save acknowledged" else "Demo security setup needed")
    }
    Text(
        "Creates one organization in this preview only. Nothing is sent or saved.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    WhumpoPrimaryButton(onClick = { actions.createOrganization() }, enabled = state.canCreate && starterEditing == null, modifier = Modifier.fillMaxWidth()) {
        Text("Create ${answers.organizationName}")
    }
    WhumpoQuietButton(onClick = { actions.back() }, enabled = starterEditing == null) { Text("Back to conversation") }
}

@Composable
private fun StarterPlan(
    state: CoreOnboardingSnapshot,
    actions: PreviewActions,
    enabled: Boolean,
    editing: CoreStarterKind?,
    edit: (CoreStarterKind?) -> Unit,
) {
    val plan = state.starterActions
    var draft by remember(editing) { mutableStateOf(plan.firstOrNull { it.kind == editing }?.title.orEmpty()) }
    var invalid by remember(editing) { mutableStateOf(false) }
    Text("Starter plan", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
    Text("These are drafts for you to review.")
    plan.forEach { action ->
        SummaryCard {
            if (editing == action.kind && enabled) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it; invalid = false },
                    label = { Text("Action title") },
                    isError = invalid,
                    supportingText = if (invalid) { { Text("Use up to 160 characters. Leave out private numbers.") } } else null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                WhumpoPrimaryButton(onClick = {
                    if (actions.editStarterTitle(action.kind, draft)) edit(null) else invalid = true
                }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Save title") }
                WhumpoQuietButton(onClick = { edit(null) }) { Text("Cancel") }
            } else {
                Text(action.title, style = MaterialTheme.typography.titleMedium)
                Text(action.reason)
                if (action.kind == state.nextAction) {
                    Text("Your next action", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                } else if (action.requires.isEmpty()) {
                    WhumpoQuietButton(
                        onClick = { actions.selectNextAction(action.kind) },
                        enabled = enabled && editing == null,
                        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Do next: ${action.title}" },
                    ) { Text("Do this next") }
                } else {
                    Text("First: " + plan.filter { it.kind in action.requires }.joinToString(", ") { it.title })
                }
                WhumpoQuietButton(
                    onClick = { edit(action.kind) },
                    enabled = enabled && editing == null,
                    modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Edit title: ${action.title}" },
                ) { Text("Edit title") }
            }
        }
    }
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
            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "$action: $label" },
        ) { Text(action) }
    }
}

private enum class WorkspaceTab(val label: String) { Today("Today"), Work("Work"), Account("Account") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OfflineWorkspace(organization: CoreOnboardingOrganization, exit: () -> Unit) {
    var tab by remember { mutableStateOf(WorkspaceTab.Today) }
    var welcomed by remember { mutableStateOf(false) }
    val welcomeAlpha by animateFloatAsState(if (welcomed) 1f else 0.86f, tween(240), label = "Workspace welcome")
    LaunchedEffect(organization) { welcomed = true }
    Column(Modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = tab.ordinal) {
            WorkspaceTab.entries.forEach { entry ->
                Tab(
                    selected = tab == entry,
                    onClick = { tab = entry },
                    text = { Text(entry.label) },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
        key(tab) {
            StepPage(tab.label) {
                Text(organization.name, style = MaterialTheme.typography.labelLarge)
                when (tab) {
                    WorkspaceTab.Today -> {
                        Text(
                            "Welcome, ${organization.ownerName}.",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.alpha(welcomeAlpha),
                        )
                        SummaryCard {
                            val next = organization.starterActions.first { it.kind == organization.nextAction }
                            Text("Next action", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            Text(next.title, style = MaterialTheme.typography.headlineSmall)
                            Text(next.reason)
                            WhumpoPrimaryButton(onClick = { tab = WorkspaceTab.Work }, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text("Open starter plan")
                            }
                        }
                        HorizontalDivider()
                        Text("Reviews", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                        Text("No decisions waiting for review.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Website", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                        Text(
                            when (organization.websiteStatus) {
                                "Have a site" -> "You told us you have a website. Its status is unverified."
                                "Building a site" -> "You told us you’re building a site. Its status is unverified."
                                "No site" -> "You told us you don’t have a website yet."
                                else -> "Website details are open."
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        organization.websiteAddress?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        Notice("This workspace lasts until you exit the preview.")
                    }
                    WorkspaceTab.Work -> {
                        Text("Starter plan", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                        Text("Drafts from your setup conversation.")
                        organization.starterActions.sortedBy { it.kind != organization.nextAction }.forEach { action ->
                            SummaryCard {
                                if (action.kind == organization.nextAction) Text("Next action", color = MaterialTheme.colorScheme.primary)
                                Text(action.title, style = MaterialTheme.typography.titleMedium)
                                Text(action.reason)
                                if (action.requires.isNotEmpty()) {
                                    Text("First: " + organization.starterActions.filter { it.kind in action.requires }.joinToString(", ") { it.title })
                                }
                            }
                        }
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
                            organization.businessDescription?.let { Text(it) }
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
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
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
