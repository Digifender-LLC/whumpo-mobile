package com.whumpo.android.appearance

import android.content.Context
import com.whumpo.android.ui.theme.ThemePreference
import com.whumpo.android.ui.theme.ThemeStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The theme choice in app-private preferences. It is a device setting, not account data, so sign-out keeps it. Backups are off. */
class PrefsThemeStore(context: Context) : ThemeStore {
    private val prefs = context.getSharedPreferences("whumpo-appearance", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(
        ThemePreference.entries.firstOrNull { it.name == prefs.getString(KEY, null) } ?: ThemePreference.System,
    )
    override val preference: StateFlow<ThemePreference> = state.asStateFlow()

    override fun set(preference: ThemePreference) {
        prefs.edit().putString(KEY, preference.name).apply()
        state.value = preference
    }

    private companion object {
        const val KEY = "theme"
    }
}
