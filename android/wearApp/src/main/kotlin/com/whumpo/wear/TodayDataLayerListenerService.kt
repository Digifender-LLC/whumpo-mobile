package com.whumpo.wear

import androidx.wear.tiles.TileService
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.whumpo.core.wear.CoreWearTodaySnapshot
import com.whumpo.core.wear.CoreWearTodaySnapshotStore
import java.util.concurrent.TimeUnit

/** Applies only current snapshots from a native phone-app capability node, then invalidates the tile. */
class TodayDataLayerListenerService : WearableListenerService() {
    override fun onDataChanged(dataEvents: DataEventBuffer) {
        val updates = try {
            buildList {
                for (event in dataEvents) {
                    if (event.type != DataEvent.TYPE_CHANGED) continue
                    val item = event.dataItem
                    val uri = item.uri
                    if (uri.scheme != "wear" || uri.path != CoreWearTodaySnapshot.DATA_PATH) continue
                    val payload = item.data ?: continue
                    if (payload.size !in 1..1024) continue
                    add(uri.host to payload.copyOf())
                }
            }
        } finally {
            dataEvents.release()
        }
        if (updates.isEmpty()) return

        val allowedPhoneNodes = try {
            val capability = Tasks.await(
                Wearable.getCapabilityClient(this).getCapability(
                    CoreWearTodaySnapshot.PHONE_CAPABILITY,
                    CapabilityClient.FILTER_ALL,
                ),
                5,
                TimeUnit.SECONDS,
            )
            capability.nodes.mapTo(mutableSetOf()) { it.id }
        } catch (_: Exception) {
            return
        }
        if (allowedPhoneNodes.isEmpty()) return

        val storage = WearTodaySnapshotStorage(this)
        val now = System.currentTimeMillis()
        val accepted = storage.applyBatch(
            updates.map { (sourceNodeId, payload) ->
                CoreWearTodaySnapshotStore.Update(sourceNodeId, payload)
            },
            allowedPhoneNodes,
            now,
        )
        if (accepted) TileService.getUpdater(this).requestUpdate(TodayTileService::class.java)
    }
}
