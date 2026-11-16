package com.whumpo.core.auth

import com.whumpo.core.api.CORE_MAX_VERSION
import com.whumpo.core.api.CoreDomainClaim
import com.whumpo.core.api.CoreDomainCommand
import com.whumpo.core.api.CoreDomainOperationKind
import com.whumpo.core.api.CoreDomainStatus
import com.whumpo.core.api.CoreHttpException
import com.whumpo.core.api.CoreProjectChoice
import com.whumpo.core.api.CoreProjectChoices
import com.whumpo.core.api.CoreRole
import com.whumpo.core.api.CoreWorkspaceChoice
import com.whumpo.core.api.CoreWorkspaceChoices
import com.whumpo.core.api.PartnerHttpException
import com.whumpo.core.api.coreIdentifier
import com.whumpo.core.api.normalizeCoreDomainHostname
import com.whumpo.core.api.requireDomain
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
import kotlin.time.Clock
import kotlin.time.Instant

enum class DomainNotice { Unavailable, Denied, InvalidHostname, Changed, Unconfirmed, CheckSaved }

data class DomainProject(val id: String, val name: String?, val fixed: Boolean)

data class CoreSiteDomainState(
    val project: DomainProject? = null,
    val projectChoices: CoreProjectChoices? = null,
    val workspace: CoreWorkspaceChoice? = null,
    val workspaceChoices: CoreWorkspaceChoices? = null,
    val claims: List<CoreDomainClaim> = emptyList(),
    val claim: CoreDomainClaim? = null,
    val busy: Boolean = false,
    val pending: Boolean = false,
    val readbackOnly: Boolean = false,
    val notice: DomainNotice? = null,
)

