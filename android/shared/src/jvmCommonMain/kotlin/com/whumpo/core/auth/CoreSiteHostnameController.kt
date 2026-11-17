package com.whumpo.core.auth

import com.whumpo.core.api.CoreApiClient
import com.whumpo.core.api.CoreDomainClaim
import com.whumpo.core.api.CoreDomainStatus
import com.whumpo.core.api.CoreHostnameAttachment
import com.whumpo.core.api.CoreHostnameCommand
import com.whumpo.core.api.CoreHostnamePublication
import com.whumpo.core.api.CoreHostnameReceipt
import com.whumpo.core.api.CoreHostnameStatus
import com.whumpo.core.api.CoreHttpException
import com.whumpo.core.api.CoreProjectChoice
import com.whumpo.core.api.CoreProjectChoices
import com.whumpo.core.api.CoreProtocolException
import com.whumpo.core.api.CoreRole
import com.whumpo.core.api.CoreWorkspaceChoice
import com.whumpo.core.api.CoreWorkspaceChoices
import com.whumpo.core.api.coreIdentifier
import java.io.IOException
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Instant
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

enum class HostnameAction { Attach, Resume, Disable }
enum class HostnamePending { Retry, CheckStatus }
enum class HostnameNotice { MissingPublication, MissingProof, Changed, PolicyUnavailable, Denied, Unavailable, Unconfirmed, CheckStatus, Current, Recorded }

data class HostnameReview(
    val action: HostnameAction,
    val hostname: String,
    val publicationVersion: Long? = null,
    val expiresAt: Instant? = null,
)

data class CoreSiteHostnameState(
    val project: DomainProject? = null,
    val workspaces: CoreWorkspaceChoices? = null,
    val workspace: CoreWorkspaceChoice? = null,
    val projects: CoreProjectChoices? = null,
    val attachment: CoreHostnameAttachment? = null,
    val loaded: Boolean = false,
    val claims: List<CoreDomainClaim>? = null,
    val review: HostnameReview? = null,
    val pending: HostnamePending? = null,
    val pendingUntil: Instant? = null,
    val policyUnavailable: Boolean = false,
    val busy: Boolean = false,
    val notice: HostnameNotice? = null,
)

