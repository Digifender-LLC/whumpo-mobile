package com.whumpo.core.auth

import com.whumpo.core.api.CoreApiClient
import com.whumpo.core.api.CoreAttachmentMetadata
import com.whumpo.core.api.CoreAttachmentVersion
import com.whumpo.core.api.CoreHttpException
import com.whumpo.core.api.CoreProtocolException
import com.whumpo.core.api.CoreRole
import com.whumpo.core.api.CoreUploadState
import com.whumpo.core.api.CoreWorkTask
import com.whumpo.core.api.coreIdentifier
import java.security.MessageDigest
import java.text.Normalizer
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class CoreWorkAttachmentFile(name: String, mediaType: String, bytes: ByteArray) {
    companion object {
        const val MaximumBytes = 4 * 1024 * 1024
        private val extensions = mapOf(
            "application/pdf" to setOf("pdf"),
            "image/jpeg" to setOf("jpg", "jpeg"),
            "image/png" to setOf("png"),
            "text/plain" to setOf("txt"),
        )
        val allowedMediaTypes: Set<String> get() = extensions.keys
    }

    private val canonicalName = Normalizer.normalize(name.trim(), Normalizer.Form.NFC)
    internal val metadata: CoreAttachmentMetadata
    internal val payload: ByteArray

    init {
        require(bytes.size in 1..MaximumBytes)
        require(canonicalName.isNotBlank() && canonicalName.length <= 200 &&
            name.none { Character.isISOControl(it) || it == '/' || it == '\\' })
        require(canonicalName.substringAfterLast('.', "").lowercase() in extensions[mediaType].orEmpty())
        require(matchesMedia(bytes, mediaType))
        payload = bytes.copyOf()
        val digest = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
        metadata = CoreAttachmentMetadata(canonicalName, mediaType, payload.size, digest)
    }

    private fun matchesMedia(bytes: ByteArray, mediaType: String): Boolean = when (mediaType) {
        "application/pdf" -> bytes.hasPrefix(0x25, 0x50, 0x44, 0x46, 0x2d)
        "image/jpeg" -> bytes.hasPrefix(0xff, 0xd8, 0xff)
        "image/png" -> bytes.hasPrefix(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        "text/plain" -> bytes.none { it == 0.toByte() } &&
            runCatching { bytes.decodeToString(throwOnInvalidSequence = true) }.isSuccess
        else -> false
    }

    private fun ByteArray.hasPrefix(vararg prefix: Int): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it].toByte() }
}

enum class CoreAttachmentNotice { InvalidFile, CouldNotConfirm, DestinationChanged, ReservationExpired }

data class CoreWorkAttachmentState(
    val fileName: String? = null,
    val byteCount: Int? = null,
    val busy: Boolean = false,
    val saved: Boolean = false,
    val attached: Boolean = false,
    val canRetry: Boolean = false,
    val notice: CoreAttachmentNotice? = null,
)

