package com.ricardo.txtreader.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.ricardo.txtreader.model.FolderEntry
import com.ricardo.txtreader.model.TxtDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Normalizer

class TxtRepository(
    private val context: Context,
    private val contentResolver: ContentResolver = context.contentResolver
) {
    fun getTreeDocument(uri: Uri): DocumentFile? = DocumentFile.fromTreeUri(context, uri)

    fun getFolderEntries(folderUri: Uri): List<FolderEntry> {
        val folder = DocumentFile.fromTreeUri(context, folderUri)
            ?: DocumentFile.fromSingleUri(context, folderUri)
            ?: return emptyList()

        return folder.listFiles()
            .filter { it.isDirectory || isReadableTextFile(it) }
            .mapNotNull { file ->
                val name = file.name ?: return@mapNotNull null
                FolderEntry(uri = file.uri, name = name, isDirectory = file.isDirectory)
            }
            .sortedWith(compareBy<FolderEntry> { !it.isDirectory }.thenBy { it.name.lowercase() })
    }

    fun getTxtFilesInFolder(folderUri: Uri, readEntries: Set<String> = emptySet()): List<TxtDocument> {
        val folder = DocumentFile.fromTreeUri(context, folderUri)
            ?: DocumentFile.fromSingleUri(context, folderUri)
            ?: return emptyList()

        return folder.listFiles()
            .filter { isReadableTextFile(it) }
            .sortedBy { it.name?.lowercase().orEmpty() }
            .map {
                val name = it.name.orEmpty()
                TxtDocument(
                    uri = it.uri,
                    name = name,
                    parentUri = folderUri,
                    isRead = readEntries.contains(it.uri.toString()) || readEntries.contains(readNameKey(name)),
                    isMarkdown = isMarkdownFile(it)
                )
            }
    }

    suspend fun readText(uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: error("No se pudo abrir el archivo")
        }
    }

    suspend fun markAsRead(uri: Uri, parentUri: Uri?): Result<Uri> = withContext(Dispatchers.IO) {
        runCatching {
            val file = DocumentFile.fromSingleUri(context, uri)
                ?: error("No se pudo acceder al archivo actual")

            val currentName = file.name ?: error("Archivo sin nombre")
            val dotIndex = currentName.lastIndexOf('.')
            val baseName = if (dotIndex > 0) currentName.substring(0, dotIndex) else currentName
            val extension = if (dotIndex > 0) currentName.substring(dotIndex) else ".txt"

            if (baseName.endsWith("_leido", ignoreCase = true) || baseName.endsWith("_leído", ignoreCase = true)) {
                return@runCatching uri
            }

            val targetName = baseName + "_leido" + extension
            val renamed = file.renameTo(targetName)
            if (!renamed) error("No se pudo renombrar el archivo como leído")

            val parent = parentUri?.let { DocumentFile.fromTreeUri(context, it) ?: DocumentFile.fromSingleUri(context, it) }
            val renamedFile = parent?.findFile(targetName)
            renamedFile?.uri ?: file.uri
        }
    }

    private fun isReadableTextFile(file: DocumentFile): Boolean {
        val name = file.name?.lowercase().orEmpty()
        val type = file.type?.lowercase().orEmpty()
        return file.isFile && (
            name.endsWith(".txt") ||
            name.endsWith(".md") ||
            name.endsWith(".markdown") ||
            type == "text/plain" ||
            type == "text/markdown"
        )
    }

    private fun isMarkdownFile(file: DocumentFile): Boolean {
        val name = file.name?.lowercase().orEmpty()
        val type = file.type?.lowercase().orEmpty()
        return name.endsWith(".md") || name.endsWith(".markdown") || type == "text/markdown"
    }

    private fun readNameKey(name: String): String =
        "name:" + Normalizer.normalize(name.trim().lowercase(), Normalizer.Form.NFD)
            .replace("\\p{Mn}+".toRegex(), "")
}
