package com.whumpo.android.ui.chat.forms

import java.math.BigDecimal

internal actual fun decimalNumberProblem(text: String, kind: FieldKind.Number): DecimalNumberProblem? {
    val wireValue = text.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return DecimalNumberProblem.Range
    val value = BigDecimal(text)
    val min = BigDecimal.valueOf(kind.min)
    val max = BigDecimal.valueOf(kind.max)
    if (value < min || value > max) return DecimalNumberProblem.Range
    // Do not silently round typed digits when the existing encoder converts the answer to Double.
    if (value.compareTo(BigDecimal.valueOf(wireValue)) != 0) return DecimalNumberProblem.Precision
    val step = BigDecimal.valueOf(kind.step)
    val remainder = value.subtract(min).remainder(step).abs()
    // Retain canonical floating artifacts (0.30000000000000004 on a 0.2 grid) without allowing
    // the tolerance to grow with the answer or the grid offset. Never widen the JSON number format.
    val tolerance = step.multiply(BigDecimal("0.000000001"))
    return if (remainder > tolerance && step.subtract(remainder) > tolerance) DecimalNumberProblem.Step else null
}
