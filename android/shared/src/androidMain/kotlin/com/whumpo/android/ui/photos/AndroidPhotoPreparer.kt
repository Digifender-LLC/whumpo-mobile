package com.whumpo.android.ui.photos

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads the photo the system photo picker returned (a `content://` URI the picker grants for this one pick, so the app holds
 * no storage permission) and prepares it with [PhotoPipeline].
 */
class AndroidPhotoPreparer(private val resolver: ContentResolver) : PhotoPreparer {
    override suspend fun prepare(source: String): PreparedPhoto = withContext(Dispatchers.IO) {
        val uri = Uri.parse(source)
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) throw PhotoRejected("Choose a photo from the photo picker.")
        val bytes = readBytes(uri) ?: throw PhotoRejected("That photo can't be read. Choose it again.")
        PhotoPipeline.prepare(bytes, displayName(uri))
    }

    private fun readBytes(uri: Uri): ByteArray? = readCapped(resolver, uri)

    private fun displayName(uri: Uri): String? =
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (_: SecurityException) {
            null
        }
}

/**
 * The same preparation the web does before an upload: JPEG, PNG or WebP up to 10 MiB, orientation applied, scaled to fit
 * 1600 x 1600, flattened on white, re-encoded as baseline JPEG within 1 MiB. Re-encoding writes a fresh file, so EXIF, GPS,
 * thumbnails and colour profiles from the original never reach it. The server strips metadata again and validates.
 */
internal object PhotoPipeline {
    const val MAX_INPUT_BYTES = 10 * 1024 * 1024
    const val MAX_OUTPUT_BYTES = 1_048_576
    const val MAX_SIDE = 1600
    private val QUALITIES = intArrayOf(85, 70, 55, 40, 25)

    fun prepare(input: ByteArray, displayName: String?): PreparedPhoto {
        if (input.isEmpty()) throw PhotoRejected("That photo is empty.")
        if (input.size > MAX_INPUT_BYTES) throw PhotoRejected("Choose a photo under 10 MB.")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(input, 0, input.size, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0 || bounds.outMimeType !in ALLOWED_TYPES) {
            throw PhotoRejected("Choose a JPEG, PNG or WebP photo.")
        }
        var sample = 1
        while (maxOf(width, height) / (sample * 2) >= MAX_SIDE) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(input, 0, input.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw PhotoRejected("That photo can't be read.")
        val orientation = try {
            ExifInterface(ByteArrayInputStream(input)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (_: IOException) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val matrix = Matrix().apply {
            applyOrientation(orientation)
            val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(decoded.width, decoded.height))
            postScale(scale, scale)
        }
        val turned = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (turned !== decoded) decoded.recycle()
        val flat = Bitmap.createBitmap(turned.width, turned.height, Bitmap.Config.ARGB_8888)
        Canvas(flat).apply {
            drawColor(Color.WHITE)
            drawBitmap(turned, 0f, 0f, null)
        }
        turned.recycle()
        try {
            for (quality in QUALITIES) {
                val out = ByteArrayOutputStream()
                flat.compress(Bitmap.CompressFormat.JPEG, quality, out)
                val jpeg = stripMetadata(out.toByteArray())
                if (jpeg.size <= MAX_OUTPUT_BYTES) return PreparedPhoto(fileName(displayName), jpeg)
            }
        } finally {
            flat.recycle()
        }
        throw PhotoRejected("Choose a less detailed photo.")
    }

    /** Safe file name for the upload: the picked name without its extension, plain characters only, `.jpg`. */
    fun fileName(displayName: String?): String {
        val stem = displayName.orEmpty().substringBeforeLast('.').filter { it.isLetterOrDigit() || it in " ._-" }.trim { it == '.' || it == ' ' }.take(90)
        return (stem.ifEmpty { "photo" }) + ".jpg"
    }

    /**
     * Drops every APPn (EXIF, GPS, thumbnails, ICC, JFIF) and comment segment before the scan, as the server does.
     * The encoder writes none of the original's metadata anyway; this keeps the output free of anything it adds.
     */
    fun stripMetadata(jpeg: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(jpeg.size)
        out.write(jpeg, 0, 2)
        var i = 2
        while (i + 4 <= jpeg.size && jpeg[i] == 0xFF.toByte()) {
            val marker = jpeg[i + 1].toInt() and 0xFF
            val length = ((jpeg[i + 2].toInt() and 0xFF) shl 8) or (jpeg[i + 3].toInt() and 0xFF)
            if (marker == 0xDA) {
                out.write(jpeg, i, jpeg.size - i)
                return out.toByteArray()
            }
            if (marker !in 0xE0..0xEF && marker != 0xFE) out.write(jpeg, i, 2 + length)
            i += 2 + length
        }
        throw PhotoRejected("That photo can't be prepared.")
    }

    private fun Matrix.applyOrientation(orientation: Int) {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                postRotate(90f)
                postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                postRotate(-90f)
                postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(-90f)
        }
    }

    private val ALLOWED_TYPES = setOf("image/jpeg", "image/png", "image/webp")
}

/**
 * Reads a `content://` photo into memory, at most one byte over [PhotoPipeline.MAX_INPUT_BYTES] so a larger file can be
 * refused without loading all of it. Shared by the upload preparer and the share preview, so both apply the same cap.
 * Null when the provider cannot be read.
 */
internal fun readCapped(resolver: ContentResolver, uri: Uri): ByteArray? =
    try {
        resolver.openInputStream(uri)?.use { stream -> readCapped(stream) }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    } catch (_: RuntimeException) {
        null // a hostile provider can throw anything
    }

internal fun readCapped(stream: java.io.InputStream): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    val limit = PhotoPipeline.MAX_INPUT_BYTES + 1
    while (out.size() < limit) {
        val n = stream.read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (n < 0) break
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}
