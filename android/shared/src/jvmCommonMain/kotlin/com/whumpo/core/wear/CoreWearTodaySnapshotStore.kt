package com.whumpo.core.wear

/** Process-wide serialized read/compare/commit so an older callback cannot overwrite a newer snapshot. */
class CoreWearTodaySnapshotStore(
    private val loadEncoded: () -> ByteArray?,
    private val saveEncoded: (ByteArray) -> Boolean,
) {
    data class Update(val sourceNodeId: String?, val encodedSnapshot: ByteArray)

    fun read(nowMillis: Long): CoreWearTodaySnapshot? = synchronized(processLock) {
        val encoded = loadEncoded() ?: return@synchronized null
        runCatching { CoreWearTodaySnapshot.decode(encoded) }
            .getOrNull()
            ?.takeIf { it.available && it.validAt(nowMillis) }
    }

    fun apply(
        sourceNodeId: String?,
        phoneCapabilityNodeIds: Set<String>,
        encodedSnapshot: ByteArray,
        nowMillis: Long,
    ): Boolean = synchronized(processLock) {
        if (sourceNodeId.isNullOrBlank() || sourceNodeId !in phoneCapabilityNodeIds) return@synchronized false
        val incoming = runCatching { CoreWearTodaySnapshot.decode(encodedSnapshot) }.getOrNull() ?: return@synchronized false
        val current = loadEncoded()?.let { bytes -> runCatching { CoreWearTodaySnapshot.decode(bytes) }.getOrNull() }
        if (!acceptsCoreWearTodaySnapshot(incoming, current, senderIsPhoneCapabilityNode = true, nowMillis)) {
            return@synchronized false
        }
        saveEncoded(CoreWearTodaySnapshot.encode(incoming))
    }

    /** Applies every update in order; an accepted early row must not skip a later tombstone. */
    fun applyBatch(
        updates: Iterable<Update>,
        phoneCapabilityNodeIds: Set<String>,
        nowMillis: Long,
    ): Boolean = synchronized(processLock) {
        var changed = false
        for (update in updates) {
            val accepted = apply(update.sourceNodeId, phoneCapabilityNodeIds, update.encodedSnapshot, nowMillis)
            changed = accepted || changed
        }
        changed
    }

    private companion object {
        val processLock = Any()
    }
}
