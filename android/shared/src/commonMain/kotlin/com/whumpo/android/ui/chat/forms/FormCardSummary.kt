package com.whumpo.android.ui.chat.forms

/** Metadata learned from an explicit form read or confirmed receipt; erased with the signed-in account. */
data class FormCardSummary(val title: String, val state: String, val hasDraft: Boolean)
