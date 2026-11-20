package com.whumpo.core.auth

import com.whumpo.core.api.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull

data class CoreWorkHistoryState(
    val busy: Boolean = false, val unavailable: Boolean = false,
    val page: CoreWorkHistoryPage? = null, val current: CoreWorkTask? = null,
)
data class CoreWorkSourceState(val busy: Boolean = false, val unavailable: Boolean = false, val result: CoreWorkSourceResult? = null)

/** Current task read fences history and source navigation in the captured actor/project scope. */
class CoreWorkHistoryController(
    private val sessions: CoreOrganizationSessionController,
    private val active: CoreOrganizationSessionController.State.Active,
    private val task: CoreWorkTask,
) {
    private val mutableState = MutableStateFlow(CoreWorkHistoryState())
    val state = mutableState.asStateFlow()
    private val sourceState = MutableStateFlow(CoreWorkSourceState())
    val source = sourceState.asStateFlow()
    private val operation = Mutex()
    @Volatile private var retired = false
    private var selectedOrigin: CoreRecordedTaskSource? = null
    private val sourceCursors = mutableSetOf<String>()

    suspend fun load(after: CoreWorkHistoryBookmark? = null) {
        if (!operation.tryLock()) return
        try {
            checkCurrent(); closeSource()
            update { it.copy(busy = true, unavailable = false) }
            val done = withTimeoutOrNull(15_000) {
                val page = sessions.requestForActiveSession(active) { api, token -> api.workHistory(token, task.id, after) }
                val current = currentTask()
                if (current.version < (page.changes.lastOrNull()?.version ?: after?.version ?: 0)) throw CoreProtocolException()
                update { CoreWorkHistoryState(page = page, current = current) }
                true
            } ?: false
            if (!done) update { CoreWorkHistoryState(unavailable = true) }
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) {
            currentCoroutineContext().ensureActive(); update { CoreWorkHistoryState(unavailable = true) }
        } finally { update { it.copy(busy = false) }; operation.unlock() }
    }

    suspend fun openSource(origin: CoreRecordedTaskSource, next: CoreWorkSourceBookmark? = null) {
        if (!operation.tryLock()) return
        try {
            checkCurrent()
            if (origin.taskId != task.id || state.value.page?.changes?.none { it.origin === origin } != false ||
                next != null && (selectedOrigin !== origin || sourceState.value.result?.next != next)) throw CoreProtocolException()
            if (next == null) { selectedOrigin = origin; sourceCursors.clear() }
            updateSource { CoreWorkSourceState(busy = true) }
            val done = withTimeoutOrNull(15_000) {
                currentTask()
                val page = sessions.requestForActiveSession(active) { api, token -> api.workSource(token, origin, next) }
                currentTask()
                if (page.next?.let { !sourceCursors.add(it.id) } == true) throw CoreProtocolException()
                if (selectedOrigin === origin) updateSource { CoreWorkSourceState(result = page) }
                true
            } ?: false
            if (!done) updateSource { CoreWorkSourceState(unavailable = true) }
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) {
            currentCoroutineContext().ensureActive(); updateSource { CoreWorkSourceState(unavailable = true) }
        } finally { operation.unlock() }
    }

    fun closeSource() { selectedOrigin = null; sourceCursors.clear(); sourceState.value = CoreWorkSourceState() }
    fun retire() { retired = true; closeSource(); mutableState.value = CoreWorkHistoryState() }

    private suspend fun currentTask(): CoreWorkTask {
        checkCurrent()
        return sessions.canonicalTaskForPartner(active, task.id).also {
            if (it.projectId != task.projectId) throw CoreProtocolException()
            checkCurrent()
        }
    }
    private fun checkCurrent() {
        if (retired || !sessions.commitIfCurrent(active.epoch) {}) throw CancellationException("Work history changed")
    }
    private fun update(change: (CoreWorkHistoryState) -> CoreWorkHistoryState) {
        sessions.commitIfCurrent(active.epoch) { if (!retired) mutableState.value = change(mutableState.value) }
    }
    private fun updateSource(change: (CoreWorkSourceState) -> CoreWorkSourceState) {
        sessions.commitIfCurrent(active.epoch) { if (!retired) sourceState.value = change(sourceState.value) }
    }
}
