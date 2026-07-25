package com.ricardo.txtreader.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ricardo.txtreader.model.ReaderPreferences
import com.ricardo.txtreader.model.ReadingPosition
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.time.Instant

private val Context.dataStore by preferencesDataStore(name = "txt_reader_prefs")

data class BackupImportResult(
    val readEntries: Int,
    val readingPositions: Int,
    val legacyTxtReaderBackup: Boolean
)

class PreferencesRepository(private val context: Context) {
    private object Keys {
        val TREE_URI = stringPreferencesKey("tree_uri")
        val CURRENT_FOLDER_URI = stringPreferencesKey("current_folder_uri")
        val CURRENT_FILE_URI = stringPreferencesKey("current_file_uri")
        val CURRENT_FILE_NAME = stringPreferencesKey("current_file_name")
        val FONT_SCALE = floatPreferencesKey("font_scale")
        val READ_ENTRIES = stringSetPreferencesKey("read_entries")
        val READING_POSITIONS = stringPreferencesKey("reading_positions")
    }

    val preferences: Flow<ReaderPreferences> = context.dataStore.data.map { prefs ->
        ReaderPreferences(
            treeUri = prefs[Keys.TREE_URI],
            currentFolderUri = prefs[Keys.CURRENT_FOLDER_URI],
            currentFileUri = prefs[Keys.CURRENT_FILE_URI],
            currentFileName = prefs[Keys.CURRENT_FILE_NAME],
            fontScaleSp = prefs[Keys.FONT_SCALE] ?: 19f,
            readEntries = prefs[Keys.READ_ENTRIES] ?: emptySet()
        )
    }

    suspend fun saveTreeUri(uri: String) {
        context.dataStore.edit { it[Keys.TREE_URI] = uri }
    }

    suspend fun saveCurrentFolderUri(uri: String?) {
        context.dataStore.edit {
            if (uri == null) it.remove(Keys.CURRENT_FOLDER_URI) else it[Keys.CURRENT_FOLDER_URI] = uri
        }
    }

    suspend fun saveCurrentFileUri(uri: String?) {
        context.dataStore.edit {
            if (uri == null) it.remove(Keys.CURRENT_FILE_URI) else it[Keys.CURRENT_FILE_URI] = uri
        }
    }

    suspend fun saveCurrentFileName(name: String?) {
        context.dataStore.edit {
            if (name == null) it.remove(Keys.CURRENT_FILE_NAME) else it[Keys.CURRENT_FILE_NAME] = name
        }
    }

    suspend fun saveFontScale(sp: Float) {
        context.dataStore.edit { it[Keys.FONT_SCALE] = sp }
    }

    suspend fun saveReadEntries(entries: Set<String>) {
        context.dataStore.edit { it[Keys.READ_ENTRIES] = entries }
    }

    fun observeReadingPosition(fileUri: String): Flow<ReadingPosition?> =
        context.dataStore.data.map { prefs ->
            decodeReadingPositions(prefs[Keys.READING_POSITIONS]).getReadingPosition(fileUri)
        }

    suspend fun getReadingPosition(fileUri: String): ReadingPosition? {
        val prefs = context.dataStore.data.first()
        return decodeReadingPositions(prefs[Keys.READING_POSITIONS]).getReadingPosition(fileUri)
    }

    suspend fun saveReadingPosition(position: ReadingPosition) {
        context.dataStore.edit { prefs ->
            val root = decodeReadingPositions(prefs[Keys.READING_POSITIONS])
            root.put(position.fileUri, position.toJson())
            prefs[Keys.READING_POSITIONS] = root.toString()
        }
    }

    suspend fun removeReadingPosition(fileUri: String) {
        context.dataStore.edit { prefs ->
            val root = decodeReadingPositions(prefs[Keys.READING_POSITIONS])
            root.remove(fileUri)
            prefs[Keys.READING_POSITIONS] = root.toString()
        }
    }

    suspend fun exportBackupJson(): String {
        val prefs = context.dataStore.data.first()
        val positions = decodeReadingPositions(prefs[Keys.READING_POSITIONS])
        val exportedPositions = JSONObject()
        positions.keys().forEach { fileUri ->
            val item = positions.optJSONObject(fileUri) ?: return@forEach
            val withName = JSONObject(item.toString())
                .put("fileUri", fileUri)
                .put("fileName", fileNameFromUri(fileUri))
            exportedPositions.put(fileUri, withName)
        }

        return JSONObject()
            .put("type", "tts_reader_backup")
            .put("version", 1)
            .put("exportedAt", Instant.now().toString())
            .put("treeUri", prefs[Keys.TREE_URI])
            .put("currentFolderUri", prefs[Keys.CURRENT_FOLDER_URI])
            .put("currentFileUri", prefs[Keys.CURRENT_FILE_URI])
            .put("currentFileName", prefs[Keys.CURRENT_FILE_NAME])
            .put("fontScaleSp", prefs[Keys.FONT_SCALE] ?: 19f)
            .put("readEntries", JSONArray(prefs[Keys.READ_ENTRIES]?.toList() ?: emptyList<String>()))
            .put("readingPositions", exportedPositions)
            .toString(2)
    }

