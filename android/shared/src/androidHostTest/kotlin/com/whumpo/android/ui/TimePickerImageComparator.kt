package com.whumpo.android.ui

import com.dropbox.differ.Color
import com.dropbox.differ.Image
import com.dropbox.differ.ImageComparator
import com.dropbox.differ.Mask
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Native Skia's Clear/Xor selector compositing differs slightly between macOS ARM and Linux x86.
 * Hosted artifacts differed only at its antialiased circle/glyph edges: light RGB <= 3, alpha <= 1;
 * dark red <= 4 only when both pixels have partial alpha, otherwise RGB <= 3; alpha <= 1. The dark extension is one
 * measured partial-alpha selector edge (ARGB bc315179 -> bc35547a), not a pixel-count waiver. This comparator is used only for the two normal-size clock popup captures.
 * Every pixel must satisfy those channel bounds at the same coordinate; there is no pixel budget,
 * alignment search, ignored region, or dimension tolerance. Other captures keep their defaults.
 */
internal class TimePickerImageComparator private constructor(private val redLimit: Int) : ImageComparator {
    companion object {
        val Light = TimePickerImageComparator(redLimit = 3)
        val Dark = TimePickerImageComparator(redLimit = 4)
    }

    override fun compare(left: Image, right: Image, diff: Mask?): ImageComparator.ComparisonResult {
        val width = maxOf(left.width, right.width)
        val height = maxOf(left.height, right.height)
        require(diff == null || (diff.width >= width && diff.height >= height))
        var differences = 0
        for (y in 0 until height) for (x in 0 until width) {
            val matches = x < left.width && x < right.width && y < left.height && y < right.height &&
                matches(left.getPixel(x, y), right.getPixel(x, y))
            if (!matches) differences++
            diff?.setValue(x, y, if (matches) 0f else 1f)
        }
        // Even degenerate or equal-area images with different dimensions are never equal.
        if (left.width != right.width || left.height != right.height) differences = maxOf(1, differences)
        return ImageComparator.ComparisonResult(differences, width * height, width, height)
    }

    private fun matches(left: Color, right: Color): Boolean {
        val partialAlpha = left.a > 0f && left.a < 1f && right.a > 0f && right.a < 1f
        val red = if (partialAlpha) redLimit else 3
        return near(left.r, right.r, red) && near(left.g, right.g, 3) && near(left.b, right.b, 3) && near(left.a, right.a, 1)
    }

    private fun near(left: Float, right: Float, maximum: Int): Boolean =
        left.isFinite() && right.isFinite() && abs((left * 255f).roundToInt() - (right * 255f).roundToInt()) <= maximum
}
