package dev.okhsunrog.yamu

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import java.io.File
import java.util.UUID

internal object LyricsPublisher {
    private const val APP_DIRECTORY = "Ya Music"
    private const val MUSIC_DIRECTORY = "Music"

    private data class Document(
        val uri: Uri,
        val mimeType: String,
    )

    fun supportsTree(context: Context, treeUri: Uri): Boolean =
        rootName(context, treeUri)?.let { name ->
            name.equals(MUSIC_DIRECTORY, ignoreCase = true) ||
                name.equals(APP_DIRECTORY, ignoreCase = true)
        } == true

    fun canWriteTree(context: Context, treeUri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any { permission ->
            permission.uri == treeUri && permission.isWritePermission
        } && supportsTree(context, treeUri)

    fun publish(
        context: Context,
        sourcePath: String,
        treeUri: Uri,
        relativeDirectory: String? = null,
    ) {
        val source = File(sourcePath)
        require(source.isFile) { "Файл с текстом не найден: $sourcePath" }
        val mimeType = when (source.extension.lowercase()) {
            "lrc" -> "application/lrc"
            "txt" -> "text/plain"
            else -> error("Неподдерживаемый формат файла с текстом")
        }

        val resolver = context.contentResolver
        var directory = rootDocumentUri(treeUri)
        val rootName = documentName(context, directory)
        require(
            rootName.equals(MUSIC_DIRECTORY, ignoreCase = true) ||
                rootName.equals(APP_DIRECTORY, ignoreCase = true),
        ) { "Для текстов выберите папку Music или Music/Ya Music" }
        if (rootName.equals(MUSIC_DIRECTORY, ignoreCase = true)) {
            directory = findOrCreateDirectory(context, directory, APP_DIRECTORY)
        }
        for (segment in directorySegments(relativeDirectory)) {
            directory = findOrCreateDirectory(context, directory, segment)
        }

        val existing = findChild(context, directory, source.name)
        require(existing?.mimeType != DocumentsContract.Document.MIME_TYPE_DIR) {
            "Папка уже использует имя ${source.name}"
        }
        val temporaryName = "${source.name}.part-${UUID.randomUUID()}"
        val temporary = requireNotNull(
            DocumentsContract.createDocument(resolver, directory, mimeType, temporaryName),
        ) { "Android не создал временный файл с текстом" }

        try {
            val descriptor = requireNotNull(resolver.openFileDescriptor(temporary, "w")) {
                "Android не открыл файл с текстом для записи"
            }
            ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
                output.fd.sync()
            }
            existing?.let { document ->
                check(DocumentsContract.deleteDocument(resolver, document.uri)) {
                    "Android не заменил прежний файл с текстом"
                }
            }
            requireNotNull(DocumentsContract.renameDocument(resolver, temporary, source.name)) {
                "Android не присвоил файлу с текстом окончательное имя"
            }
            source.delete()
        } catch (error: Throwable) {
            runCatching { DocumentsContract.deleteDocument(resolver, temporary) }
            throw error
        }
    }

    private fun rootName(context: Context, treeUri: Uri): String? =
        runCatching { documentName(context, rootDocumentUri(treeUri)) }.getOrNull()

    private fun rootDocumentUri(treeUri: Uri): Uri =
        DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )

    internal fun directorySegments(relativeDirectory: String?): List<String> =
        relativeDirectory
            ?.split('/')
            .orEmpty()
            .filter(String::isNotBlank)
            .onEach { segment ->
                require(segment != "." && segment != "..") {
                    "Некорректная папка для файла с текстом"
                }
            }

    private fun findOrCreateDirectory(context: Context, parent: Uri, name: String): Uri {
        val existing = findChild(context, parent, name)
        if (existing != null) {
            require(existing.mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                "Файл уже использует имя папки $name"
            }
            return existing.uri
        }
        return requireNotNull(
            DocumentsContract.createDocument(
                context.contentResolver,
                parent,
                DocumentsContract.Document.MIME_TYPE_DIR,
                name,
            ),
        ) { "Android не создал папку $name" }
    }

    private fun findChild(context: Context, parent: Uri, name: String): Document? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            parent,
            DocumentsContract.getDocumentId(parent),
        )
        return context.contentResolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            )
            val nameColumn = cursor.getColumnIndexOrThrow(
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            )
            val mimeColumn = cursor.getColumnIndexOrThrow(
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            )
            while (cursor.moveToNext()) {
                if (cursor.getString(nameColumn) == name) {
                    return@use Document(
                        DocumentsContract.buildDocumentUriUsingTree(
                            parent,
                            cursor.getString(idColumn),
                        ),
                        cursor.getString(mimeColumn),
                    )
                }
            }
            null
        }
    }

    private fun documentName(context: Context, document: Uri): String? =
        context.contentResolver.query(
            document,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
}
