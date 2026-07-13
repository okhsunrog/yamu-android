package dev.okhsunrog.yamusdownloader

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.OpenableColumns
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

internal data class PublishedTrack(
    val uri: Uri,
    val displayName: String,
    val location: String,
    val mimeType: String,
)

internal data class PublishedDownload(
    val title: String,
    val location: String,
    val fileCount: Int,
    val isCollection: Boolean,
    val shareTrack: PublishedTrack?,
)

internal object TrackPublisher {
    private const val ALBUM_DIRECTORY = "Ya Music"
    private const val PREFERENCES = "published-tracks"
    private const val TRACKED_URI_PREFIX = "uri:"

    fun publish(
        context: Context,
        sourcePath: String,
        relativeDirectory: String? = null,
    ): PublishedTrack {
        val source = File(sourcePath)
        require(source.isFile) { "Скачанный файл не найден: $sourcePath" }
        val mimeType = mimeTypeFor(source.extension)
        val targetDirectory = listOfNotNull(ALBUM_DIRECTORY, relativeDirectory)
            .joinToString("/")

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val musicDirectory = requireNotNull(
                context.getExternalFilesDir(Environment.DIRECTORY_MUSIC),
            ).resolve(targetDirectory)
            check(musicDirectory.mkdirs() || musicDirectory.isDirectory) {
                "Android не смог создать папку для музыки"
            }
            val destination = musicDirectory.resolve(source.name)
            replaceFile(source, destination)
            source.delete()
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.files",
                destination,
            )
            return PublishedTrack(
                uri,
                destination.name,
                "папке приложения Music/$targetDirectory",
                mimeType,
            )
        }

        val resolver = context.contentResolver
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val relativePath = "${Environment.DIRECTORY_MUSIC}/$targetDirectory"
        val publicationKey = publicationKey(targetDirectory, source.name)
        val previousUris = trackedUris(context, publicationKey)
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, source.name)
            put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
            put(MediaStore.Audio.Media.RELATIVE_PATH, relativePath)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = requireNotNull(resolver.insert(collection, values)) {
            "Android не смог создать файл в медиатеке"
        }

        try {
            saveTrackedUris(context, publicationKey, previousUris + uri)
            resolver.openOutputStream(uri, "w").use { output ->
                requireNotNull(output) { "Android не открыл файл для записи" }
                source.inputStream().use { input -> input.copyTo(output) }
            }
            values.clear()
            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            check(resolver.update(uri, values, null, null) > 0) {
                "Android не завершил публикацию файла"
            }

            val remainingUris = previousUris.filterTo(mutableSetOf()) { previous ->
                try {
                    resolver.delete(previous, null, null) <= 0 && uriExists(context, previous)
                } catch (_: SecurityException) {
                    true
                }
            }
            runCatching {
                resolver.update(
                    uri,
                    ContentValues().apply {
                        put(MediaStore.Audio.Media.DISPLAY_NAME, source.name)
                        put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
                        put(MediaStore.Audio.Media.RELATIVE_PATH, relativePath)
                    },
                    null,
                    null,
                )
            }
            saveTrackedUris(context, publicationKey, remainingUris + uri)
            source.delete()
        } catch (error: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            runCatching { saveTrackedUris(context, publicationKey, previousUris) }
            throw error
        }

        return PublishedTrack(
            uri = uri,
            displayName = displayName(context, uri, source.name),
            location = "Music/$targetDirectory",
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

    private fun displayName(context: Context, uri: Uri, fallback: String): String =
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: fallback

    private fun publicationKey(directory: String, fileName: String): String =
        "$TRACKED_URI_PREFIX$directory/$fileName"

    private fun trackedUris(context: Context, key: String): Set<Uri> =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getStringSet(key, emptySet())
            .orEmpty()
            .mapTo(mutableSetOf(), Uri::parse)

    @SuppressLint("UseKtx") // The crash journal needs commit()'s success result.
    private fun saveTrackedUris(context: Context, key: String, uris: Set<Uri>) {
        val editor = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
        if (uris.isEmpty()) {
            editor.remove(key)
        } else {
            editor.putStringSet(key, uris.mapTo(mutableSetOf(), Uri::toString))
        }
        check(editor.commit()) { "Android не сохранил индекс опубликованной музыки" }
    }

    private fun uriExists(context: Context, uri: Uri): Boolean = try {
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)
            ?.use { cursor -> cursor.moveToFirst() } == true
    } catch (_: SecurityException) {
        false
    }

    private fun replaceFile(source: File, destination: File) {
        val temporary = requireNotNull(destination.parentFile).resolve(
            ".${destination.name}.part-${UUID.randomUUID()}",
        )
        try {
            source.inputStream().use { input ->
                FileOutputStream(temporary).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            try {
                Files.move(
                    temporary.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporary.toPath(),
                    destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } finally {
            temporary.delete()
        }
    }
}
