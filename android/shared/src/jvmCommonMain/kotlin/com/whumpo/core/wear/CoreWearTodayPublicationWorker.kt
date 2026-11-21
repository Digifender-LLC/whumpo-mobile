package com.whumpo.core.wear

import com.whumpo.core.api.CoreHttpException
import com.whumpo.core.auth.CoreOrganizationSessionController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Process-owned Core session observer; the injected writer is the exact Data Layer publication boundary. */
class CoreWearTodayPublicationWorker(
    private val organizations: CoreOrganizationSessionController,
    processScope: CoroutineScope,
    private val nextGeneration: () -> Long,
    private val publish: suspend (CoreWearTodaySnapshot, CoreOrganizationSessionController.State.Active?) -> Unit,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val readSnapshot: suspend (
        CoreOrganizationSessionController.State.Active,
        Long,
        Long,
    ) -> CoreWearTodaySnapshot? = { active, generation, publishedAt ->
        organizations.readCurrentWearTodaySnapshot(active, generation, publishedAt)
    },
) : AutoCloseable {
    private sealed interface Intent {
        data object Invalidate : Intent
        data class Refresh(val epoch: Long) : Intent
    }

    private val job = SupervisorJob(processScope.coroutineContext[Job])
    private val scope = CoroutineScope(processScope.coroutineContext + job)
    private val intents = Channel<Intent>(Channel.BUFFERED)
    private var scheduledRefresh: Job? = null

    init {
        scope.launch {
            var initialized = false
            var observedEpoch: Long? = null
            organizations.state.collect { state ->
                val active = state as? CoreOrganizationSessionController.State.Active
                if (active == null) {
                    if (!initialized || observedEpoch != null) intents.send(Intent.Invalidate)
                    observedEpoch = null
                } else {
                    if (!initialized || observedEpoch != active.epoch) intents.send(Intent.Invalidate)
                    observedEpoch = active.epoch
                    intents.send(Intent.Refresh(active.epoch))
                }
                initialized = true
            }
        }
        scope.launch {
            for (intent in intents) {
                when (intent) {
                    Intent.Invalidate -> publishUnavailableSafely()
                    is Intent.Refresh -> refresh(intent.epoch)
                }
            }
        }
    }

    /** Requests fresh counts after the paired watch becomes reachable or the phone activity resumes. */
    fun refreshToday() {
        scope.launch {
            val active = organizations.state.value as? CoreOrganizationSessionController.State.Active
            if (active == null) intents.send(Intent.Invalidate)
            else intents.send(Intent.Refresh(active.epoch))
        }
    }

    private suspend fun refresh(epoch: Long) {
        val active = organizations.state.value as? CoreOrganizationSessionController.State.Active ?: return
        if (active.epoch != epoch) return
        try {
            val publishedAt = nowMillis()
            val snapshot = readSnapshot(active, nextGeneration(), publishedAt) ?: return
            currentCoroutineContext().ensureActive()
            if (organizations.state.value !== active) return
            if (!snapshot.validAt(nowMillis())) {
                publishUnavailableSafely()
                scheduleRefresh(epoch, RETRY_MILLIS)
                return
            }
            publish(snapshot, active)
            scheduleRefresh(epoch, REFRESH_MILLIS)
        } catch (error: CancellationException) {
            // A stale session read can be canceled while this process-owned worker remains active.
            currentCoroutineContext().ensureActive()
        } catch (error: CoreHttpException) {
            // A 401 changes the authoritative session state; other service failures get a bounded retry.
            if (error.status != 401) scheduleRefresh(epoch, RETRY_MILLIS)
        } catch (_: Exception) {
            // Network or Data Layer errors leave the prior snapshot bounded by the five-minute receiver TTL.
            scheduleRefresh(epoch, RETRY_MILLIS)
        }
    }

    private suspend fun publishUnavailableSafely() {
        val now = nowMillis()
        try {
            publish(
                CoreWearTodaySnapshot(
                    generation = nextGeneration(),
                    generatedAtMillis = now,
                    publishedAtMillis = now,
                    expiresAtMillis = now + CoreWearTodaySnapshot.MAX_AGE_MILLIS,
                    available = false,
                ),
                null,
            )
        } catch (error: CancellationException) {
            // Tasks.await may surface a canceled task even though this process-owned worker is still active.
            currentCoroutineContext().ensureActive()
        } catch (_: Exception) {
            // Receiver TTL is the offline revocation bound when Data Layer delivery is unavailable.
        }
    }

    private fun scheduleRefresh(epoch: Long, delayMillis: Long) {
        scheduledRefresh?.cancel()
        scheduledRefresh = scope.launch {
            delay(delayMillis)
            val active = organizations.state.value as? CoreOrganizationSessionController.State.Active
            if (active?.epoch == epoch) intents.send(Intent.Refresh(epoch))
        }
    }

    override fun close() {
        intents.close()
        job.cancel()
    }

    private companion object {
        const val REFRESH_MILLIS = CoreWearTodaySnapshot.MAX_AGE_MILLIS - 30_000L
        const val RETRY_MILLIS = 60_000L
    }
}
