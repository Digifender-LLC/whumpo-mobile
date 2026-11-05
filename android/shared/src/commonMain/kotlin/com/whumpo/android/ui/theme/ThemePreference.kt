package com.whumpo.android.ui.theme

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The owner's appearance choice. Dynamic colour is deliberately not offered (docs/android/app-ux.md). */
enum class ThemePreference(val label: String) {
    System("System"),
    Light("Light"),
    Dark("Dark"),
    ;

    /** Whether to draw dark, given what the system says. */
    fun isDark(systemDark: Boolean): Boolean = when (this) {
        System -> systemDark
        Light -> false
        Dark -> true
    }
}

/** Where the choice lives. The Android app persists it in app-private preferences; it is not account data. */
interface ThemeStore {
    val preference: StateFlow<ThemePreference>

    fun set(preference: ThemePreference)
}

class InMemoryThemeStore(initial: ThemePreference = ThemePreference.System) : ThemeStore {
    private val state = MutableStateFlow(initial)
    override val preference: StateFlow<ThemePreference> = state.asStateFlow()

    override fun set(preference: ThemePreference) {
        state.value = preference
    }
}
