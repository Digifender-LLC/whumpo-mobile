package com.whumpo.core.auth

import com.whumpo.core.api.CoreAdaptiveCategory
import com.whumpo.core.api.CoreAdaptiveProfile
import com.whumpo.core.api.CoreOrigin
import com.whumpo.core.api.CoreSession
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant

/** Display order only. A caller must confirm readability and enforce the remaining lease. */
class CoreAdaptiveProjection internal constructor(
    private val order: List<CoreAdaptiveCategory>,
    val remainingMillis: Long,
) {
    fun shortcuts(available: Set<CoreAdaptiveCategory>): List<CoreAdaptiveCategory> =
        order.filter(available::contains).take(2)
}

/** Callers provide an app-private no-backup directory. This file contains no session authority. */
class CoreAdaptiveProfileCache(
    private val directory: File,
    private val wallMillis: () -> Long = System::currentTimeMillis,
) {
    private val file = File(directory, "profiles-v1.bin")
    private val storagePath = file.absoluteFile.toPath().normalize()

    private data class Entry(
        val actor: String,
        val scope: String,
        val serverMillis: Long,
        val observedMillis: Long,
        val order: List<CoreAdaptiveCategory>,
    ) {
        fun remaining(now: Long): Long {
            if (serverMillis <= 0 || serverMillis > observedMillis || observedMillis > now) return 0
            return MAX_AGE - (now - serverMillis)
        }
    }

    fun load(origin: CoreOrigin, session: CoreSession): CoreAdaptiveProjection? = synchronized(lock) {
        val now = wallMillis()
        val entries = observe(now)
        if (session.expiresAt <= now) return@synchronized null
        val scope = digest(origin.value, session.userId, session.orgId)
        val actor = digest(origin.value, session.userId)
        val entry = entries.firstOrNull { it.scope == scope && it.actor == actor }
            ?: return@synchronized null
        if (entry != entries.first() && !write(entry, entries, now)) return@synchronized null
        CoreAdaptiveProjection(entry.order, minOf(entry.remaining(now), session.expiresAt - now))
    }

    internal fun save(origin: CoreOrigin, session: CoreSession, profile: CoreAdaptiveProfile) = synchronized(lock) {
        val now = wallMillis()
        val entries = observe(now)
        if (session.expiresAt <= now || session.userId != profile.userId || session.orgId != profile.orgId) {
            return@synchronized
        }
        val server = runCatching { Instant.parse(profile.serverTime).toEpochMilli() }.getOrNull()
            ?: return@synchronized
        val order = (profile.pins + if (profile.learningEnabled) profile.learnedOrder else CoreAdaptiveCategory.entries)
            .distinct()
        val entry = Entry(digest(origin.value, session.userId), digest(origin.value, session.userId, session.orgId), server, now, order)
        if (entry.remaining(now) <= 0 || order.toSet() != CoreAdaptiveCategory.entries.toSet()) return@synchronized
        if (entries.any { it.scope == entry.scope && it.serverMillis > server }) return@synchronized
        write(entry, entries, now)
        Unit
    }

    /** False means unreadable/undeletable bytes may remain across a process restart. */
    fun eraseActor(origin: CoreOrigin, userId: String) = erase { it.actor == digest(origin.value, userId) }

    /** False has the same storage failure meaning as [eraseActor]. */
    fun eraseScope(origin: CoreOrigin, session: CoreSession) =
        erase { it.scope == digest(origin.value, session.userId, session.orgId) }

    // A failed update falls back to discarding the disposable file. If that also fails,
    // report failure and disable reads from this path for the rest of the process.
    private fun erase(match: (Entry) -> Boolean): Boolean = synchronized(lock) {
        val now = wallMillis()
        if (storagePath in disabledPaths) return@synchronized discard()
        val entries = observe(now)
        if (storagePath in disabledPaths) return@synchronized discard()
        runCatching { persist(entries.filterNot(match), now) }.fold(
            onSuccess = { true },
            onFailure = { discard() },
        )
    }

    private fun write(first: Entry, entries: List<Entry>, now: Long): Boolean {
        val updated = (listOf(first) + entries.filterNot { it.scope == first.scope }).take(CAPACITY)
        return update(updated, now)
    }

    private fun update(entries: List<Entry>, now: Long): Boolean {
        if (storagePath in disabledPaths) return false
        return runCatching { persist(entries, now) }.fold(
            onSuccess = { true },
            onFailure = { discard(); false },
        )
    }

    // Record every observation before callers can return or use the snapshot.
    private fun observe(now: Long): List<Entry> {
        if (storagePath in disabledPaths || !file.exists()) return emptyList()
        return try {
            DataInputStream(file.inputStream().buffered()).use { input ->
                require(input.readInt() == VERSION)
                val observedHighWater = input.readLong()
                require(observedHighWater in 1..now)
                val count = input.readUnsignedByte()
                require(count <= CAPACITY)
                val scopes = HashSet<String>()
                val entries = List(count) {
                    val actor = input.readDigest()
                    val scope = input.readDigest()
                    require(scopes.add(scope))
                    val server = input.readLong()
                    val observed = input.readLong()
                    require(observed in 1..observedHighWater)
                    // UTF lengths are checked before allocation, including for corrupt files.
                    val order = List(CoreAdaptiveCategory.entries.size) {
                        val length = input.readUnsignedByte()
                        require(length in 1..32)
                        CoreAdaptiveCategory.valueOf(ByteArray(length).also(input::readFully).decodeToString(throwOnInvalidSequence = true))
                    }
                    require(order.toSet() == CoreAdaptiveCategory.entries.toSet())
                    Entry(actor, scope, server, observed, order)
                }
                require(input.read() == -1)
                entries.filter { it.remaining(now) > 0 }.also { fresh ->
                    if ((observedHighWater != now || fresh.size != entries.size) && !update(fresh, now)) {
                        return emptyList()
                    }
                }
            }
        } catch (_: Exception) {
            discard()
            emptyList()
        }
    }

    private fun persist(entries: List<Entry>, now: Long) {
        if (entries.isEmpty()) {
            Files.deleteIfExists(file.toPath())
            return
        }
        val directoryPath = directory.toPath()
        Files.createDirectories(directoryPath)
        val temporary = Files.createTempFile(directoryPath, "profile-", ".tmp")
        try {
            FileOutputStream(temporary.toFile()).use { output ->
                val data = DataOutputStream(output)
                data.writeInt(VERSION)
                data.writeLong(now)
                data.writeByte(entries.size)
                for (entry in entries) {
                    data.write(entry.actor.hexToByteArray())
                    data.write(entry.scope.hexToByteArray())
                    data.writeLong(entry.serverMillis)
                    data.writeLong(entry.observedMillis)
                    for (category in entry.order) {
                        val bytes = category.name.encodeToByteArray()
                        data.writeByte(bytes.size)
                        data.write(bytes)
                    }
                }
                output.fd.sync()
            }
            Files.move(temporary, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun discard(): Boolean = runCatching { Files.deleteIfExists(file.toPath()) }.fold(
        onSuccess = { true },
        onFailure = { disabledPaths.add(storagePath); false },
    )

    private fun DataInputStream.readDigest(): String = ByteArray(32).also(::readFully).toHexString()

    private fun digest(vararg parts: String): String {
        val hash = MessageDigest.getInstance("SHA-256")
        for (part in parts) {
            val bytes = part.encodeToByteArray()
            hash.update(java.nio.ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
            hash.update(bytes)
        }
        return hash.digest().toHexString()
    }

    private companion object {
        const val VERSION = 1
        // The fixed codec allows at most 212 bytes per entry, below the 16 KiB budget.
        const val CAPACITY = 8
        const val MAX_AGE = 86_400_000L
        val lock = Any()
        val disabledPaths = mutableSetOf<java.nio.file.Path>()
    }
}
