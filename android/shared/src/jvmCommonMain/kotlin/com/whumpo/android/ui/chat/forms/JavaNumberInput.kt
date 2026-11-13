package com.whumpo.android.ui.chat.forms

import java.text.DecimalFormatSymbols
import java.util.Locale

internal actual fun numberInputSymbols(): NumberInputSymbols = DecimalFormatSymbols.getInstance(Locale.getDefault()).let {
    NumberInputSymbols(it.decimalSeparator, it.zeroDigit, it.minusSign)
}
