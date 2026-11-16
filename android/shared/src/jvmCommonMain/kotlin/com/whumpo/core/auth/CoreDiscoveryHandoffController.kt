package com.whumpo.core.auth

import com.whumpo.core.api.CoreDiscoveryActionId
import com.whumpo.core.api.CoreDiscoveryHandoff
import com.whumpo.core.api.CoreDiscoveryPlanStatus
import com.whumpo.core.api.CoreHandoffCommand
import com.whumpo.core.api.CoreHandoffDependency
import com.whumpo.core.api.CoreHandoffLineage
import com.whumpo.core.api.CoreHandoffState
import com.whumpo.core.api.CoreHttpException
import com.whumpo.core.api.CoreProjectChoice
import com.whumpo.core.api.CoreProjectChoices
import com.whumpo.core.api.CoreRole
import com.whumpo.core.api.CoreWorkTask
import com.whumpo.core.api.CoreWorkspaceChoice
import com.whumpo.core.api.CoreWorkspaceChoices
import com.whumpo.core.api.PartnerApproval
import com.whumpo.core.api.PartnerDecision
import com.whumpo.core.api.checkDiscovery
import com.whumpo.core.api.coreIdentifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

enum class DiscoveryHandoffNotice { Unavailable, Changed, Denied, SignInAgain, Unconfirmed, CheckSaved }

data class DiscoveryHandoffReview(
    val actionId: CoreDiscoveryActionId,
    val title: String,
    val reason: String,
    val discoveryRevision: Long,
    val answerRevision: Long,
    val planRevision: Long,
    val fixedProjectId: String?,
    val project: CoreProjectChoice?,
    val prerequisites: List<CoreDiscoveryActionId>,
    val pendingPrerequisites: List<CoreDiscoveryActionId>,
    val dependencies: List<CoreHandoffDependency>,
    val existing: CoreDiscoveryHandoff?,
) {
    val projectId: String? get() = fixedProjectId ?: project?.id
    val canSubmit: Boolean get() = projectId != null && existing == null && pendingPrerequisites.isEmpty()

    internal fun command() = CoreHandoffCommand(checkNotNull(projectId), discoveryRevision,
        answerRevision, planRevision, actionId)
}

data class DiscoveryHandoffState(
    val inventory: List<CoreDiscoveryHandoff> = emptyList(),
    val review: DiscoveryHandoffReview? = null,
    val detail: CoreDiscoveryHandoff? = null,
    val approval: PartnerApproval? = null,
    val verifiedTask: CoreWorkTask? = null,
    val workspaces: CoreWorkspaceChoices? = null,
    val workspace: CoreWorkspaceChoice? = null,
    val projects: CoreProjectChoices? = null,
    val busy: Boolean = false,
    val pending: Boolean = false,
    val acknowledged: Boolean = false,
    val notice: DiscoveryHandoffNotice? = null,
)

