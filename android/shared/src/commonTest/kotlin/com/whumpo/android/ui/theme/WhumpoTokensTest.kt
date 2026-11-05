package com.whumpo.android.ui.theme

import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class WhumpoTokensTest {
    private fun lum(c: Long): Double {
        fun ch(shift: Int): Double {
            val v = ((c shr shift) and 0xFF) / 255.0
            return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * ch(16) + 0.7152 * ch(8) + 0.0722 * ch(0)
    }

    private fun contrast(a: Long, b: Long): Double {
        val (hi, lo) = listOf(lum(a), lum(b)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    private val schemes = mapOf("light" to WhumpoTokens.LightScheme, "dark" to WhumpoTokens.DarkScheme)

    @Test
    fun primaryIsNeverSkyBlueInLightTheme() {
        assertNotEquals(WhumpoTokens.BrandSkyBlue, WhumpoTokens.LightScheme.primary)
        assertEquals(WhumpoTokens.Light.link, WhumpoTokens.LightScheme.primary)
    }

    @Test
    fun brandActionFillIsSkyBlueWithDeepSkyText() {
        for ((name, s) in schemes) {
            assertEquals(WhumpoTokens.BrandSkyBlue, s.primaryContainer, name)
            assertEquals(WhumpoTokens.BrandDeepSky, s.onPrimaryContainer, name)
        }
    }

    @Test
    fun textPairsReach4_5() {
        for ((name, s) in schemes) {
            val pairs = listOf(
                s.onPrimary to s.primary, s.onPrimaryContainer to s.primaryContainer,
                s.onSecondary to s.secondary, s.onSecondaryContainer to s.secondaryContainer,
                s.onTertiaryContainer to s.tertiaryContainer, s.onError to s.error,
                s.onErrorContainer to s.errorContainer, s.onSurface to s.surface,
                s.onSurfaceVariant to s.surfaceContainer, s.primary to s.surface,
                s.inverseOnSurface to s.inverseSurface,
            )
            for ((fg, bg) in pairs) assertTrue(contrast(fg, bg) >= 4.5, "$name ${fg.toString(16)} on ${bg.toString(16)}")
        }
    }

    @Test
    fun bordersReach3() {
        for ((name, s) in schemes) {
            for (bg in listOf(s.surface, s.surfaceContainerLowest, s.surfaceContainer)) {
                assertTrue(contrast(s.outline, bg) >= 3.0, "$name outline on ${bg.toString(16)}")
                assertTrue(contrast(s.focusRing, bg) >= 3.0, "$name focus ring on ${bg.toString(16)}")
            }
        }
    }

    @Test
    fun typeAndMotionComeFromTokens() {
        assertEquals(40f, WhumpoTokens.TypeDisplay.size)
        assertTrue(WhumpoTokens.TypeDisplay.display)
        assertEquals(16f, WhumpoTokens.TypeBody.size)
        assertEquals(150, WhumpoTokens.DurationFastMs)
        assertEquals(28, WhumpoTokens.RadiusXlDp)
    }
}
