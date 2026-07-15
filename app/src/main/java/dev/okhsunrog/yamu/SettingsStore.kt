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

    var embedLyrics: Boolean
        get() = preferences.getBoolean(EMBED_LYRICS, true)
        set(value) {
            preferences.edit { putBoolean(EMBED_LYRICS, value) }
        }

    var saveLyricsFiles: Boolean
        get() = preferences.getBoolean(SAVE_LYRICS_FILES, false)
        set(value) {
            preferences.edit { putBoolean(SAVE_LYRICS_FILES, value) }
        }

    var lyricsDirectoryUri: String?
        get() = preferences.getString(LYRICS_DIRECTORY_URI, null)
        set(value) {
            preferences.edit {
                if (value == null) remove(LYRICS_DIRECTORY_URI)
                else putString(LYRICS_DIRECTORY_URI, value)
            }
        }

    private companion object {
        const val PREFERENCES = "settings"
        const val PREFER_MP3 = "prefer_mp3"
        const val EMBED_LYRICS = "embed_lyrics"
        const val SAVE_LYRICS_FILES = "save_lyrics_files"
        const val LYRICS_DIRECTORY_URI = "lyrics_directory_uri"
    }
}
