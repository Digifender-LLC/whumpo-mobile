package com.whumpo.wear

import android.content.Context
import com.whumpo.core.wear.CoreWearTodaySnapshot
import com.whumpo.core.wear.acceptsCoreWearTodaySnapshot

/** Keeps only one authenticated, short-lived aggregate snapshot in app-private, non-backed-up storage. */
internal class WearTodaySnapshotStorage(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun read(nowMillis: Long): CoreWearTodaySnapshot? {
        val encoded = preferences.getString(SNAPSHOT_KEY, null) ?: return null
        val snapshot = runCatching { CoreWearTodaySnapshot.decode(encoded.encodeToByteArray()) }.getOrNull() ?: return null
        return snapshot.takeIf { it.available && it.validAt(nowMillis) }
    }

    fun apply(
        sourceNodeId: String?,
        phoneCapabilityNodeIds: Set<String>,
        encodedSnapshot: ByteArray,
        nowMillis: Long,
    ): Boolean {
        if (sourceNodeId.isNullOrBlank() || sourceNodeId !in phoneCapabilityNodeIds) return false
        val incoming = runCatching { CoreWearTodaySnapshot.decode(encodedSnapshot) }.getOrNull() ?: return false
        val current = preferences.getString(SNAPSHOT_KEY, null)?.let { encoded ->
            runCatching { CoreWearTodaySnapshot.decode(encoded.encodeToByteArray()) }.getOrNull()
        }
        if (!acceptsCoreWearTodaySnapshot(incoming, current, senderIsPhoneCapabilityNode = true, nowMillis)) return false
        return preferences.edit()
            .putString(SNAPSHOT_KEY, CoreWearTodaySnapshot.encode(incoming).decodeToString())
            .commit()
    }

    private companion object {
        const val PREFERENCES = "wear-today-snapshot"
        const val SNAPSHOT_KEY = "snapshot-v1"
    }
}
