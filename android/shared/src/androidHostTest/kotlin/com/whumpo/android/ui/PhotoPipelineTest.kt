package com.whumpo.android.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import com.whumpo.android.ui.photos.PhotoPipeline
import com.whumpo.android.ui.photos.PhotoRejected
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The photo contract on real bitmap code: orientation applied, 1600 px fit, no metadata, 1 MiB, strict inputs. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PhotoPipelineTest {
    private fun jpeg(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(Color.rgb(200, 80, 40))
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray()
    }

    /** Inserts an EXIF APP1 segment (orientation 6 = rotate 90 degrees, plus a recognisable "GPS" payload) after the SOI marker. */
    private fun withExif(jpeg: ByteArray, orientation: Int = 6): ByteArray {
        val tiff = byteArrayOf(
            0x4D, 0x4D, 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08, // big-endian header, IFD0 at 8
            0x00, 0x01, // one entry
            0x01, 0x12, 0x00, 0x03, 0x00, 0x00, 0x00, 0x01, 0x00, orientation.toByte(), 0x00, 0x00, // Orientation (SHORT)
            0x00, 0x00, 0x00, 0x00, // no next IFD
        )
        val payload = "Exif".toByteArray() + byteArrayOf(0, 0) + tiff + MARKER.toByteArray()
        val length = payload.size + 2
        val segment = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (length shr 8).toByte(), length.toByte()) + payload
        return jpeg.copyOfRange(0, 2) + segment + jpeg.copyOfRange(2, jpeg.size)
    }

    private fun bounds(bytes: ByteArray) = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, this)
    }

    /** Marker bytes of every segment before the scan. */
    private fun markers(jpeg: ByteArray): List<Int> {
        val found = mutableListOf<Int>()
        var i = 2
        while (i + 4 <= jpeg.size && jpeg[i] == 0xFF.toByte()) {
            val marker = jpeg[i + 1].toInt() and 0xFF
            found += marker
            if (marker == 0xDA) break
            i += 2 + (((jpeg[i + 2].toInt() and 0xFF) shl 8) or (jpeg[i + 3].toInt() and 0xFF))
        }
        return found
    }

    @Test
    fun exifAndAllOtherMetadataNeverReachTheUpload() {
        val prepared = PhotoPipeline.prepare(withExif(jpeg(2000, 1000)), "IMG_0001.jpg")
        assertFalse(String(prepared.jpeg, Charsets.ISO_8859_1).contains(MARKER))
        val found = markers(prepared.jpeg)
        assertTrue("no APPn or comment segments, got $found", found.none { it in 0xE0..0xEF || it == 0xFE })
        // Baseline JPEG: SOF0 (0xC0), tables, then the scan.
        assertTrue(0xC0 in found && 0xDB in found && 0xC4 in found && found.last() == 0xDA)
        assertEquals(0xFF.toByte(), prepared.jpeg.first())
        assertEquals(0xD8.toByte(), prepared.jpeg[1])
        assertEquals(0xD9.toByte(), prepared.jpeg.last())
    }

    @Test
    fun orientationIsAppliedBeforeTheMetadataIsDroppedAndTheLongSideFits1600() {
        // 2000 x 1000 tagged "rotate 90" becomes upright 1000 x 2000, then scales to 800 x 1600.
        val b = bounds(PhotoPipeline.prepare(withExif(jpeg(2000, 1000)), null).jpeg)
        assertEquals(800, b.outWidth)
        assertEquals(1600, b.outHeight)
    }

    @Test
    fun aSmallUntaggedPhotoKeepsItsSize() {
        val b = bounds(PhotoPipeline.prepare(jpeg(640, 480), null).jpeg)
        assertEquals(640, b.outWidth)
        assertEquals(480, b.outHeight)
    }

    @Test
    fun outputStaysWithinOneMebibyte() {
        assertTrue(PhotoPipeline.prepare(jpeg(3000, 2000), null).jpeg.size <= PhotoPipeline.MAX_OUTPUT_BYTES)
    }

    @Test
    fun uploadNamesAreSafePlainFileNames() {
        assertEquals("IMG_0001 1.jpg", PhotoPipeline.fileName("IMG_0001 (1).jpg"))
        assertEquals("Holiday Rome2026.jpg", PhotoPipeline.fileName("Holiday: Rome/2026.PNG"))
        assertEquals("photo.jpg", PhotoPipeline.fileName(null))
        assertEquals("photo.jpg", PhotoPipeline.fileName("..."))
        assertTrue(PhotoPipeline.fileName("x".repeat(500)).length <= 94)
    }

    @Test
    fun nonImagesEmptyAndOversizedInputsAreRefused() {
        assertThrows(PhotoRejected::class.java) { PhotoPipeline.prepare(ByteArray(0), null) }
        assertThrows(PhotoRejected::class.java) { PhotoPipeline.prepare("not an image".toByteArray(), null) }
        assertThrows(PhotoRejected::class.java) { PhotoPipeline.prepare(ByteArray(PhotoPipeline.MAX_INPUT_BYTES + 1), null) }
    }

    private companion object {
        const val MARKER = "GPSLatitude=51.5074;GPSLongitude=-0.1278"
    }
}
