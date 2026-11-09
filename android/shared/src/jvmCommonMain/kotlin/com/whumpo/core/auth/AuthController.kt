package com.whumpo.core.auth

import com.whumpo.core.account.AccountScope
import com.whumpo.core.account.AccountStateStore
import com.whumpo.core.api.HandoffExchangeRequest
import com.whumpo.core.api.HandoffStartRequest
import com.whumpo.core.api.MobileApiClient
import com.whumpo.core.api.MobileApiException
import com.whumpo.core.api.SessionTokenInfo
import com.whumpo.core.data.AccountRepository
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Result of a fresh-passkey reauthentication (the reauth endpoints). */
sealed interface ReauthResult {
    /** `freshUntil` moved; retry the sensitive action with the same request ID. */
    data object Fresh : ReauthResult

    /** The owner dismissed the system sheet. */
    data object Cancelled : ReauthResult
    data object UpdateRequired : ReauthResult
    data class Failed(val problem: AuthProblem) : ReauthResult
}

/**
 * The sign-in state machine: passkey sign-in, the browser hand-off (PKCE S256) and its App Link return, the
 * onboarding gate, fresh-passkey reauthentication and sign-out. No Android types: Credential Manager and the browser
 * are the [PasskeyAuthenticator] and [BrowserLauncher] interfaces, so JVM tests drive it with fakes and a `MockEngine`.
 *
 * Nothing retries by itself. A failed step shows a problem on the sign-in screen and waits for the owner.
 * shortcut: a pending hand-off lives in memory only. If the process dies while the browser is open, the owner starts
 * the sign-in again; persisting it needs a signed-out sealed slot, which the account store does not have.
 */
