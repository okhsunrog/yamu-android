package dev.okhsunrog.yamusdownloader

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

internal data class PublishedTrack(
    val uri: Uri,
    val displayName: String,
    val location: String,
    val mimeType: String,
)

internal object TrackPublisher {
    private const val ALBUM_DIRECTORY = "Ya Music"

    fun publish(context: Context, sourcePath: String): PublishedTrack {
        val source = File(sourcePath)
        require(source.isFile) { "Скачанный файл не найден: $sourcePath" }
        val mimeType = mimeTypeFor(source.extension)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.files",
                source,
            )
            return PublishedTrack(uri, source.name, "папке приложения", mimeType)
        }

        val resolver = context.contentResolver
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, source.name)
            put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
            put(
                MediaStore.Audio.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_MUSIC}/$ALBUM_DIRECTORY",
            )
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = requireNotNull(resolver.insert(collection, values)) {
            "Android не смог создать файл в медиатеке"
        }

        try {
            resolver.openOutputStream(uri, "w").use { output ->
                requireNotNull(output) { "Android не открыл файл для записи" }
                source.inputStream().use { input -> input.copyTo(output) }
            }
            values.clear()
            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            source.delete()
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }

        return PublishedTrack(
            uri = uri,
            displayName = source.name,
            location = "Music/$ALBUM_DIRECTORY",
            mimeType = mimeType,
        )
    }

    fun share(context: Context, track: PublishedTrack) {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = track.mimeType
            putExtra(Intent.EXTRA_STREAM, track.uri)
            clipData = ClipData.newUri(context.contentResolver, track.displayName, track.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(sendIntent, "Поделиться треком"))
    }

    private fun mimeTypeFor(extension: String): String = when (extension.lowercase()) {
        "flac" -> "audio/flac"
        "m4a", "mp4" -> "audio/mp4"
        "mp3" -> "audio/mpeg"
        "aac" -> "audio/aac"
        else -> "audio/*"
    }
}
