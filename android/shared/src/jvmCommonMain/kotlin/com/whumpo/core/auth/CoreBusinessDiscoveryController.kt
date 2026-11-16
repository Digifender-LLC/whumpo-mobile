package com.whumpo.core.auth

import com.whumpo.core.api.CoreDiscoveryCommand
import com.whumpo.core.api.CoreDiscoveryEdit
import com.whumpo.core.api.CoreDiscoveryKey
import com.whumpo.core.api.CoreDiscoveryPlanStatus
import com.whumpo.core.api.CoreDiscoveryReceipt
import com.whumpo.core.api.CoreDiscoverySnapshot
import com.whumpo.core.api.CoreDiscoveryValue
import com.whumpo.core.api.CoreHttpException
import com.whumpo.core.api.CoreRole
import com.whumpo.core.api.checkDiscovery
import com.whumpo.core.api.encodeDiscovery
import com.whumpo.core.api.isDiscoveryText
import java.text.Normalizer
import java.util.UUID
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

enum class CoreDiscoveryNotice {
    Unavailable, PolicyUnavailable, InvalidAnswer, Unconfirmed, ConfirmationPending, Changed, SignInAgain, Rejected,
}

data class CoreConnectedDiscoveryState(
    val snapshot: CoreDiscoverySnapshot? = null,
    val busy: Boolean = false,
    val pending: Boolean = false,
    val acknowledged: Boolean = false,
    val requiresRefresh: Boolean = false,
    val notice: CoreDiscoveryNotice? = null,
    val invalidatedKeys: List<CoreDiscoveryKey> = emptyList(),
) {
    val canEdit: Boolean get() = snapshot != null && !busy && !pending && !requiresRefresh
}

/** Owns connected discovery and its retry for one authenticated organization session. */
class CoreBusinessDiscoveryController(
    private val sessions: CoreOrganizationSessionController,
    private val active: CoreOrganizationSessionController.State.Active,
) {
    private val mutableState = MutableStateFlow(CoreConnectedDiscoveryState())
    val state = mutableState.asStateFlow()
    private val current get() = mutableState.value
    private var job: Job? = null
    private var retired = false
    private var pending: Attempt? = null
    private var observedRevision = 0L
    private var observedAnswerRevision = 0L

    private data class Attempt(
        val baseline: CoreDiscoverySnapshot,
        val body: String,
        val key: String = UUID.randomUUID().toString(),
        var receipt: CoreDiscoveryReceipt? = null,
    )

    fun retire() {
        retired = true
        job?.cancel()
        pending = null
        mutableState.value = CoreConnectedDiscoveryState()
    }

    internal fun hideAfterDeniedRead() {
        update { copy(snapshot = null, invalidatedKeys = emptyList(), requiresRefresh = true) }
    }

    suspend fun refresh() = perform {
        val attempt = pending
        when {
            attempt == null -> publish(read())
            attempt.receipt != null -> reconcile(attempt)
            else -> update { copy(notice = CoreDiscoveryNotice.Unconfirmed) }
        }
    }

    suspend fun submit(command: CoreDiscoveryCommand, reviewedRevision: Long) = perform {
        val snapshot = current.snapshot?.takeUnless { current.pending || current.requiresRefresh } ?: return@perform
        if (snapshot.revision != reviewedRevision) {
            update { copy(requiresRefresh = true, notice = CoreDiscoveryNotice.Changed) }
            return@perform
        }
        val checked = command.checkedFor(snapshot)
        if (checked == null) {
            update { copy(notice = CoreDiscoveryNotice.InvalidAnswer) }
            return@perform
        }
        val body = checked.encodeDiscovery()
        if (body.encodeToByteArray().size > 4096) {
            update { copy(notice = CoreDiscoveryNotice.InvalidAnswer) }
            return@perform
        }
        val attempt = Attempt(snapshot, body)
        pending = attempt
        update { copy(pending = true, invalidatedKeys = emptyList()) }
        execute(attempt, retry = false)
    }

    suspend fun retry() = perform { pending?.let { execute(it, retry = true) } }

    private suspend fun read(): CoreDiscoverySnapshot = try {
        sessions.requestForActiveSession(active) { api, token -> api.businessDiscovery(token) }.also {
            checkDiscovery(it.orgId == active.session.orgId && it.revision >= observedRevision &&
                it.answerRevision >= observedAnswerRevision)
        }
    } catch (error: CoreHttpException) {
        if (error.status == 403 || error.status == 423) {
            hideAfterDeniedRead()
        }
        throw error
    }

    private suspend fun execute(attempt: Attempt, retry: Boolean) {
        try {
            if (attempt.receipt == null) {
                val receipt = sessions.requestForActiveSession(active) { api, token ->
                    api.writeBusinessDiscovery(token, attempt.key, attempt.baseline.revision, attempt.body)
                }
                checkDiscovery(receipt.orgId == active.session.orgId &&
                    receipt.revision >= attempt.baseline.revision &&
                    receipt.answerRevision >= attempt.baseline.answerRevision &&
                    (receipt.planRevision == null || receipt.planRevision <= receipt.revision))
                attempt.receipt = receipt
                update { copy(acknowledged = true) }
            }
            reconcile(attempt)
        } catch (failure: CoreHttpException) {
            if (retry || attempt.receipt != null) throw failure
            val notice = when (failure.status) {
                403 -> CoreDiscoveryNotice.SignInAgain
                409 -> CoreDiscoveryNotice.Changed
                423 -> CoreDiscoveryNotice.PolicyUnavailable
                400, 422, 428 -> CoreDiscoveryNotice.Rejected
                else -> throw failure
            }
            pending = null
            update { copy(pending = false, requiresRefresh = true, notice = notice) }
        }
    }

    private suspend fun reconcile(attempt: Attempt) {
        val receipt = checkNotNull(attempt.receipt)
        val snapshot = read()
        checkDiscovery(snapshot.revision >= receipt.revision && snapshot.answerRevision >= receipt.answerRevision)
        if (snapshot.revision == receipt.revision) {
            checkDiscovery(snapshot.answerRevision == receipt.answerRevision &&
                (snapshot.planStatus == CoreDiscoveryPlanStatus.Stale || snapshot.plan?.revision == receipt.planRevision))
        }
        pending = null
        publish(snapshot, receipt.invalidatedKeys)
    }

    private fun publish(snapshot: CoreDiscoverySnapshot, invalidatedKeys: List<CoreDiscoveryKey> = emptyList()) {
        update {
            observedRevision = snapshot.revision
            observedAnswerRevision = snapshot.answerRevision
            copy(snapshot = snapshot, pending = false, acknowledged = false, requiresRefresh = false,
                invalidatedKeys = invalidatedKeys)
        }
    }

    private fun update(transform: CoreConnectedDiscoveryState.() -> CoreConnectedDiscoveryState) {
        sessions.commitIfCurrent(active.epoch) {
            if (!retired) mutableState.value = current.transform()
        }
    }

    private suspend fun perform(operation: suspend () -> Unit) = coroutineScope {
        val running = currentCoroutineContext()[Job]
        var started = false
        sessions.commitIfCurrent(active.epoch) {
            if (!retired && !current.busy && active.session.role == CoreRole.owner) {
                job = running
                mutableState.value = current.copy(busy = true, notice = null)
                started = true
            }
        }
        if (!started) return@coroutineScope
        val epochWatcher = launch(start = CoroutineStart.UNDISPATCHED) {
            sessions.state.first { it !== active }
            running?.cancel(CancellationException("Core discovery session changed"))
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
                    (error as? CoreHttpException)?.status == 403 -> CoreDiscoveryNotice.SignInAgain
                    (error as? CoreHttpException)?.status == 423 -> CoreDiscoveryNotice.PolicyUnavailable
                    acknowledged -> CoreDiscoveryNotice.ConfirmationPending
                    pending -> CoreDiscoveryNotice.Unconfirmed
                    else -> CoreDiscoveryNotice.Unavailable
                }, requiresRefresh = !pending)
            }
        } finally {
            epochWatcher.cancel()
            update {
                job = null
                copy(busy = false)
            }
        }
    }
}

