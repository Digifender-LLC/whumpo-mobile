package com.whumpo.core.auth

import kotlinx.coroutines.sync.Mutex

internal class CoreWorkQueryLeaseOwner { @Volatile var abandoned = false }

/** Coordinates only retained-query cleanup for this exact active session, never task authority. */
internal class CoreWorkQueryLeases {
    val mutex = Mutex()
    private class Lease(val release: suspend () -> Unit)
    private val leases = mutableMapOf<CoreWorkQueryLeaseOwner, Lease>()

    fun own(owner: CoreWorkQueryLeaseOwner, release: suspend () -> Unit) { leases[owner] = Lease(release) }
    suspend fun release(owner: CoreWorkQueryLeaseOwner) {
        val lease = leases[owner] ?: return
        lease.release()
        if (leases[owner] === lease) leases.remove(owner)
    }
    suspend fun releaseAbandoned() {
        for (owner in leases.keys.filter { it.abandoned }) release(owner)
    }
}
