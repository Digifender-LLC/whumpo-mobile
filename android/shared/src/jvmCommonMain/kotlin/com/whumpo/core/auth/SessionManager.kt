package com.whumpo.core.auth

import com.whumpo.core.account.AccountScope
import com.whumpo.core.account.AccountStateStore
import com.whumpo.core.account.StateKind
import com.whumpo.core.account.StateSlot
import com.whumpo.core.api.SessionProvider
import java.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The native bearer session: the [SessionProvider] for `MobileApiClient` and its sealed storage.
 *
 * The token is kept in memory and in the Keystore-sealed [AccountStateStore] (`SecretBox`, no-backup storage).
 * "Signed in" means an account is active in the store. A local expiry, a server rejection or a sign-out erases the whole
 * store and destroys its key, which is also what the contract asks for on the first `401`.
 *
 * shortcut: the contract exposes no account id, so the store scope is the masked address label from `GET /session`.
 * Two accounts with the same mask cannot see each other's data because [establish] always starts from an erased store.
 */
class SessionManager(
    private val store: AccountStateStore,
    private val io: CoroutineDispatcher,
    private val clock: () -> Instant = Instant::now,
) : SessionProvider {
    sealed interface State {
        data object Restoring : State

        /** [ended] is true when the session was ended by expiry or by the server, not by the owner. */
        data class SignedOut(val ended: Boolean) : State
        data object SignedIn : State
    }

    @Serializable
    private class Stored(val token: String, val expiresAt: String, val freshUntil: String?, val label: String) {
        override fun toString() = "Stored(<redacted>)"
    }

    private val restored = MutableStateFlow(false)
    private val ended = MutableStateFlow(false)

    @Volatile private var token: String? = null

    @Volatile private var expiresAt: Instant? = null

    @Volatile private var freshUntilAt: Instant? = null

    @Volatile private var label: String? = null

    val state: Flow<State> = combine(restored, ended, store.activeAccount) { isRestored, wasEnded, account ->
        when {
            !isRestored -> State.Restoring
            account != null -> State.SignedIn
            else -> State.SignedOut(wasEnded)
        }
    }

    /** Whether the last passkey proof is inside the ten-minute window that sensitive controls need. */
    val isFresh: Boolean get() = freshUntilAt?.let { clock().isBefore(it) } == true

    /** Loads the stored session. Call once at start-up; unreadable or expired state is erased. */
    suspend fun restore() = withContext(io) {
        try {
            store.restore()
            val account = store.activeAccount.value
            val stored = account?.let { store.read(it, SLOT) }?.let { Json.decodeFromString(Stored.serializer(), it) }
            stored?.let(::adopt)
            // An account with no stored session (a crash mid sign-in) is not a sign-in.
            if (account != null && stored == null) {
                ended.value = true
                store.signOut()
            }
        } catch (_: Exception) {
            store.signOut()
        }
        if (expiresAt?.let { !clock().isBefore(it) } == true) clear(ended = true)
        restored.value = true
    }

    /** Holds [newToken] in memory so `GET /session` can run. Nothing is stored until [establish]. */
    fun begin(newToken: String) {
        token = newToken
    }

    /** Drops a token from [begin] when the sign-in could not be completed. */
    fun discardPending() {
        if (store.activeAccount.value == null) token = null
    }

    /** Stores the session begun with [begin] for the account called [accountLabel]. */
    suspend fun establish(accountLabel: String, expiresAt: String, freshUntil: String?) = withContext(io) {
        val current = checkNotNull(token) { "begin() first" }
        val expires = Instant.parse(expiresAt)
        val fresh = freshUntil?.let(Instant::parse)
        val scope = AccountScope.of(accountLabel)
        store.signOut()
        store.activate(scope)
        store.write(scope, SLOT, Json.encodeToString(Stored.serializer(), Stored(current, expiresAt, freshUntil, accountLabel)))
        token = current
        this@SessionManager.expiresAt = expires
        freshUntilAt = fresh
        label = accountLabel
        ended.value = false
    }

    /** A fresh passkey proof moved `freshUntil`; it never extends `expiresAt`. */
    suspend fun updateFresh(freshUntil: String) = withContext(io) {
        val newFresh = Instant.parse(freshUntil)
        val account = store.activeAccount.value
        val t = token
        val e = expiresAt
        val l = label
        if (account != null && t != null && e != null && l != null) {
            store.write(account, SLOT, Json.encodeToString(Stored.serializer(), Stored(t, e.toString(), freshUntil, l)))
            freshUntilAt = newFresh
        }
    }

    /** Erases the session and every account-scoped record on this device. */
    suspend fun clear(ended: Boolean = false) = withContext(io) {
        token = null
        expiresAt = null
        freshUntilAt = null
        label = null
        this@SessionManager.ended.value = ended
        store.signOut()
    }

    override suspend fun bearerToken(): String? {
        val t = token
        val exp = expiresAt
        val expired = exp != null && !clock().isBefore(exp)
        if (expired) {
            clear(ended = true)
            return null
        }
        return t
    }

    override suspend fun onSessionRejected() = clear(ended = true)

    private fun adopt(stored: Stored) {
        token = stored.token
        expiresAt = Instant.parse(stored.expiresAt)
        freshUntilAt = stored.freshUntil?.let(Instant::parse)
        label = stored.label
    }

    private companion object {
        val SLOT = StateSlot(StateKind.SESSION, "native")
    }
}
