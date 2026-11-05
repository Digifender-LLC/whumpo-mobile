package com.whumpo.android.ui.photos

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.exifinterface.media.ExifInterface
import com.whumpo.android.ui.share.PhotoPreviewer
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The thumbnail in the share dialog. It follows the same safe path as [AndroidPhotoPreparer]: `content://` only, the same
 * 10 MiB input cap, JPEG, PNG or WebP only, then a decode downsampled to thumbnail size. It only reads: nothing is written
 * to disk or cache, nothing is sent, and no metadata is shown (EXIF is read for the orientation alone, then discarded).
 *
 * The read runs off the main thread under [timeoutMs]. A provider that hangs cannot freeze the dialog: the caller gets null
 * after the timeout and the stuck read is abandoned on its own IO thread.
 *
 * [open] is injectable so tests can serve bytes or hang without a real provider.
 */
class AndroidPhotoPreviewer(
    private val open: (Uri) -> InputStream?,
    private val timeoutMs: Long = 4_000,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : PhotoPreviewer {
    constructor(resolver: ContentResolver) : this({ uri ->
        try {
            resolver.openInputStream(uri)
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    })

    // Not a child of the caller: a read blocked inside a provider cannot be interrupted, and a child would make the caller wait for it.
    private val scope = CoroutineScope(SupervisorJob() + io)

    override suspend fun thumbnail(source: String, maxSidePx: Int): ImageBitmap? {
        val uri = Uri.parse(source)
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return null
        val decode = scope.async { decode(uri, maxSidePx) }
        // await() is cancellable at once, so a blocked read costs one IO thread, never the caller.
        val result = withTimeoutOrNull(timeoutMs) { decode.await() }
        if (result == null) decode.cancel()
        return result?.asImageBitmap()
    }

    private fun decode(uri: Uri, maxSidePx: Int): Bitmap? =
        try {
            val bytes = open(uri)?.use { readCapped(it) } ?: return null
            if (bytes.isEmpty() || bytes.size > PhotoPipeline.MAX_INPUT_BYTES) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val w = bounds.outWidth
            val h = bounds.outHeight
            if (w <= 0 || h <= 0 || bounds.outMimeType !in ALLOWED || w.toLong() * h > MAX_PIXELS) return null
            var sample = 1
            while (maxOf(w, h) / (sample * 2) >= maxSidePx) sample *= 2
            val small = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            val orientation = try {
                ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } catch (_: IOException) {
                ExifInterface.ORIENTATION_NORMAL
            }
            val matrix = Matrix().apply {
                when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                    ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(-90f)
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
                }
                val scale = minOf(1f, maxSidePx.toFloat() / maxOf(small.width, small.height))
                postScale(scale, scale)
            }
            Bitmap.createBitmap(small, 0, 0, small.width, small.height, matrix, true).also { if (it !== small) small.recycle() }
        } catch (_: Exception) {
            null // corrupt, truncated, out of memory for this one decode, or a hostile provider: no preview, dialog still works
        } catch (_: OutOfMemoryError) {
            null
        }

    private companion object {
        val ALLOWED = setOf("image/jpeg", "image/png", "image/webp")

        /** A header can claim any size; refuse a decode that would be a decompression bomb even when downsampled. */
        const val MAX_PIXELS = 100_000_000L
    }
}
