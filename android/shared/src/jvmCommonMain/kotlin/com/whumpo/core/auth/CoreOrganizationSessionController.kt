package com.whumpo.core.auth

import com.whumpo.core.account.CoreAccountScope
import com.whumpo.core.api.CORE_MAX_VERSION
import com.whumpo.core.api.CoreApiClient
import com.whumpo.core.api.CoreEffectState
import com.whumpo.core.api.CoreHttpException
import com.whumpo.core.api.CoreMembershipPage
import com.whumpo.core.api.CoreNewTask
import com.whumpo.core.api.CoreProjectChoice
import com.whumpo.core.api.CoreProjectChoices
import com.whumpo.core.api.CoreTaskCommandAcceptance
import com.whumpo.core.api.CoreTaskCommandStatus
import com.whumpo.core.api.CoreTaskOutcome
import com.whumpo.core.api.CoreWorkspaceChoices
import com.whumpo.core.api.CoreOrganizationEntitlements
import com.whumpo.core.api.CoreOrganizationProfile
import com.whumpo.core.api.CoreProtocolException
import com.whumpo.core.api.CoreRole
import com.whumpo.core.api.CoreSession
import com.whumpo.core.api.CoreSessionToken
import com.whumpo.core.api.CORE_TODAY_SECTION_LIMIT
import com.whumpo.core.api.CoreTaskStatus
import com.whumpo.core.api.CoreTodayBriefing
import com.whumpo.core.api.CoreTodaySiteActivity
import com.whumpo.core.api.CoreTodaySiteKind
import com.whumpo.core.api.CoreTodayTaskSource
import com.whumpo.core.api.CoreWebsiteHistoryItem
import com.whumpo.core.api.CoreWebsiteHistoryPage
import com.whumpo.core.api.CoreWebsiteOperation
import com.whumpo.core.api.CoreWorkPage
import com.whumpo.core.api.CoreWorkTask
import com.whumpo.core.api.coreBearer
import com.whumpo.core.api.coreDigest
import com.whumpo.core.api.coreIdentifier
import com.whumpo.core.api.coreTimestamp
import com.whumpo.core.api.CoreApplicationRecoveryState
import com.whumpo.core.api.CoreRecoveryPoint
import com.whumpo.core.api.CoreRestoreResult
import com.whumpo.core.api.recoveryId
import com.whumpo.core.api.verifyRecoveryPoint
import com.whumpo.core.api.verifyRestore
import java.text.Normalizer
import java.time.Instant
import java.time.format.DateTimeParseException
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

    internal suspend fun establishAccountSession(
        scoped: CoreSessionToken,
        accountId: String,
        organizationId: String,
        validGrant: () -> Boolean,
    ): State.Active {
        require(coreBearer.matches(scoped.token))
        if (!validGrant()) throw CancellationException("Account grant changed")
        val transition = synchronized(lock) { beginTransition() }
        return verifyAndActivate(transition, scoped.token, accountId, organizationId, scoped.expiresAt, validGrant)
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

    /** Reads the current task by identity, allowing an authorized move within the organization. */
    suspend fun canonicalTask(taskId: String): CoreWorkTask = request { current ->
        api.task(current.token, taskId).also { task ->
            verifyTask(task)
            if (task.id != taskId) throw CoreProtocolException()
        }
    }

    /** Null means the project has no reserved website target. */
    suspend fun websiteReceipts(projectId: String, before: String? = null, limit: Int = 10): CoreWebsiteHistoryPage? =
        request { current -> readWebsiteReceipts(current, projectId, before, limit) }

    private suspend fun readWebsiteReceipts(
        current: Credential,
        projectId: String,
        before: String?,
        limit: Int,
    ): CoreWebsiteHistoryPage? {
        val target = try {
            api.websiteTarget(current.token, projectId)
        } catch (error: CoreHttpException) {
            if (error.status == 404 && before == null) return null
            throw error
        }
        verifyOrganization(target.orgId, current)
        if (target.projectId != projectId || !coreIdentifier.matches(target.id) ||
            target.version !in 1..CORE_MAX_VERSION) throw CoreProtocolException()
        verifyCurrent(current)
        return api.websiteHistory(current.token, target.id, before, limit).also { page ->
            val versions = page.items.map { it.receipt.targetVersion }
            if (page.items.size > limit ||
                versions.zipWithNext().any { (first, second) -> first <= second } ||
                (before != null && versions.any { it >= before.toLong() }) ||
                (page.nextCursor != null && page.nextCursor != versions.lastOrNull()?.toString())
            ) throw CoreProtocolException()
            page.items.forEach { verifyReceipt(it, target.id, current) }
        }
    }

    /** A verified briefing bound to the organization session that read it. */
    class Today internal constructor(
        val epoch: Long,
        internal val scope: CoreAccountScope,
        val briefing: CoreTodayBriefing,
    )

    suspend fun today(): Today = request { current ->
        val briefing = api.todayBriefing(current.token)
        verifyToday(briefing)
        Today(current.state.epoch, current.state.scope, briefing)
    }

    /**
     * Reads the current task named by a blocked or deadline item of [today] by its exact canonical ID.
     * The task may have moved to another project since the briefing was generated.
     */
    suspend fun todayTask(today: Today, source: CoreTodayTaskSource): CoreWorkTask {
        val sections = today.briefing.sections
        require(sections.blocked.items.any { it.source === source } || sections.deadlines.items.any { it.source === source })
        return request { current ->
            verifyTodayScope(today, current)
            api.task(current.token, source.taskId).also { task ->
                verifyTask(task)
                if (task.id != source.taskId || task.version < source.version) throw CoreProtocolException()
            }
        }
    }

    /**
     * Reads the website history entry for a site receipt in [today] at its exact target version and verifies
     * that it is the same receipt. Null means the history no longer lists that version. Reviews have no receipt.
     */
    suspend fun todayWebsiteReceipt(today: Today, activity: CoreTodaySiteActivity): CoreWebsiteHistoryItem? {
        require(!activity.source.kind.review && today.briefing.sections.siteActivity?.items?.any { it === activity } == true)
        val source = activity.source
        // History cursors are exclusive, so the next version selects the newest receipt at or below this one.
        val before = source.targetVersion.takeIf { it < CORE_MAX_VERSION }?.let { (it + 1).toString() }
        return request { current ->
            verifyTodayScope(today, current)
            val item = readWebsiteReceipts(current, source.projectId, before, 1)?.items?.singleOrNull()
                ?.takeIf { it.receipt.targetVersion == source.targetVersion } ?: return@request null
            val receipt = item.receipt
            if (receipt.targetId != source.targetId || receipt.effectId != source.effectId ||
                receipt.artifactDigest != source.artifactDigest || item.operation != source.operation ||
                (item.operation == CoreWebsiteOperation.Publish && item.buildId != source.buildId)
            ) throw CoreProtocolException()
            item
        }
    }

    private fun verifyTodayScope(today: Today, current: Credential) {
        if (current.state.epoch != today.epoch || current.state.scope != today.scope) {
            throw CancellationException("Core session changed")
        }
    }

    private fun verifyToday(briefing: CoreTodayBriefing) {
        val sections = briefing.sections
        val all = listOf(sections.approvals, sections.blocked, sections.deadlines, sections.verified) +
            listOfNotNull(sections.siteActivity)
        val approvals = sections.approvals.items
        val verified = sections.verified.items
        val site = sections.siteActivity?.items.orEmpty()
        val generatedAt = todayInstant(briefing.generatedAt)
        if (todayInstant(briefing.verifiedSince) != generatedAt.minusSeconds(86_400) ||
            todayInstant(briefing.dueBefore) != generatedAt.plusSeconds(86_400) ||
            all.any { it.items.size > CORE_TODAY_SECTION_LIMIT } ||
            sections.siteActivity?.items?.isEmpty() == true || briefing.empty != all.all { it.items.isEmpty() } ||
            !distinct(sections.blocked.items.map { it.source.taskId }) ||
            !distinct(sections.deadlines.items.map { it.source.taskId }) ||
            !distinct(approvals.map { it.source.approvalId }) || !distinct(approvals.map { it.source.effectId }) ||
            !distinct(verified.map { it.source.receiptId }) || !distinct(verified.map { it.source.effectId }) ||
            !distinct(site.map { it.source.effectId })
        ) throw CoreProtocolException()
        sections.blocked.items.forEach {
            verifyTodayRecord(it.provenance, it.observedAt)
            verifyTodayTask(it.source, it.title)
            if (it.status != CoreTaskStatus.Blocked) throw CoreProtocolException()
            it.dueAt?.let(::todayInstant)
        }
        sections.deadlines.items.forEach {
            verifyTodayRecord(it.provenance, it.observedAt)
            verifyTodayTask(it.source, it.title)
            if (it.status !in deadlineStatuses) throw CoreProtocolException()
            todayInstant(it.dueAt)
        }
        approvals.forEach {
            verifyTodayRecord(it.provenance, it.observedAt)
            val source = it.source
            if (source.kind != "approval" || !coreIdentifier.matches(source.approvalId) ||
                !coreIdentifier.matches(source.effectId) || !coreIdentifier.matches(source.runId) ||
                source.version !in 1..CORE_MAX_VERSION) throw CoreProtocolException()
            todayInstant(it.expiresAt)
        }
        verified.forEach {
            verifyTodayRecord(it.provenance, it.observedAt)
            val source = it.source
            if (source.kind != "effect_receipt" || !coreIdentifier.matches(source.effectId) ||
                !coreIdentifier.matches(source.receiptId) || !coreIdentifier.matches(source.runId)) throw CoreProtocolException()
        }
        site.forEach {
            verifyTodayRecord(it.provenance, it.observedAt)
            val source = it.source
            val minimumVersion = when (source.kind) {
                CoreTodaySiteKind.PublicationReceipt -> 2L
                CoreTodaySiteKind.WithdrawalReceipt -> 3L
                CoreTodaySiteKind.PublicationReview, CoreTodaySiteKind.WithdrawalReview -> 1L
            }
            if (source.operation != source.kind.operation ||
                listOf(source.effectId, source.targetId, source.projectId, source.taskId, source.buildId)
                    .any { id -> !coreIdentifier.matches(id) } ||
                !coreDigest.matches(source.artifactDigest) || source.targetVersion !in minimumVersion..CORE_MAX_VERSION ||
                !it.createdAtValid ||
                (source.kind.review != it.createdAtPresent) || (source.kind.review != (it.decisionAccess != null))
            ) throw CoreProtocolException()
        }
    }

    private fun verifyTodayRecord(provenance: String, observedAt: String) {
        if (provenance != "source_record") throw CoreProtocolException()
        todayInstant(observedAt)
    }

    private fun verifyTodayTask(source: CoreTodayTaskSource, title: String) {
        if (source.kind != "task" || !coreIdentifier.matches(source.taskId) || !coreIdentifier.matches(source.projectId) ||
            source.version !in 1..CORE_MAX_VERSION || title.isEmpty() || title.codePointCount(0, title.length) > 200
        ) throw CoreProtocolException()
    }

    private fun todayInstant(value: String): Instant {
        if (!coreTimestamp.matches(value)) throw CoreProtocolException()
        return try {
            Instant.parse(value)
        } catch (_: DateTimeParseException) {
            throw CoreProtocolException()
        }
    }

    private fun distinct(ids: List<String>) = ids.distinct().size == ids.size

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
    ) {
        /** The verified acceptance for this exact request key; guarded by the controller lock. */
        internal var acceptance: CoreTaskCommandAcceptance? = null
    }

    fun prepareTaskCreation(epoch: Long, project: CoreProjectChoice, title: String, description: String): TaskCreation {
        require(coreIdentifier.matches(project.id))
        return synchronized(lock) {
            val current = requireCredential().state
            if (current.epoch != epoch) throw CancellationException("Core session changed")
            if (current.session.role == CoreRole.viewer) throw CoreHttpException(403)
            val normalizedTitle = normalizeTaskText(title.trim(::isTaskTitleSpace), 200)
            require(normalizedTitle.isNotEmpty())
            val normalizedDescription = description.takeIf(String::isNotEmpty)?.let { normalizeTaskText(it, 10_000) }
            TaskCreation(current.scope, epoch, UUID.randomUUID().toString(), CoreNewTask(project.id, normalizedTitle, normalizedDescription))
        }
    }

    suspend fun createTask(submission: TaskCreation): CoreTaskCommandAcceptance = request { current ->
        verifySubmission(submission, current)
        if (current.state.session.role == CoreRole.viewer) throw CoreHttpException(403)
        api.createTask(current.token, submission.draft, submission.key).also { accepted ->
            if (!coreIdentifier.matches(accepted.runId) || !coreIdentifier.matches(accepted.effectId) ||
                when (accepted.status) {
                    CoreTaskCommandStatus.Queued -> accepted.approvalId != null
                    CoreTaskCommandStatus.WaitingApproval -> accepted.approvalId?.let(coreIdentifier::matches) != true
                }
            ) throw CoreProtocolException()
            val context = currentCoroutineContext()
            synchronized(lock) {
                context.ensureActive()
                if (credential !== current) throw CancellationException("Core session changed")
                requireCredential()
                val previous = submission.acceptance
                if (previous != null && (previous.runId != accepted.runId || previous.effectId != accepted.effectId)) {
                    throw CoreProtocolException()
                }
                submission.acceptance = accepted
            }
        }
    }

    /**
     * Reads the outcome of the effect accepted for [submission] in its initiating session. A confirmed effect
     * resolves to the canonical task, which may have moved to another project since creation.
     */
    suspend fun taskOutcome(submission: TaskCreation): CoreTaskOutcome = request { current ->
        verifySubmission(submission, current)
        val accepted = checkNotNull(synchronized(lock) { submission.acceptance }) { "Task command was not accepted" }
        val effect = api.effect(current.token, accepted.effectId)
        if (effect.id != accepted.effectId || effect.runId != accepted.runId ||
            (effect.state == CoreEffectState.Confirmed) != (effect.taskId != null) ||
            effect.taskId?.let(coreIdentifier::matches) == false) throw CoreProtocolException()
        when (effect.state) {
            CoreEffectState.Queued -> CoreTaskOutcome.Queued
            CoreEffectState.WaitingApproval -> CoreTaskOutcome.WaitingApproval
            CoreEffectState.Failed -> CoreTaskOutcome.Failed
            CoreEffectState.Canceled -> CoreTaskOutcome.Canceled
            CoreEffectState.Confirmed -> {
                val taskId = checkNotNull(effect.taskId)
                verifyCurrent(current)
                val task = api.task(current.token, taskId)
                verifyTask(task)
                if (task.id != taskId) throw CoreProtocolException()
                CoreTaskOutcome.Confirmed(task)
            }
        }
    }

    class RecoveryProject internal constructor(internal val epoch: Long, val id: String)

    data class RecoverySnapshot(val state: CoreApplicationRecoveryState, val points: List<CoreRecoveryPoint>)

    sealed interface RecoveryAction {
        data class Capture(val revision: Long) : RecoveryAction
        data class Remove(val point: CoreRecoveryPoint, val confirmation: String) : RecoveryAction
        data class Prepare(val point: CoreRecoveryPoint, val revision: Long) : RecoveryAction
        data class Confirm(val review: CoreRestoreResult.Review, val confirmation: String) : RecoveryAction
    }

    class RecoveryAttempt internal constructor(
        internal val project: RecoveryProject,
        val action: RecoveryAction,
        internal val key: String,
    )

    sealed interface RecoveryOutcome {
        data class Captured(val point: CoreRecoveryPoint) : RecoveryOutcome
        data object Removed : RecoveryOutcome
        data class Reviewed(val result: CoreRestoreResult) : RecoveryOutcome
    }

    fun recoveryProject(epoch: Long, project: CoreProjectChoice): RecoveryProject {
        require(coreIdentifier.matches(project.id))
        return RecoveryProject(epoch, project.id)
    }

    fun prepareRecoveryAttempt(project: RecoveryProject, action: RecoveryAction): RecoveryAttempt {
        return RecoveryAttempt(project, action, UUID.randomUUID().toString())
    }

    suspend fun recovery(project: RecoveryProject): RecoverySnapshot = request { current ->
        verifyRecoveryAuthority(project, current)
        val state = api.recoveryState(current.token, project.id)
        if (state.projectId != project.id || state.revision !in 0..CORE_MAX_VERSION ||
            state.runtimeEligible && !state.enabled ||
            listOfNotNull(state.programId, state.testId, state.accessId, state.disableReviewId).any { !recoveryId.matches(it) } ||
            (state.enabled && (state.programId == null || state.testId == null || state.accessId == null)) ||
            (!state.enabled && listOfNotNull(state.programId, state.testId, state.accessId, state.disableReviewId).isNotEmpty())
        ) throw CoreProtocolException()
        verifyCurrent(current)
        val points = api.recoveryPoints(current.token, project.id).points
        if (points.size > 10 || !distinct(points.map { it.id }) || points.any { it.removed }) throw CoreProtocolException()
        points.forEach { verifyRecoveryPoint(it, project.id) }
        RecoverySnapshot(state, points)
    }

    suspend fun readApplicationRestore(project: RecoveryProject, review: CoreRestoreResult.Review): CoreRestoreResult =
        request { current ->
            verifyRecoveryAuthority(project, current)
            verifyRestore(review)
            require(review.projectId == project.id)
            api.restoreReview(current.token, review.id).also { verifyExactRestore(review, it) }
        }

    suspend fun writeRecovery(attempt: RecoveryAttempt): RecoveryOutcome = request { current ->
        val project = attempt.project
        verifyRecoveryAuthority(project, current)
        when (val action = attempt.action) {
            is RecoveryAction.Capture -> {
                require(action.revision in 1..CORE_MAX_VERSION)
                val point = api.captureRecoveryPoint(current.token, project.id, action.revision, attempt.key)
                verifyRecoveryPoint(point, project.id)
                if (point.applicationRevision != action.revision) throw CoreProtocolException()
                RecoveryOutcome.Captured(point)
            }
            is RecoveryAction.Remove -> {
                require(action.confirmation == "REMOVE" && !action.point.removed)
                verifyRecoveryPoint(action.point, project.id)
                val removal = api.removeRecoveryPoint(current.token, action.point.id, attempt.key)
                if (removal.id != action.point.id || !removal.removed) throw CoreProtocolException()
                RecoveryOutcome.Removed
            }
            is RecoveryAction.Prepare -> {
                require(!action.point.removed && action.revision in 1..CORE_MAX_VERSION)
                verifyRecoveryPoint(action.point, project.id)
                val result = api.prepareApplicationRestore(current.token, project.id, action.point.id, action.revision, attempt.key)
                verifyRestore(result)
                if (result is CoreRestoreResult.Review &&
                    (result.projectId != project.id || result.pointId != action.point.id ||
                        result.programId != action.point.programId || result.expectedApplicationRevision != action.revision)
                ) throw CoreProtocolException()
                RecoveryOutcome.Reviewed(result)
            }
            is RecoveryAction.Confirm -> {
                val review = action.review
                verifyRestore(review)
                require(action.confirmation == "RESTORE" && review.projectId == project.id && review.state == "waiting_approval")
                val result = api.confirmApplicationRestore(current.token, review.id, review.payloadHash, attempt.key)
                verifyExactRestore(review, result)
                if (result.receipt == null) throw CoreProtocolException()
                RecoveryOutcome.Reviewed(result)
            }
        }
    }

    private fun verifyRecoveryAuthority(project: RecoveryProject, current: Credential) {
        if (project.epoch != current.state.epoch) throw CancellationException("Core session changed")
        if (current.state.session.role != CoreRole.owner) throw CoreHttpException(403)
    }

    private fun verifyExactRestore(expected: CoreRestoreResult.Review, result: CoreRestoreResult) {
        verifyRestore(result)
        if (result.payloadHash != expected.payloadHash) throw CoreProtocolException()
        when (result) {
            is CoreRestoreResult.Review -> if (result.copy(state = expected.state, receipt = expected.receipt) != expected) {
                throw CoreProtocolException()
            }
            is CoreRestoreResult.Compacted -> if (result.reviewId != expected.id ||
                (result.receipt?.restoredDocuments ?: 0) > expected.documents
            ) throw CoreProtocolException()
        }
    }

    private fun verifySubmission(submission: TaskCreation, current: Credential) {
        if (current.state.epoch != submission.epoch || current.state.scope != submission.scope) {
            throw CancellationException("Core session changed")
        }
    }

    private fun normalizeTaskText(text: String, maxCodePoints: Int): String {
        require(text.codePoints().noneMatch { it == 0 || it in 0xD800..0xDFFF })
        return Normalizer.normalize(text, Normalizer.Form.NFC).also {
            require(it.codePointCount(0, it.length) <= maxCodePoints)
        }
    }

    // Match ECMAScript trim used by the task contract, including the byte-order mark.
    private fun isTaskTitleSpace(character: Char): Boolean =
        character in '\u0009'..'\u000D' || character == '\u2028' || character == '\u2029' ||
            character == '\uFEFF' || Character.getType(character) == Character.SPACE_SEPARATOR.toInt()

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
            task.title.isEmpty() || task.version !in 1..CORE_MAX_VERSION) throw CoreProtocolException()
    }

    /** Run a short, non-suspending result commit only while the captured epoch is active. */
    fun commitIfCurrent(capturedEpoch: Long, commit: () -> Unit): Boolean = synchronized(lock) {
        val current = credential ?: return@synchronized false
        if (current.state.epoch != capturedEpoch || current.state.session.expiresAt <= now()) return@synchronized false
        commit()
        true
    }

    fun expireIfCurrent(capturedEpoch: Long) = synchronized(lock) {
        val current = credential ?: return@synchronized
        if (current.state.epoch == capturedEpoch && current.state.session.expiresAt <= now()) clearLocked()
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
        validGrant: () -> Boolean = { true },
    ): State.Active {
        try {
            val session = api.session(token)
            if (!coreIdentifier.matches(session.sessionId) || !coreIdentifier.matches(session.userId) ||
                !coreIdentifier.matches(session.orgId) || session.expiresAt <= now() ||
                (userId != null && session.userId != userId) || (orgId != null && session.orgId != orgId) ||
                (expiresAt != null && session.expiresAt != expiresAt)) throw CoreProtocolException()
            currentCoroutineContext().ensureActive()
            if (!validGrant()) throw CancellationException("Account grant changed")
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

    private companion object {
        val deadlineStatuses = setOf(
            CoreTaskStatus.Backlog, CoreTaskStatus.Ready, CoreTaskStatus.InProgress, CoreTaskStatus.NeedsApproval,
        )
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