private fun CoreDiscoveryCommand.checkedFor(snapshot: CoreDiscoverySnapshot): CoreDiscoveryCommand? {
    return when (this) {
        is CoreDiscoveryCommand.Answer -> takeIf { key in snapshot.activeQuestions }?.let {
            val declared = value as? CoreDiscoveryValue.Declared
            if (declared == null) this
            else key.input(declared.value)?.let { copy(value = CoreDiscoveryValue.Declared(it)) }
        }
        is CoreDiscoveryCommand.Clear -> takeIf { key in snapshot.activeQuestions && key in snapshot.answers }
        CoreDiscoveryCommand.DeferRemaining ->
            takeIf { snapshot.activeQuestions.any { it !in snapshot.answers } }
        CoreDiscoveryCommand.RefreshPlan -> takeIf { snapshot.planStatus != CoreDiscoveryPlanStatus.Current }
        is CoreDiscoveryCommand.EditPlan -> {
            val plan = snapshot.plan?.takeIf { it.revision == planRevision } ?: return null
            when (val change = edit) {
                is CoreDiscoveryEdit.Title -> {
                    if (plan.actions.none { it.id == change.id }) return null
                    val title = change.value?.let {
                        if (it.any(Char::isISOControl)) return null
                        Normalizer.normalize(it.trim(), Normalizer.Form.NFC)
                    }
                    if (title != null && !title.isDiscoveryText(200)) return null
                    copy(edit = change.copy(value = title))
                }
                is CoreDiscoveryEdit.Defer -> takeIf { plan.actions.any { it.id == change.id } }
                is CoreDiscoveryEdit.Select -> takeIf {
                    change.id == null || plan.actions.any { it.id == change.id && !it.deferred && it.dependencies.isEmpty() }
                }
            }
        }
    }
}

private fun CoreDiscoveryKey.input(raw: String): String? {
    if (raw.length > 1000 || raw.any(Char::isISOControl)) return null
    val text = Normalizer.normalize(raw.trim(), Normalizer.Form.NFC)
    val value = when (this) {
        CoreDiscoveryKey.Country -> text.uppercase()
        CoreDiscoveryKey.DomainName -> text.lowercase()
        CoreDiscoveryKey.WebsiteUrl -> {
            val authorityEnd = text.indexOf('/', text.indexOf("://") + 3)
            if (authorityEnd == -1) text.lowercase()
            else text.substring(0, authorityEnd).lowercase() + text.substring(authorityEnd)
        }
        else -> text
    }
    return value.takeIf(::accepts)
}
