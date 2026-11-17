package com.whumpo.core.auth

import com.whumpo.core.api.CoreAdaptiveBucket
import com.whumpo.core.api.CoreAdaptiveCategory
import com.whumpo.core.api.CoreAdaptiveContribution
import com.whumpo.core.api.CoreAdaptiveControl
import com.whumpo.core.api.CoreAdaptiveProfile
import com.whumpo.core.api.CoreAdaptiveReceipt
import com.whumpo.core.api.CoreAdaptiveRegistration
import com.whumpo.core.api.CoreAdaptiveVisit
import com.whumpo.core.api.CoreAdaptiveWriter
import com.whumpo.core.api.CoreApiClient
import com.whumpo.core.api.CoreHttpException
import com.whumpo.core.api.CoreProtocolException
import com.whumpo.core.api.coreAdaptiveMaxInteger
import com.whumpo.core.api.coreAdaptiveUuid
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class CoreAdaptiveNotice { Unavailable, Retry, Changed }

@ConsistentCopyVisibility
data class CoreAdaptiveReview internal constructor(
    internal val windowId: UUID,
    internal val generation: String,
    internal val preferenceRevision: Long,
)

data class CoreAdaptiveHomeState(
    val shortcuts: List<CoreAdaptiveCategory?> = emptyList(),
    val provisional: List<CoreAdaptiveCategory> = emptyList(),
    val pins: List<CoreAdaptiveCategory> = emptyList(),
    val learningEnabled: Boolean = false,
    val review: CoreAdaptiveReview? = null,
    val saving: Boolean = false,
    val notice: CoreAdaptiveNotice? = null,
)

