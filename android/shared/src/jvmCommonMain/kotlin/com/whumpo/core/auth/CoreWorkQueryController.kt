package com.whumpo.core.auth

import com.whumpo.core.api.CoreWorkFilters
import com.whumpo.core.api.CoreWorkDirectory
import com.whumpo.core.api.CoreWorkChoice
import com.whumpo.core.api.CoreRole
import com.whumpo.core.api.workQueryPath
import com.whumpo.core.api.CoreWorkQueryItem
import com.whumpo.core.api.CoreWorkQueryPage
import com.whumpo.core.api.CoreProtocolException
import com.whumpo.core.api.PartnerHttpException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

enum class CoreWorkQueryNotice { Unavailable, RestartRequired }
data class CoreWorkChoiceState(
    val items: List<CoreWorkChoice> = emptyList(), val nextCursor: String? = null,
    val busy: Boolean = false, val unavailable: Boolean = false,
)
data class CoreWorkQueryState(
    val filters: CoreWorkFilters = CoreWorkFilters(),
    val draft: CoreWorkFilters = filters,
    val choices: Map<CoreWorkDirectory, CoreWorkChoiceState> = emptyMap(),
    val items: List<CoreWorkQueryItem> = emptyList(),
    val page: CoreWorkQueryPage? = null,
    val busy: Boolean = false,
    val notice: CoreWorkQueryNotice? = null,
)

