package com.whumpo.android.ui.chat.forms

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

internal actual fun decimalNumberProblem(text: String, kind: FieldKind.Number): DecimalNumberProblem? {
    val wireValue = text.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return DecimalNumberProblem.Range
    val value = BigDecimal(text)
    val min = canonicalDecimal(kind.min)
    val max = canonicalDecimal(kind.max)
    if (value < min || value > max) return DecimalNumberProblem.Range
    // Do not silently round typed digits when the existing encoder converts the answer to Double.
    if (value.compareTo(canonicalDecimal(wireValue)) != 0) return DecimalNumberProblem.Precision
    val step = canonicalDecimal(kind.step)
    val remainder = value.subtract(min).remainder(step).abs()
    // Retain canonical floating artifacts (0.30000000000000004 on a 0.2 grid) without allowing
    // the tolerance to grow with the answer or the grid offset. Never widen the JSON number format.
    val tolerance = step.multiply(BigDecimal("0.0000001"))
    return if (remainder >= tolerance && step.subtract(remainder) >= tolerance) DecimalNumberProblem.Step else null
}

internal actual fun canonicalNumberDecimal(value: Double): String = canonicalDecimal(value).toPlainString()

/**
 * Node/JSON.stringify's closest shortest decimal, including subnormals. Java/Android toString
 * may choose a longer decimal. Work from the exact binary value and consider both adjacent
 * decimals at each precision, since the binary rounding interval can be asymmetric.
 */
private fun canonicalDecimal(value: Double): BigDecimal {
    require(value.isFinite())
    if (value == 0.0) return BigDecimal.ZERO
    val positive = kotlin.math.abs(value)
    val exact = BigDecimal(positive)
    for (precision in 1..17) {
        val lower = exact.round(MathContext(precision, RoundingMode.FLOOR)).stripTrailingZeros()
        val upper = exact.round(MathContext(precision, RoundingMode.CEILING)).stripTrailingZeros()
        val candidates = listOf(lower, upper).distinct().filter { it.toDouble() == positive }
        val best = candidates.minWithOrNull(compareBy<BigDecimal> { it.subtract(exact).abs() }
            .thenBy { it.unscaledValue().testBit(0) })
        if (best != null) return if (value < 0.0) best.negate() else best
    }
    error("A finite binary64 value requires at most 17 decimal digits")
}
