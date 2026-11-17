package com.whumpo.core.account

import com.whumpo.core.api.CoreNewTask
import com.whumpo.core.api.CoreTaskCommandAcceptance
import com.whumpo.core.api.CoreTaskCommandStatus
import com.whumpo.core.api.coreIdentifier
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One sealed draft per verified Core scope. Callers supply a private, no-backup directory. */
class CoreOfflineWorkStore(
    private val directory: File,
    secretBox: () -> SecretBox,
) {
    private val box by lazy(secretBox)

    internal fun read(scope: CoreAccountScope): OfflineWorkDraft? = synchronized(operationLock) {
        val file = file(scope)
        if (!file.exists()) return@synchronized null
        protect {
            val sealed = file.inputStream().use { input ->
                val bytes = ByteArray(MAX_BYTES + 1)
                var length = 0
                while (length < bytes.size) {
                    val count = input.read(bytes, length, bytes.size - length)
                    if (count < 0) break
                    length += count
                }
                bytes.copyOf(length)
            }
            require(sealed.size in 1..MAX_BYTES)
            Json.decodeFromString<OfflineWorkDraft>(box.open(sealed, aad(scope)).decodeToString(throwOnInvalidSequence = true))
                .also(OfflineWorkDraft::verify)
        }
    }

    internal fun replace(scope: CoreAccountScope, expected: OfflineWorkDraft?, draft: OfflineWorkDraft): Boolean = synchronized(operationLock) {
        if (read(scope) != expected) return@synchronized false
        protect {
            draft.verify()
            val sealed = box.seal(Json.encodeToString(draft).encodeToByteArray(), aad(scope))
            require(sealed.size in 1..MAX_BYTES)
            Files.createDirectories(directory.toPath())
            val temporary = Files.createTempFile(directory.toPath(), "work-", ".tmp")
            try {
                FileOutputStream(temporary.toFile()).use { output ->
                    output.write(sealed)
                    output.fd.sync()
                }
                Files.move(temporary, file(scope).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally {
                Files.deleteIfExists(temporary)
            }
            true
        }
    }

    internal fun remove(scope: CoreAccountScope, expected: OfflineWorkDraft): Boolean = synchronized(operationLock) {
        read(scope) == expected && protect { Files.deleteIfExists(file(scope).toPath()) }
    }

    private fun file(scope: CoreAccountScope): File = File(directory, "${scope.storageKey}.bin")

    private fun aad(scope: CoreAccountScope) = "whumpo-offline-work-v1:${scope.storageKey}".encodeToByteArray()

    private inline fun <T> protect(operation: () -> T): T = try {
        operation()
    } catch (_: Exception) {
        throw OfflineWorkStorageException()
    }

    private companion object {
        const val MAX_BYTES = 262_144
        val operationLock = Any()
    }
}

internal class OfflineWorkStorageException : IOException("Could not access the saved task draft")

@Serializable
internal data class OfflineWorkDraft(
    val title: String,
    val description: String,
    val pending: OfflineWorkPending? = null,
) {
    fun verify() {
        require(title.isNotBlank() && title.validOfflineText(200) && description.validOfflineText(10_000))
        pending?.let {
            val decoded = it.decode()
            require(decoded.title == title && decoded.description.orEmpty() == description)
        }
    }
}

@Serializable
internal data class OfflineWorkPending(
    val workspaceId: String,
    val projectId: String,
    val key: String,
    val body: String,
    val acceptance: CoreTaskCommandAcceptance? = null,
) {
    fun decode(): CoreNewTask {
        require(coreIdentifier.matches(workspaceId) && coreIdentifier.matches(projectId) && coreIdentifier.matches(key))
        acceptance?.let { require(it.isOfflineAcceptance()) }
        return Json.decodeFromString<CoreNewTask>(body).also {
            require(it.projectId == projectId && Json.encodeToString(it) == body)
        }
    }
}

internal fun String.validOfflineText(limit: Int): Boolean =
    length <= limit * 2 && codePointCount(0, length) <= limit && codePoints().noneMatch { it == 0 || it in 0xD800..0xDFFF }

internal fun CoreTaskCommandAcceptance.isOfflineAcceptance(): Boolean =
    coreIdentifier.matches(runId) && coreIdentifier.matches(effectId) &&
        when (status) {
            CoreTaskCommandStatus.Queued -> approvalId == null
            CoreTaskCommandStatus.WaitingApproval -> approvalId?.let(coreIdentifier::matches) == true
        }
