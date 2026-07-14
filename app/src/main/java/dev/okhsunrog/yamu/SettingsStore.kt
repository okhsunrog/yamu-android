package dev.okhsunrog.yamu

import android.content.Context
import androidx.core.content.edit

internal class SettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    var preferMp3: Boolean
        get() = preferences.getBoolean(PREFER_MP3, false)
        set(value) {
            preferences.edit { putBoolean(PREFER_MP3, value) }
        }

    private companion object {
        const val PREFERENCES = "settings"
        const val PREFER_MP3 = "prefer_mp3"
    }
}
