package com.whumpo.core.auth

enum class CoreStaticShortcut(private val action: String) {
    Today("com.whumpo.android.local.shortcut.TODAY"),
    Work("com.whumpo.android.local.shortcut.WORK");

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

    private fun withinLifetime(elapsedMillis: Long) = elapsedMillis - startedMillis in 0 until 5_000L

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
        fun receive(action: String?, readyEpoch: Long?, elapsedMillis: Long, lifetime: Long): CoreStaticShortcutRequest? {
            val destination = CoreStaticShortcut.fromAction(action) ?: return null
            return readyEpoch?.let { CoreStaticShortcutRequest(destination, it, elapsedMillis, lifetime) }
        }
    }
}
