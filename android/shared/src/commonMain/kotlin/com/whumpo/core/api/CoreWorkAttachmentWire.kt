package com.whumpo.core.api

import kotlinx.serialization.Serializable

@Serializable
internal data class CoreAttachmentMetadata(
    val fileName: String,
    val mediaType: String,
    val byteLength: Int,
    val sha256: String,
)

@Serializable
internal enum class CoreUploadState { reserved, uploaded, committed, expired }

@Serializable
internal data class CoreAttachmentUpload(
    val uploadId: String,
    val fileId: String,
    val version: Long,
    val state: CoreUploadState,
    val expiresAt: String,
    val fileName: String,
    val mediaType: String,
    val byteLength: Int,
    val sha256: String,
) {
    fun matches(metadata: CoreAttachmentMetadata): Boolean =
        fileName == metadata.fileName && mediaType == metadata.mediaType &&
            byteLength == metadata.byteLength && sha256 == metadata.sha256

    fun sameReservation(original: CoreAttachmentUpload): Boolean =
        uploadId == original.uploadId && fileId == original.fileId &&
            version == original.version && expiresAt == original.expiresAt
}

@Serializable
internal data class CoreAttachmentVersion(
    val fileId: String,
    val version: Long,
    val fileName: String,
    val mediaType: String,
    val byteLength: Int,
    val sha256: String,
    val sourceKind: String,
    val createdAt: String,
) {
    fun matches(upload: CoreAttachmentUpload): Boolean =
        fileId == upload.fileId && version == upload.version && fileName == upload.fileName &&
            mediaType == upload.mediaType && byteLength == upload.byteLength && sha256 == upload.sha256 &&
            sourceKind == "upload"
}

@Serializable
internal data class CoreAttachmentLink(
    val id: String,
    val fileId: String,
    val version: Long,
    val targetType: String,
    val targetId: String,
    val createdAt: String,
) {
    fun matches(file: CoreAttachmentVersion, taskId: String): Boolean =
        coreIdentifier.matches(id) && fileId == file.fileId && version == file.version &&
            targetType == "task" && targetId == taskId
}

@Serializable
internal data class CoreAttachmentLinks(val items: List<CoreAttachmentLink>, val nextCursor: String? = null)
