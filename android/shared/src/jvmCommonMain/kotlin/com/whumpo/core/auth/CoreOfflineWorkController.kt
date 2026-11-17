package com.whumpo.core.auth

import com.whumpo.core.account.CoreOfflineWorkStore
import com.whumpo.core.account.OfflineWorkDraft
import com.whumpo.core.account.OfflineWorkPending
import com.whumpo.core.account.OfflineWorkStorageException
import com.whumpo.core.account.isOfflineAcceptance
import com.whumpo.core.account.validOfflineText
import com.whumpo.core.api.CORE_MAX_VERSION
import com.whumpo.core.api.CoreApiClient
import com.whumpo.core.api.CoreEffectState
import com.whumpo.core.api.CoreHttpException
import com.whumpo.core.api.CoreProjectChoice
import com.whumpo.core.api.CoreProjectChoices
import com.whumpo.core.api.CoreProtocolException
import com.whumpo.core.api.CoreRole
import com.whumpo.core.api.CoreTaskCommandStatus
import com.whumpo.core.api.CoreTaskOutcome
import com.whumpo.core.api.CoreWorkTask
import com.whumpo.core.api.CoreWorkspaceChoice
import com.whumpo.core.api.CoreWorkspaceChoices
import com.whumpo.core.api.PartnerReceipts
import com.whumpo.core.api.PartnerRoute
import com.whumpo.core.api.coreDigest
import com.whumpo.core.api.coreIdentifier
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.time.Instant

enum class OfflineWorkNotice { Saved, Unreadable, StorageUnavailable, NetworkUnavailable, AccessDenied, ProjectUnavailable, Changed, Unconfirmed, Unavailable }

data class OfflineWorkReview(
    val projectName: String,
    val title: String,
    val description: String,
    val retry: Boolean,
)

data class CoreOfflineWorkState(
    val loaded: Boolean = false,
    val connected: Boolean = false,
    val busy: Boolean = false,
    val title: String = "",
    val description: String = "",
    val saved: Boolean = false,
    val frozen: Boolean = false,
    val accessLost: Boolean = false,
    val workspaces: CoreWorkspaceChoices? = null,
    val workspace: CoreWorkspaceChoice? = null,
    val projects: CoreProjectChoices? = null,
    val review: OfflineWorkReview? = null,
    val outcome: CoreTaskOutcome? = null,
    val notice: OfflineWorkNotice? = null,
)

