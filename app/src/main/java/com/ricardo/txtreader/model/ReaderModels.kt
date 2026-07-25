package com.ricardo.txtreader.model

import android.net.Uri

data class TxtDocument(
    val uri: Uri,
    val name: String,
    val parentUri: Uri?,
    val isRead: Boolean = false,
    val isMarkdown: Boolean = false
)

data class FolderEntry(
    val uri: Uri,
    val name: String,
    val isDirectory: Boolean
)

data class ReaderPreferences(
    val treeUri: String? = null,
    val currentFolderUri: String? = null,
    val currentFileUri: String? = null,
    val currentFileName: String? = null,
    val fontScaleSp: Float = 19f,
    val readEntries: Set<String> = emptySet()
)

data class ReadingPosition(
    val fileUri: String,
    val characterOffset: Int,
    val chunkIndex: Int,
    val chunkStartOffset: Int,
    val chunkEndOffset: Int,
    val textPreview: String,
    val scrollY: Int,
    val updatedAt: Long
)

data class SpeechChunk(
    val index: Int,
    val text: String,
    val startOffset: Int,
    val endOffset: Int
)
