package com.whumpo.android.ui.common

import com.whumpo.android.ui.state.UiState
import com.whumpo.core.api.MobileApiException
import kotlin.coroutines.cancellation.CancellationException

/** What happened and what is safe, in the words the Error state shows. [what] names the thing ("your projects"). */
fun MobileApiException.userMessage(what: String): Pair<String, String> = when (this) {
    is MobileApiException.ProofRequired, is MobileApiException.IdentityChanged ->
        "You've been signed out." to "Sign in again to continue."
    is MobileApiException.Transport -> "Couldn't reach Whumpo to load $what." to "Nothing was changed."
    is MobileApiException.RateLimited ->
        "Too many requests." to (retryAfterSeconds?.let { "Try again in $it seconds. Nothing was changed." } ?: "Try again soon. Nothing was changed.")
    is MobileApiException.Unavailable -> "Whumpo is unavailable right now." to "Nothing was changed. Try again soon."
    else -> "Couldn't load $what." to "Nothing was changed."
}

/** Runs a read and maps it onto the shared state components. A stale client becomes UpdateRequired; nothing else is hidden. */
suspend fun <T> loadUi(what: String, isEmpty: (T) -> Boolean = { false }, block: suspend () -> T): UiState<T> =
    try {
        block().let { if (isEmpty(it)) UiState.Empty else UiState.Content(it) }
    } catch (e: CancellationException) {
        throw e
    } catch (_: MobileApiException.ClientUpdateRequired) {
        UiState.UpdateRequired
    } catch (e: MobileApiException) {
        e.userMessage(what).let { (title, safe) -> UiState.Error(title, safe) }
    }