class AuthController(
    private val api: MobileApiClient,
    private val sessions: SessionManager,
    private val passkeys: PasskeyAuthenticator,
    private val browser: BrowserLauncher,
    private val origin: String,
    private val scope: CoroutineScope,
) : AuthActions {
    private enum class SetupKind { Account, Onboarding }

    private sealed interface Step {
        data object Idle : Step
        data object Busy : Step
        data class Problem(val problem: AuthProblem) : Step
        data class Waiting(val exchanging: Boolean = false) : Step
        // Session-bearing steps are not data classes: their token must not appear in toString().
        class Setup(
            val kind: SetupKind,
            val busy: Boolean = false,
            val problem: AuthProblem? = null,
            val token: String? = null,
            val path: String? = null,
        ) : Step
        class Ready(val token: String) : Step
        class Update(val token: String? = null) : Step
    }

    private class Handoff(val id: String, val state: String, val verifier: String, val url: String)

    private val step = MutableStateFlow<Step>(Step.Idle)
    private var job: Job? = null
    private var handoff: Handoff? = null
    private var signingOut = false

    // Unconfined: the gate follows the account store on the thread that changed it, so it never lags a sign-out.
    val state: StateFlow<AuthState> = combine(sessions.state, step) { session, current ->
        val token = if (session is SessionManager.State.SignedIn) sessions.bearerToken() else null
        val signedOut = AuthState.SignedOut(
            if ((session as? SessionManager.State.SignedOut)?.ended == true) AuthProblem.SessionEnded else null,
        )
        when {
            current is Step.Update && (current.token == null || current.token == token) -> AuthState.UpdateRequired
            session is SessionManager.State.Restoring -> AuthState.Restoring
            session is SessionManager.State.SignedIn -> when {
                current is Step.Ready && current.token == token -> AuthState.Ready
                current is Step.Setup && current.kind == SetupKind.Onboarding && current.token == token ->
                    AuthState.FinishSetup(current.busy, current.problem, canCheck = true, canOpen = current.path != null)
                else -> AuthState.FinishSetup(
                    busy = current is Step.Setup && current.token == null && current.busy,
                    canCheck = true,
                    canOpen = current is Step.Setup && current.kind == SetupKind.Account,
                )
            }
            else -> when (current) {
                is Step.Idle, is Step.Ready, is Step.Update -> signedOut
                is Step.Busy -> AuthState.SignedOut(busy = true)
                is Step.Problem -> AuthState.SignedOut(current.problem)
                is Step.Waiting -> AuthState.WaitingForBrowser(current.exchanging)
                is Step.Setup -> if (current.kind == SetupKind.Account) {
                    AuthState.FinishSetup(current.busy, current.problem, canOpen = true)
                } else {
                    signedOut
                }
            }
        }
    }.stateIn(scope + Dispatchers.Unconfined, SharingStarted.Eagerly, AuthState.Restoring)

    /** Loads the stored session and checks its welcome gate before allowing the shell. Call once at start-up. */
    fun start() = launchStep {
        step.value = Step.Setup(SetupKind.Onboarding, busy = true)
        sessions.restore()
        checkOnboarding()
    }

    // ---- Passkey sign-in -----------------------------------------------------------------------------------------

    override fun signInWithPasskey() = launchStep {
        step.value = Step.Busy
        val options = api.loginOptions()
        currentCoroutineContext().ensureActive()
        when (val result = passkeys.getAssertion(options.options.toString())) {
            PasskeyResult.Cancelled -> step.value = Step.Idle
            PasskeyResult.NoCredential -> step.value = Step.Problem(AuthProblem.NoPasskey)
            PasskeyResult.Unavailable -> step.value = Step.Problem(AuthProblem.PasskeyUnavailable)
            is PasskeyResult.Assertion ->
                if (tooLarge(result.json)) {
                    step.value = Step.Problem(AuthProblem.CredentialTooLarge)
                } else {
                    finishSignIn(api.loginVerify(options.challengeId, parse(result.json)).session)
                }
        }
    }

    /** Stores a verified session, then holds the shell closed until its welcome state is known. */
    private suspend fun finishSignIn(info: SessionTokenInfo) {
        currentCoroutineContext().ensureActive()
        sessions.begin(info.token)
        try {
            val label = api.getSession().account.label
            currentCoroutineContext().ensureActive()
            if (sessions.bearerToken() != info.token) return
            // Hold the gate shut while the account is stored and the welcome state is read.
            step.value = Step.Setup(SetupKind.Onboarding, busy = true, token = info.token)
            sessions.establish(label, info.expiresAt, info.freshUntil, expectedToken = info.token)
        } catch (e: Throwable) {
            if (sessions.bearerToken() == info.token) sessions.discardPending()
            throw e
        }
        checkOnboarding()
    }

    /** Fail closed, with an explicit retry. A result belongs only to the session that requested it. */
    private suspend fun checkOnboarding() {
        currentCoroutineContext().ensureActive()
        val token = sessions.bearerToken()
        if (token == null) {
            step.value = Step.Idle
            return
        }
        step.value = Step.Setup(SetupKind.Onboarding, busy = true, token = token)
        val next = try {
            val onboarding = api.bootstrap().onboarding
            if (onboarding.complete) {
                Step.Ready(token)
            } else {
                Step.Setup(SetupKind.Onboarding, token = token, path = onboarding.browserPath?.takeIf(::isSafePath))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: MobileApiException) {
            if (e is MobileApiException.ClientUpdateRequired) {
                Step.Update(token)
            } else {
                Step.Setup(SetupKind.Onboarding, problem = problemOf(e), token = token)
            }
        } catch (_: Exception) {
            Step.Setup(SetupKind.Onboarding, problem = AuthProblem.Generic, token = token)
        }
        currentCoroutineContext().ensureActive()
        if (sessions.bearerToken() == token) step.value = next
    }

    // ---- Browser hand-off ----------------------------------------------------------------------------------------

    override fun signInWithBrowser() = launchStep {
        step.value = Step.Busy
        startHandoff("signin")
    }

    override fun startInvite() = launchStep {
        step.value = Step.Busy
        startHandoff("signup")
    }

    private suspend fun startHandoff(purpose: String) {
        val verifier = Pkce.newVerifier()
        val state = Pkce.newState()
        val response = api.handoffStart(HandoffStartRequest(purpose, state, Pkce.challenge(verifier), RETURN_URI))
        currentCoroutineContext().ensureActive()
        val url = browserUrl(response.browserPath)
        if (url == null) {
            step.value = Step.Problem(AuthProblem.HandoffFailed)
            return
        }
        handoff = Handoff(response.handoffId, state, verifier, url)
        step.value = if (browser.open(url)) {
            Step.Waiting()
        } else {
            handoff = null
            Step.Problem(AuthProblem.NoBrowser)
        }
    }

    override fun cancelHandoff() {
        job?.cancel()
        handoff = null
        step.value = Step.Idle
    }

    /**
     * The verified App Link `https://whumpo.com/app/return?code=...&state=...` arrived. Anything that is not that link
     * is ignored. The hand-off is single use: it is dropped before the exchange, so a replayed link does nothing.
     *
     * Strict (WH-083): exactly `code` (64 lowercase hex) and `state` (equal to what was sent), each once, nothing else.
     * A link whose `state` is not the pending one is ignored and leaves the hand-off waiting; a matching `state` with a bad `code` or extra parameters still spends it.
     */
    fun onReturn(uri: String) {
        val params = returnParams(uri) ?: return
        // A link for another hand-off (a stray or hostile explicit intent) is not ours: ignore it and keep the pending one.
        if (params["state"] != handoff?.state) return
        launchStep {
            val pending = handoff ?: return@launchStep
            handoff = null
            step.value = Step.Waiting(exchanging = true)
            val code = params["code"]
            if (code == null || !HEX64.matches(code) || params["state"] != pending.state || params.size != 2) {
                step.value = Step.Problem(AuthProblem.HandoffFailed)
                return@launchStep
            }
            try {
                finishSignIn(api.handoffExchange(HandoffExchangeRequest(pending.id, code, pending.state, pending.verifier)).session)
            } catch (e: MobileApiException) {
                currentCoroutineContext().ensureActive()
                // The code is spent whatever went wrong, so the only way forward is a new hand-off.
                step.value = if (e is MobileApiException.ClientUpdateRequired) Step.Update() else Step.Problem(AuthProblem.HandoffFailed)
            }
        }
    }

    // ---- Finishing setup in the browser ----------------------------------------------------------------------------

    override fun openSetup() {
        when (val current = step.value) {
            is Step.Waiting -> handoff?.let { browser.open(it.url) }
            is Step.Setup -> if (current.kind == SetupKind.Account) {
                launchStep {
                    step.value = Step.Setup(SetupKind.Account, busy = true)
                    // The account exists after identity_incomplete; the browser resumes enrollment or recovery.
                    startHandoff("signin")
                }
            } else {
                launchStep {
                    val token = sessions.bearerToken() ?: return@launchStep
                    val path = current.path?.takeIf { current.token == token }
                    val url = path?.let(::browserUrl)
                    // Without a path the browser is never tried, so a failed bootstrap keeps its own problem.
                    val kept = current.problem?.takeIf { current.token == token }
                    step.value = Step.Setup(
                        SetupKind.Onboarding,
                        problem = when {
                            url == null -> kept ?: AuthProblem.NoBrowser
                            browser.open(url) -> null
                            else -> AuthProblem.NoBrowser
                        },
                        token = token,
                        path = path,
                    )
                }
            }
            else -> Unit
        }
    }

    override fun checkSetupAgain() {
        if ((step.value as? Step.Setup)?.kind == SetupKind.Account) return openSetup()
        launchStep { checkOnboarding() }
    }

    // ---- Reauthentication and sign-out -----------------------------------------------------------------------------

    /** Whether the last passkey proof is still inside the ten-minute window that sensitive controls need. */
    val isFresh: Boolean get() = sessions.isFresh

    /** Runs a new passkey assertion (the reauth endpoints). On [ReauthResult.Fresh], retry the action with the same request ID. */
    suspend fun reauthenticate(): ReauthResult =
        try {
            val options = api.reauthOptions()
            when (val result = passkeys.getAssertion(options.options.toString())) {
                PasskeyResult.Cancelled -> ReauthResult.Cancelled
                PasskeyResult.NoCredential -> ReauthResult.Failed(AuthProblem.NoPasskey)
                PasskeyResult.Unavailable -> ReauthResult.Failed(AuthProblem.PasskeyUnavailable)
                is PasskeyResult.Assertion -> if (tooLarge(result.json)) {
                    ReauthResult.Failed(AuthProblem.CredentialTooLarge)
                } else {
                    sessions.updateFresh(api.reauthVerify(options.challengeId, parse(result.json)).session.freshUntil)
                    ReauthResult.Fresh
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: MobileApiException) {
            if (e is MobileApiException.ClientUpdateRequired) ReauthResult.UpdateRequired else ReauthResult.Failed(problemOf(e))
        } catch (_: Exception) {
            ReauthResult.Failed(AuthProblem.Generic)
        }

    override fun signOut() {
        scope.launch { signOutNow() }
    }

    /** Tells the server (best effort, even if it answers 401) and always erases local state. */
    suspend fun signOutNow() {
        if (signingOut) return
        signingOut = true
        job?.cancel()
        handoff = null
        try {
            step.value = Step.Setup(SetupKind.Onboarding, busy = true, token = sessions.bearerToken())
            withTimeoutOrNull(LOGOUT_TIMEOUT_MS) {
                try {
                    api.logout()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // The app clears local state even when the server cannot be reached (contract).
                }
            }
            sessions.clear(ended = false)
            step.value = Step.Idle
        } finally {
            signingOut = false
        }
    }

    override fun openHelp() {
        browser.open("$origin/contact")
    }

    override fun openStore() {
        browser.open(PLAY_URL)
    }

    // ---- Helpers ---------------------------------------------------------------------------------------------------

    /** One sign-in step at a time: a second tap while one runs is ignored. */
    private fun launchStep(block: suspend () -> Unit) {
        if (signingOut || job?.isActive == true) return
        job = scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: MobileApiException) {
                currentCoroutineContext().ensureActive()
                step.value = when (e) {
                    is MobileApiException.ClientUpdateRequired -> Step.Update()
                    is MobileApiException.IdentityIncomplete -> Step.Setup(SetupKind.Account)
                    else -> Step.Problem(problemOf(e))
                }
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                step.value = Step.Problem(AuthProblem.Generic)
            }
        }
    }

    private fun problemOf(e: MobileApiException): AuthProblem = when (e) {
        is MobileApiException.Transport -> AuthProblem.Network
        is MobileApiException.RateLimited -> AuthProblem.RateLimited
        is MobileApiException.Unavailable -> AuthProblem.ServiceUnavailable
        is MobileApiException.ProofRequired -> AuthProblem.Rejected
        else -> AuthProblem.Generic
    }

    private fun tooLarge(json: String) = json.toByteArray(Charsets.UTF_8).size > MAX_CREDENTIAL_BYTES

    private fun parse(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    /** The app composes the URL from its constant origin; the server only supplies a path. */
    private fun browserUrl(path: String): String? = if (isSafePath(path)) origin + path else null

    private fun isSafePath(path: String) =
        path.startsWith("/") && !path.startsWith("//") && !path.contains('\\') && path.none { it.isWhitespace() || it.isISOControl() }

    private fun returnParams(uri: String): Map<String, String>? {
        if (!uri.startsWith(RETURN_URI) || '#' in uri) return null
        val rest = uri.removePrefix(RETURN_URI)
        if (rest.isNotEmpty() && !rest.startsWith("?")) return null
        val pairs = rest.removePrefix("?").split('&').map { it.split('=', limit = 2) }
        // A malformed or repeated pair is ambiguous; refuse the whole link.
        if (pairs.any { it.size != 2 }) return null
        return pairs.associate { it[0] to it[1] }.takeIf { it.size == pairs.size }
    }

    private companion object {
        const val RETURN_URI = "https://whumpo.com/app/return"
        const val PLAY_URL = "https://play.google.com/store/apps/details?id=com.whumpo.android"
        const val LOGOUT_TIMEOUT_MS = 5_000L
        const val MAX_CREDENTIAL_BYTES = 8 * 1024
        val HEX64 = Regex("[0-9a-f]{64}")
    }
}

/** [AccountRepository] for the shell: Settings sign-out runs the full sign-out (server call, then local erase). */
class SessionAccountRepository(
    store: AccountStateStore,
    private val auth: AuthController,
) : AccountRepository {
    override val activeAccount: Flow<AccountScope?> = store.activeAccount

    override suspend fun signOut() = auth.signOutNow()
}
