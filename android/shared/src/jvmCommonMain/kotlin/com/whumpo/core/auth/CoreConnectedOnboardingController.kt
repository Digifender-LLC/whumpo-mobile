package com.whumpo.core.auth

import com.whumpo.core.api.CORE_MAX_VERSION
import com.whumpo.core.api.CoreBriefingCadence
import com.whumpo.core.api.CoreHttpException
import com.whumpo.core.api.CoreOnboardingProfile
import com.whumpo.core.api.CoreOnboardingProfileStatus
import com.whumpo.core.api.CoreOnboardingStage
import com.whumpo.core.api.CoreOnboardingSummary
import com.whumpo.core.api.CoreOrganizationReadiness
import com.whumpo.core.api.CoreProtocolException
import com.whumpo.core.api.CoreRole
import com.whumpo.core.api.coreIdentifier
import java.text.Normalizer
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class CoreSetupQuestion(internal val field: String, internal val maxLength: Int) {
    Organization("organizationLabel", 200),
    Owner("ownerName", 200),
    Goal("goal", 4000),
    TimeZone("timeZone", 200),
    Cadence("briefingCadence", 6);

    fun answer(profile: CoreOnboardingProfile): String? = when (this) {
        Organization -> profile.organizationLabel
        Owner -> profile.ownerName
        Goal -> profile.goal
        TimeZone -> profile.timeZone
        Cadence -> profile.briefingCadence?.name
    }

    internal fun normalized(value: String): String? {
        if (value.length > maxLength * 2 || !validSetupUnicode(value)) return null
        val text = Normalizer.normalize(value.trim(::setupWhitespace), Normalizer.Form.NFC)
        if (text.isEmpty() || text.codePointCount(0, text.length) > maxLength) return null
        return when (this) {
            TimeZone -> text.takeIf(setupTimeZone::matches)
            Cadence -> text.takeIf { name -> CoreBriefingCadence.entries.any { it.name == name } }
            else -> text
        }
    }
}

private val setupTimeZone = Regex("[A-Za-z][A-Za-z0-9_+/\\-]*")

private fun setupWhitespace(character: Char): Boolean =
    character in '\u0009'..'\u000D' || character == '\u2028' || character == '\u2029' ||
        character == '\uFEFF' || Character.getType(character) == Character.SPACE_SEPARATOR.toInt()

private fun validSetupUnicode(text: String): Boolean =
    text.codePoints().noneMatch { it == 0 || it in 0xD800..0xDFFF }

enum class CoreSetupNotice {
    Unavailable, InvalidAnswer, RequestUnconfirmed, ConfirmationPending, ProfileChanged, SignInAgain, Rejected,
}

data class CoreConnectedSetupState(
    val readiness: CoreOrganizationReadiness? = null,
    val profile: CoreOnboardingProfile? = null,
    val stage: CoreOnboardingStage? = null,
    val question: CoreSetupQuestion? = null,
    val deferred: Set<CoreSetupQuestion> = emptySet(),
    val busy: Boolean = false,
    val pending: Boolean = false,
    val acknowledged: Boolean = false,
    val reviewRequired: Boolean = false,
    val notice: CoreSetupNotice? = null,
) {
    val canOpenWorkspace: Boolean get() = readiness?.ready == true && !pending
    val canEdit: Boolean get() = stage == CoreOnboardingStage.verified
}

