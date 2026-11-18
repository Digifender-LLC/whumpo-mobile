package com.whumpo.android.core

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ProviderInfo
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import com.whumpo.core.auth.CoreWorkAttachmentFile
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption

class CoreDocumentPhotoFileProvider : FileProvider() {
    override fun attachInfo(context: Context, info: ProviderInfo) {
        super.attachInfo(context, info)
        CoreWorkDocumentPhoto.clearAbandoned(context)
    }
}

private const val Directory = "work-document-photos"
private const val Prefix = "document-"
private const val Suffix = ".jpg"
private const val CameraGrants = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

internal class CoreTakeDocumentPhoto : ActivityResultContracts.TakePicture() {
    override fun createIntent(context: Context, input: Uri): Intent = super.createIntent(context, input).apply {
        clipData = ClipData.newRawUri("Document photo", input)
        addFlags(CameraGrants)
    }
}

/** The output and transient grant belong to one capture in the current view only. */
internal class CoreWorkDocumentPhoto private constructor(
    private val context: Context,
    private val file: File,
    val uri: Uri,
) {
    fun read(): CoreWorkAttachmentFile {
        context.revokeUriPermission(uri, CameraGrants)
        require(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS))
        val size = file.length()
        require(size in 1..CoreWorkAttachmentFile.MaximumBytes.toLong())
        val bytes = file.inputStream().use { stream ->
            ByteArray(size.toInt()).also { output ->
                var offset = 0
                while (offset < output.size) {
                    val count = stream.read(output, offset, output.size - offset)
                    require(count > 0)
                    offset += count
                }
                require(stream.read() == -1)
            }
        }
        require(bytes.size >= 5 && bytes[bytes.lastIndex - 1] == 0xff.toByte() && bytes.last() == 0xd9.toByte())
        val decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            require(info.mimeType == "image/jpeg")
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setOnPartialImageListener { false }
            var sample = 1
            while (maxOf(info.size.width, info.size.height) / sample > 1024) sample *= 2
            decoder.setTargetSampleSize(sample)
        }
        decoded.recycle()
        return CoreWorkAttachmentFile("Document photo.jpg", "image/jpeg", bytes)
    }

    fun close() {
        runCatching { context.revokeUriPermission(uri, CameraGrants) }
        file.delete()
    }

    companion object {
        private var clearedAfterProcessStart = false

        @Synchronized
        fun clearAbandoned(context: Context) {
            if (clearedAfterProcessStart) return
            val directory = File(context.cacheDir, Directory)
            clearedAfterProcessStart = true
            if (!Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) return
            directory.listFiles().orEmpty().filter { file ->
                file.name.startsWith(Prefix) && file.name.endsWith(Suffix) &&
                    Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)
            }.forEach { file ->
                runCatching { context.revokeUriPermission(uri(context, file), CameraGrants) }
                file.delete()
            }
        }

        fun create(context: Context): CoreWorkDocumentPhoto {
            val app = context.applicationContext
            val directory = File(app.cacheDir, Directory)
            check(Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS) || directory.mkdir())
            val file = File.createTempFile(Prefix, Suffix, directory)
            return try {
                CoreWorkDocumentPhoto(app, file, uri(app, file))
            } catch (error: Exception) {
                file.delete()
                throw error
            }
        }

        private fun uri(context: Context, file: File): Uri =
            FileProvider.getUriForFile(context, "${context.packageName}.work-document-photos", file)
    }
}
