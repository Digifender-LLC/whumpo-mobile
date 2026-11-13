package com.whumpo.android.ui.chat.forms

internal enum class DecimalNumberProblem { Range, Precision, Step }

/** Checks normalized decimal text while preserving the existing finite Double wire format. */
internal expect fun decimalNumberProblem(text: String, kind: FieldKind.Number): DecimalNumberProblem?

/** Shortest decimal value that round-trips to [value], in plain notation; finite values only. */
internal expect fun canonicalNumberDecimal(value: Double): String
