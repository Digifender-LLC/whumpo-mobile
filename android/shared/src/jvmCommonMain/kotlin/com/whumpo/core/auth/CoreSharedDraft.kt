package com.whumpo.core.auth

import com.whumpo.core.account.validOfflineText
import com.whumpo.core.api.CoreRole
import java.net.URI

/** One inert share item. The original text is never opened as a URL. */
class CoreSharedDraftItem private constructor(val text: String, val isLink: Boolean) {
    companion object {
        private val linkScheme = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")
        private const val MAX_LENGTH = 4_000

        fun parse(value: CharSequence): CoreSharedDraftItem? {
            if (value.length > MAX_LENGTH) return null
            val text = value.toString().trim()
            if (text.isEmpty() || !text.validOfflineText(MAX_LENGTH) ||
                text.any { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' }) return null
            val scheme = linkScheme.find(text)
            val link = scheme != null && text.none(Char::isWhitespace)
            if (link) {
                val uri = runCatching { URI(text) }.getOrNull() ?: return null
                if (!uri.scheme.equals("https", ignoreCase = true) || uri.host == null ||
                    uri.rawUserInfo != null || uri.port != -1 && uri.port !in 1..65535) return null
            }
            return CoreSharedDraftItem(text, link)
        }
    }
}

/** Activity-owned revision for interactions that displace an offered destination. */
class CoreSharedDraftRoute {
    private var revision = 0L

    fun interrupt() {
        revision++
    }

    fun receive(item: CoreSharedDraftItem, readyEpoch: Long?, role: CoreRole?, now: Long): CoreSharedDraftReview? {
        if (readyEpoch == null || role == null || role == CoreRole.viewer) return null
        val captured = revision
        return CoreSharedDraftReview(item, readyEpoch, now) { captured == revision }
    }
}

/** A delivery from the Sharesheet can wait for its first focused resume in the same ready session. */
class CoreSharedDraftReview internal constructor(
    val item: CoreSharedDraftItem,
    val epoch: Long,
    private val receivedAt: Long,
    private val inCurrentRoute: () -> Boolean,
) {
    private var focused = false
    private var used = false
    private var cancelled = false

    fun observeForeground(resumed: Boolean, windowFocused: Boolean, now: Long): Boolean {
        val previousFocus = focused
        focused = resumed && windowFocused
        if ((previousFocus && !focused) || !inCurrentRoute() || !inTime(now)) cancel()
        return !cancelled
    }

    fun cancel() {
        cancelled = true
    }

    fun matches(currentEpoch: Long?): Boolean =
        !cancelled && !used && epoch == currentEpoch && inCurrentRoute()

    fun canReview(currentEpoch: Long?, now: Long): Boolean =
        focused && matches(currentEpoch) && inTime(now)

    fun accept(currentEpoch: Long?, now: Long): String? {
        if (!canReview(currentEpoch, now)) return null
        used = true
        return item.text
    }

    private fun inTime(now: Long): Boolean = now - receivedAt in 0 until REVIEW_MILLIS

    fun remainingMillis(now: Long): Long = if (inTime(now)) REVIEW_MILLIS - (now - receivedAt) else 0L

    companion object {
        private const val REVIEW_MILLIS = 60_000L
    }
}