/** The composition owns calls/cancellation; this object starts no job and retains no coroutine scope. */
class CoreWorkQueryController(
    private val sessions: CoreOrganizationSessionController,
    private val active: CoreOrganizationSessionController.State.Active,
) {
    private val mutableState = MutableStateFlow(CoreWorkQueryState())
    val state = mutableState.asStateFlow()
    private val operation = Mutex()
    private val leaseOwner = CoreWorkQueryLeaseOwner()
    private val leases = active.workQueryLeases
    @Volatile private var retired = false
    private val choiceLock = Any()
    private val choiceGenerations = mutableMapOf<CoreWorkDirectory, Long>()
    private val choiceCursors = mutableMapOf<CoreWorkDirectory, MutableSet<String>>()

    fun editFilters(filters: CoreWorkFilters) {
        workQueryPath(filters)
        val changedWorkspace = filters.workspaceId != state.value.draft.workspaceId
        if (changedWorkspace) synchronized(choiceLock) {
            choiceGenerations[CoreWorkDirectory.Project] = (choiceGenerations[CoreWorkDirectory.Project] ?: 0) + 1
            choiceCursors.remove(CoreWorkDirectory.Project)
        }
        update { it.copy(draft = filters, choices = if (changedWorkspace) it.choices - CoreWorkDirectory.Project else it.choices) }
    }

    suspend fun loadChoices(kind: CoreWorkDirectory, more: Boolean = false) {
        checkCurrent()
        if (kind == CoreWorkDirectory.Assignee && active.session.role != CoreRole.owner) {
            update { it.copy(choices = it.choices - kind) }; return
        }
        val previous = state.value.choices[kind] ?: CoreWorkChoiceState()
        val cursor = if (more) previous.nextCursor ?: return else null
        val workspace = state.value.draft.workspaceId
        val generation = synchronized(choiceLock) {
            if (!more) choiceCursors.remove(kind)
            ((choiceGenerations[kind] ?: 0) + 1).also { choiceGenerations[kind] = it }
        }
        fun currentChoice() = synchronized(choiceLock) { choiceGenerations[kind] == generation } &&
            (kind != CoreWorkDirectory.Project || workspace == state.value.draft.workspaceId)
        update { it.copy(choices = it.choices + (kind to if (more) previous.copy(busy = true) else CoreWorkChoiceState(busy = true))) }
        try {
            val result = withTimeout(15_000) {
                sessions.requestForActiveSession(active) { api, token -> api.workDirectory(token, kind, cursor, workspace) }
            }
            currentCoroutineContext().ensureActive(); checkCurrent()
            if (!currentChoice()) return
            if (more && result.items.any { item -> previous.items.any { it.id == item.id } }) throw CoreProtocolException()
            val items = if (more) previous.items + result.items else result.items
            if (items.size > 10_000) throw CoreProtocolException()
            result.nextCursor?.let { next ->
                synchronized(choiceLock) {
                    if (!choiceCursors.getOrPut(kind) { mutableSetOf() }.add(next)) throw CoreProtocolException()
                }
            }
            update { it.copy(choices = it.choices + (kind to CoreWorkChoiceState(items, result.nextCursor))) }
        } catch (_: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            if (currentChoice()) update { it.copy(choices = it.choices + (kind to CoreWorkChoiceState(unavailable = true))) }
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            if (currentChoice()) update { it.copy(choices = it.choices + (kind to CoreWorkChoiceState(unavailable = true))) }
        }
    }

    suspend fun apply(filters: CoreWorkFilters) { editFilters(filters); load(filters, null) }
    suspend fun refresh() = apply(state.value.filters)
    suspend fun more() {
        val cursor = state.value.page?.nextCursor ?: return
        load(state.value.filters, cursor)
    }

    private suspend fun load(filters: CoreWorkFilters, cursor: String?) = operation.withLock {
        checkCurrent()
        val prior = state.value
        update { if (cursor == null) CoreWorkQueryState(filters = filters, choices = it.choices, busy = true) else it.copy(busy = true, notice = null) }
        try {
            val caller = currentCoroutineContext()[Job]
            val page = leases.mutex.withLock {
                withContext(NonCancellable) {
                    withTimeout(15_000) {
                        if (cursor == null) { leases.releaseAbandoned(); leases.release(leaseOwner) }
                        checkCurrent()
                        val value = sessions.requestForActiveSession(active) { api, token ->
                            api.workQuery(token, filters, cursor).also { result ->
                                if (cursor == null) result.nextCursor?.let { owned ->
                                    leases.own(leaseOwner) {
                                        // An old cleanup failure must not expire a replacement actor.
                                        if (sessions.state.value === active) sessions.requestForActiveSession(active) { currentApi, currentToken ->
                                            currentApi.releaseWorkQuery(currentToken, owned)
                                        } else api.releaseWorkQuery(token, owned)
                                    }
                                }
                            }
                        }
                        if (retired || caller?.isActive == false) {
                            leases.release(leaseOwner)
                            throw CancellationException("Work query was abandoned")
                        }
                        value
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            checkCurrent()
            if (cursor != null && (prior.page?.asOf != page.asOf || prior.page.counts != page.counts ||
                    page.items.any { item -> prior.items.any { it.task.id == item.task.id } })) throw CoreProtocolException()
            update { it.copy(items = if (cursor == null) page.items else prior.items + page.items, page = page, busy = false) }
        } catch (_: TimeoutCancellationException) {
            withContext(NonCancellable) { releaseOwnedSafely() }
            currentCoroutineContext().ensureActive(); checkCurrent()
            update { it.copy(items = emptyList(), page = null, busy = false, notice = CoreWorkQueryNotice.Unavailable) }
        } catch (error: CancellationException) {
            withContext(NonCancellable) { releaseOwnedSafely() }
            throw error
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            update { it.copy(items = emptyList(), page = null, busy = false,
                notice = if (error is PartnerHttpException && error.code == "WORK_QUERY_RESTART")
                    CoreWorkQueryNotice.RestartRequired else CoreWorkQueryNotice.Unavailable) }
        }
    }

    /** Clears renderable private data synchronously; the composition's finally block joins [close]. */
    fun retire() { retired = true; leaseOwner.abandoned = true; mutableState.value = CoreWorkQueryState() }

    suspend fun close() {
        retire()
        operation.withLock { withContext(NonCancellable) { releaseOwnedSafely() } }
    }

    private suspend fun releaseOwned() {
        leases.mutex.withLock { withTimeout(15_000) { leases.release(leaseOwner) } }
    }

    private suspend fun releaseOwnedSafely() {
        try { releaseOwned() } catch (_: Exception) {
            // Release cannot confer authority after scope/session loss. The server still owns its
            // bounded expiry/cap; no abandoned result is published and no new query is started here.
        }
    }

    private fun checkCurrent() {
        if (retired || !sessions.commitIfCurrent(active.epoch) {}) throw CancellationException("Work view changed")
    }
    private fun update(change: (CoreWorkQueryState) -> CoreWorkQueryState) {
        sessions.commitIfCurrent(active.epoch) { if (!retired) mutableState.value = change(mutableState.value) }
    }
}
