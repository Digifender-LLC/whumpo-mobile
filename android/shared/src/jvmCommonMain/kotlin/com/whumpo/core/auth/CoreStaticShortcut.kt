package com.whumpo.core.auth

import com.whumpo.core.api.CoreRole

enum class CoreStaticShortcut(private val action: String) {
    Today("com.whumpo.android.local.shortcut.TODAY"),
    Work("com.whumpo.android.local.shortcut.WORK"),
    NewTaskDraft("com.whumpo.android.local.shortcut.NEW_TASK_DRAFT"),
    PendingApprovals("com.whumpo.android.local.shortcut.PENDING_APPROVALS");

    fun allows(role: CoreRole?): Boolean = when (this) {
        Today, Work -> true
        NewTaskDraft -> role != null && role != CoreRole.viewer
        PendingApprovals -> role != null
    }

    companion object {
        fun fromAction(action: String?): CoreStaticShortcut? = entries.firstOrNull { it.action == action }
    }
}

/** A foreground navigation request, owned by the exact ready in-memory session at delivery. */
class CoreStaticShortcutRequest private constructor(
    val destination: CoreStaticShortcut,
    private val epoch: Long,
    private val startedMillis: Long,
    private val lifetime: Long,
) {
    private var used = false
    private var focused = false
    private var reachedFocus = false
    private var invalidated = false

    // Android may briefly resume and pause a previously paused activity during intent delivery.
    // Retain an already admitted request until its first focused resume, never past focus loss.
    fun observeForeground(resumed: Boolean, windowFocused: Boolean, elapsedMillis: Long): Boolean {
        focused = resumed && windowFocused
        if (!withinLifetime(elapsedMillis) || reachedFocus && !focused) invalidated = true
        if (focused) reachedFocus = true
        return !invalidated
    }

    fun withinLifetime(elapsedMillis: Long) = elapsedMillis - startedMillis in 0 until 5_000L

    suspend fun verify(
        currentEpoch: () -> Long?,
        elapsedMillis: () -> Long,
        currentLifetime: () -> Long,
        readyRead: suspend () -> Boolean,
    ): Boolean {
        fun current() = focused && !invalidated && currentEpoch() == epoch &&
            currentLifetime() == lifetime && withinLifetime(elapsedMillis())
        if (used || !current()) return false
        used = true
        return readyRead() && current()
    }

    companion object {
        fun receive(
            action: String?, readyEpoch: Long?, elapsedMillis: Long, lifetime: Long, role: CoreRole? = null,
        ): CoreStaticShortcutRequest? {
            val destination = CoreStaticShortcut.fromAction(action)?.takeIf { it.allows(role) } ?: return null
            return readyEpoch?.let { CoreStaticShortcutRequest(destination, it, elapsedMillis, lifetime) }
        }
    }
}

/** Allows one focus move after the canonical Pending decisions section actually mounts. */
class CorePendingApprovalsFocus(private val current: () -> Boolean) {
    private var used = false
    private var cancelled = false

    fun cancel() {
        cancelled = true
    }

    suspend fun reveal(scroll: suspend () -> Unit, focus: () -> Unit) {
        if (used || cancelled || !current()) return
        used = true
        scroll()
        if (!cancelled && current()) focus()
    }
}
