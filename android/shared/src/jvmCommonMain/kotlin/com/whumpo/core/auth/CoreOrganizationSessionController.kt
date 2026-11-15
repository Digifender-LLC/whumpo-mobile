package com.whumpo.core.auth

import com.whumpo.core.account.CoreAccountScope
import com.whumpo.core.api.CORE_MAX_VERSION
import com.whumpo.core.api.CoreApiClient
import com.whumpo.core.api.CoreHttpException
import com.whumpo.core.api.CoreMembershipPage
import com.whumpo.core.api.CoreNewTask
import com.whumpo.core.api.CoreProjectChoice
import com.whumpo.core.api.CoreProjectChoices
import com.whumpo.core.api.CoreTaskCommandAcceptance
import com.whumpo.core.api.CoreTaskCommandStatus
import com.whumpo.core.api.CoreWorkspaceChoices
import com.whumpo.core.api.CoreOrganizationEntitlements
import com.whumpo.core.api.CoreOrganizationProfile
import com.whumpo.core.api.CoreProtocolException
import com.whumpo.core.api.CoreSession
import com.whumpo.core.api.CoreSessionToken
import com.whumpo.core.api.CoreWebsiteHistoryItem
import com.whumpo.core.api.CoreWebsiteHistoryPage
import com.whumpo.core.api.CoreWebsiteOperation
import com.whumpo.core.api.CoreWorkPage
import com.whumpo.core.api.CoreWorkTask
import com.whumpo.core.api.coreBearer
import com.whumpo.core.api.coreDigest
import com.whumpo.core.api.coreIdentifier
import java.text.Normalizer
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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

    /** Returns the platform outcome without exposing the assertion or bearer to presentation code. */
    suspend fun signIn(passkeys: PasskeyAuthenticator): SignInResult {
        val transition = synchronized(lock) { beginTransition() }
        try {
            val challenge = api.authenticationOptions()
            if (!coreBearer.matches(challenge.challengeId) ||
                (challenge.options["challenge"] as? JsonPrimitive)?.isString != true) throw CoreProtocolException()
            return when (val result = passkeys.getAssertion(challenge.options.toString())) {
                is PasskeyResult.Assertion -> {
                    val assertion = Json.parseToJsonElement(result.json) as? JsonObject ?: throw CoreProtocolException()
                    currentCoroutineContext().ensureActive()
                    synchronized(lock) {
                        if (epoch != transition) throw CancellationException("Core session changed")
                    }
                    val replacement = api.authenticate(challenge.challengeId, assertion)
                    if (!coreBearer.matches(replacement.token)) throw CoreProtocolException()
                    verifyAndActivate(transition, replacement.token, expiresAt = replacement.expiresAt)
                    SignInResult.SignedIn
                }
                PasskeyResult.Cancelled -> SignInResult.Cancelled
                PasskeyResult.NoCredential -> SignInResult.NoCredential
                PasskeyResult.Unavailable -> SignInResult.Unavailable
            }
        } finally {
            abortTransition(transition)
        }
    }

    enum class SignInResult { SignedIn, Cancelled, NoCredential, Unavailable }

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

    suspend fun work(cursor: String? = null, limit: Int = 10): CoreWorkPage = request { current ->
        api.work(current.token, cursor, limit).also { page ->
            if (page.items.size > limit || page.items.map { it.id }.distinct().size != page.items.size ||
                page.nextCursor?.let { it == cursor || !coreDigest.matches(it) || page.items.isEmpty() } == true
            ) throw CoreProtocolException()
            page.items.forEach(::verifyTask)
        }
    }

    suspend fun task(taskId: String, projectId: String): CoreWorkTask = request { current ->
        api.task(current.token, taskId).also { task ->
            verifyTask(task)
            if (task.id != taskId || task.projectId != projectId) throw CoreProtocolException()
        }
    }

    /** Null means the project has no reserved website target. */
    suspend fun websiteReceipts(projectId: String, before: String? = null, limit: Int = 10): CoreWebsiteHistoryPage? =
        request { current ->
            val target = try {
                api.websiteTarget(current.token, projectId)
            } catch (error: CoreHttpException) {
                if (error.status == 404 && before == null) return@request null
                throw error
            }
            verifyOrganization(target.orgId, current)
            if (target.projectId != projectId || !coreIdentifier.matches(target.id) ||
                target.version !in 1..CORE_MAX_VERSION) throw CoreProtocolException()
            verifyCurrent(current)
            api.websiteHistory(current.token, target.id, before, limit).also { page ->
                val versions = page.items.map { it.receipt.targetVersion }
                if (page.items.size > limit ||
                    versions.zipWithNext().any { (first, second) -> first <= second } ||
                    (before != null && versions.any { it >= before.toLong() }) ||
                    (page.nextCursor != null && page.nextCursor != versions.lastOrNull()?.toString())
                ) throw CoreProtocolException()
                page.items.forEach { verifyReceipt(it, target.id, current) }
            }
        }

    suspend fun workspaceChoices(cursor: String? = null): CoreWorkspaceChoices = request { current ->
        api.workspaceChoices(current.token, cursor, 20).also { page ->
            verifyChoices(page.items.map { it.id }, cursor, page.nextCursor)
            if (page.items.any { it.name.isBlank() }) throw CoreProtocolException()
        }
    }

    suspend fun projectChoices(workspaceId: String, cursor: String? = null): CoreProjectChoices = request { current ->
        api.projectChoices(current.token, workspaceId, cursor, 20).also { page ->
            verifyChoices(page.items.map { it.id }, cursor, page.nextCursor)
            if (page.items.any { it.workspaceId != workspaceId || it.name.isBlank() }) throw CoreProtocolException()
        }
    }

    private fun verifyChoices(ids: List<String>, cursor: String?, nextCursor: String?) {
        if (ids.size > 20 || ids.distinct().size != ids.size || ids.any { !coreIdentifier.matches(it) } ||
            nextCursor?.let { it == cursor || ids.isEmpty() || !coreIdentifier.matches(it) } == true
        ) throw CoreProtocolException()
    }

    class TaskCreation internal constructor(
        internal val scope: CoreAccountScope,
        internal val epoch: Long,
        internal val key: String,
        internal val draft: CoreNewTask,
    )

    fun prepareTaskCreation(epoch: Long, project: CoreProjectChoice, title: String, description: String): TaskCreation {
        val normalizedTitle = Normalizer.normalize(title.trim(), Normalizer.Form.NFC)
        val normalizedDescription = Normalizer.normalize(description.trim(), Normalizer.Form.NFC).ifEmpty { null }
        require(coreIdentifier.matches(project.id) && normalizedTitle.length in 1..200 &&
            (normalizedDescription?.length ?: 0) <= 10_000)
        return synchronized(lock) {
            val current = requireCredential().state
            if (current.epoch != epoch) throw CancellationException("Core session changed")
            TaskCreation(current.scope, epoch, UUID.randomUUID().toString(), CoreNewTask(project.id, normalizedTitle, normalizedDescription))
        }
    }

    suspend fun createTask(submission: TaskCreation): CoreTaskCommandAcceptance = request { current ->
        if (current.state.epoch != submission.epoch || current.state.scope != submission.scope) {
            throw CancellationException("Core session changed")
        }
        api.createTask(current.token, submission.draft, submission.key).also { accepted ->
            if (!coreIdentifier.matches(accepted.runId) || !coreIdentifier.matches(accepted.effectId) ||
                when (accepted.status) {
                    CoreTaskCommandStatus.Queued -> accepted.approvalId != null
                    CoreTaskCommandStatus.WaitingApproval -> accepted.approvalId?.let(coreIdentifier::matches) != true
                }
            ) throw CoreProtocolException()
        }
    }

    private fun verifyReceipt(item: CoreWebsiteHistoryItem, targetId: String, current: Credential) {
        val receipt = item.receipt
        verifyOrganization(receipt.orgId, current)
        if (receipt.targetId != targetId || !coreIdentifier.matches(receipt.effectId) ||
            !coreDigest.matches(receipt.artifactDigest) || receipt.targetVersion !in 2..CORE_MAX_VERSION ||
            receipt.confirmedAt.isBlank()) throw CoreProtocolException()
        when (item.operation) {
            CoreWebsiteOperation.Publish -> {
                if (item.buildId?.let(coreIdentifier::matches) != true ||
                    receipt.artifactBytes?.let { it in 1..32_768 } != true || receipt.withdrawnEffectId != null ||
                    (receipt.pageManifestDigest == null) != (receipt.pageCount == null) ||
                    (receipt.pageManifestDigest != null &&
                        (!coreDigest.matches(receipt.pageManifestDigest) || receipt.pageCount?.let { it in 2..4 } != true))
                ) throw CoreProtocolException()
            }
            CoreWebsiteOperation.Withdraw -> {
                if (item.buildId != null || receipt.targetVersion < 3 ||
                    receipt.withdrawnEffectId?.let(coreIdentifier::matches) != true ||
                    receipt.artifactBytes != null || receipt.pageManifestDigest != null ||
                    receipt.pageCount != null) throw CoreProtocolException()
            }
        }
    }

    private fun verifyTask(task: CoreWorkTask) {
        if (!coreIdentifier.matches(task.id) || !coreIdentifier.matches(task.projectId) ||
            task.parentTaskId?.let { !coreIdentifier.matches(it) } == true ||
            task.title.isBlank() || task.version !in 1..CORE_MAX_VERSION) throw CoreProtocolException()
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
                replacement.expiresAt != source.expiresAt) throw CoreProtocolException()
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

    private suspend fun verifyCurrent(current: Credential) {
        currentCoroutineContext().ensureActive()
        synchronized(lock) {
            if (credential !== current) throw CancellationException("Core session changed")
            requireCredential()
        }
    }

    private suspend fun <T> request(operation: suspend (Credential) -> T): T {
        val current = synchronized(lock) { requireCredential() }
        val result = try {
            operation(current)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            synchronized(lock) {
                if (credential !== current) {
                    throw CancellationException("Core session changed")
                }
                if (credential === current && error is CoreHttpException && error.status == 401) clearLocked()
            }
            throw error
        }
        verifyCurrent(current)
        return result
    }
}
