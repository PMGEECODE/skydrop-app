package com.famage.skydrop.ui.theme

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The three supported theme modes, mirroring the desktop 3-way toggle. */
enum class ThemePreference { SYSTEM, LIGHT, DARK }

/**
 * Persists the user's theme selection in [SharedPreferences] and exposes it
 * as a [StateFlow] so the UI can react immediately without restarting.
 *
 * The class is a process-scoped singleton: call [getInstance] from anywhere
 * and you always get the same object (backed by the same prefs file and the
 * same in-memory [StateFlow]).
 */
class ThemePreferenceStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _themePreference: MutableStateFlow<ThemePreference> =
        MutableStateFlow(loadFromPrefs())

    /** Observe this in the UI layer to reactively follow the theme choice. */
    val themePreference: StateFlow<ThemePreference> = _themePreference.asStateFlow()

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Persist [pref] and update the [StateFlow] immediately.
     * Safe to call from any thread; [SharedPreferences.apply] is async.
     */
    fun setPreference(pref: ThemePreference) {
        prefs.edit().putString(KEY_THEME, pref.name).apply()
        _themePreference.value = pref
    }

    // ── Internal helpers ────────────────────────────────────────────────────

    private fun loadFromPrefs(): ThemePreference {
        val stored = prefs.getString(KEY_THEME, null) ?: return ThemePreference.SYSTEM
        return try {
            ThemePreference.valueOf(stored)
        } catch (_: IllegalArgumentException) {
            ThemePreference.SYSTEM
        }
    }

    // ── Companion / singleton ───────────────────────────────────────────────

    companion object {
        private const val PREFS_NAME = "skydrop_theme_prefs"
        private const val KEY_THEME = "theme_preference"

        @Volatile
        private var INSTANCE: ThemePreferenceStore? = null

        fun getInstance(context: Context): ThemePreferenceStore =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: ThemePreferenceStore(context).also { INSTANCE = it }
            }
    }
}
