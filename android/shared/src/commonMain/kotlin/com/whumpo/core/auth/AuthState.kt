package com.whumpo.core.auth

/** Why sign-in did not finish. The sign-in screens turn each one into plain words and a safe next step. */
enum class AuthProblem {
    /** This phone has no Whumpo passkey. */
    NoPasskey,

    /** Credential Manager could not run (no provider, unsupported, interrupted). */
    PasskeyUnavailable,
    Network,
    ServiceUnavailable,
    RateLimited,

    /** The server refused the assertion (`proof_required`). */
    Rejected,

    /** The session expired or was revoked. */
    SessionEnded,

    /** The browser hand-off returned something we do not trust, or the exchange failed. */
    HandoffFailed,
    NoBrowser,

    /** Credential Manager's response is over the contract's 8 KiB limit; nothing was sent. */
    CredentialTooLarge,
    Generic,
}

/** What the sign-in gate shows. [Ready] means the shell may be shown. */
sealed interface AuthState {
    /** Reading the stored session. */
    data object Restoring : AuthState

    /** Signed out. [busy] is true while a passkey sign-in or a hand-off start is running. */
    data class SignedOut(val problem: AuthProblem? = null, val busy: Boolean = false) : AuthState

    /** The system browser is open for a hand-off; [exchanging] once the one-time code came back. */
    data class WaitingForBrowser(val exchanging: Boolean = false) : AuthState

    /**
     * The account (or its first-run welcome) must be finished in the browser. [canCheck] is true once signed in
     * (the welcome step), when the app can ask the server whether it is done.
     */
    data class FinishSetup(val busy: Boolean = false, val problem: AuthProblem? = null, val canCheck: Boolean = false) : AuthState

    /** `client_update_required`: nothing else is allowed. */
    data object UpdateRequired : AuthState

    data object Ready : AuthState
}

/** Everything the sign-in screens can ask for. Implemented by the controller; faked in screen tests. */
interface AuthActions {
    fun signInWithPasskey()
    fun signInWithBrowser()
    fun startInvite()
    fun cancelHandoff()
    fun openSetup()
    fun checkSetupAgain()
    fun signOut()
    fun openHelp()
    fun openStore()
}