    suspend fun importBackupJson(raw: String, currentFileUrisByName: Map<String, String>): BackupImportResult {
        val backup = JSONObject(raw)
        val importedPositions = backup.optJSONObject("readingPositions") ?: JSONObject()
        val legacyReadFiles = backup.optJSONArray("readFiles")
        var importedPositionCount = 0
        var importedReadCount = 0

        context.dataStore.edit { prefs ->
            val mergedReadEntries = (prefs[Keys.READ_ENTRIES] ?: emptySet()).toMutableSet()

            backup.optJSONArray("readEntries")?.let { entries ->
                for (index in 0 until entries.length()) {
                    entries.optString(index).takeIf { it.isNotBlank() }?.let { mergedReadEntries += it }
                }
            }

            legacyReadFiles?.let { readFiles ->
                for (index in 0 until readFiles.length()) {
                    val item = readFiles.optJSONObject(index) ?: continue
                    val fileName = item.optString("name").takeIf { it.isNotBlank() }
                    val exportedUri = item.optString("uri").takeIf { it.isNotBlank() }
                    val targetUri = fileName?.let { currentFileUrisByName[it] } ?: exportedUri
                    fileName?.let { mergedReadEntries += readNameKey(it) }
                    if (!targetUri.isNullOrBlank()) {
                        mergedReadEntries += targetUri
                    }
                }
            }
            importedReadCount = mergedReadEntries.size - (prefs[Keys.READ_ENTRIES]?.size ?: 0)
            prefs[Keys.READ_ENTRIES] = mergedReadEntries

            val currentPositions = decodeReadingPositions(prefs[Keys.READING_POSITIONS])
            importedPositions.keys().forEach { oldUri ->
                val item = importedPositions.optJSONObject(oldUri) ?: return@forEach
                val fileName = item.optString("fileName").takeIf { it.isNotBlank() }
                    ?: fileNameFromUri(item.optString("fileUri", oldUri))
                val targetUri = currentFileUrisByName[fileName] ?: item.optString("fileUri", oldUri)
                if (targetUri.isBlank()) return@forEach

                val position = JSONObject(item.toString())
                position.remove("fileUri")
                position.remove("fileName")
                currentPositions.put(targetUri, position)
                importedPositionCount += 1
            }
            prefs[Keys.READING_POSITIONS] = currentPositions.toString()
        }
        return BackupImportResult(
            readEntries = importedReadCount.coerceAtLeast(0),
            readingPositions = importedPositionCount,
            legacyTxtReaderBackup = legacyReadFiles != null && importedPositions.length() == 0
        )
    }

    private fun decodeReadingPositions(raw: String?): JSONObject =
        runCatching { if (raw.isNullOrBlank()) JSONObject() else JSONObject(raw) }.getOrDefault(JSONObject())

    private fun JSONObject.getReadingPosition(fileUri: String): ReadingPosition? {
        val item = optJSONObject(fileUri) ?: return null
        return ReadingPosition(
            fileUri = fileUri,
            characterOffset = item.optInt("characterOffset", 0),
            chunkIndex = item.optInt("chunkIndex", -1),
            chunkStartOffset = item.optInt("chunkStartOffset", 0),
            chunkEndOffset = item.optInt("chunkEndOffset", 0),
            textPreview = item.optString("textPreview", ""),
            scrollY = item.optInt("scrollY", 0),
            updatedAt = item.optLong("updatedAt", 0L)
        )
    }

    private fun ReadingPosition.toJson(): JSONObject =
        JSONObject()
            .put("characterOffset", characterOffset)
            .put("chunkIndex", chunkIndex)
            .put("chunkStartOffset", chunkStartOffset)
            .put("chunkEndOffset", chunkEndOffset)
            .put("textPreview", textPreview)
            .put("scrollY", scrollY)
            .put("updatedAt", updatedAt)

    private fun fileNameFromUri(fileUri: String): String {
        val uri = runCatching { Uri.parse(fileUri) }.getOrNull() ?: return fileUri
        val documentName = runCatching {
            DocumentFile.fromSingleUri(context, uri)?.name
                ?: DocumentFile.fromTreeUri(context, uri)?.name
        }.getOrNull()
        if (!documentName.isNullOrBlank()) return documentName

        val last = uri.lastPathSegment ?: return fileUri
        return last.substringAfterLast('/').substringAfterLast(':')
    }

    private fun readNameKey(name: String): String =
        "name:" + Normalizer.normalize(name.trim().lowercase(), Normalizer.Form.NFD)
            .replace("\\p{Mn}+".toRegex(), "")
}