/** Owned by one connected foreground preview and one exact active session. */
class CoreAdaptiveHomeController(
    private val sessions: CoreOrganizationSessionController,
    private val active: CoreOrganizationSessionController.State.Active,
    private val cache: CoreAdaptiveProfileCache? = null,
    private val onCacheFailure: () -> Unit = {},
    private val waitForProjectionExpiry: suspend (Long) -> Unit = { delay(it) },
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val serial = Mutex()
    private val mutableState = MutableStateFlow(CoreAdaptiveHomeState())
    val state = mutableState.asStateFlow()
    private var owner = UUID.randomUUID()
    private var foreground = false
    private var foregroundIsCurrent: () -> Boolean = { true }
    private var window = 0L
    private var visiting = false
    private var visitPending: UUID? = null
    private var availability = emptySet<CoreAdaptiveCategory>()
    private var profile: CoreAdaptiveProfile? = null
    private var projection: Pair<CoreAdaptiveProjection, Long>? = null
    private var sample: Pair<Instant, Long>? = null
    private var pending: CoreAdaptiveControl? = null
    private var receipt: CoreAdaptiveReceipt? = null
    private var writer: CoreAdaptiveWriter? = null
    private var registration: CoreAdaptiveRegistration? = null
    private var contribution: CoreAdaptiveContribution? = null
    private val opens = mutableMapOf<CoreAdaptiveCategory, Long>()
    private val buffered = mutableMapOf<CoreAdaptiveCategory, CoreAdaptiveBucket>()

    fun foreground(visible: Boolean, isCurrent: () -> Boolean = { true }) {
        foregroundIsCurrent = isCurrent
        if (foreground == visible) return
        foreground = visible
        window++
        if (!visible) {
            owner = UUID.randomUUID()
            sample = null
            buffered.clear()
            availability = emptySet()
            projection = null
            visiting = false
            visitPending = null
            mutableState.value = mutableState.value.copy(shortcuts = emptyList(), provisional = emptyList(), review = null)
        }
    }

    fun availability(value: Set<CoreAdaptiveCategory>) {
        availability = value
        mutableState.value = mutableState.value.copy(
            shortcuts = mutableState.value.shortcuts.map { it?.takeIf(value::contains) },
            provisional = provisionalShortcuts(),
        )
    }

    suspend fun enterToday() {
        val stamp = window
        if (visiting || !current(stamp)) return
        visiting = true
        visitPending = UUID.randomUUID()
        if (profile == null) {
            val started = monotonicMillis()
            sessions.commitIfCurrent(active.epoch) {
                projection = cache?.load(sessions.origin, active.session)?.let { it to started }
            }
        }
        mutableState.value = mutableState.value.copy(
            shortcuts = profile?.takeIf { sample != null }?.shortcuts(availability).orEmpty(),
            provisional = provisionalShortcuts(),
        )
        val lease = projection
        coroutineScope {
            val expiry = lease?.let {
                launch {
                    waitForProjectionExpiry((it.first.remainingMillis - (monotonicMillis() - it.second)).coerceAtLeast(0L))
                    if (current(stamp) && projection === it) clearProjection()
                }
            }
            try {
                refresh()
            } finally {
                expiry?.cancel()
                if (projection === lease) clearProjection()
            }
        }
    }

    fun leaveToday() {
        visiting = false
        visitPending = null
        clearProjection()
        mutableState.value = mutableState.value.copy(shortcuts = emptyList())
    }

    private fun provisionalShortcuts(): List<CoreAdaptiveCategory> {
        val (cached, started) = projection ?: return emptyList()
        val elapsed = monotonicMillis() - started
        return if (foreground && foregroundIsCurrent() && visiting && elapsed in 0 until cached.remainingMillis) cached.shortcuts(availability)
        else emptyList()
    }

    private fun clearProjection() {
        projection = null
        mutableState.value = mutableState.value.copy(provisional = emptyList())
    }

    suspend fun refresh() = run {
        readProfile(it, initialize = visiting)
        if (receipt != null) settleControl(it)
        val currentVisit = visitPending
        if (visiting && currentVisit != null) {
            if (mutableState.value.shortcuts.isEmpty()) {
                mutableState.value = mutableState.value.copy(shortcuts = profile?.shortcuts(availability).orEmpty())
            }
            val generation = checkNotNull(profile).generation
            val visited = request(it) { api, token -> api.adaptiveVisit(token, CoreAdaptiveVisit(generation)) }
            if (visited.generation != generation) throw CoreProtocolException()
            install(visited, it)
            if (visitPending == currentVisit) visitPending = null
        }
        if (pending == null) flush(it)
    }

    suspend fun open(category: CoreAdaptiveCategory) {
        if (!foreground || !foregroundIsCurrent() || category !in availability || pending != null || profile?.learningEnabled != true) return
        val current = time() ?: return
        val now = monotonicMillis()
        if (opens[category]?.let { now - it < 1_800_000 } == true || category in buffered) return
        if (!sessions.commitIfCurrent(active.epoch) {
                opens[category] = now
                buffered[category] = CoreAdaptiveBucket(current.toString().take(10), category, 1)
            }) return
        refresh()
    }

    suspend fun preferences(review: CoreAdaptiveReview, enabled: Boolean, pins: List<CoreAdaptiveCategory>) {
        if (pins.size <= 2 && pins.distinct().size == pins.size) control(review, enabled, pins)
    }

    suspend fun reset(review: CoreAdaptiveReview) = control(review, null, null)

    suspend fun retry() = run {
        readProfile(it, initialize = false)
        settleControl(it)
    }

    private suspend fun control(review: CoreAdaptiveReview, enabled: Boolean?, pins: List<CoreAdaptiveCategory>?) = run { stamp ->
        if (pending != null) return@run
        val current = profile ?: return@run
        if (review != review(current)) {
            mutableState.value = mutableState.value.copy(notice = CoreAdaptiveNotice.Changed)
            return@run
        }
        val time = time() ?: return@run
        pending = CoreAdaptiveControl(review.generation, review.preferenceRevision, commandId(), expiry(time), enabled, pins)
        mutableState.value = mutableState.value.copy(saving = true, notice = null)
        if (enabled != true) {
            forgetUsage()
            mutableState.value = mutableState.value.copy(shortcuts = mutableState.value.shortcuts.map { null })
        }
        settleControl(stamp)
    }

    private suspend fun run(operation: suspend (Long) -> Unit) {
        val stamp = window
        serial.withLock {
            if (!current(stamp)) return@withLock
            try {
                operation(stamp)
                guardCurrent(stamp)
                mutableState.value = mutableState.value.copy(saving = pending != null,
                    notice = mutableState.value.notice?.takeIf { it == CoreAdaptiveNotice.Changed })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (current(stamp)) {
                    mutableState.value = mutableState.value.copy(saving = pending != null,
                        notice = if (pending == null) CoreAdaptiveNotice.Unavailable else CoreAdaptiveNotice.Retry)
                }
            }
        }
    }

    private fun current(stamp: Long): Boolean = foreground && foregroundIsCurrent() && stamp == window && sessions.commitIfCurrent(active.epoch) {}
    private fun guardCurrent(stamp: Long) { if (!current(stamp)) throw CancellationException("Adaptive view changed") }

    private suspend fun <T> request(stamp: Long, action: suspend (CoreApiClient, String) -> T): T {
        guardCurrent(stamp)
        return sessions.requestForActiveSession(active, action).also { guardCurrent(stamp) }
    }

    private fun install(value: CoreAdaptiveProfile, stamp: Long, sampled: Boolean = false) {
        guardCurrent(stamp)
        val server = runCatching { Instant.parse(value.serverTime) }.getOrNull() ?: throw CoreProtocolException()
        if (value.schemaVersion != 1 || value.algorithmVersion != 1 ||
            value.orgId != active.session.orgId || value.userId != active.session.userId ||
            !coreAdaptiveUuid.matches(value.generation) || value.revision !in 1..coreAdaptiveMaxInteger ||
            value.preferenceRevision !in 1..value.revision || value.pins.size > 2 || value.pins.distinct().size != value.pins.size ||
            value.learnedOrder.toSet() != CoreAdaptiveCategory.entries.toSet() || value.learnedOrder.size != CoreAdaptiveCategory.entries.size ||
            value.evidence !in setOf("cold_start", "learned") ||
            value.evidence == "cold_start" && value.learnedOrder != CoreAdaptiveCategory.entries ||
            value.evidence == "learned" && (!value.learningEnabled || value.evaluatedDay == null) ||
            value.evaluatedDay != null && (!value.learningEnabled ||
                runCatching { java.time.LocalDate.parse(value.evaluatedDay).isAfter(server.atOffset(java.time.ZoneOffset.UTC).toLocalDate()) }
                    .getOrDefault(true))) throw CoreProtocolException()
        val before = profile
        if (before != null && (value.revision < before.revision ||
                value.revision == before.revision && value.generation != before.generation)) throw CoreProtocolException()
        if (before?.generation != value.generation || before.learningEnabled && !value.learningEnabled) {
            forgetUsage()
            if (before != null) mutableState.value = mutableState.value.copy(shortcuts = mutableState.value.shortcuts.map { null })
        }
        profile = value
        if (sampled) {
            sample = server to monotonicMillis()
            clearProjection()
        }
        mutableState.value = mutableState.value.copy(pins = value.pins, learningEnabled = value.learningEnabled, review = review(value))
    }

    private fun review(value: CoreAdaptiveProfile) = CoreAdaptiveReview(owner, value.generation, value.preferenceRevision)

    private suspend fun readProfile(stamp: Long, initialize: Boolean) {
        val data = try { request(stamp) { api, token -> api.adaptiveProfile(token) } }
        catch (error: CoreHttpException) {
            if (error.status == 404 || error.status == 403 || error.status == 423) {
                guardCurrent(stamp)
                clearProjection()
                profile = null
                sample = null
                forgetUsage()
                mutableState.value = mutableState.value.copy(shortcuts = emptyList(), review = null)
                sessions.commitIfCurrent(active.epoch) {
                    if (cache?.eraseScope(sessions.origin, active.session) == false) onCacheFailure()
                }
            }
            if (error.status != 404 || !initialize || pending != null) throw error
            request(stamp) { api, token -> api.adaptiveProfile(token, initialize = true) }
            return readProfile(stamp, initialize = false)
        }
        install(data, stamp, sampled = true)
        sessions.commitIfCurrent(active.epoch) { cache?.save(sessions.origin, active.session, data) }
    }

    private suspend fun settleControl(stamp: Long) {
        val command = pending ?: return
        val now = time() ?: return
        if (receipt == null) {
            if (!Instant.parse(command.expiresAt).isAfter(now)) {
                pending = null
                mutableState.value = mutableState.value.copy(notice = CoreAdaptiveNotice.Changed)
                return
            }
            try {
                val result = request(stamp) { api, token -> api.adaptiveControl(token, command) }
                if (result.commandId != command.commandId || !result.readbackRequired ||
                    !coreAdaptiveUuid.matches(result.committedGeneration) ||
                    result.committedRevision !in 1..coreAdaptiveMaxInteger) throw CoreProtocolException()
                receipt = result
            } catch (error: CoreHttpException) {
                if (error.status != 409) throw error
                pending = null
                mutableState.value = mutableState.value.copy(notice = CoreAdaptiveNotice.Changed)
                readProfile(stamp, initialize = false)
                return
            }
        }
        readProfile(stamp, initialize = false)
        val confirmed = checkNotNull(receipt)
        val current = checkNotNull(profile)
        if (current.revision < confirmed.committedRevision ||
            current.revision == confirmed.committedRevision && current.generation != confirmed.committedGeneration) {
            throw CoreProtocolException()
        }
        receipt = null
        pending = null
    }

    private suspend fun flush(stamp: Long) {
        val current = profile?.takeIf { it.learningEnabled } ?: return
        val now = time() ?: return
        if (writer == null && buffered.isNotEmpty()) {
            val command = registration ?: CoreAdaptiveRegistration(current.generation, commandId(), expiry(now))
            registration = command
            if (!Instant.parse(command.expiresAt).isAfter(now)) {
                forgetUsage()
                return
            }
            val result = request(stamp) { api, token -> api.adaptiveRegister(token, command) }
            if (result.acceptedSequence != 0L) throw CoreProtocolException()
            requireWriter(result, current.generation)
            install(result.profile, stamp)
            if (!result.profile.learningEnabled) return
            writer = result
            registration = null
        }
        val savedWriter = writer ?: return
        contribution?.let { sent ->
            val observed = try { request(stamp) { api, token -> api.adaptiveWriter(token, sent.writerId) } }
            catch (error: CoreHttpException) {
                if (error.status != 404) throw error
                forgetUsage()
                return
            }
            requireWriter(observed, sent.generation)
            if (observed.writerId != sent.writerId || observed.acceptedSequence !in (sent.sequence - 1)..sent.sequence) {
                throw CoreProtocolException()
            }
            install(observed.profile, stamp)
            if (!observed.profile.learningEnabled) return
            writer = observed
            if (observed.acceptedSequence == sent.sequence) contribution = null
        }
        val latest = writer ?: savedWriter
        if (contribution == null && buffered.isNotEmpty() && latest.acceptedSequence < coreAdaptiveMaxInteger) {
            val day = now.toString().take(10)
            val batch = buffered.values.filter { it.day == day }.sortedBy { it.category.ordinal }
            buffered.clear()
            if (batch.isNotEmpty()) {
                contribution = CoreAdaptiveContribution(1, current.generation, latest.writerId, latest.acceptedSequence + 1, batch)
            }
        }
        val sent = contribution ?: return
        val result = request(stamp) { api, token -> api.adaptiveContribute(token, sent) }
        if (result.acceptedSequence != sent.sequence || result.profile.generation != sent.generation) throw CoreProtocolException()
        install(result.profile, stamp)
        val advanced = request(stamp) { api, token -> api.adaptiveWriter(token, sent.writerId) }
        requireWriter(advanced, sent.generation)
        if (advanced.writerId != sent.writerId || advanced.acceptedSequence != sent.sequence) throw CoreProtocolException()
        install(advanced.profile, stamp)
        if (advanced.profile.learningEnabled) writer = advanced
        contribution = null
    }

    private fun requireWriter(value: CoreAdaptiveWriter, generation: String) {
        if (!coreAdaptiveUuid.matches(value.writerId) || value.profile.generation != generation ||
            value.acceptedSequence !in 0..coreAdaptiveMaxInteger) throw CoreProtocolException()
    }

    private fun time(): Instant? = sample?.let { (server, observed) ->
        (monotonicMillis() - observed).takeIf { it in 0..900_000 }?.let(server::plusMillis)
    }
    private fun commandId() = UUID.randomUUID().toString()
    private fun expiry(now: Instant) = java.time.format.DateTimeFormatterBuilder().appendInstant(3).toFormatter()
        .format(now.plus(23, ChronoUnit.HOURS))
    private fun forgetUsage() {
        opens.clear()
        buffered.clear()
        writer = null
        registration = null
        contribution = null
    }
}