/** Owns one organization's reviewed Work handoffs and exact in-memory retries. */
class CoreDiscoveryHandoffController(
    private val sessions: CoreOrganizationSessionController,
    private val active: CoreOrganizationSessionController.State.Active,
    private val discovery: CoreBusinessDiscoveryController,
) {
    private val partner = CorePartnerProtocol(sessions, active)
    private val mutableState = MutableStateFlow(DiscoveryHandoffState())
    val state = mutableState.asStateFlow()
    private val current get() = mutableState.value
    private var retired = false
    private var job: Job? = null
    private var attempt: Attempt? = null

    private sealed interface Attempt {
        var acknowledged: Boolean
        data class Admit(
            val command: CoreHandoffCommand,
            val dependencies: List<CoreHandoffDependency>,
            val wire: PartnerWrite = PartnerWrite(partnerOperationKey(), command.encode()),
            var receipt: CoreDiscoveryHandoff? = null,
        ) : Attempt {
            override var acknowledged = false
        }
        data class Decide(
            val handoff: CoreDiscoveryHandoff,
            val approval: PartnerApproval,
            val choice: PartnerDecision,
            val wire: PartnerWrite = PartnerWrite.decision(approval, choice),
        ) : Attempt {
            override var acknowledged = false
        }
    }

    fun retire() {
        retired = true
        job?.cancel()
        attempt = null
        mutableState.value = DiscoveryHandoffState()
    }

    suspend fun refreshInventory() = perform {
        if (attempt == null) reloadInventory()
    }

    suspend fun beginReview(action: CoreDiscoveryActionId, displayedRevision: Long) = perform {
        if (attempt != null) return@perform
        resetDetail()
        val review = loadReview(action, null, displayedRevision) ?: return@perform
        val workspaces = if (review.fixedProjectId == null && review.existing == null) {
            read { sessions.partnerWorkspaceChoices(active, null) }
        } else null
        update { copy(review = review, workspaces = workspaces) }
    }

    suspend fun moreWorkspaces() = perform {
        val page = current.workspaces ?: return@perform
        val cursor = page.nextCursor ?: return@perform
        if (attempt != null) return@perform
        val next = read { sessions.partnerWorkspaceChoices(active, cursor) }
        update { copy(workspaces = next) }
    }

    suspend fun chooseWorkspace(choice: CoreWorkspaceChoice) = perform {
        val review = current.review ?: return@perform
        if (attempt != null || review.fixedProjectId != null || choice !in current.workspaces?.items.orEmpty()) return@perform
        val projects = read { sessions.partnerProjectChoices(active, choice.id, null) }
        update {
            copy(workspace = choice, projects = projects,
                review = review.copy(project = null, dependencies = emptyList(), pendingPrerequisites = review.prerequisites))
        }
    }

    suspend fun moreProjects() = perform {
        val workspace = current.workspace ?: return@perform
        val cursor = current.projects?.nextCursor ?: return@perform
        if (attempt != null) return@perform
        val next = read { sessions.partnerProjectChoices(active, workspace.id, cursor) }
        update { copy(projects = next) }
    }

    suspend fun chooseProject(choice: CoreProjectChoice) = perform {
        val previous = current.review ?: return@perform
        if (attempt != null || previous.fixedProjectId != null || choice !in current.projects?.items.orEmpty() ||
            choice.workspaceId != current.workspace?.id) return@perform
        val review = loadReview(previous.actionId, choice, previous.discoveryRevision) ?: return@perform
        if (review.planRevision != previous.planRevision || review.answerRevision != previous.answerRevision ||
            review.fixedProjectId != previous.fixedProjectId) {
            changed()
            return@perform
        }
        update { copy(review = review) }
    }

    suspend fun submit(reviewed: DiscoveryHandoffReview) = perform {
        if (attempt != null || current.review != reviewed || !reviewed.canSubmit) return@perform
        val canonical = loadReview(reviewed.actionId, reviewed.project, reviewed.discoveryRevision) ?: return@perform
        if (canonical != reviewed) {
            changed()
            return@perform
        }
        execute(Attempt.Admit(canonical.command(), canonical.dependencies), retry = false)
    }

    suspend fun inspect(id: String) = perform {
        if (attempt != null) return@perform
        resetDetail()
        showDetail(detail(id))
        reloadInventory()
    }

    suspend fun reviewApproval(id: String) = perform {
        if (attempt != null) return@perform
        val handoff = current.detail?.takeIf { it.id == id } ?: return@perform
        val latest = detail(id)
        checkTransition(handoff, latest)
        showDetail(latest)
        if (latest.state != CoreHandoffState.WaitingApproval || latest.lineage != CoreHandoffLineage.Current) return@perform
        val approval = read { partner.discoveryApproval(latest) }
        update { copy(approval = approval) }
    }

    suspend fun decide(reviewed: PartnerApproval, choice: PartnerDecision) = perform {
        val handoff = current.detail ?: return@perform
        if (attempt != null || current.approval != reviewed) return@perform
        val latest = detail(handoff.id)
        checkTransition(handoff, latest)
        if (latest != handoff || read { partner.discoveryApproval(latest) } != reviewed) {
            showDetail(latest)
            update { copy(notice = DiscoveryHandoffNotice.Changed) }
            return@perform
        }
        execute(Attempt.Decide(latest, reviewed, choice), retry = false)
    }

    suspend fun retry() = perform { attempt?.let { execute(it, retry = true) } }

    private suspend fun loadReview(actionId: CoreDiscoveryActionId, selected: CoreProjectChoice?, displayedRevision: Long): DiscoveryHandoffReview? {
        val tokenScope = read {
            sessions.requestForActiveSession(active) { api, token -> api.organizationReadiness(token) }
        }
        checkDiscovery(tokenScope.orgId == active.session.orgId &&
            tokenScope.projectId?.let(coreIdentifier::matches) != false)
        val snapshot = read {
            sessions.requestForActiveSession(active) { api, token -> api.businessDiscovery(token) }
        }
        checkDiscovery(snapshot.orgId == active.session.orgId)
        val plan = snapshot.plan
        val action = plan?.actions?.singleOrNull { it.id == actionId && !it.deferred }
        if (!tokenScope.ready || snapshot.revision != displayedRevision || snapshot.planStatus != CoreDiscoveryPlanStatus.Current ||
            action == null || (selected != null && tokenScope.projectId != null)) {
            changed()
            return null
        }
        val items = reloadInventory()
        val projectId = tokenScope.projectId ?: selected?.id
        val dependencies = action.dependencies.mapNotNull { prerequisite ->
            if (projectId == null || plan.actions.none { it.id == prerequisite && !it.deferred }) return@mapNotNull null
            val item = items.singleOrNull {
                it.actionId == prerequisite && it.projectId == projectId &&
                    it.lineage == CoreHandoffLineage.Current && it.state == CoreHandoffState.Confirmed
            } ?: return@mapNotNull null
            CoreHandoffDependency(prerequisite, checkNotNull(item.task).taskId)
        }
        val existing = items.firstOrNull {
            it.actionId == actionId && when (it.state) {
                CoreHandoffState.WaitingApproval, CoreHandoffState.Queued -> true
                CoreHandoffState.Confirmed -> it.lineage == CoreHandoffLineage.Current
                CoreHandoffState.Failed, CoreHandoffState.Canceled -> false
            }
        }
        return DiscoveryHandoffReview(actionId, action.titleOverride ?: action.title, action.reason,
            snapshot.revision, snapshot.answerRevision, plan.revision, tokenScope.projectId, selected,
            action.dependencies, action.dependencies.filter { id -> dependencies.none { it.actionId == id } },
            dependencies, existing)
    }

    private suspend fun reloadInventory(): List<CoreDiscoveryHandoff> = read {
        sessions.requestForActiveSession(active) { api, token -> api.discoveryHandoffs(token, active.session.orgId) }
    }.also { items -> update { copy(inventory = items) } }

    private suspend fun detail(id: String): CoreDiscoveryHandoff = read {
        sessions.requestForActiveSession(active) { api, token -> api.discoveryHandoff(token, id) }
    }.also { checkDiscovery(it.id == id && it.orgId == active.session.orgId) }

    private suspend fun execute(operation: Attempt, retry: Boolean) {
        attempt = operation
        update { copy(pending = true, acknowledged = operation.acknowledged, approval = null, verifiedTask = null) }
        try {
            if (!operation.acknowledged) {
                when (operation) {
                    is Attempt.Admit -> {
                        val receipt = sessions.requestForActiveSession(active) { api, token ->
                            api.submitDiscoveryHandoff(token, operation.wire.key, operation.wire.body)
                        }
                        checkDiscovery(receipt.orgId == active.session.orgId && operation.command.matches(receipt) &&
                            receipt.dependencies == operation.dependencies)
                        operation.receipt = receipt
                    }
                    is Attempt.Decide -> partner.decide(operation.approval, operation.choice, operation.wire)
                }
                operation.acknowledged = true
                update { copy(acknowledged = true) }
            }
            val expected = when (operation) {
                is Attempt.Admit -> checkNotNull(operation.receipt)
                is Attempt.Decide -> operation.handoff
            }
            val latest = detail(expected.id)
            checkTransition(expected, latest)
            if (operation is Attempt.Decide) {
                checkDiscovery(latest.effectVersion > expected.effectVersion && latest.state != CoreHandoffState.WaitingApproval)
            }
            showDetail(latest)
            reloadInventory()
            attempt = null
            update { copy(pending = false, acknowledged = false) }
        } catch (error: CoreHttpException) {
            // A write can commit before its response loses authorization in a subsequent read.
            if (error.status == 403 || error.status == 423) hideAfterDeniedRead()
            if (retry || operation.acknowledged || error.status !in setOf(400, 409, 422, 428)) throw error
            attempt = null
            resetDetail()
            update {
                copy(pending = false, acknowledged = false, notice = DiscoveryHandoffNotice.Changed)
            }
        }
    }

    private fun checkTransition(before: CoreDiscoveryHandoff, after: CoreDiscoveryHandoff) {
        checkDiscovery(before.copy(state = after.state, effectVersion = after.effectVersion, task = after.task,
            lineage = after.lineage) == after && after.effectVersion >= before.effectVersion)
        if (after.effectVersion == before.effectVersion) checkDiscovery(after.state == before.state && after.task == before.task)
    }

    private suspend fun showDetail(handoff: CoreDiscoveryHandoff) {
        update { copy(detail = handoff, review = null, approval = null, verifiedTask = null) }
        if (handoff.state == CoreHandoffState.Confirmed) {
            val task = read { partner.verifyDiscoveryTask(handoff) }
            update { copy(verifiedTask = task) }
        }
    }

    private fun resetDetail() {
        update { copy(review = null, detail = null, approval = null, verifiedTask = null,
            workspaces = null, workspace = null, projects = null) }
    }

    private fun changed() {
        resetDetail()
        update { copy(notice = DiscoveryHandoffNotice.Changed) }
    }

    private suspend fun <T> read(operation: suspend () -> T): T = try {
        operation()
    } catch (error: CoreHttpException) {
        if (error.status == 403 || error.status == 423) hideAfterDeniedRead()
        throw error
    }

    private fun hideAfterDeniedRead() {
        discovery.hideAfterDeniedRead()
        resetDetail()
        update { copy(inventory = emptyList()) }
    }

    private fun update(transform: DiscoveryHandoffState.() -> DiscoveryHandoffState) {
        sessions.commitIfCurrent(active.epoch) { if (!retired) mutableState.value = current.transform() }
    }

    private suspend fun perform(operation: suspend () -> Unit) = coroutineScope {
        val running = currentCoroutineContext()[Job]
        var started = false
        sessions.commitIfCurrent(active.epoch) {
            if (!retired && !current.busy && active.session.role == CoreRole.owner) {
                started = true
                job = running
                mutableState.value = current.copy(busy = true, notice = null)
            }
        }
        if (!started) return@coroutineScope
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            sessions.state.first { it !== active }
            running?.cancel(CancellationException("Discovery handoff session changed"))
        }
        try {
            currentCoroutineContext().ensureActive()
            operation()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            update {
                copy(notice = when {
                    (error as? CoreHttpException)?.status == 403 -> DiscoveryHandoffNotice.SignInAgain
                    (error as? CoreHttpException)?.status == 423 -> DiscoveryHandoffNotice.Denied
                    acknowledged -> DiscoveryHandoffNotice.CheckSaved
                    pending -> DiscoveryHandoffNotice.Unconfirmed
                    else -> DiscoveryHandoffNotice.Unavailable
                })
            }
        } finally {
            watcher.cancel()
            update {
                job = null
                copy(busy = false)
            }
        }
    }
}
