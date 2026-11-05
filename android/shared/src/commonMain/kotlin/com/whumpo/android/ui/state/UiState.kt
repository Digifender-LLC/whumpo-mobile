package com.whumpo.android.ui.state

/** What a remote-backed screen is showing. Screens map this onto the state components in this package. */
sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data object Empty : UiState<Nothing>

    /** [what] says what happened, [safe] says what is safe ("Nothing was changed."). */
    data class Error(val what: String, val safe: String) : UiState<Nothing>
    data object UpdateRequired : UiState<Nothing>
    data class Content<T>(val value: T) : UiState<T>
}
