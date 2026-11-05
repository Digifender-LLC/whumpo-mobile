package com.whumpo.android.ui.photos

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** A prepared photo waiting for its upload to be confirmed: the exact bytes, name and ID every retry must repeat. */
class PendingUpload(val id: String, val projectId: String, val name: String, val jpeg: ByteArray)

/**
 * Where prepared uploads survive process death. Contents belong to the signed-in account and are erased with it
 * (sign-out or account change), so private bytes never outlive their owner.
 */
interface UploadStore {
    /** Changes whenever the signed-in account does, including sign-out; collectors drop anything held for the old one. */
    val account: Flow<Any?>

    /**
     * Stores [upload] for [owner], the account value last seen on [account]. False when [owner] is no longer the signed-in
     * account (so one account's bytes can never land in the next one's slot) or the bytes cannot be kept; the upload may
     * still proceed in memory.
     */
    suspend fun save(upload: PendingUpload, owner: Any?): Boolean

    suspend fun load(): List<PendingUpload>

    suspend fun remove(id: String)
}

/** No persistence (previews and tests that do not exercise recovery). */
object NoUploadStore : UploadStore {
    override val account: Flow<Any?> = flowOf(null)
    override suspend fun save(upload: PendingUpload, owner: Any?) = false
    override suspend fun load(): List<PendingUpload> = emptyList()
    override suspend fun remove(id: String) = Unit
}
