package com.whumpo.core.auth

import com.whumpo.core.api.*
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PartnerDirectory { Active, History }

enum class PartnerNotice {
    Unavailable, ReadOnly, InvalidInput, DraftLimit, Saved, SaveUnconfirmed, SaveAwaitingHistory,
    ClosedSaveUnresolved, CreateUnconfirmed, RunUnconfirmed, RunDisabled, RequestRejected,
    DecisionUnconfirmed, DecisionRecorded, ReviewChanged, SignInAgain,
}

data class PartnerState(
    val directory: PartnerDirectory = PartnerDirectory.Active,
    val conversations: List<PartnerConversation> = emptyList(),
    val history: List<PartnerTopic> = emptyList(),
    val nextDirectory: String? = null,
    val agents: List<PartnerAgent> = emptyList(),
    val nextAgents: String? = null,
    val creationPending: Boolean = false,
    val conversation: PartnerConversation? = null,
    val topic: PartnerTopic? = null,
    val messages: List<PartnerMessage> = emptyList(),
    val moreMessages: Boolean = false,
    val messageDraft: String = "",
    val savePending: Boolean = false,
    val saveAcknowledged: Boolean = false,
    val savedForRun: PartnerMessage? = null,
    val runPending: Boolean = false,
    val run: PartnerRun? = null,
    val approval: PartnerApproval? = null,
    val decisionPending: Boolean = false,
    val proof: PartnerTaskProof? = null,
    val notice: PartnerNotice? = null,
    val busy: Boolean = false,
)