/** The offline pane owns no network work until the user explicitly reconnects. */
class CoreOfflineWorkController(
    private val sessions: CoreOrganizationSessionController,
    private val active: CoreOrganizationSessionController.State.Active,
    private val store: CoreOfflineWorkStore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private class Reviewed(val summary: OfflineWorkReview, val frozen: OfflineWorkDraft)

    private val mutableState = MutableStateFlow(CoreOfflineWorkState())
    val state = mutableState.asStateFlow()
    private val current get() = mutableState.value
    private var draft: OfflineWorkDraft? = null
    private var reviewed: Reviewed? = null
    private var job: Job? = null
    private var retired = false
    private var generation = 0L

    fun retire() {
        retired = true
        generation++
        job?.cancel()
        draft = null
        reviewed = null
        mutableState.value = CoreOfflineWorkState()
    }

    fun offline() {
        generation++
        job?.cancel()
        job = null
        resetConnection()
    }

    private fun resetConnection() {
        update {
            reviewed = null
            copy(connected = false, busy = false, workspaces = null, workspace = null, projects = null, review = null, outcome = null)
        }
    }

    suspend fun load() = perform {
        if (current.connected || current.accessLost) return@perform
        val stored = try {
            disk { store.read(active.scope) }
        } catch (_: OfflineWorkStorageException) {
            setDraft(null)
            update { copy(notice = OfflineWorkNotice.Unreadable) }
            return@perform
        }
        setDraft(stored)
        update { copy(notice = null) }
    }

    suspend fun save(title: String, description: String) = perform {
        if (current.connected || !current.loaded || current.accessLost || draft?.pending != null ||
            current.notice == OfflineWorkNotice.Unreadable) return@perform
        val next = OfflineWorkDraft(title, description)
        try {
            next.verify()
        } catch (_: IllegalArgumentException) {
            update { copy(notice = OfflineWorkNotice.Changed) }
            return@perform
        }
        persist(next)
        update { copy(notice = OfflineWorkNotice.Saved) }
    }

    suspend fun discard() = perform {
        if (current.connected || !current.loaded || current.accessLost) return@perform
        val previous = draft?.takeIf { it.pending == null } ?: return@perform
        if (!disk { store.remove(active.scope, previous) }) throw OfflineWorkStorageException()
        setDraft(null)
        update { copy(notice = null) }
    }

    suspend fun reconnect() = perform {
        if (current.connected || !current.loaded || current.accessLost || draft == null) return@perform
        update { copy(connected = true) }
        verifyAuthority()
        val pending = draft?.pending
        if (pending == null) {
            val workspaces = online { sessions.partnerWorkspaceChoices(active, null) }
            update { copy(workspaces = workspaces) }
        } else {
            val project = verifyProject(pending)
            if (pending.acceptance == null) showReview(checkNotNull(draft), project, retry = true)
            else update { copy(outcome = pending.acceptance.status.offlineOutcome()) }
        }
    }

    suspend fun chooseWorkspace(choice: CoreWorkspaceChoice) = perform {
        if (choice !in current.workspaces?.items.orEmpty()) return@perform
        val projects = online { sessions.partnerProjectChoices(active, choice.id, null) }
        update { copy(workspace = choice, projects = projects) }
    }

    fun changeWorkspace() {
        if (!current.busy && current.connected) update { copy(workspace = null, projects = null) }
    }

    suspend fun moreWorkspaces() = perform {
        val cursor = current.workspaces?.nextCursor ?: return@perform
        val choices = online { sessions.partnerWorkspaceChoices(active, cursor) }
        update { copy(workspaces = choices, workspace = null, projects = null) }
    }

    suspend fun moreProjects() = perform {
        val cursor = current.projects?.nextCursor ?: return@perform
        val workspace = current.workspace ?: return@perform
        val choices = online { sessions.partnerProjectChoices(active, workspace.id, cursor) }
        update { copy(projects = choices) }
    }

    suspend fun chooseProject(project: CoreProjectChoice) = perform {
        val previous = draft?.takeIf { it.pending == null } ?: return@perform
        if (project !in current.projects?.items.orEmpty() || project.workspaceId != current.workspace?.id) return@perform
        ensureOnline()
        val request = sessions.prepareTaskCreation(active.epoch, project, previous.title, previous.description)
        val frozen = OfflineWorkDraft(request.draft.title, request.draft.description.orEmpty(),
            OfflineWorkPending(project.workspaceId, project.id, request.key, Json.encodeToString(request.draft)))
        showReview(frozen, project, retry = false)
    }

    fun cancelReview() {
        if (current.busy) return
        if (draft?.pending != null) offline() else update {
            reviewed = null
            copy(review = null)
        }
    }

    suspend fun submit(selection: OfflineWorkReview) = perform {
        val selected = reviewed?.takeIf { it.summary === selection } ?: return@perform
        val pending = checkNotNull(selected.frozen.pending)
        pending.decode()
        verifyAuthority()
        verifyProject(pending)
        if (draft?.pending == null) persist(selected.frozen)
        else if (!disk { store.read(active.scope) == selected.frozen }) throw OfflineWorkStorageException()
        update {
            reviewed = null
            copy(review = null, workspaces = null, workspace = null, projects = null)
        }
        val accepted = request { client, token -> client.createTask(token, pending.body, pending.key) }
        if (!accepted.isOfflineAcceptance()) throw CoreProtocolException()
        persist(selected.frozen.copy(pending = pending.copy(acceptance = accepted)))
        update { copy(outcome = accepted.status.offlineOutcome()) }
    }

    suspend fun checkTask() = perform {
        if (!current.connected) return@perform
        val previous = draft ?: return@perform
        val pending = previous.pending ?: return@perform
        val accepted = pending.acceptance ?: return@perform
        verifyAuthority()
        verifyProject(pending)
        val effect = request { client, token -> client.effect(token, accepted.effectId) }
        if (effect.id != accepted.effectId || effect.runId != accepted.runId ||
            (effect.state == CoreEffectState.Confirmed) != (effect.taskId != null)
        ) throw CoreProtocolException()
        val outcome = when (effect.state) {
            CoreEffectState.Queued -> CoreTaskOutcome.Queued
            CoreEffectState.WaitingApproval -> CoreTaskOutcome.WaitingApproval
            CoreEffectState.Failed -> CoreTaskOutcome.Failed
            CoreEffectState.Canceled -> CoreTaskOutcome.Canceled
            CoreEffectState.Confirmed -> {
                val task = verifiedTask(pending, checkNotNull(effect.taskId))
                ensureOnline()
                if (!disk { store.remove(active.scope, previous) }) throw OfflineWorkStorageException()
                setDraft(null)
                CoreTaskOutcome.Confirmed(task)
            }
        }
        update { copy(outcome = outcome) }
    }

    private suspend fun verifiedTask(pending: OfflineWorkPending, taskId: String): CoreWorkTask {
        val accepted = checkNotNull(pending.acceptance)
        if (!coreIdentifier.matches(taskId)) throw CoreProtocolException()
        val receipt = request { client, token ->
            client.partner<PartnerReceipts>(token, PartnerRoute.Receipts, accepted.effectId)
        }.items.singleOrNull() ?: throw CoreProtocolException()
        val evidence = receipt.taskEvidence
        if (receipt.schemaVersion != 1 || receipt.orgId != active.session.orgId || receipt.effectId != accepted.effectId ||
            receipt.runId != accepted.runId || !coreIdentifier.matches(receipt.receiptId) || receipt.completion != null ||
            !partnerText(receipt.redactedSummary, 8_000) ||
            evidence.taskId != taskId || evidence.taskVersion != 1L || !coreDigest.matches(evidence.taskDigest) ||
            !orderedTimes(receipt.verifiedAt, receipt.createdAt)
        ) throw CoreProtocolException()
        val task = online { sessions.task(taskId, pending.projectId) }
        val original = pending.decode()
        if (task.version !in evidence.taskVersion..CORE_MAX_VERSION ||
            (task.version == evidence.taskVersion && (task.title != original.title || task.description != original.description))
        ) throw CoreProtocolException()
        return task
    }

    private fun orderedTimes(verified: String, created: String): Boolean = try {
        Instant.parse(verified) <= Instant.parse(created)
    } catch (_: IllegalArgumentException) {
        false
    }

    private suspend fun verifyAuthority() {
        val who = request { client, token -> client.session(token) }
        if (who != active.session || who.role == CoreRole.viewer) throw CoreHttpException(403)
        val readiness = request { client, token -> client.organizationReadiness(token) }
        if (!readiness.ready || readiness.orgId != who.orgId) throw CoreHttpException(403)
    }

    private suspend fun verifyProject(pending: OfflineWorkPending): CoreProjectChoice {
        var cursor: String? = null
        val seen = mutableSetOf<String>()
        repeat(50) {
            val page = online { sessions.partnerProjectChoices(active, pending.workspaceId, cursor) }
            page.items.singleOrNull { it.id == pending.projectId }?.let { return it }
            val next = page.nextCursor ?: throw CoreHttpException(404)
            if (!seen.add(next)) throw CoreProtocolException()
            cursor = next
        }
        throw CoreProtocolException()
    }

    private fun showReview(frozen: OfflineWorkDraft, project: CoreProjectChoice, retry: Boolean) {
        val summary = OfflineWorkReview(project.name, frozen.title, frozen.description, retry)
        update {
            reviewed = Reviewed(summary, frozen)
            copy(review = summary)
        }
    }

    private suspend fun persist(next: OfflineWorkDraft) {
        val previous = draft
        if (!disk { store.replace(active.scope, previous, next) }) throw OfflineWorkStorageException()
        setDraft(next)
    }

    private fun setDraft(value: OfflineWorkDraft?) {
        update {
            draft = value
            copy(loaded = true, title = value?.title.orEmpty(), description = value?.description.orEmpty(),
                saved = value != null, frozen = value?.pending != null)
        }
    }

    private suspend fun <T> request(operation: suspend (CoreApiClient, String) -> T): T =
        online { sessions.requestForActiveSession(active, operation) }

    private suspend fun <T> online(operation: suspend () -> T): T {
        ensureOnline()
        val result = operation()
        ensureOnline()
        return result
    }

    private suspend fun ensureOnline() {
        currentCoroutineContext().ensureActive()
        if (retired || !current.connected || !sessions.commitIfCurrent(active.epoch) {}) throw CancellationException("Work is offline")
    }

    private suspend fun <T> disk(operation: () -> T): T = withContext(io) {
        currentCoroutineContext().ensureActive()
        if (!sessions.commitIfCurrent(active.epoch) {} || retired) throw CancellationException("Work session changed")
        operation()
    }

    private fun update(transform: CoreOfflineWorkState.() -> CoreOfflineWorkState) {
        sessions.commitIfCurrent(active.epoch) {
            if (!retired && active.session.role != CoreRole.viewer) mutableState.value = current.transform()
        }
    }

    private suspend fun perform(operation: suspend () -> Unit) = coroutineScope {
        val running = currentCoroutineContext()[Job]
        var started = false
        val captured = generation
        val unreadable = current.notice == OfflineWorkNotice.Unreadable
        update {
            if (busy) this else {
                started = true
                job = running
                copy(busy = true, notice = notice.takeIf { unreadable })
            }
        }
        if (!started) return@coroutineScope
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            sessions.state.first { it !== active }
            running?.cancel(CancellationException("Work session changed"))
        }
        try {
            currentCoroutineContext().ensureActive()
            operation()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            if (captured == generation) onFailure(error)
        } finally {
            watcher.cancel()
            if (captured == generation) update {
                job = null
                copy(busy = false)
            }
        }
    }

    private fun onFailure(error: Exception) {
        val reason = when (error) {
            is OfflineWorkStorageException -> OfflineWorkNotice.StorageUnavailable
            is IOException -> if (draft?.pending?.acceptance == null && current.frozen) OfflineWorkNotice.Unconfirmed
                else OfflineWorkNotice.NetworkUnavailable
            is CoreHttpException -> when (error.status) {
                401, 403, 423 -> OfflineWorkNotice.AccessDenied
                404 -> OfflineWorkNotice.ProjectUnavailable
                409 -> OfflineWorkNotice.Changed
                else -> if (current.frozen && draft?.pending?.acceptance == null) OfflineWorkNotice.Unconfirmed
                    else OfflineWorkNotice.Unavailable
            }
            else -> OfflineWorkNotice.Unavailable
        }
        resetConnection()
        if (reason == OfflineWorkNotice.AccessDenied) {
            setDraft(null)
            update { copy(accessLost = true, notice = reason) }
        } else {
            update { copy(notice = reason) }
        }
    }
}

private fun CoreTaskCommandStatus.offlineOutcome(): CoreTaskOutcome = when (this) {
    CoreTaskCommandStatus.Queued -> CoreTaskOutcome.Queued
    CoreTaskCommandStatus.WaitingApproval -> CoreTaskOutcome.WaitingApproval
}