/** One verified owner session owns selection and any uncertain DNS operation. */
class CoreSiteDomainController(
    private val sessions: CoreOrganizationSessionController,
    private val active: CoreOrganizationSessionController.State.Active,
    private val now: () -> Instant = { Clock.System.now() },
) {
    private val mutableState = MutableStateFlow(CoreSiteDomainState())
    val state = mutableState.asStateFlow()
    private val current get() = mutableState.value
    private var attempt: Attempt? = null
    private var job: Job? = null
    private var retired = false

    private enum class Recheck { Conflict, ValidationRejected }

    private class Attempt(val project: DomainProject, val command: CoreDomainCommand) {
        val write = PartnerWrite(partnerOperationKey(), command.encode())
        var acknowledgement: CoreDomainClaim? = null
        var recheck: Recheck? = null
        val readbackOnly get() = acknowledgement != null || recheck != null
    }

    fun retire() {
        retired = true
        job?.cancel()
        attempt = null
        mutableState.value = CoreSiteDomainState()
    }

    suspend fun refresh() = perform {
        if (attempt != null) return@perform
        val fixed = provisionedProject()
        val previous = current.project?.takeIf { it.fixed == (fixed != null) && (fixed == null || it.id == fixed) }
        val project = previous ?: fixed?.let { DomainProject(it, null, fixed = true) }
        if (project == null) loadWorkspaces() else showClaims(project)
    }

    suspend fun chooseDifferentProject() = perform {
        if (attempt == null && current.project?.fixed == false && provisionedProject() == null) loadWorkspaces()
    }

    suspend fun moreWorkspaces() = perform {
        val cursor = current.workspaceChoices?.nextCursor ?: return@perform
        if (attempt != null || current.project != null) return@perform
        val choices = read { sessions.partnerWorkspaceChoices(active, cursor) }
        update { copy(workspaceChoices = choices, workspace = null, projectChoices = null) }
    }

    suspend fun chooseWorkspace(workspace: CoreWorkspaceChoice) = perform {
        if (attempt != null || current.project != null || workspace !in current.workspaceChoices?.items.orEmpty()) return@perform
        val choices = read { sessions.partnerProjectChoices(active, workspace.id, null) }
        update { copy(workspace = workspace, projectChoices = choices) }
    }

    suspend fun moreProjects() = perform {
        val workspace = current.workspace ?: return@perform
        val cursor = current.projectChoices?.nextCursor ?: return@perform
        if (attempt != null || current.project != null) return@perform
        val choices = read { sessions.partnerProjectChoices(active, workspace.id, cursor) }
        update { copy(projectChoices = choices) }
    }

    suspend fun chooseProject(choice: CoreProjectChoice) = perform {
        if (attempt != null || current.project != null || choice !in current.projectChoices?.items.orEmpty() ||
            choice.workspaceId != current.workspace?.id) return@perform
        val fixed = provisionedProject()
        if (fixed != null) {
            showClaims(DomainProject(fixed, null, fixed = true))
            update { copy(notice = DomainNotice.Changed) }
        } else {
            showClaims(DomainProject(choice.id, choice.name, fixed = false))
        }
    }

    suspend fun inspect(claim: CoreDomainClaim) = perform {
        val project = current.project ?: return@perform
        if (attempt != null || claim !in current.claims) return@perform
        update { copy(claim = null) }
        val detail = detail(project.id, claim.id)
        requireDomain(detail.follows(claim))
        display(project, detail)
    }

    suspend fun prepare(hostname: String) = perform {
        val project = current.project ?: return@perform
        if (attempt != null || current.claims.size >= 5) return@perform
        val canonical = normalizeCoreDomainHostname(hostname)
        if (canonical == null) {
            update { copy(notice = DomainNotice.InvalidHostname) }
        } else {
            execute(Attempt(project, CoreDomainCommand.Prepare(canonical)))
        }
    }

    suspend fun check(claim: CoreDomainClaim) = operate(claim, CoreDomainOperationKind.Check)
    suspend fun renew(claim: CoreDomainClaim) = operate(claim, CoreDomainOperationKind.Renew)

    private suspend fun operate(claim: CoreDomainClaim, kind: CoreDomainOperationKind) = perform {
        val project = current.project ?: return@perform
        if (attempt != null || current.claim != claim || claim.projectId != project.id) return@perform
        val expired = claim.statusAt(now()) == CoreDomainStatus.Expired
        if ((kind == CoreDomainOperationKind.Renew) != expired ||
            (kind == CoreDomainOperationKind.Renew && claim.generation >= CORE_MAX_VERSION)) {
            update { copy(notice = DomainNotice.Changed) }
            return@perform
        }
        execute(Attempt(project, CoreDomainCommand.Existing(kind, claim)))
    }

    suspend fun retry() = perform { attempt?.let { execute(it) } }

    fun dismissClaim() {
        update { copy(claim = null) }
    }

    private suspend fun execute(operation: Attempt) {
        val retrying = attempt === operation
        attempt = operation
        update { copy(claim = null, pending = true, readbackOnly = operation.readbackOnly) }
        if (!operation.readbackOnly) {
            try {
                operation.acknowledgement = read {
                    sessions.requestForActiveSession(active) { api, token ->
                        api.submitDomainCommand(token, active.session.orgId, operation.project.id, operation.command,
                            operation.write.body, operation.write.key)
                    }
                }
            } catch (error: CoreHttpException) {
                when {
                    error.status == 409 -> operation.recheck = Recheck.Conflict
                    error is PartnerHttpException && error.status == 422 && error.rejectsWrite &&
                        operation.command.kind == CoreDomainOperationKind.Prepare -> {
                        if (retrying) {
                            operation.recheck = Recheck.ValidationRejected
                        } else {
                            attempt = null
                            update { copy(pending = false, notice = DomainNotice.InvalidHostname) }
                            return
                        }
                    }
                    else -> throw error
                }
            }
            update { copy(readbackOnly = true) }
        }
        val acknowledgement = operation.acknowledgement
        if (acknowledgement != null) {
            val canonical = detail(operation.project.id, acknowledgement.id)
            requireDomain(canonical.follows(acknowledgement))
            if (current.project == operation.project) {
                display(operation.project, canonical)
            } else {
                showClaims(operation.project, canonical)
                update { copy(claim = claims.single { it.id == canonical.id }) }
            }
        } else {
            showClaims(operation.project, operation.command.claim)
            update {
                val knownClaim = claims.any { it.hostname == operation.command.expectedHostname }
                copy(notice = if (operation.recheck == Recheck.ValidationRejected && !knownClaim) {
                    DomainNotice.InvalidHostname
                } else DomainNotice.Changed)
            }
        }
        attempt = null
        update { copy(pending = false, readbackOnly = false) }
    }

    private suspend fun provisionedProject(): String? = read {
        sessions.requestForActiveSession(active) { api, token -> api.organizationReadiness(token) }
    }.let {
        requireDomain(it.orgId == active.session.orgId && it.projectId?.let(coreIdentifier::matches) != false)
        if (!it.ready) throw CoreHttpException(423)
        it.projectId
    }

    private suspend fun loadWorkspaces() {
        val choices = read { sessions.partnerWorkspaceChoices(active, null) }
        update { copy(project = null, workspaceChoices = choices, projectChoices = null, workspace = null,
            claims = emptyList(), claim = null) }
    }

    private suspend fun showClaims(project: DomainProject, requiredBaseline: CoreDomainClaim? = null) {
        val items = read {
            sessions.requestForActiveSession(active) { api, token -> api.domainClaims(token, active.session.orgId, project.id) }
        }
        val previous = current.claims.takeIf { current.project == project }.orEmpty()
        requireDomain((previous + listOfNotNull(requiredBaseline)).all { older -> items.any { it.follows(older) } })
        val selected = current.claim?.id
        update { copy(project = project, projectChoices = null, workspace = null, workspaceChoices = null,
            claims = items, claim = items.firstOrNull { it.id == selected }) }
    }

    private suspend fun detail(project: String, id: String): CoreDomainClaim = read {
        sessions.requestForActiveSession(active) { api, token -> api.domainClaim(token, active.session.orgId, project, id) }
    }

    private fun display(project: DomainProject, detail: CoreDomainClaim) {
        val previous = current.claims.takeIf { current.project == project }.orEmpty()
        val older = previous.firstOrNull { it.id == detail.id || it.hostname == detail.hostname }
        requireDomain(older?.let(detail::follows) != false)
        val items = previous.filterNot { it.id == detail.id } + detail
        requireDomain(items.size <= 5)
        update { copy(project = project, claims = items.sortedBy { it.hostname }, claim = detail) }
    }

    private suspend fun <T> read(operation: suspend () -> T): T = try {
        operation()
    } catch (error: CoreHttpException) {
        if (error.deniesDomainAccess()) clearPrivate()
        throw error
    }

    private fun CoreHttpException.deniesDomainAccess() = status == 401 || status == 403 || status == 404 || status == 423

    private fun clearPrivate() {
        update { copy(project = null, workspaceChoices = null, workspace = null, projectChoices = null,
            claims = emptyList(), claim = null) }
    }

    private fun update(transform: CoreSiteDomainState.() -> CoreSiteDomainState) {
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
            running?.cancel(CancellationException("Domain claim session changed"))
        }
        try {
            currentCoroutineContext().ensureActive()
            operation()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            val denied = (error as? CoreHttpException)?.deniesDomainAccess() == true
            if (denied) clearPrivate()
            update {
                copy(notice = when {
                    denied -> DomainNotice.Denied
                    readbackOnly -> DomainNotice.CheckSaved
                    pending -> DomainNotice.Unconfirmed
                    else -> DomainNotice.Unavailable
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
