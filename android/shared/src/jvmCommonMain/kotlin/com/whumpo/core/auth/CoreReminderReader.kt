package com.whumpo.core.auth

import com.whumpo.core.api.CoreProtocolException
import com.whumpo.core.api.CoreReminderNotice
import com.whumpo.core.api.CoreReminderState
import com.whumpo.core.api.CoreReminderTarget
import com.whumpo.core.api.CoreTaskReminder
import com.whumpo.core.api.CoreWorkTask
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class CoreReminderChangedException : IllegalStateException()

sealed class CoreReminderAttempt(
    internal val active: CoreOrganizationSessionController.State.Active,
    val target: CoreReminderTarget,
) {
    class Arm internal constructor(active: CoreOrganizationSessionController.State.Active, target: CoreReminderTarget) :
        CoreReminderAttempt(active, target) {
        internal val key = UUID.randomUUID().toString()
        internal val body = buildJsonObject {
            put("taskVersion", target.taskVersion)
            put("dueAt", target.dueAt)
        }.toString()
    }

    class Cancel internal constructor(active: CoreOrganizationSessionController.State.Active, internal val scheduled: CoreTaskReminder) :
        CoreReminderAttempt(active, scheduled.target)
}

class CoreReminderStatus internal constructor(
    internal val active: CoreOrganizationSessionController.State.Active,
    val target: CoreReminderTarget,
    val reminder: CoreTaskReminder?,
) {
    fun armAttempt(): CoreReminderAttempt.Arm? =
        if (reminder?.state == CoreReminderState.Armed || reminder?.state == CoreReminderState.Delivered) null
        else CoreReminderAttempt.Arm(active, target)

    fun cancelAttempt(): CoreReminderAttempt.Cancel? =
        reminder?.takeIf { it.state == CoreReminderState.Armed }?.let { CoreReminderAttempt.Cancel(active, it) }

    fun settles(attempt: CoreReminderAttempt): Boolean {
        if (active !== attempt.active || target != attempt.target) return false
        return when (attempt) {
            is CoreReminderAttempt.Arm -> reminder?.state in setOf(CoreReminderState.Armed, CoreReminderState.Delivered)
            is CoreReminderAttempt.Cancel -> reminder?.id == attempt.scheduled.id && reminder.state != CoreReminderState.Armed
        }
    }
}

class CoreReminderPage internal constructor(
    internal val active: CoreOrganizationSessionController.State.Active,
    private val continuation: String?,
    val items: List<CoreReminderNotice>,
) {
    val hasMore: Boolean get() = continuation != null && items.size < maximumNotices

    internal fun nextFor(active: CoreOrganizationSessionController.State.Active): String {
        if (this.active !== active) throw CancellationException("Reminder scope changed")
        return continuation?.takeIf { hasMore } ?: throw CoreProtocolException()
    }

    internal fun acknowledge(notice: CoreReminderNotice, read: String) =
        CoreReminderPage(active, continuation, items.map { if (it === notice) it.copy(acknowledgedAt = read) else it })

    internal fun requireItem(active: CoreOrganizationSessionController.State.Active, notice: CoreReminderNotice) {
        if (this.active !== active) throw CancellationException("Reminder scope changed")
        if (items.none { it === notice }) throw CoreProtocolException()
    }
}

private const val maximumNotices = 100

suspend fun CoreOrganizationSessionController.reminderStatus(
    active: CoreOrganizationSessionController.State.Active,
    target: CoreReminderTarget,
    whileCurrent: () -> Boolean,
): CoreReminderStatus {
    confirmReminderTask(active, target, whileCurrent)
    return readReminderStatus(active, target, whileCurrent)
}

suspend fun CoreOrganizationSessionController.submitReminder(
    active: CoreOrganizationSessionController.State.Active,
    attempt: CoreReminderAttempt,
    whileCurrent: () -> Boolean,
) {
    if (attempt.active !== active) throw CancellationException("Reminder scope changed")
    confirmReminderTask(active, attempt.target, whileCurrent)
    requestVisibleSession(active, whileCurrent) { client, token ->
        when (attempt) {
            is CoreReminderAttempt.Arm -> client.armTaskReminder(token, attempt.target, attempt.key, attempt.body)
            is CoreReminderAttempt.Cancel -> client.cancelTaskReminder(token, attempt.scheduled)
        }
    }
}

suspend fun CoreOrganizationSessionController.reminderPage(
    active: CoreOrganizationSessionController.State.Active,
    after: CoreReminderPage? = null,
    whileCurrent: () -> Boolean,
): CoreReminderPage {
    val before = after?.nextFor(active)
    val wire = requestVisibleSession(active, whileCurrent) { client, token -> client.reminderNotices(token, before) }
    if (before != null && before == wire.before) throw CoreProtocolException()
    val recent = after?.items.orEmpty()
    val ids = recent.mapTo(mutableSetOf()) { it.id }
    if (wire.items.any { it.id in ids }) throw CoreProtocolException()
    return CoreReminderPage(active, wire.before, (recent + wire.items).take(maximumNotices))
}

suspend fun CoreOrganizationSessionController.openReminder(
    active: CoreOrganizationSessionController.State.Active,
    page: CoreReminderPage,
    notice: CoreReminderNotice,
    whileCurrent: () -> Boolean,
): CoreWorkTask {
    page.requireItem(active, notice)
    val target = requestVisibleSession(active, whileCurrent) { client, token -> client.resolveReminderNotice(token, notice) }
    return confirmReminderTask(active, target, whileCurrent)
}

suspend fun CoreOrganizationSessionController.markReminderRead(
    active: CoreOrganizationSessionController.State.Active,
    page: CoreReminderPage,
    notice: CoreReminderNotice,
    whileCurrent: () -> Boolean,
): CoreReminderPage {
    page.requireItem(active, notice)
    val read = requestVisibleSession(active, whileCurrent) { client, token -> client.acknowledgeReminderNotice(token, notice) }
    return page.acknowledge(notice, read)
}

private suspend fun CoreOrganizationSessionController.readReminderStatus(
    active: CoreOrganizationSessionController.State.Active,
    target: CoreReminderTarget,
    whileCurrent: () -> Boolean,
): CoreReminderStatus = CoreReminderStatus(active, target,
    requestVisibleSession(active, whileCurrent) { client, token -> client.taskReminder(token, target) })

private suspend fun CoreOrganizationSessionController.confirmReminderTask(
    active: CoreOrganizationSessionController.State.Active,
    target: CoreReminderTarget,
    whileCurrent: () -> Boolean,
): CoreWorkTask {
    val task = requestVisibleSession(active, whileCurrent) { client, token -> client.linkedWorkTask(token, target.taskId) }
    if (CoreReminderTarget.fromTask(task) != target) throw CoreReminderChangedException()
    return task
}
