package com.whumpo.android.ui.chat.forms

/** Locale symbols for typed decimal input. Grouping separators are deliberately not accepted. */
internal data class NumberInputSymbols(val decimal: Char, val zero: Char, val minus: Char)

internal expect fun numberInputSymbols(): NumberInputSymbols

internal fun normalizedNumberInput(text: String, symbols: NumberInputSymbols = numberInputSymbols()): String =
    text.trim().map { c ->
        when {
            c == symbols.decimal -> '.'
            c == symbols.minus -> '-'
            c >= symbols.zero && c.code < symbols.zero.code + 10 -> '0' + (c.code - symbols.zero.code)
            else -> c
        }
    }.joinToString("")
