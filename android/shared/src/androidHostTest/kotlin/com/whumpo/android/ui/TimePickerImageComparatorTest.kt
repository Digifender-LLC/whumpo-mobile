package com.whumpo.android.ui

import com.dropbox.differ.Color
import com.dropbox.differ.Image
import com.dropbox.differ.Mask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The renderer allowance cannot hide a single out-of-bound pixel, shape change or missing glyph. */
class TimePickerImageComparatorTest {
    private class Pixels(override val width: Int, override val height: Int, private val pixels: IntArray) : Image {
        override fun getPixel(x: Int, y: Int): Color {
            val argb = pixels[y * width + x]
            return Color(((argb ushr 16) and 255) / 255f, ((argb ushr 8) and 255) / 255f, (argb and 255) / 255f, (argb ushr 24) / 255f)
        }
    }

    private fun compare(left: Image, right: Image) = TimePickerImageComparator.compare(left, right, null).pixelDifferences
    private fun image(value: Int) = Pixels(2, 2, IntArray(4) { value })

    @Test
    fun allChannelsAcceptOnlyTheirMeasuredBounds() {
        val original = image(0x80646464.toInt())
        assertEquals(0, compare(original, image(0x81676767.toInt())))
        assertEquals(0, compare(original, image(0x7f616161)))
        for (channel in listOf(0, 8, 16)) {
            assertTrue(compare(original, image(0x80646464.toInt() + (4 shl channel))) > 0)
            assertTrue(compare(original, image(0x80646464.toInt() - (4 shl channel))) > 0)
        }
        assertTrue(compare(original, image(0x82646464.toInt())) > 0)
        assertTrue(compare(original, image(0x7e646464)) > 0)
    }

    @Test
    fun oneBadPixelFailsAndIsMarkedInTheDiagnosticMask() {
        val original = image(0xff646464.toInt())
        val changed = Pixels(2, 2, intArrayOf(0xff646464.toInt(), 0xff646464.toInt(), 0xff646468.toInt(), 0xff646464.toInt()))
        val mask = Mask(2, 2)
        val result = TimePickerImageComparator.compare(original, changed, mask)
        assertEquals(1, result.pixelDifferences)
        assertEquals(1f, mask.getValue(0, 1))
        assertEquals(0f, mask.getValue(1, 1))
    }

    @Test
    fun dimensionsAndOnePixelShiftCannotPass() {
        assertTrue(compare(image(0xff646464.toInt()), Pixels(1, 4, IntArray(4) { 0xff646464.toInt() })) > 0)
        val before = Pixels(3, 2, intArrayOf(0, -1, 0, 0, -1, -1))
        val shifted = Pixels(3, 2, intArrayOf(0, 0, -1, 0, 0, -1))
        assertTrue(compare(before, shifted) > 0)
    }

    @Test
    fun missingGlyphCannotPass() {
        val before = Pixels(3, 2, intArrayOf(0, -1, 0, 0, -1, -1))
        assertTrue(compare(before, Pixels(3, 2, IntArray(6))) > 0)
    }
}
