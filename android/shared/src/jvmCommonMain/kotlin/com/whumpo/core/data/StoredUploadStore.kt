package com.whumpo.core.data

import com.whumpo.android.ui.photos.PendingUpload
import com.whumpo.android.ui.photos.UploadStore
import com.whumpo.core.account.AccountStateStore
import com.whumpo.core.account.StateKind
import com.whumpo.core.account.StateSlot
import java.util.Base64
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Prepared uploads in the encrypted account store (no-backup, key erased on sign-out). At most [MAX_PENDING] at a time. */
class StoredUploadStore(
    private val store: AccountStateStore,
    private val io: CoroutineDispatcher,
    private val clock: () -> Long = System::currentTimeMillis,
) : UploadStore {
    override val account: Flow<Any?> = store.activeAccount

    @Serializable
    private class Record(val projectId: String, val name: String, val jpeg: String, val savedAt: Long = 0)

    override suspend fun save(upload: PendingUpload, owner: Any?): Boolean = withContext(io) {
        val current = store.activeAccount.value ?: return@withContext false
        if (current != owner) return@withContext false
        val slot = slot(upload.id) ?: return@withContext false
        if (upload.jpeg.size > MAX_BYTES) return@withContext false
        try {
            val held = store.list(current, StateKind.UPLOAD)
            if (slot.name !in held && held.size >= MAX_PENDING) return@withContext false
            val record = Record(upload.projectId, upload.name, Base64.getEncoder().encodeToString(upload.jpeg), clock())
            store.write(current, slot, Json.encodeToString(Record.serializer(), record))
        } catch (_: Exception) {
            // IOException, or a Keystore/crypto failure while sealing: the bytes are simply not kept. The caller carries on in memory.
            false
        }
    }

    override suspend fun load(): List<PendingUpload> = withContext(io) {
        val owner = store.activeAccount.value ?: return@withContext emptyList()
        store.list(owner, StateKind.UPLOAD).mapNotNull { name ->
            val slot = StateSlot(StateKind.UPLOAD, name)
            val text = store.read(owner, slot) ?: return@mapNotNull null
            try {
                val r = Json.decodeFromString(Record.serializer(), text)
                if (clock() - r.savedAt > MAX_AGE_MILLIS) { // a stuck upload must not hold a slot forever
                    store.remove(owner, slot)
                    return@mapNotNull null
                }
                PendingUpload(name.removePrefix(PREFIX), r.projectId, r.name, Base64.getDecoder().decode(r.jpeg))
            } catch (_: Exception) {
                store.remove(owner, slot) // unreadable: never trusted
                null
            }
        }
    }

    override suspend fun remove(id: String) {
        withContext(io) {
            val owner = store.activeAccount.value ?: return@withContext
            slot(id)?.let { store.remove(owner, it) }
        }
    }

    /** Upload IDs are UUIDs; anything else cannot name a slot. */
    private fun slot(id: String): StateSlot? =
        (PREFIX + id.lowercase()).takeIf { StateSlot.isValidName(it) }?.let { StateSlot(StateKind.UPLOAD, it) }

    companion object {
        private const val PREFIX = "u-"
        const val MAX_PENDING = 5
        const val MAX_BYTES = 1_048_576

        /** Seven days: long enough to retry after a bad week, short enough that dead uploads free their slot. */
        const val MAX_AGE_MILLIS = 7L * 24 * 60 * 60 * 1000
    }
}
