package com.whumpo.core.data

/** A remote-backed value, or the honest reason it cannot be shown yet. */
sealed interface Availability<out T> {
    data class Ready<T>(val value: T) : Availability<T>

    data class Unavailable(val reason: UnavailableReason) : Availability<Nothing>
}

enum class UnavailableReason {
    /** No owner is signed in on this device. Native sign-in arrives with WH-083. */
    SIGN_IN_REQUIRED,

    /** Signed in, but the `/api/mobile/v1` routes are not implemented yet (WH-083/084). */
    SERVICE_NOT_CONNECTED,
}
