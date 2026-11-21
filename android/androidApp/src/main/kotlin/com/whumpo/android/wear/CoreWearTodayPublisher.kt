package com.whumpo.android.wear

import android.content.Context
import android.util.AtomicFile
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.whumpo.core.api.CoreHttpException
import com.whumpo.core.auth.CoreOrganizationSessionController
import com.whumpo.core.wear.CoreWearGenerationCounter
import com.whumpo.core.wear.CoreWearTodaySnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Serializes app-process session changes into one persistent, counts-only Wear DataItem. */
internal class CoreWearTodayPublisher(
    context: Context,
    private val organizations: CoreOrganizationSessionController,
    processScope: CoroutineScope,
) : AutoCloseable {
    private sealed interface Intent {
        data object Invalidate : Intent
        data class Refresh(val epoch: Long) : Intent
    }

    private val appContext = context.applicationContext
    private val dataClient = Wearable.getDataClient(appContext)
    private val capabilityClient = Wearable.getCapabilityClient(appContext)
    private val generation = AtomicWearGeneration(File(appContext.noBackupFilesDir, "wear-today-generation"))
    private val parentJob = processScope.coroutineContext[Job]
    private val job = SupervisorJob(parentJob)
    private val scope = CoroutineScope(processScope.coroutineContext + job + Dispatchers.IO)
    private val intents = Channel<Intent>(Channel.BUFFERED)
    private var scheduledRefresh: Job? = null

    private val watchCapabilityListener = CapabilityClient.OnCapabilityChangedListener { info: CapabilityInfo ->
        if (info.nodes.isNotEmpty()) refreshToday()
    }

    init {
        capabilityClient.addListener(watchCapabilityListener, CoreWearTodaySnapshot.WATCH_CAPABILITY)
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
                    Intent.Invalidate -> {
                        scheduledRefresh?.cancel()
                        scheduledRefresh = null
                        publishUnavailable()
                    }
                    is Intent.Refresh -> refresh(intent.epoch)
                }
            }
        }
    }

    /** Called when the paired Wear app becomes reachable while this process still owns an active session. */
    fun refreshToday() {
        scope.launch {
            val active = organizations.state.value as? CoreOrganizationSessionController.State.Active ?: return@launch
            intents.send(Intent.Refresh(active.epoch))
        }
    }

    private suspend fun refresh(epoch: Long) {
        val active = organizations.state.value as? CoreOrganizationSessionController.State.Active ?: return
        if (active.epoch != epoch) return
        try {
            val today = organizations.today()
            currentCoroutineContext().ensureActive()
            if (organizations.state.value !== active || today.epoch != epoch) return
            val briefing = today.briefing
            val generatedAt = Instant.parse(briefing.generatedAt).toEpochMilli()
            val publishedAt = System.currentTimeMillis()
            val snapshot = CoreWearTodaySnapshot.fromBriefing(
                briefing = briefing,
                generation = generation.next(),
                generatedAtMillis = generatedAt,
                publishedAtMillis = publishedAt,
            )
            if (!snapshot.validAt(System.currentTimeMillis())) {
                publishUnavailable()
                scheduleRefresh(epoch, RETRY_MILLIS)
                return
            }
            currentCoroutineContext().ensureActive()
            if (organizations.state.value !== active) return
            put(snapshot)
            scheduleRefresh(epoch, REFRESH_MILLIS)
        } catch (error: CancellationException) {
            // A stale epoch is canceled by the controller; only cancellation of this process scope stops the actor.
            currentCoroutineContext().ensureActive()
        } catch (error: CoreHttpException) {
            // A 401 changes the authoritative session state; other service failures get a bounded retry.
            if (error.status != 401) scheduleRefresh(epoch, RETRY_MILLIS)
        } catch (_: Exception) {
            // Network or Data Layer errors leave the prior snapshot bounded by the five-minute receiver TTL.
            scheduleRefresh(epoch, RETRY_MILLIS)
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

    private suspend fun publishUnavailable() {
        val now = System.currentTimeMillis()
        put(
            CoreWearTodaySnapshot(
                generation = generation.next(),
                generatedAtMillis = now,
                publishedAtMillis = now,
                expiresAtMillis = now + CoreWearTodaySnapshot.MAX_AGE_MILLIS,
                available = false,
            ),
        )
    }

    private suspend fun put(snapshot: CoreWearTodaySnapshot) = withContext(Dispatchers.IO) {
        Tasks.await(
            GoogleApiAvailability.getInstance().checkApiAvailability(dataClient),
            5,
            TimeUnit.SECONDS,
        )
        val request = PutDataMapRequest.create(CoreWearTodaySnapshot.DATA_PATH).apply {
            dataMap.putByteArray(DATA_KEY, CoreWearTodaySnapshot.encode(snapshot))
        }.asPutDataRequest().setUrgent()
        Tasks.await(dataClient.putDataItem(request), 10, TimeUnit.SECONDS)
    }

    override fun close() {
        capabilityClient.removeListener(watchCapabilityListener, CoreWearTodaySnapshot.WATCH_CAPABILITY)
        intents.close()
        job.cancel()
    }

    private class AtomicWearGeneration(path: File) {
        private val file = AtomicFile(path)
        private val counter = CoreWearGenerationCounter(::read, ::persist)

        fun next(): Long = counter.next()

        private fun read(): Long = if (!file.baseFile.exists()) 0L else DataInputStream(file.openRead()).use { input ->
                val value = input.readLong()
                check(input.read() == -1) { "Invalid persisted Wear generation" }
                value
            }

        private fun persist(next: Long): Boolean {
            val output = file.startWrite()
            try {
                DataOutputStream(output).apply { writeLong(next); flush() }
                file.finishWrite(output)
            } catch (error: Exception) {
                file.failWrite(output)
                throw error
            }
            return true
        }
    }

    private companion object {
        const val DATA_KEY = "snapshot"
        const val REFRESH_MILLIS = CoreWearTodaySnapshot.MAX_AGE_MILLIS - 30_000L
        const val RETRY_MILLIS = 60_000L

    }
}