class CoreSiteHostnameController(
    private val sessions: CoreOrganizationSessionController,
    private val active: CoreOrganizationSessionController.State.Active,
    private val now: () -> Instant = { Clock.System.now() },
) {
    private class Review(
        val summary: HostnameReview,
        val command: CoreHostnameCommand,
        val claim: CoreDomainClaim? = null,
        val publication: CoreHostnamePublication? = null,
    )

    private class Attempt(val project: String, val review: Review) {
        val key = UUID.randomUUID().toString()
        var readback = false
        var receipt: CoreHostnameReceipt? = null
        var changed = false
    }

    private val mutableState = MutableStateFlow(CoreSiteHostnameState())
    val state = mutableState.asStateFlow()
    private val current get() = mutableState.value
    private var review: Review? = null
    private var attempt: Attempt? = null
    private var job: Job? = null
    private var retired = false

    fun retire() {
        retired = true
        job?.cancel()
        review = null
        attempt = null
        mutableState.value = CoreSiteHostnameState()
    }

    suspend fun refresh() = perform {
        if (attempt != null) return@perform
        val fixed = provisionedProject()
        val previous = current.project?.takeIf { it.fixed == (fixed != null) && (fixed == null || it.id == fixed) }
        val project = previous ?: fixed?.let { DomainProject(it, null, true) }
        if (project == null) chooseWorkspacePage() else showProject(project)
    }

    suspend fun chooseDifferentProject() = perform {
        if (attempt == null && current.project?.fixed == false && provisionedProject() == null) chooseWorkspacePage()
    }

    suspend fun moreWorkspaces() = perform {
        val cursor = current.workspaces?.nextCursor ?: return@perform
        val choices = sessions.partnerWorkspaceChoices(active, cursor)
        update { copy(workspaces = choices, workspace = null, projects = null) }
    }

    suspend fun chooseWorkspace(choice: CoreWorkspaceChoice) = perform {
        if (choice !in current.workspaces?.items.orEmpty()) return@perform
        val choices = sessions.partnerProjectChoices(active, choice.id, null)
        update { copy(workspace = choice, projects = choices) }
    }

    suspend fun moreProjects() = perform {
        val cursor = current.projects?.nextCursor ?: return@perform
        val workspace = current.workspace ?: return@perform
        val choices = sessions.partnerProjectChoices(active, workspace.id, cursor)
        update { copy(projects = choices) }
    }

    suspend fun chooseProject(choice: CoreProjectChoice) = perform {
        if (choice !in current.projects?.items.orEmpty() || choice.workspaceId != current.workspace?.id) return@perform
        val fixed = provisionedProject()
        showProject(if (fixed == null) DomainProject(choice.id, choice.name, false) else DomainProject(fixed, null, true))
    }

    suspend fun loadClaims() = perform {
        val project = current.project?.id ?: return@perform
        if (!canReviewProof()) return@perform
        clearReview()
        if (publication(project) == null) {
            update { copy(claims = null, notice = HostnameNotice.MissingPublication) }
            return@perform
        }
        val choices = claims(project)
        update { copy(claims = choices, notice = HostnameNotice.MissingProof.takeIf { choices.isEmpty() }) }
    }

    suspend fun reviewClaim(selected: CoreDomainClaim) = perform {
        val project = current.project?.id ?: return@perform
        if (!canReviewProof() || selected !in current.claims.orEmpty()) return@perform
        clearReview()
        val base = current.attachment
        val choices = claims(project)
        val claim = choices.singleOrNull { it == selected && (base == null || base.hostname == it.hostname) }
        if (claim == null) {
            update { copy(claims = choices, notice = HostnameNotice.Changed) }
            return@perform
        }
        val publication = publication(project)
        if (publication == null) {
            update { copy(claims = null, notice = HostnameNotice.MissingPublication) }
            return@perform
        }
        val response = request { api, token -> api.hostnameReview(token, active.session.orgId, project, claim, publication) }
        if (response.current != base || claim.statusAt(now()) != CoreDomainStatus.OwnershipVerified) {
            update { copy(attachment = response.current, claims = null, notice = HostnameNotice.Changed) }
            return@perform
        }
        val command = if (base == null) CoreHostnameCommand.Attach(claim.hostname, response.approval)
            else CoreHostnameCommand.Resume(base, response.approval)
        val action = if (base == null) HostnameAction.Attach else HostnameAction.Resume
        setReview(Review(HostnameReview(action, command.hostname, publication.targetVersion, claim.evidenceValidUntil),
            command, claim, publication))
    }

    fun reviewDisable() {
        val base = current.attachment ?: return
        if (current.busy || attempt != null || !current.loaded || base.status == CoreHostnameStatus.Detaching) return
        setReview(Review(HostnameReview(HostnameAction.Disable, base.hostname), CoreHostnameCommand.Disable(base)))
    }

    fun cancelReview() {
        if (!current.busy) clearReview()
    }

    fun expireReview() {
        val deadline = review?.summary?.expiresAt
        if (deadline != null && deadline <= now() && !current.busy) {
            clearReview()
            update { copy(notice = HostnameNotice.Changed) }
        }
        val pending = attempt
        val pendingDeadline = pending?.review?.summary?.expiresAt
        if (pending != null && !pending.readback && pendingDeadline != null && pendingDeadline <= now() && !current.busy) {
            readbackOnly(pending)
        }
    }

    suspend fun confirm(reviewed: HostnameReview) = perform {
        val selected = review?.takeIf { it.summary === reviewed } ?: return@perform
        val project = current.project?.id ?: return@perform
        if (attempt != null) return@perform
        clearReview()
        val latest = status(project)
        if (latest != selected.command.base || !fresh(selected, project)) {
            update { copy(attachment = latest, claims = null, notice = HostnameNotice.Changed) }
            return@perform
        }
        val operation = Attempt(project, selected)
        attempt = operation
        update { copy(attachment = null, loaded = false, claims = null) }
        send(operation)
    }

    suspend fun retry() = perform {
        val operation = attempt?.takeUnless { it.readback } ?: return@perform
        val expires = operation.review.summary.expiresAt
        if (expires != null && expires <= now()) {
            readbackOnly(operation)
            readback(operation)
        } else {
            send(operation)
        }
    }

    suspend fun checkStatus() = perform {
        val operation = attempt ?: return@perform
        readbackOnly(operation)
        readback(operation)
    }

    private fun canReviewProof() = current.loaded && attempt == null && !current.policyUnavailable &&
        (current.attachment == null || current.attachment?.status == CoreHostnameStatus.ReproofRequired)

    private suspend fun fresh(selected: Review, project: String): Boolean {
        val claim = selected.claim ?: return true
        return claim.statusAt(now()) == CoreDomainStatus.OwnershipVerified && claim in claims(project) &&
            selected.publication == publication(project) && claim.statusAt(now()) == CoreDomainStatus.OwnershipVerified
    }

    private suspend fun send(operation: Attempt) {
        try {
            operation.receipt = request { api, token ->
                api.submitHostnameCommand(token, active.session.orgId, operation.project, operation.review.command, operation.key)
            }
        } catch (error: CoreHttpException) {
            when (error.status) {
                409 -> operation.changed = true
                in 500..599 -> { allowExactRetry(operation); return }
                else -> { readbackOnly(operation); throw error }
            }
        } catch (error: IOException) {
            allowExactRetry(operation)
            return
        } catch (error: CoreProtocolException) {
            readbackOnly(operation)
            throw error
        }
        readbackOnly(operation)
        readback(operation)
    }

    private fun allowExactRetry(operation: Attempt) {
        val deadline = operation.review.summary.expiresAt
        if (deadline != null && deadline <= now()) readbackOnly(operation)
        else update { copy(pending = HostnamePending.Retry, pendingUntil = deadline, notice = HostnameNotice.Unconfirmed) }
    }

    private fun readbackOnly(operation: Attempt) {
        operation.readback = true
        update { copy(pending = HostnamePending.CheckStatus, pendingUntil = null, notice = HostnameNotice.CheckStatus) }
    }

    private suspend fun readback(operation: Attempt) {
        val latest = status(operation.project)
        operation.receipt?.verifyReadback(latest)
        attempt = null
        val notice = when {
            operation.changed -> HostnameNotice.Changed
            operation.receipt != null -> HostnameNotice.Recorded
            else -> HostnameNotice.Current
        }
        update { copy(attachment = latest, loaded = true, pending = null, notice = notice) }
    }

    private suspend fun chooseWorkspacePage() {
        clearReview()
        update { CoreSiteHostnameState(busy = busy) }
        val choices = sessions.partnerWorkspaceChoices(active, null)
        update { copy(workspaces = choices) }
    }

    private suspend fun showProject(project: DomainProject) {
        clearReview()
        update { CoreSiteHostnameState(project = project, busy = busy) }
        val attachment = status(project.id)
        update { copy(attachment = attachment, loaded = true) }
    }

    private suspend fun provisionedProject(): String? = request { api, token -> api.organizationReadiness(token) }.let {
        if (it.orgId != active.session.orgId || it.projectId?.let(coreIdentifier::matches) == false) throw CoreProtocolException()
        if (!it.ready) throw CoreHttpException(403)
        it.projectId
    }

    private suspend fun status(project: String) =
        request { api, token -> api.hostnameStatus(token, active.session.orgId, project) }

    private suspend fun claims(project: String) =
        request { api, token -> api.domainClaims(token, active.session.orgId, project) }
            .filter { it.statusAt(now()) == CoreDomainStatus.OwnershipVerified }

    private suspend fun publication(project: String) = sessions.currentPublication(active, project)?.let {
        CoreHostnamePublication(it.targetId, it.targetVersion, it.effectId, it.artifactDigest, it.pageManifestDigest)
    }

    private suspend fun <T> request(operation: suspend (CoreApiClient, String) -> T): T =
        sessions.requestForActiveSession(active, operation)

    private fun setReview(value: Review) {
        update {
            this@CoreSiteHostnameController.review = value
            copy(review = value.summary, notice = null)
        }
    }

    private fun clearReview() {
        update {
            this@CoreSiteHostnameController.review = null
            copy(review = null)
        }
    }

    private fun update(transform: CoreSiteHostnameState.() -> CoreSiteHostnameState) {
        sessions.commitIfCurrent(active.epoch) {
            if (!retired) mutableState.value = current.transform()
        }
    }

    private suspend fun perform(operation: suspend () -> Unit) = coroutineScope {
        val running = currentCoroutineContext()[Job]
        var started = false
        update {
            if (busy || active.session.role != CoreRole.owner) this else {
                started = true
                job = running
                copy(busy = true, notice = null)
            }
        }
        if (!started) return@coroutineScope
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            sessions.state.first { it !== active }
            running?.cancel(CancellationException("Hostname session changed"))
        }
        try {
            currentCoroutineContext().ensureActive()
            operation()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            clearReview()
            update {
                when ((error as? CoreHttpException)?.status) {
                    401, 403, 404 -> copy(attachment = null, claims = null, loaded = false, notice = HostnameNotice.Denied)
                    423 -> copy(claims = null, policyUnavailable = true, notice = HostnameNotice.PolicyUnavailable)
                    else -> copy(attachment = null, claims = null, loaded = false,
                        notice = if (pending == HostnamePending.CheckStatus) HostnameNotice.CheckStatus else HostnameNotice.Unavailable)
                }
            }
        } finally {
            watcher.cancel()
            update {
                job = null
                copy(busy = false)
            }
            expireReview()
        }
    }
}
