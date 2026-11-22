package com.whumpo.wear

import android.content.Context
import com.whumpo.core.wear.CoreWearTodaySnapshot
import com.whumpo.core.wear.CoreWearTodaySnapshotStore

/** Keeps only one authenticated, short-lived aggregate snapshot in app-private, non-backed-up storage. */
internal class WearTodaySnapshotStorage(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val store = CoreWearTodaySnapshotStore(
        loadEncoded = { preferences.getString(SNAPSHOT_KEY, null)?.encodeToByteArray() },
        saveEncoded = { encoded ->
            preferences.edit().putString(SNAPSHOT_KEY, encoded.decodeToString()).commit()
        },
    )

    fun read(nowMillis: Long): CoreWearTodaySnapshot? = store.read(nowMillis)

    fun apply(
        sourceNodeId: String?,
        phoneCapabilityNodeIds: Set<String>,
        encodedSnapshot: ByteArray,
        nowMillis: Long,
    ): Boolean = store.apply(sourceNodeId, phoneCapabilityNodeIds, encodedSnapshot, nowMillis)

    fun applyBatch(
        updates: Iterable<CoreWearTodaySnapshotStore.Update>,
        phoneCapabilityNodeIds: Set<String>,
        nowMillis: Long,
    ): Boolean = store.applyBatch(updates, phoneCapabilityNodeIds, nowMillis)

    private companion object {
        const val PREFERENCES = "wear-today-snapshot"
        const val SNAPSHOT_KEY = "snapshot-v1"
    }
}
