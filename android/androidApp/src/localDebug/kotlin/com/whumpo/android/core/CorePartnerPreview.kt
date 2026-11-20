package com.whumpo.android.core

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.whumpo.android.ui.partner.PartnerApprovalDetails
import com.whumpo.android.ui.partner.PartnerButton
import com.whumpo.android.ui.partner.PartnerConversationContent
import com.whumpo.android.ui.partner.PartnerDecisionButtons
import com.whumpo.android.ui.partner.PartnerHeading
import com.whumpo.android.ui.partner.PartnerStatus
import com.whumpo.android.ui.partner.partnerText
import com.whumpo.core.api.CoreProjectChoice
import com.whumpo.core.api.CoreProjectChoices
import com.whumpo.core.api.CoreWorkspaceChoice
import com.whumpo.core.api.CoreWorkspaceChoices
import com.whumpo.core.api.PartnerAgentKind
import com.whumpo.core.api.PartnerAgent
import com.whumpo.core.api.PartnerApprovalStatus
import com.whumpo.core.api.PartnerMessage
import com.whumpo.core.api.PartnerMessageRole
import com.whumpo.core.auth.CorePartnerController
import com.whumpo.core.auth.PartnerDirectory
import com.whumpo.core.auth.PartnerNotice
import com.whumpo.core.auth.PartnerState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun CorePartnerPreview(
    controller: CorePartnerController,
    organization: String,
    signInAgain: () -> Unit,
    close: () -> Unit,
    onReadable: (Boolean) -> Unit = {},
) {
    val state by controller.state.collectAsState()
    val conversation = state.conversation
    val actions = rememberCoroutineScope()
    if (conversation == null) {
        var creating by remember { mutableStateOf(false) }
        val back = { if (creating) creating = false else close() }
        BackHandler(onBack = back)
        LaunchedEffect(Unit) {
            controller.directory(state.directory, onRead = onReadable)
        }
        PartnerPage {
            PartnerButton(if (creating) "Back to conversations" else "Back to organization", onClick = back)
            Text(organization, style = MaterialTheme.typography.labelLarge)
            PartnerHeading(if (creating) "New temporary conversation" else "Partner")
            state.notice?.let { PartnerStatus(it.partnerText()) }
            if (state.busy) PartnerStatus("Working…")
            if (creating) {
                PartnerCreateForm(controller, state)
            } else {
                PartnerButton("Active conversations", !state.busy) {
                    actions.launch { controller.directory(PartnerDirectory.Active) }
                }
                PartnerButton("Conversation history", !state.busy) {
                    actions.launch { controller.directory(PartnerDirectory.History) }
                }
                Text(if (state.directory == PartnerDirectory.Active) "Active" else "Retained history",
                    style = MaterialTheme.typography.titleLarge)
                if (!state.busy && state.conversations.isEmpty() && state.history.isEmpty()) Text("No conversations found.")
                state.conversations.forEach { entry ->
                    val label = if (entry.kind == PartnerAgentKind.partner) "Partner" else "Specialist"
                    PartnerButton("$label · ${entry.id.take(12)}", !state.busy) { controller.open(entry) }
                }
                state.history.forEach { entry ->
                    PartnerButton("${entry.title} · ${if (entry.closed) "Closed" else "Open"}", !state.busy) {
                        controller.open(entry.conversation)
                    }
                }
                if (state.nextDirectory != null) {
                    PartnerButton("More conversations", !state.busy) {
                        actions.launch { controller.directory(state.directory, more = true) }
                    }
                }
                if (controller.canCreate) {
                    PartnerButton(if (state.creationPending) "Retry exact conversation request" else "New conversation",
                        !state.busy, primary = true) { creating = true }
                }
            }
        }
        return
    }

    key(conversation.id) {
        val detailActions = rememberCoroutineScope()
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        var source by remember { mutableStateOf<PartnerMessage?>(null) }
        var showReview by remember { mutableStateOf(state.decisionPending) }
        val back = {
            when {
                showReview -> showReview = false
                source != null -> source = null
                else -> controller.closeConversation()
            }
        }
        BackHandler(onBack = back)
        LaunchedEffect(Unit) { controller.refreshConversation() }
        val run = state.run
        LaunchedEffect(lifecycle, conversation.id, run?.id) {
            if (run != null) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                controller.observeRunEvents(conversation.id, run.id)
            }
        }
        LaunchedEffect(lifecycle, run?.id, run?.status?.shouldPoll, showReview, source) {
            if (run?.status?.shouldPoll == true && !showReview && source == null) {
                lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    controller.pollRun(conversation.id, run.id)
                }
            }
        }

        val runSource = source
        if (runSource != null && !state.runPending) {
            PartnerRunForm(controller, state, runSource, back) { project, title ->
                detailActions.launch {
                    val previousRunId = controller.state.value.run?.id
                    controller.startRun(runSource, project, title)
                    val latest = controller.state.value
                    if (latest.runPending || latest.run?.id != previousRunId ||
                        latest.notice in setOf(PartnerNotice.RunDisabled, PartnerNotice.RequestRejected)) source = null
                }
            }
        } else if (showReview) {
            PartnerReview(controller, state, back, signInAgain)
        } else {
            PartnerConversationContent(
                organization = organization,
                title = state.topic?.title ?: if (conversation.kind == PartnerAgentKind.partner) "Partner" else "Specialist",
                state = state,
                canWrite = controller.canWrite,
                userId = controller.userId,
                onBack = back,
                onRefresh = { detailActions.launch { controller.refreshConversation() } },
                onMore = { detailActions.launch { controller.moreMessages() } },
                onDraft = controller::editMessage,
                onSave = { detailActions.launch { controller.saveMessage() } },
                onRequestRun = { source = it },
                onRetryRun = { detailActions.launch { controller.startRun(null, null, "") } },
                onOpenRun = { detailActions.launch { controller.openRun(it) } },
                onRefreshRun = { detailActions.launch { controller.refreshRun() } },
                onReview = { step ->
                    detailActions.launch {
                        controller.review(step)
                        showReview = controller.state.value.approval?.id == step.approvalId
                    }
                },
                onVerify = { detailActions.launch { controller.verifyTask(it) } },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun PartnerCreateForm(controller: CorePartnerController, state: PartnerState) {
    val actions = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var agent by remember { mutableStateOf<PartnerAgent?>(null) }
    LaunchedEffect(Unit) {
        if (!state.creationPending && state.agents.isEmpty()) controller.agents()
    }
    if (state.creationPending) {
        PartnerButton("Retry exact creation request", !state.busy, primary = true) {
            actions.launch { controller.create(null, "") }
        }
        return
    }
    OutlinedTextField(title, { if (it.length <= 512) title = it },
        label = { Text("Conversation title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    agent?.let { Text("Agent: ${it.name}") }
    state.agents.forEach { entry ->
        PartnerButton(entry.name, !state.busy && agent != entry) { agent = entry }
    }
    if (state.nextAgents != null) {
        PartnerButton("More agents", !state.busy) { actions.launch { controller.agents(more = true) } }
    }
    if (state.agents.isEmpty() && !state.busy) {
        PartnerButton("Refresh agents") { actions.launch { controller.agents() } }
    }
    PartnerButton("Create conversation", !state.busy && title.isNotBlank() && agent != null, primary = true) {
        actions.launch { controller.create(agent, title) }
    }
}

@Composable
private fun PartnerRunForm(
    controller: CorePartnerController,
    state: PartnerState,
    source: PartnerMessage,
    close: () -> Unit,
    request: (CoreProjectChoice, String) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var workspaces by remember { mutableStateOf<CoreWorkspaceChoices?>(null) }
    var workspace by remember { mutableStateOf<CoreWorkspaceChoice?>(null) }
    var projects by remember(workspace) { mutableStateOf<CoreProjectChoices?>(null) }
    var project by remember(workspace) { mutableStateOf<CoreProjectChoice?>(null) }
    var workspaceCursor by remember { mutableStateOf<String?>(null) }
    var projectCursor by remember(workspace) { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf(0) }

    LaunchedEffect(workspaceCursor, attempt, workspace) {
        if (workspace == null) {
            error = false
            try {
                workspaces = controller.workspaces(workspaceCursor)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = true
            }
        }
    }
    LaunchedEffect(workspace, projectCursor, attempt) {
        val choice = workspace ?: return@LaunchedEffect
        error = false
        try {
            projects = controller.projects(choice.id, projectCursor)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error = true
        }
    }
    PartnerPage {
        PartnerButton("Back to conversation", onClick = close)
        PartnerHeading("Request one task run")
        Text("Saved message", style = MaterialTheme.typography.labelLarge)
        Text(source.content, maxLines = 4, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        Text("Choose a project and review the task title before submitting.")
        if (error) {
            PartnerStatus("Could not load project choices.")
            PartnerButton("Refresh choices") { attempt++ }
        }
        state.notice?.let { PartnerStatus(it.partnerText()) }
        val selectedWorkspace = workspace
        if (selectedWorkspace == null) {
            val page = workspaces
            if (page?.items?.isEmpty() == true) Text("No workspaces available.")
            page?.items?.forEach { item -> PartnerButton(item.name) { workspace = item } }
            page?.nextCursor?.let { cursor -> PartnerButton("More workspaces") { workspaceCursor = cursor } }
        } else {
            Text(selectedWorkspace.name, style = MaterialTheme.typography.titleMedium)
            PartnerButton("Change workspace", !state.busy) { workspace = null }
            val page = projects
            if (page?.items?.isEmpty() == true) Text("No projects available.")
            page?.items?.forEach { item -> PartnerButton(item.name, !state.busy && item != project) { project = item } }
            page?.nextCursor?.let { cursor -> PartnerButton("More projects", !state.busy) { projectCursor = cursor } }
        }
        project?.let { Text("Project: ${it.name}") }
        OutlinedTextField(title, { if (it.length <= 800) title = it }, label = { Text("Task title") },
            enabled = !state.busy, singleLine = true, modifier = Modifier.fillMaxWidth())
        PartnerButton("Submit task run request", project != null && title.isNotBlank() && !state.busy,
            primary = true) { project?.let { request(it, title) } }
    }
}

@Composable
private fun PartnerReview(
    controller: CorePartnerController,
    state: PartnerState,
    close: () -> Unit,
    signInAgain: () -> Unit,
) {
    val actions = rememberCoroutineScope()
    val approval = state.approval
    PartnerPage {
        PartnerButton("Back to conversation", onClick = close)
        PartnerHeading("Task approval")
        state.notice?.let { PartnerStatus(it.partnerText()) }
        if (state.notice == PartnerNotice.SignInAgain) PartnerButton("Sign in again", onClick = signInAgain)
        if (approval != null) {
            PartnerApprovalDetails(approval)
            if (state.decisionPending && controller.canCreate) {
                PartnerButton("Retry exact approval decision", !state.busy, primary = true) { actions.launch { controller.decide(null) } }
            } else if (approval.status == PartnerApprovalStatus.pending && controller.canCreate) {
                PartnerDecisionButtons(!state.busy) { choice -> actions.launch { controller.decide(choice) } }
            }
        }
    }
}

@Composable
internal fun LocalPartnerSample(close: () -> Unit) {
    BackHandler(onBack = close)
    PartnerPage {
        PartnerButton("Back to sign-in", onClick = close)
        PartnerHeading("Partner sample")
        Text("Offline sample · no organization is connected.")
        Text("Conversation", style = MaterialTheme.typography.titleLarge)
        Text("You")
        Text("Prepare a list of the supplies for our team workshop.")
        Text("In a connected organization you can save a message and submit a separate request for one task run.")
    }
}

@Composable
private fun PartnerPage(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
}