/** Owns setup and pending attempts for one exact authenticated epoch. */
class CoreConnectedOnboardingController(
    private val sessions: CoreOrganizationSessionController,
    private val active: CoreOrganizationSessionController.State.Active,
) {
    val isOwner = active.session.role == CoreRole.owner

    private val mutableState = MutableStateFlow(CoreConnectedSetupState())
    val state = mutableState.asStateFlow()
    private val current get() = mutableState.value

    private var job: Job? = null
    private var retired = false
    private var pending: Attempt? = null
    private var editedQuestion: CoreSetupQuestion? = null

    private sealed interface Write {
        data class Profile(
            val baseline: CoreOnboardingProfile,
            val body: String?,
            val question: CoreSetupQuestion? = null,
            val value: String? = null,
        ) : Write
        data object Complete : Write
    }

    private class Attempt(val write: Write, val key: String = UUID.randomUUID().toString()) {
        var attempted = false
        var profile: CoreOnboardingProfile? = null
        var completion: CoreOnboardingSummary? = null
    }

    fun retire() {
        retired = true
        job?.cancel()
        job = null
        pending = null
        editedQuestion = null
        mutableState.value = CoreConnectedSetupState()
    }

    suspend fun refresh() = perform {
        when (val attempt = pending) {
            null -> loadGate()
            else -> when (attempt.write) {
                Write.Complete -> reconcileCompletion(attempt)
                is Write.Profile -> {
                    if (attempt.profile != null) reconcileProfile(attempt)
                    else update { copy(notice = CoreSetupNotice.RequestUnconfirmed) }
                }
            }
        }
    }

    fun editQuestion(question: CoreSetupQuestion) {
        if (!current.canEdit || current.pending || current.busy || current.reviewRequired || current.canOpenWorkspace) return
        editedQuestion = question
        update { copy(question = question, notice = null) }
    }

    suspend fun answer(question: CoreSetupQuestion, text: String?) = perform {
        val profile = editableProfile() ?: return@perform
        if (question != current.question) return@perform
        val value = text?.let(question::normalized)
        if (text != null && value == null) {
            update { copy(notice = CoreSetupNotice.InvalidAnswer) }
            return@perform
        }
        val patch = JsonObject(mapOf(question.field to (value?.let(::JsonPrimitive) ?: JsonNull))).toString()
        execute(Attempt(Write.Profile(profile, patch, question, value)))
    }

    suspend fun skipRemaining() = perform {
        val profile = editableProfile() ?: return@perform
        execute(Attempt(Write.Profile(profile, null)))
    }

    suspend fun createWorkspace() = perform {
        if (editableProfile() == null || current.question != null) return@perform
        execute(Attempt(Write.Complete))
    }

    suspend fun retry() = perform {
        pending?.let { execute(it) }
    }

    private fun editableProfile(): CoreOnboardingProfile? =
        current.profile.takeIf { isOwner && current.canEdit && !current.reviewRequired && !current.canOpenWorkspace && pending == null }

    private suspend fun requestedReadiness(): CoreOrganizationReadiness =
        sessions.requestForActiveSession(active) { api, token -> api.organizationReadiness(token) }.also {
            requireWire(it.orgId == active.session.orgId && (it.projectId == null ||
                (it.ready && coreIdentifier.matches(it.projectId))))
        }

    private suspend fun requestedSummary(): CoreOnboardingSummary {
        requireWire(isOwner)
        return sessions.requestForActiveSession(active) { api, token -> api.onboardingSummary(token) }.also(::verifySummary)
    }

    private suspend fun loadGate() {
        val readiness = requestedReadiness()
        if (readiness.ready || !isOwner) {
            update { copy(readiness = readiness, profile = null, stage = null, question = null) }
            return
        }
        update { copy(readiness = readiness) }
        val summary = requestedSummary()
        if (summary.stage == CoreOnboardingStage.ready) {
            publishReady(summary, requestedReadiness())
        } else {
            showSummary(summary)
        }
    }

    private suspend fun execute(attempt: Attempt) {
        val previouslyAttempted = attempt.attempted
        pending = attempt
        attempt.attempted = true
        update { copy(pending = true) }
        try {
            when (val write = attempt.write) {
                is Write.Profile -> {
                    if (attempt.profile == null) {
                        val ack = sessions.requestForActiveSession(active) { api, token ->
                            api.writeOnboardingProfile(token, attempt.key, write.baseline.version, write.body)
                        }
                        verifyProfile(ack)
                        val expected = write.baseline.copy(
                            version = write.baseline.version + 1,
                            status = if (write.body == null) CoreOnboardingProfileStatus.skipped else CoreOnboardingProfileStatus.draft,
                            organizationLabel = if (write.question == CoreSetupQuestion.Organization) write.value else write.baseline.organizationLabel,
                            ownerName = if (write.question == CoreSetupQuestion.Owner) write.value else write.baseline.ownerName,
                            goal = if (write.question == CoreSetupQuestion.Goal) write.value else write.baseline.goal,
                            timeZone = if (write.question == CoreSetupQuestion.TimeZone) write.value else write.baseline.timeZone,
                            briefingCadence = if (write.question == CoreSetupQuestion.Cadence) {
                                write.value?.let(CoreBriefingCadence::valueOf)
                            } else write.baseline.briefingCadence,
                        )
                        requireWire(ack == expected)
                        attempt.profile = ack
                        update { copy(acknowledged = true) }
                    }
                    reconcileProfile(attempt)
                }
                Write.Complete -> {
                    if (attempt.completion == null) {
                        val ack = sessions.requestForActiveSession(active) { api, token -> api.completeOnboarding(token, attempt.key) }
                        verifySummary(ack)
                        requireWire(ack.stage == CoreOnboardingStage.ready)
                        attempt.completion = ack
                        update { copy(acknowledged = true) }
                    }
                    reconcileCompletion(attempt)
                }
            }
        } catch (error: CoreHttpException) {
            if (previouslyAttempted || current.acknowledged) {
                if (error.status == 403) update { copy(notice = CoreSetupNotice.SignInAgain) } else throw error
                return
            }
            val notice = when (error.status) {
                403 -> CoreSetupNotice.SignInAgain
                409 -> CoreSetupNotice.ProfileChanged
                422, 428 -> CoreSetupNotice.Rejected
                else -> throw error
            }
            pending = null
            update { copy(pending = false, reviewRequired = true, notice = notice) }
        }
    }

    private suspend fun reconcileProfile(attempt: Attempt) {
        val ack = checkNotNull(attempt.profile)
        val summary = requestedSummary()
        requireWire(summary.profile.version >= ack.version &&
            (summary.profile.version > ack.version || summary.profile == ack))
        if (summary.stage == CoreOnboardingStage.ready) {
            publishReady(summary, requestedReadiness())
            return
        }
        val write = attempt.write as Write.Profile
        val deferred = if (write.question != null && write.value == null && write.question.answer(summary.profile) == null) {
            current.deferred + write.question
        } else current.deferred - setOfNotNull(write.question)
        pending = null
        editedQuestion = null
        update { copy(pending = false, acknowledged = false, deferred = deferred) }
        showSummary(summary)
    }

    private suspend fun reconcileCompletion(attempt: Attempt) {
        val summary = requestedSummary()
        if (summary.stage != CoreOnboardingStage.ready) {
            update { copy(notice = if (attempt.completion == null) CoreSetupNotice.RequestUnconfirmed else CoreSetupNotice.ConfirmationPending) }
            return
        }
        attempt.completion?.let { ack ->
            requireWire(ack.workspaceId == summary.workspaceId && ack.projectId == summary.projectId &&
                ack.agentId == summary.agentId && ack.conversationId == summary.conversationId)
        }
        publishReady(summary, requestedReadiness())
    }

    private fun publishReady(summary: CoreOnboardingSummary, readiness: CoreOrganizationReadiness) {
        requireWire(readiness.ready && summary.projectId != null && readiness.projectId == summary.projectId)
        pending = null
        editedQuestion = null
        update { CoreConnectedSetupState(readiness = readiness, busy = busy) }
    }

    private fun showSummary(summary: CoreOnboardingSummary) {
        val profile = summary.profile
        val next = when {
            summary.stage != CoreOnboardingStage.verified -> null
            editedQuestion != null -> editedQuestion
            profile.status == CoreOnboardingProfileStatus.skipped -> null
            else -> CoreSetupQuestion.entries.firstOrNull { it !in current.deferred && it.answer(profile) == null }
        }
        update { copy(profile = profile, stage = summary.stage, question = next, reviewRequired = false) }
    }

    private fun verifySummary(summary: CoreOnboardingSummary) {
        requireWire(summary.orgId == active.session.orgId)
        verifyProfile(summary.profile)
        val identifiers = listOf(summary.workspaceId, summary.projectId, summary.agentId, summary.conversationId)
        requireWire(if (summary.stage == CoreOnboardingStage.ready) identifiers.all { it != null && coreIdentifier.matches(it) }
            else identifiers.all { it == null })
    }

    private fun verifyProfile(profile: CoreOnboardingProfile) {
        requireWire(profile.version in 1..CORE_MAX_VERSION)
        CoreSetupQuestion.entries.forEach { question ->
            question.answer(profile)?.let {
                requireWire(question.normalized(it) == it)
            }
        }
    }

    private fun requireWire(condition: Boolean) {
        if (!condition) throw CoreProtocolException()
    }

    private fun update(change: CoreConnectedSetupState.() -> CoreConnectedSetupState) {
        if (!retired) sessions.commitIfCurrent(active.epoch) { mutableState.value = current.change() }
    }

    private suspend fun perform(action: suspend () -> Unit) = coroutineScope {
        if (retired || current.busy || !sessions.commitIfCurrent(active.epoch) {}) return@coroutineScope
        job = currentCoroutineContext()[Job]
        update { copy(busy = true, notice = null) }
        try {
            action()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            update {
                copy(notice = when {
                    acknowledged -> CoreSetupNotice.ConfirmationPending
                    pending -> CoreSetupNotice.RequestUnconfirmed
                    else -> CoreSetupNotice.Unavailable
                })
            }
        } finally {
            job = null
            update { copy(busy = false) }
        }
    }
}
