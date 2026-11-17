package com.whumpo.core.auth

import com.whumpo.core.api.CoreTaskStatus
import com.whumpo.core.api.CoreWorkTask
import java.time.Instant
import java.time.format.DateTimeFormatterBuilder

/** An exact reviewed deadline and the minimal information for a calendar editor. */
@ConsistentCopyVisibility
data class CoreTaskCalendarReview private constructor(
    private val taskId: String,
    private val projectId: String,
    private val version: Long,
    private val status: CoreTaskStatus,
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
) {
    suspend fun verify(
        current: () -> Boolean,
        read: suspend (taskId: String, projectId: String) -> CoreWorkTask,
    ): Boolean {
        if (!current()) return false
        val fresh = from(read(taskId, projectId))
        return current() && fresh == this
    }

    companion object {
        private val utcTime = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}Z")

        private val canonicalTime = DateTimeFormatterBuilder().appendInstant(3).toFormatter()

        fun from(task: CoreWorkTask): CoreTaskCalendarReview? {
            if (task.archivedAt != null || task.status == CoreTaskStatus.Done || task.status == CoreTaskStatus.Canceled) return null
            val due = task.dueAt?.takeIf(utcTime::matches) ?: return null
            val instant = runCatching { Instant.parse(due) }.getOrNull() ?: return null
            if (canonicalTime.format(instant) != due) return null
            val start = instant.toEpochMilli()
            return CoreTaskCalendarReview(task.id, task.projectId, task.version, task.status, task.title, start, start + 1_800_000L)
        }
    }
}