/** Owns one explicit attachment to an exact task in one connected organization session. */
class CoreWorkAttachmentController(
    private val sessions: CoreOrganizationSessionController,
    private val active: CoreOrganizationSessionController.State.Active,
    private val task: CoreWorkTask,
) {
    private class DestinationChanged : Exception()
    private class ReservationExpired : Exception()

    private class Attempt(
        var file: CoreWorkAttachmentFile?,
        val metadata: String,
        val linkBody: String,
        val reserveKey: String = UUID.randomUUID().toString(),
        val linkKey: String = UUID.randomUUID().toString(),
        var saved: CoreAttachmentVersion? = null,
    )

    private val mutableState = MutableStateFlow(CoreWorkAttachmentState())
    val state = mutableState.asStateFlow()
    private val running = Mutex()
    private var selected: CoreWorkAttachmentFile? = null
    private var attempt: Attempt? = null
    @Volatile private var retired = false

    private val eligible get() = active.session.role != CoreRole.viewer && task.archivedAt == null

    fun selected(file: CoreWorkAttachmentFile) = update {
        if (!eligible || attempt != null || state.value.busy || state.value.attached) return@update
        selected = file
        mutableState.value = CoreWorkAttachmentState(file.metadata.fileName, file.metadata.byteLength)
    }

    fun invalidSelection() = update {
        if (attempt == null) {
            selected = null
            mutableState.value = CoreWorkAttachmentState(notice = CoreAttachmentNotice.InvalidFile)
        }
    }

    fun retire() {
        retired = true
        selected = null
        attempt = null
        mutableState.value = CoreWorkAttachmentState()
    }

    suspend fun confirm() = run(false)
    suspend fun retry() = run(true)

    private suspend fun run(retry: Boolean) {
        if (!eligible || retired || !running.tryLock()) return
        try {
            requireCurrent()
            val pending = if (retry) {
                attempt?.takeIf { state.value.canRetry } ?: return
            } else {
                val file = selected?.takeIf { attempt == null && !state.value.attached } ?: return
                Attempt(file, Json.encodeToString(file.metadata),
                    buildJsonObject { put("targetType", "task"); put("targetId", task.id) }.toString()).also {
                    attempt = it
                    selected = null
                }
            }
            update { mutableState.value = state.value.copy(busy = true, canRetry = false, notice = null) }
            recheckDestination()
            val file = pending.saved ?: save(pending)
            requireCurrent()
            pending.saved = file
            pending.file = null
            recheckDestination()
            update { mutableState.value = state.value.copy(saved = true) }
            val link = request { api, token -> api.attachTaskFile(token, file, pending.linkBody, pending.linkKey) }
            if (!link.matches(file, task.id) || runCatching { Instant.parse(link.createdAt) }.isFailure) {
                throw CoreProtocolException()
            }
            val readback = request { api, token -> api.taskFileLinks(token, file) }
            if (readback.items.size > 100 || readback.items.filter { it.id == link.id } != listOf(link)) {
                throw CoreProtocolException()
            }
            recheckDestination()
            update {
                attempt = null
                mutableState.value = state.value.copy(attached = true)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            update {
                when (error) {
                    is DestinationChanged -> {
                        attempt = null
                        selected = null
                        mutableState.value = CoreWorkAttachmentState(notice = CoreAttachmentNotice.DestinationChanged)
                    }
                    is ReservationExpired -> {
                        attempt = null
                        mutableState.value = CoreWorkAttachmentState(notice = CoreAttachmentNotice.ReservationExpired)
                    }
                    else -> mutableState.value = state.value.copy(
                        canRetry = attempt != null,
                        notice = CoreAttachmentNotice.CouldNotConfirm,
                    )
                }
            }
        } finally {
            update { mutableState.value = state.value.copy(busy = false) }
            running.unlock()
        }
    }

    private suspend fun save(attempt: Attempt): CoreAttachmentVersion {
        val file = checkNotNull(attempt.file)
        val reservation = request { api, token -> api.reserveTaskFile(token, attempt.metadata, attempt.reserveKey) }
        if (!coreIdentifier.matches(reservation.uploadId) || !coreIdentifier.matches(reservation.fileId) ||
            reservation.version != 1L || reservation.state != CoreUploadState.reserved ||
            !reservation.matches(file.metadata) || runCatching { Instant.parse(reservation.expiresAt) }.isFailure
        ) throw CoreProtocolException()
        val status = request { api, token -> api.taskFileUpload(token, reservation.uploadId) }
        if (!status.sameReservation(reservation) || !status.matches(file.metadata)) throw CoreProtocolException()
        if (status.state == CoreUploadState.expired) throw ReservationExpired()
        if (status.state == CoreUploadState.reserved) {
            recheckDestination()
            val uploaded = request { api, token -> api.sendTaskFile(token, reservation.uploadId, file.payload) }
            if (!uploaded.sameReservation(reservation) || !uploaded.matches(file.metadata) ||
                uploaded.state !in setOf(CoreUploadState.uploaded, CoreUploadState.committed)
            ) throw CoreProtocolException()
        }
        recheckDestination()
        val committed = request { api, token -> api.finalizeTaskFile(token, reservation.uploadId) }
        if (!committed.matches(reservation) || runCatching { Instant.parse(committed.createdAt) }.isFailure) {
            throw CoreProtocolException()
        }
        return committed
    }

    private suspend fun recheckDestination() {
        try {
            val identity = request { api, token -> api.session(token) }
            if (identity != active.session) throw DestinationChanged()
            val currentTask = request { api, token -> api.task(token, task.id) }
            if (currentTask.id != task.id || currentTask.projectId != task.projectId ||
                currentTask.version != task.version || currentTask.title != task.title || currentTask.archivedAt != null
            ) throw DestinationChanged()
        } catch (error: CoreHttpException) {
            if (error.status in setOf(403, 404, 423)) throw DestinationChanged()
            throw error
        }
    }

    private suspend fun <T> request(operation: suspend (CoreApiClient, String) -> T): T {
        requireCurrent()
        return sessions.requestForActiveSession(active, operation).also { requireCurrent() }
    }

    private suspend fun requireCurrent() {
        currentCoroutineContext().ensureActive()
        if (retired || !sessions.commitIfCurrent(active.epoch) {}) throw CancellationException("Attachment view changed")
    }

    private fun update(action: () -> Unit) {
        sessions.commitIfCurrent(active.epoch) { if (!retired) action() }
    }
}
