package com.whumpo.core.auth

import com.whumpo.core.account.CoreAccountScope
import com.whumpo.core.api.CoreApiClient
import com.whumpo.core.api.CoreHttpException
import com.whumpo.core.api.CoreMembershipPage
import com.whumpo.core.api.CoreOrganizationEntitlements
import com.whumpo.core.api.CoreOrganizationProfile
import com.whumpo.core.api.CoreProtocolException
import com.whumpo.core.api.CoreSession
import com.whumpo.core.api.CoreSessionToken
import com.whumpo.core.api.coreBearer
import com.whumpo.core.api.coreIdentifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory integration boundary. The caller owns secure token acquisition and the controller lifetime.
 * Cancel work for invalidated epochs and gate every deferred UI/cache commit with [commitIfCurrent].
 */
class CoreOrganizationSessionController(
    private val api: CoreApiClient,
    private val now: () -> Long = System::currentTimeMillis,
) {
    sealed interface State {
        data object SignedOut : State
        data object Transitioning : State
        data class Active(val epoch: Long, val session: CoreSession, val scope: CoreAccountScope) : State
    }

    private class Credential(val state: State.Active, val token: String)

    private val lock = Any()
    private var epoch = 0L
    private var credential: Credential? = null
    private val mutableState = MutableStateFlow<State>(State.SignedOut)
    val state: StateFlow<State> = mutableState.asStateFlow()

    suspend fun establish(bearer: String): State.Active {
        require(coreBearer.matches(bearer)) { "Invalid core bearer format" }
        val transition = synchronized(lock) { beginTransition() }
        return verifyAndActivate(transition, bearer)
    }

    fun clear() {
        synchronized(lock) { clearLocked() }
    }

    suspend fun switchOrganization(targetOrgId: String): State.Active {
        require(coreIdentifier.matches(targetOrgId))
        return replace(targetOrgId) { api.switch(it, targetOrgId) }
    }

    suspend fun rotate(): State.Active = replace(null) { api.rotate(it) }

    suspend fun organizations(cursor: String? = null, limit: Int = 25): CoreMembershipPage =
        request { api.organizations(it.token, cursor, limit) }

    suspend fun profile(): CoreOrganizationProfile = request {
        api.profile(it.token).also { profile -> verifyOrganization(profile.orgId, it) }
    }

    suspend fun rename(name: String, version: Long, key: String): CoreOrganizationProfile = request {
        api.rename(it.token, name, version, key).also { profile -> verifyOrganization(profile.orgId, it) }
    }

    suspend fun entitlements(): CoreOrganizationEntitlements = request {
        api.entitlements(it.token).also { coverage -> verifyOrganization(coverage.orgId, it) }
    }

    /** Run a short, non-suspending result commit only while the captured epoch is active. */
    fun commitIfCurrent(capturedEpoch: Long, commit: () -> Unit): Boolean = synchronized(lock) {
        val current = credential ?: return@synchronized false
        if (current.state.epoch != capturedEpoch || current.state.session.expiresAt <= now()) return@synchronized false
        commit()
        true
    }

    private fun beginTransition(): Long {
        credential = null
        mutableState.value = State.Transitioning
        return ++epoch
    }

    private fun clearLocked() {
        ++epoch
        credential = null
        mutableState.value = State.SignedOut
    }

    private fun requireCredential(): Credential {
        val current = checkNotNull(credential) { "No active core session" }
        if (current.state.session.expiresAt <= now()) {
            clearLocked()
            throw CoreHttpException(401)
        }
        return current
    }

    private suspend fun replace(
        targetOrgId: String?,
        exchange: suspend (String) -> CoreSessionToken,
    ): State.Active {
        val (current, transition) = synchronized(lock) { requireCredential() to beginTransition() }
        val source = current.state.session
        try {
            val replacement = exchange(current.token)
            if (!coreBearer.matches(replacement.token) || replacement.token == current.token ||
                (targetOrgId != null && replacement.expiresAt != source.expiresAt)) throw CoreProtocolException()
            return verifyAndActivate(transition, replacement.token, source.userId,
                targetOrgId ?: source.orgId, replacement.expiresAt)
        } finally {
            abortTransition(transition)
        }
    }

    private suspend fun verifyAndActivate(
        transition: Long,
        token: String,
        userId: String? = null,
        orgId: String? = null,
        expiresAt: Long? = null,
    ): State.Active {
        try {
            val session = api.session(token)
            if (!coreIdentifier.matches(session.sessionId) || !coreIdentifier.matches(session.userId) ||
                !coreIdentifier.matches(session.orgId) || session.expiresAt <= now() ||
                (userId != null && session.userId != userId) || (orgId != null && session.orgId != orgId) ||
                (expiresAt != null && session.expiresAt != expiresAt)) throw CoreProtocolException()
            currentCoroutineContext().ensureActive()
            return synchronized(lock) {
                if (epoch != transition) throw CancellationException("Core session changed")
                val scope = CoreAccountScope.of(api.origin, session.userId, session.orgId)
                State.Active(transition, session, scope).also {
                    credential = Credential(it, token)
                    mutableState.value = it
                }
            }
        } finally {
            abortTransition(transition)
        }
    }

    private fun abortTransition(transition: Long) {
        synchronized(lock) {
            if (epoch == transition && mutableState.value == State.Transitioning) clearLocked()
        }
    }

    private fun verifyOrganization(orgId: String, current: Credential) {
        if (orgId != current.state.session.orgId) throw CoreProtocolException()
    }

    private suspend fun <T> request(operation: suspend (Credential) -> T): T {
        val current = synchronized(lock) { requireCredential() }
        try {
            val result = operation(current)
            currentCoroutineContext().ensureActive()
            synchronized(lock) {
                if (credential !== current) {
                    throw CancellationException("Core session changed")
                }
                requireCredential()
            }
            return result
        } catch (error: CoreHttpException) {
            synchronized(lock) {
                if (credential !== current) throw CancellationException("Core session changed")
                if (error.status == 401) clearLocked()
            }
            throw error
        }
    }
}
