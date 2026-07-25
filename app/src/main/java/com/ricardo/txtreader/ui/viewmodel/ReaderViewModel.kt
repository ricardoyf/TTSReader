package com.ricardo.txtreader.ui.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ricardo.txtreader.data.PreferencesRepository
import com.ricardo.txtreader.data.TxtRepository
import com.ricardo.txtreader.model.ReadingPosition
import com.ricardo.txtreader.model.TxtDocument
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.Normalizer

class ReaderViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = PreferencesRepository(application)
    private val txtRepository = TxtRepository(application)

    private val _uiState = MutableStateFlow(ReaderUiState(isLoading = true))
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    init {
        restoreLastState()
    }

    fun restoreLastState() {
        viewModelScope.launch {
            val saved = prefs.preferences.first()
            val treeUri = saved.treeUri?.let(Uri::parse)
            val currentFolderUri = saved.currentFolderUri?.let(Uri::parse)
            val currentFileUri = saved.currentFileUri?.let(Uri::parse)
            val currentFileName = saved.currentFileName

            if (treeUri == null) {
                _uiState.value = ReaderUiState(
                    isLoading = false,
                    fontSizeSp = saved.fontScaleSp,
                    restoreAvailable = false,
                    readEntries = saved.readEntries
                )
                return@launch
            }

            _uiState.value = _uiState.value.copy(
                isLoading = true,
                errorMessage = null,
                fontSizeSp = saved.fontScaleSp,
                readEntries = saved.readEntries
            )

            runCatching {
                reloadTree(treeUri, currentFolderUri, currentFileUri, currentFileName)
            }.onFailure {
                _uiState.value = ReaderUiState(
                    isLoading = false,
                    fontSizeSp = saved.fontScaleSp,
                    errorMessage = "No se pudo restaurar la carpeta. Selecciónala de nuevo.",
                    restoreAvailable = true
                )
            }
        }
    }

    fun onFolderSelected(uri: Uri) {
        viewModelScope.launch {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching {
                getApplication<Application>().contentResolver.takePersistableUriPermission(uri, flags)
            }
            prefs.saveTreeUri(uri.toString())
            prefs.saveCurrentFolderUri(uri.toString())
            prefs.saveCurrentFileUri(null)
            prefs.saveCurrentFileName(null)
            reloadTree(uri, uri, null, null)
        }
    }

    fun openFolder(uri: Uri) {
        val current = _uiState.value
        val entry = current.folderEntries.firstOrNull { it.uri == uri } ?: return
        _uiState.value = current.copy(
            currentFolderUri = uri,
            currentFolderName = entry.name,
            folderStack = current.folderStack + uri,
            folderEntries = txtRepository.getFolderEntries(uri),
            allTxtFiles = txtRepository.getTxtFilesInFolder(uri, current.readEntries),
            errorMessage = null
        )
        viewModelScope.launch { prefs.saveCurrentFolderUri(uri.toString()) }
    }

    fun navigateUp() {
        val current = _uiState.value
        val treeUri = current.treeUri ?: return
        if (current.currentFolderUri == treeUri || current.folderStack.isEmpty()) return

        val newStack = current.folderStack.dropLast(1)
        val target = newStack.lastOrNull() ?: treeUri

        _uiState.value = current.copy(
            currentFolderUri = target,
            currentFolderName = documentName(target) ?: "Biblioteca",
            folderStack = newStack,
            folderEntries = txtRepository.getFolderEntries(target),
            allTxtFiles = txtRepository.getTxtFilesInFolder(target, current.readEntries),
            errorMessage = null
        )
        viewModelScope.launch { prefs.saveCurrentFolderUri(target.toString()) }
    }

    fun openFile(uri: Uri) {
        val file = currentFolderTxtFiles().firstOrNull { it.uri == uri }
            ?: TxtDocument(uri = uri, name = documentName(uri) ?: "Archivo", parentUri = _uiState.value.currentFolderUri)
        openTxtDocument(file)
    }

    fun openNextFile() {
        viewModelScope.launch {
            val current = _uiState.value
            val nextFile = currentFolderTxtFiles(current).getOrNull(current.selectedFileIndex + 1)
            val marked = markCurrentAsReadIfNeeded()
            if (marked) refreshFilesKeepingSelection()
            nextFile?.let { openTxtDocument(it, restoreSavedPosition = false) }
        }
    }

    fun openPreviousFile() {
        viewModelScope.launch {
            val refreshed = _uiState.value
            val files = currentFolderTxtFiles(refreshed)
            val previousIndex = refreshed.selectedFileIndex - 1
            if (previousIndex in files.indices) openTxtDocument(files[previousIndex], restoreSavedPosition = false)
        }
    }

    fun markCurrentAsRead() {
        viewModelScope.launch {
            val marked = markCurrentAsReadIfNeeded()
            if (marked) {
                refreshFilesKeepingSelection()
            }
        }
    }

    fun markCurrentAsUnread() {
        viewModelScope.launch {
            val current = _uiState.value.selectedFile ?: return@launch
            val updatedReadEntries = _uiState.value.readEntries - current.uri.toString() - readNameKey(current.name)
            prefs.saveReadEntries(updatedReadEntries)
            _uiState.value = _uiState.value.copy(
                readEntries = updatedReadEntries,
                selectedFile = current.copy(isRead = false),
                errorMessage = null
            )
            refreshFilesKeepingSelection()
        }
    }

    fun buildExportJson() {
        viewModelScope.launch {
            val json = prefs.exportBackupJson()
            _uiState.value = _uiState.value.copy(exportJsonText = json)
        }
    }

    fun clearExportJson() {
        _uiState.value = _uiState.value.copy(exportJsonText = null)
    }

    fun setFontSize(sp: Float) {
        val normalized = sp.coerceIn(14f, 30f)
        _uiState.value = _uiState.value.copy(fontSizeSp = normalized)
        viewModelScope.launch { prefs.saveFontScale(normalized) }
    }

    fun saveReadingPosition(
        characterOffset: Int,
        sourceText: String,
        scrollY: Int = _uiState.value.currentScrollY,
        chunkIndex: Int = _uiState.value.currentChunkIndex,
        chunkStartOffset: Int = _uiState.value.currentChunkStartOffset,
        chunkEndOffset: Int = _uiState.value.currentChunkEndOffset,
        rangeOffset: Int? = _uiState.value.lastRangeOffset
    ) {
        val fileUri = _uiState.value.selectedFile?.uri?.toString() ?: return
        val safeOffset = characterOffset.coerceIn(0, sourceText.length)
        val position = ReadingPosition(
            fileUri = fileUri,
            characterOffset = safeOffset,
            chunkIndex = chunkIndex,
            chunkStartOffset = chunkStartOffset.coerceIn(0, sourceText.length),
            chunkEndOffset = chunkEndOffset.coerceIn(0, sourceText.length),
            textPreview = previewAround(sourceText, safeOffset),
            scrollY = scrollY.coerceAtLeast(0),
            updatedAt = System.currentTimeMillis()
        )
        _uiState.value = _uiState.value.copy(
            currentCharacterOffset = safeOffset,
            currentScrollY = scrollY.coerceAtLeast(0),
            currentChunkIndex = chunkIndex,
            currentChunkStartOffset = position.chunkStartOffset,
            currentChunkEndOffset = position.chunkEndOffset,
            lastRangeOffset = rangeOffset,
            restoredReadingPosition = position
        )
        viewModelScope.launch { prefs.saveReadingPosition(position) }
    }

    fun rememberVisiblePosition(sourceText: String, characterOffset: Int, scrollY: Int) {
        saveReadingPosition(
            characterOffset = characterOffset,
            sourceText = sourceText,
            scrollY = scrollY,
            chunkIndex = -1,
            chunkStartOffset = characterOffset,
            chunkEndOffset = characterOffset,
            rangeOffset = null
        )
    }

    fun importBackupJson(rawJson: String) {
        viewModelScope.launch {
            runCatching {
                prefs.importBackupJson(rawJson, currentFileUrisByName())
            }.onSuccess { result ->
                val saved = prefs.preferences.first()
                val importMessage = if (result.legacyTxtReaderBackup) {
                    "JSON antiguo importado: ${result.readEntries} leídos restaurados. Ese JSON no contiene posiciones."
                } else {
                    "Backup importado: ${result.readingPositions} posiciones y ${result.readEntries} leídos restaurados."
                }
                _uiState.value = _uiState.value.copy(
                    readEntries = saved.readEntries,
                    errorMessage = importMessage
                )
                refreshFilesKeepingSelection()
            }.onFailure {
                _uiState.value = _uiState.value.copy(
                    errorMessage = "No se pudo importar el backup JSON."
                )
            }
        }
    }

    private fun openTxtDocument(file: TxtDocument, restoreSavedPosition: Boolean = true) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            txtRepository.readText(file.uri)
                .onSuccess { text ->
                    val allFiles = currentFolderTxtFiles()
                    val index = allFiles.indexOfFirst { it.uri == file.uri }
                    val folderUri = file.parentUri ?: _uiState.value.currentFolderUri
                    val savedPosition = if (restoreSavedPosition) {
                        prefs.getReadingPosition(file.uri.toString())
                    } else {
                        null
                    }
                    _uiState.value = _uiState.value.copy(
                        selectedFile = file,
                        selectedFileIndex = index,
                        content = text,
                        isMarkdownContent = file.isMarkdown,
                        isLoading = false,
                        currentFolderUri = folderUri,
                        currentFolderName = folderUri?.let { documentName(it) } ?: _uiState.value.currentFolderName,
                        folderEntries = folderUri?.let { txtRepository.getFolderEntries(it) } ?: _uiState.value.folderEntries,
                        folderStack = if (folderUri != null && folderUri != _uiState.value.treeUri) listOf(folderUri) else emptyList(),
                        restoredReadingPosition = savedPosition,
                        currentCharacterOffset = savedPosition?.characterOffset ?: 0,
                        currentScrollY = savedPosition?.scrollY ?: 0,
                        currentChunkIndex = savedPosition?.chunkIndex ?: -1,
                        currentChunkStartOffset = savedPosition?.chunkStartOffset ?: 0,
                        currentChunkEndOffset = savedPosition?.chunkEndOffset ?: 0,
                        lastRangeOffset = null
                    )
                    prefs.saveCurrentFileUri(file.uri.toString())
                    prefs.saveCurrentFileName(file.name)
                    prefs.saveCurrentFolderUri(folderUri?.toString())
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = "No se pudo leer el archivo seleccionado."
                    )
                }
        }
    }

    private suspend fun reloadTree(treeUri: Uri, currentFolderUri: Uri?, currentFileUri: Uri?, currentFileName: String? = null) {
        val root = txtRepository.getTreeDocument(treeUri) ?: error("Árbol no accesible")
        val rootName = root.name ?: "Biblioteca"
        val visibleFolder = currentFolderUri ?: treeUri
        val folderFiles = txtRepository.getTxtFilesInFolder(visibleFolder, _uiState.value.readEntries)
        _uiState.value = ReaderUiState(
            treeUri = treeUri,
            currentFolderUri = visibleFolder,
            currentFolderName = documentName(visibleFolder) ?: rootName,
            folderStack = if (visibleFolder != treeUri) listOf(visibleFolder) else emptyList(),
            folderEntries = txtRepository.getFolderEntries(visibleFolder),
            allTxtFiles = folderFiles,
            fontSizeSp = _uiState.value.fontSizeSp,
            isLoading = false,
            restoreAvailable = true,
            errorMessage = if (folderFiles.isEmpty()) "No hay archivos .txt o .md en esta carpeta." else null,
            readEntries = _uiState.value.readEntries
        )

        val savedFile = currentFileUri?.let { savedUri ->
            folderFiles.firstOrNull { it.uri == savedUri }
        } ?: currentFileName?.let { savedName ->
            folderFiles.firstOrNull { readNameKey(it.name) == readNameKey(savedName) }
        }
        savedFile?.let {
            openTxtDocument(it)
        }
    }

    private suspend fun markCurrentAsReadIfNeeded(): Boolean {
        val current = _uiState.value.selectedFile ?: return false
        val updatedReadEntries = _uiState.value.readEntries + current.uri.toString() + readNameKey(current.name)
        prefs.saveReadEntries(updatedReadEntries)
        _uiState.value = _uiState.value.copy(readEntries = updatedReadEntries)

        var marked = false
        txtRepository.markAsRead(current.uri, current.parentUri)
            .onSuccess { newUri ->
                val renamedName = documentName(newUri) ?: current.name
                val replacedReadEntries = updatedReadEntries - current.uri.toString() + newUri.toString() + readNameKey(renamedName)
                _uiState.value = _uiState.value.copy(
                    selectedFile = current.copy(uri = newUri, name = renamedName, isRead = true),
                    readEntries = replacedReadEntries
                )
                prefs.saveCurrentFileUri(newUri.toString())
                prefs.saveCurrentFileName(renamedName)
                prefs.saveReadEntries(replacedReadEntries)
                marked = true
            }
            .onFailure {
                _uiState.value = _uiState.value.copy(
                    errorMessage = "No se pudo renombrar el archivo como leído. Se ha guardado igualmente como leído dentro de la app."
                )
                marked = true
            }
        return marked
    }

    private fun refreshFilesKeepingSelection(): ReaderUiState {
        val current = _uiState.value
        val folderUri = current.currentFolderUri ?: return current
        val refreshedFiles = txtRepository.getTxtFilesInFolder(folderUri, current.readEntries)
        val refreshedFolderEntries = txtRepository.getFolderEntries(folderUri)
        val selectedUri = current.selectedFile?.uri
        val newIndex = selectedUri?.let { uri -> refreshedFiles.indexOfFirst { it.uri == uri } } ?: -1
        val refreshedSelected = if (newIndex in refreshedFiles.indices) refreshedFiles[newIndex] else current.selectedFile?.copy(isRead = current.readEntries.contains(current.selectedFile.uri.toString()))
        val refreshedState = current.copy(
            allTxtFiles = refreshedFiles,
            folderEntries = refreshedFolderEntries,
            selectedFileIndex = newIndex,
            selectedFile = refreshedSelected,
            isMarkdownContent = refreshedSelected?.isMarkdown ?: current.isMarkdownContent
        )
        _uiState.value = refreshedState
        return refreshedState
    }

    private fun currentFolderTxtFiles(state: ReaderUiState = _uiState.value): List<TxtDocument> = state.allTxtFiles

    private fun currentFileUrisByName(): Map<String, String> =
        currentFolderTxtFiles().associate { it.name to it.uri.toString() }

    private fun documentName(uri: Uri): String? {
        val app = getApplication<Application>()
        return DocumentFile.fromTreeUri(app, uri)?.name ?: DocumentFile.fromSingleUri(app, uri)?.name
    }

    private fun previewAround(text: String, offset: Int, radius: Int = 80): String {
        if (text.isBlank()) return ""
        val safeOffset = offset.coerceIn(0, text.length)
        val start = (safeOffset - radius).coerceAtLeast(0)
        val end = (safeOffset + radius).coerceAtMost(text.length)
        return text.substring(start, end)
    }

    private fun readNameKey(name: String): String =
        "name:" + Normalizer.normalize(name.trim().lowercase(), Normalizer.Form.NFD)
            .replace("\\p{Mn}+".toRegex(), "")
}