/** One instance for the exact authenticated workspace epoch. Calls and edits run on the UI dispatcher. */
class CorePartnerController(
    private val sessions: CoreOrganizationSessionController,
    private val active: CoreOrganizationSessionController.State.Active,
) {
    val canCreate = active.session.role == CoreRole.owner
    val canWrite = active.session.role != CoreRole.viewer
    val userId = active.session.userId

    private val protocol = CorePartnerProtocol(sessions, active)
    private val mutableState = MutableStateFlow(PartnerState())
    val state = mutableState.asStateFlow()
    private val current get() = mutableState.value

    private data class Create(val agent: PartnerAgent, val title: String, val command: PartnerWrite, var attempted: Boolean = false)
    private data class Save(
        val content: String,
        val command: PartnerWrite,
        var cursor: PartnerMessage?,
        var attempted: Boolean = false,
        var acknowledgment: PartnerMessage? = null,
    )
    private data class Start(val source: PartnerMessage, val command: PartnerWrite, var attempted: Boolean = false)
    private data class Decision(val review: PartnerApproval, val choice: PartnerDecision, val command: PartnerWrite, var attempted: Boolean = false)
    private class Draft {
        var text = ""
        var save: Save? = null
        var latestSaved: PartnerMessage? = null
        var start: Start? = null
        var decision: Decision? = null
        var run: PartnerRun? = null
    }

    private var create: Create? = null
    private val drafts = mutableMapOf<String, Draft>()
    private val availableWorkspaces = linkedSetOf<String>()
    private val availableProjects = linkedSetOf<CoreProjectChoice>()
    private var bookmark: PartnerMessage? = null
    private var selection = 0L
    private var operationJob: Job? = null
    private var retired = false
    private val draft get() = current.conversation?.id?.let(drafts::get)

    suspend fun directory(kind: PartnerDirectory, more: Boolean = false) = perform {
        val after = current.nextDirectory.takeIf { more && current.directory == kind }
        val conversations = if (after != null) current.conversations else emptyList()
        val topics = if (after != null) current.history else emptyList()
        when (kind) {
            PartnerDirectory.Active -> {
                val page = protocol.active(after)
                update { copy(directory = kind, conversations = (conversations + page.items).takeLast(100),
                    history = emptyList(), nextDirectory = page.nextCursor) }
            }
            PartnerDirectory.History -> {
                val page = protocol.history(after)
                update { copy(directory = kind, history = (topics + page.items).takeLast(100),
                    conversations = emptyList(), nextDirectory = page.nextCursor) }
            }
        }
    }

    suspend fun agents(more: Boolean = false) = perform {
        if (!canCreate) return@perform
        val after = current.nextAgents.takeIf { more }
        val page = protocol.agents(after)
        update { copy(agents = ((if (after == null) emptyList() else agents) + page.items.filter { it.enabled })
            .takeLast(100), nextAgents = page.nextCursor) }
    }

    suspend fun create(agent: PartnerAgent?, title: String) = perform {
        if (!canCreate) return@perform
        val pending = create ?: run {
            val normalized = partnerTitle(title)
            if (normalized == null || agent == null || agent !in current.agents) {
                update { copy(notice = PartnerNotice.InvalidInput) }
                return@perform
            }
            Create(agent, normalized, PartnerWrite.create(agent, normalized)).also { create = it }
        }
        val retry = pending.attempted
        pending.attempted = true
        update { copy(creationPending = true) }
        val topic = try {
            protocol.create(pending.agent, pending.title, pending.command)
        } catch (error: PartnerHttpException) {
            if (!retry && error.rejectsWrite) {
                create = null
                update { copy(creationPending = false, notice = PartnerNotice.RequestRejected) }
                return@perform
            }
            throw error
        }
        create = null
        changeConversation(topic.conversation, topic)
        update { copy(creationPending = false) }
    }

    /** Releases view state and pending request bodies when the owning session leaves composition. */
    fun retire() {
        retired = true
        selection++
        operationJob?.cancel()
        operationJob = null
        bookmark = null
        create = null
        drafts.clear()
        availableProjects.clear()
        availableWorkspaces.clear()
        mutableState.value = PartnerState()
    }

    fun open(conversation: PartnerConversation) {
        if (!isCurrent() || current.conversation != null || current.busy || (conversation !in current.conversations && current.history.none { it.conversation == conversation })) return
        changeConversation(conversation, current.history.find { it.conversation == conversation })
    }

    fun closeConversation() {
        selection++
        operationJob?.cancel()
        operationJob = null
        bookmark = null
        update {
            copy(conversation = null, topic = null, messages = emptyList(), moreMessages = false, messageDraft = "",
                savePending = false, saveAcknowledged = false, savedForRun = null, runPending = false, run = null,
                approval = null, decisionPending = false, proof = null, notice = null, busy = false)
        }
    }

    private fun changeConversation(conversation: PartnerConversation, topic: PartnerTopic?) {
        bookmark = null
        val saved = drafts[conversation.id]
        update {
            copy(conversation = conversation, topic = topic, messages = emptyList(), moreMessages = true,
                messageDraft = saved?.text.orEmpty(), savePending = saved?.save != null,
                saveAcknowledged = saved?.save?.acknowledgment != null, savedForRun = saved?.latestSaved,
                runPending = saved?.start != null, run = saved?.run, approval = saved?.decision?.review,
                decisionPending = saved?.decision != null, proof = null)
        }
    }

    private fun retainDraft(id: String): Draft? {
        drafts[id]?.let { return it }
        if (drafts.size == 20) {
            val empty = drafts.entries.firstOrNull { (_, item) ->
                item.text.isEmpty() && item.save == null && item.start == null && item.decision == null &&
                    item.run?.status?.shouldPoll != true
            }
            if (empty == null) {
                update { copy(notice = PartnerNotice.DraftLimit) }
                return null
            }
            drafts.remove(empty.key)
        }
        return Draft().also { drafts[id] = it }
    }

    fun editMessage(text: String) {
        val topic = current.topic ?: return
        if (retired || !canWrite || topic.closed || current.savePending || text.length > 8192) return
        sessions.commitIfCurrent(active.epoch) {
            retainDraft(topic.id)?.let {
                it.text = text
                update { copy(messageDraft = text) }
            }
        }
    }

    suspend fun refreshConversation() = perform {
        val conversation = current.conversation ?: return@perform
        val topic = protocol.topic(conversation)
        update { copy(topic = topic) }
        val draft = this.draft
        draft?.save?.let { pending ->
            when {
                pending.acknowledgment != null -> confirmSave(topic.id, draft, pending)
                topic.closed -> update { copy(notice = PartnerNotice.ClosedSaveUnresolved) }
            }
        }
        readMessages(topic.id, pageBudget = 20)
    }

    suspend fun moreMessages() = perform {
        val topic = current.topic ?: return@perform
        readMessages(topic.id, pageBudget = 20)
    }

    private suspend fun readMessages(id: String, pageBudget: Int) {
        repeat(pageBudget) {
            val page = protocol.messages(id, bookmark)
            ingestVisiblePage(page)
            if (page.nextCursor == null) return
        }
    }

    private fun ingestVisiblePage(page: PartnerPage<PartnerMessage>) {
        val previous = current.messages.map(PartnerMessage::id).toSet()
        if (page.items.any { it.id in previous }) throw CoreProtocolException()
        page.items.lastOrNull()?.let { bookmark = it }
        update { copy(messages = (messages + page.items).takeLast(100), moreMessages = page.nextCursor != null) }
    }

    suspend fun saveMessage() = perform {
        val conversation = current.conversation ?: return@perform
        val draft = draft ?: return@perform
        val content = draft.text
        val topic = protocol.topic(conversation)
        update { copy(topic = topic) }
        val existing = draft.save
        if (existing?.acknowledgment != null) {
            confirmSave(topic.id, draft, existing)
            return@perform
        }
        if (topic.closed || !canWrite) {
            update { copy(notice = if (existing != null) PartnerNotice.ClosedSaveUnresolved else PartnerNotice.ReadOnly) }
            return@perform
        }
        val pending = existing ?: run {
            if (draft.text != content) return@perform
            if (!partnerText(content, 8192)) {
                update { copy(notice = PartnerNotice.InvalidInput) }
                return@perform
            }
            Save(content, PartnerWrite.append(content), bookmark).also { draft.save = it }
        }
        val retry = pending.attempted
        pending.attempted = true
        update { copy(savePending = true) }
        try {
            pending.acknowledgment = protocol.append(topic.id, pending.content, pending.command)
        } catch (error: PartnerHttpException) {
            if (!retry && error.rejectsWrite) {
                draft.save = null
                update { copy(savePending = false, notice = PartnerNotice.RequestRejected) }
                return@perform
            }
            throw error
        }
        update { copy(saveAcknowledged = true) }
        confirmSave(topic.id, draft, pending)
    }

    private suspend fun confirmSave(id: String, draft: Draft, pending: Save) {
        val expected = checkNotNull(pending.acknowledgment)
        // This cursor is independent of the visible page. It starts at the last canonical row before the POST.
        repeat(20) {
            val page = protocol.messages(id, pending.cursor)
            val match = page.items.firstOrNull { it.id == expected.id || it.sequence == expected.sequence }
            if (match != null) {
                if (match != expected || !match.content.encodeToByteArray().contentEquals(expected.content.encodeToByteArray())) {
                    throw CoreProtocolException()
                }
                if (pending.cursor == bookmark) ingestVisiblePage(page)
                draft.text = ""
                draft.save = null
                draft.latestSaved = match
                update { copy(messageDraft = "", savePending = false, saveAcknowledged = false, savedForRun = match,
                    notice = PartnerNotice.Saved) }
                return
            }
            if (page.items.any { it.sequence > expected.sequence }) throw CoreProtocolException()
            page.items.lastOrNull()?.let { pending.cursor = it }
            if (page.nextCursor == null) {
                update { copy(notice = PartnerNotice.SaveAwaitingHistory) }
                return
            }
        }
        update { copy(notice = PartnerNotice.SaveAwaitingHistory) }
    }

    suspend fun workspaces(cursor: String? = null): CoreWorkspaceChoices {
        if (!isCurrent()) throw CancellationException("Core session changed")
        return sessions.partnerWorkspaceChoices(active, cursor).also { page ->
            if (!isCurrent()) throw CancellationException("Core session changed")
            if (cursor == null) availableWorkspaces.clear()
            availableWorkspaces += page.items.map { it.id }
            while (availableWorkspaces.size > 100) availableWorkspaces.remove(availableWorkspaces.first())
        }
    }

    suspend fun projects(workspaceId: String, cursor: String? = null): CoreProjectChoices {
        if (!isCurrent()) throw CancellationException("Core session changed")
        require(workspaceId in availableWorkspaces)
        return sessions.partnerProjectChoices(active, workspaceId, cursor).also { page ->
            if (!isCurrent()) throw CancellationException("Core session changed")
            if (cursor == null) availableProjects.clear()
            availableProjects += page.items
            while (availableProjects.size > 100) availableProjects.remove(availableProjects.first())
        }
    }

    suspend fun startRun(source: PartnerMessage?, project: CoreProjectChoice?, title: String) = perform {
        val topic = current.topic ?: return@perform
        if (topic.closed || !canWrite) return@perform
        val draft = retainDraft(topic.id) ?: return@perform
        if (draft.decision != null || draft.run?.status?.shouldPoll == true || draft.run?.status == PartnerRunStatus.waiting_approval) return@perform
        val pending = draft.start ?: run {
            if (source == null || project == null || project !in availableProjects || source.userId != userId || source.role != PartnerMessageRole.user ||
                (source != draft.latestSaved && source !in current.messages)) return@perform
            val submission = try {
                sessions.prepareTaskCreation(active.epoch, project, title, "")
            } catch (_: IllegalArgumentException) {
                update { copy(notice = PartnerNotice.InvalidInput) }
                return@perform
            }
            Start(source, PartnerWrite.run(source, submission.draft.projectId, submission.draft.title)).also { draft.start = it }
        }
        val retry = pending.attempted
        pending.attempted = true
        update { copy(runPending = true) }
        val run = try {
            protocol.start(topic.id, pending.source, pending.command)
        } catch (error: PartnerHttpException) {
            if (!retry && error.rejectsWrite) {
                draft.start = null
                update { copy(runPending = false, notice = if (error.code == "POLICY_DISABLED") PartnerNotice.RunDisabled else PartnerNotice.RequestRejected) }
                return@perform
            }
            throw error
        }
        draft.start = null
        draft.run = run
        update { copy(run = run, runPending = false, approval = null, proof = null) }
    }

    suspend fun openRun(message: PartnerMessage) = perform {
        val topic = current.topic ?: return@perform
        val runId = message.runId ?: return@perform
        if (message !in current.messages || message.role != PartnerMessageRole.assistant) return@perform
        val draft = retainDraft(topic.id) ?: return@perform
        if (draft.start != null || draft.decision != null) return@perform
        val run = protocol.run(topic.id, runId, null)
        if (run.steps.mapNotNull { it.result?.receiptId }.any { it !in message.receiptIds.orEmpty() }) throw CoreProtocolException()
        draft.run = run
        update { copy(run = run, approval = null, proof = null) }
    }

    suspend fun refreshRun() = perform {
        val run = current.run ?: return@perform
        if (draft?.decision != null) return@perform
        val latest = protocol.run(run.conversationId, run.id, run.sourceMessageId)
        draft?.run = latest
        update { copy(run = latest, approval = null, proof = null) }
    }

    suspend fun pollRun(conversationId: String, runId: String) {
        repeat(12) {
            delay(2500)
            val run = current.run ?: return
            if (run.conversationId != conversationId || run.id != runId || !run.status.shouldPoll || !isCurrent()) return
            if (!current.busy && current.approval == null) {
                refreshRun()
                if (current.notice != null) return
            }
        }
    }

    suspend fun review(step: PartnerRunStep) = perform {
        val run = current.run ?: return@perform
        if (draft?.decision != null) return@perform
        val approval = protocol.approval(run, step)
        update { copy(approval = approval) }
    }

    suspend fun decide(choice: PartnerDecision?) = perform {
        if (!canCreate) return@perform
        val draft = draft ?: return@perform
        val pending = draft.decision ?: run {
            val approval = current.approval ?: return@perform
            val run = current.run ?: return@perform
            val step = run.steps.singleOrNull { it.approvalId == approval.id } ?: return@perform
            if (choice == null || approval.status != PartnerApprovalStatus.pending) return@perform
            val latest = protocol.approval(run, step)
            if (latest != approval || !Instant.parse(latest.expiresAt).isAfter(Instant.now())) {
                update { copy(approval = latest, notice = PartnerNotice.ReviewChanged) }
                return@perform
            }
            Decision(approval, choice, PartnerWrite.decision(approval, choice)).also { draft.decision = it }
        }
        val retry = pending.attempted
        pending.attempted = true
        update { copy(decisionPending = true) }
        try {
            protocol.decide(pending.review, pending.choice, pending.command)
        } catch (error: PartnerHttpException) {
            if (error.status == 403 && error.code == "FORBIDDEN") {
                update { copy(notice = PartnerNotice.SignInAgain) }
                return@perform
            }
            if (!retry && error.rejectsWrite) {
                draft.decision = null
                update { copy(decisionPending = false, approval = null, notice = PartnerNotice.ReviewChanged) }
                return@perform
            }
            throw error
        }
        draft.decision = null
        update { copy(approval = null, decisionPending = false, notice = PartnerNotice.DecisionRecorded) }
    }

    suspend fun verifyTask(step: PartnerRunStep) = perform {
        val run = current.run ?: return@perform
        update { copy(proof = null) }
        val source = current.savedForRun?.takeIf { it.id == run.sourceMessageId }
            ?: current.messages.find { it.id == run.sourceMessageId && it.role == PartnerMessageRole.user }
        val proof = protocol.verifyTask(run, step, source?.userId)
        update { copy(proof = proof) }
    }

    private fun isCurrent() = !retired && sessions.commitIfCurrent(active.epoch) {}

    private fun update(change: PartnerState.() -> PartnerState) {
        if (!retired) sessions.commitIfCurrent(active.epoch) { mutableState.value = current.change() }
    }

    private suspend fun perform(action: suspend () -> Unit) = coroutineScope {
        if (!isCurrent() || current.busy) return@coroutineScope
        operationJob = currentCoroutineContext()[Job]
        val operationSelection = selection
        update { copy(busy = true, notice = null) }
        try {
            action()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            if (selection == operationSelection) {
                update {
                    copy(notice = when {
                        decisionPending -> PartnerNotice.DecisionUnconfirmed
                        runPending -> PartnerNotice.RunUnconfirmed
                        saveAcknowledged -> PartnerNotice.SaveAwaitingHistory
                        savePending -> PartnerNotice.SaveUnconfirmed
                        creationPending -> PartnerNotice.CreateUnconfirmed
                        else -> PartnerNotice.Unavailable
                    })
                }
            }
        } finally {
            if (selection == operationSelection) {
                operationJob = null
                update { copy(busy = false) }
            }
        }
    }
}
