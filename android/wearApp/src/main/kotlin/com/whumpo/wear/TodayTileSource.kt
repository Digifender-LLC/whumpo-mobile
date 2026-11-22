package com.whumpo.wear

import android.content.Context
import android.net.Uri
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.Wearable
import com.whumpo.core.wear.CoreWearTodaySnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.concurrent.TimeUnit

internal object TodayTileSource {
    suspend fun refresh(context: Context) {
        if (BuildConfig.TILE_FIXTURE.isNotEmpty()) return
        withContext(Dispatchers.IO) {
            val dataClient = Wearable.getDataClient(context.applicationContext)
            try {
                Tasks.await(
                    GoogleApiAvailability.getInstance().checkApiAvailability(dataClient),
                    3,
                    TimeUnit.SECONDS,
                )
                val allowedPhoneNodes = Tasks.await(
                    Wearable.getCapabilityClient(context.applicationContext).getCapability(
                        CoreWearTodaySnapshot.PHONE_CAPABILITY,
                        CapabilityClient.FILTER_ALL,
                    ),
                    3,
                    TimeUnit.SECONDS,
                ).nodes.mapTo(mutableSetOf()) { it.id }
                if (allowedPhoneNodes.isEmpty()) return@withContext
                val uri = Uri.parse("wear://*/${CoreWearTodaySnapshot.DATA_PATH.removePrefix("/")}")
                val items = Tasks.await(dataClient.getDataItems(uri, DataClient.FILTER_LITERAL), 3, TimeUnit.SECONDS)
                try {
                    val storage = WearTodaySnapshotStorage(context)
                    val now = System.currentTimeMillis()
                    for (item in items) {
                        val payload = item.data ?: continue
                        if (payload.size !in 1..1024 || item.uri.path != CoreWearTodaySnapshot.DATA_PATH) continue
                        storage.apply(item.uri.host, allowedPhoneNodes, payload, now)
                    }
                } finally {
                    items.release()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Tiles remain unavailable when Data Layer access is missing; stored counts still expire locally.
            }
        }
    }

    fun snapshot(context: Context): TodayTileSnapshot? {
        fixtureSnapshot()?.let { return it }
        val wire = WearTodaySnapshotStorage(context).read(System.currentTimeMillis()) ?: return null
        return TodayTileSnapshot(
            generatedAt = Instant.ofEpochMilli(wire.generatedAtMillis),
            counts = TodayCounts(
                TodayCount(wire.approvals, wire.approvalsTruncated),
                TodayCount(wire.blocked, wire.blockedTruncated),
                TodayCount(wire.deadlines, wire.deadlinesTruncated),
            ),
            expiresAtMillis = wire.expiresAtMillis,
        )
    }

    private fun fixtureSnapshot(): TodayTileSnapshot? {
        val now = System.currentTimeMillis()
        return when (BuildConfig.TILE_FIXTURE) {
            "fresh" -> TodayTileSnapshot(
                Instant.ofEpochMilli(now),
                TodayCounts(TodayCount(2), TodayCount(1), TodayCount(3)),
                expiresAtMillis = now + CoreWearTodaySnapshot.MAX_AGE_MILLIS,
            )
            "empty" -> TodayTileSnapshot(
                Instant.ofEpochMilli(now),
                TodayCounts(TodayCount(0), TodayCount(0), TodayCount(0)),
                expiresAtMillis = now + CoreWearTodaySnapshot.MAX_AGE_MILLIS,
            )
            "expired" -> TodayTileSnapshot(
                Instant.ofEpochMilli(now - CoreWearTodaySnapshot.MAX_AGE_MILLIS - 1),
                TodayCounts(TodayCount(2), TodayCount(1), TodayCount(3)),
                expiresAtMillis = now - 1,
            )
            else -> null
        }
    }
}
