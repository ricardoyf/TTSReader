package com.ricardo.txtreader.navigation

import android.content.Intent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ricardo.txtreader.ui.screens.LibraryScreen
import com.ricardo.txtreader.ui.screens.ReaderScreen
import com.ricardo.txtreader.ui.viewmodel.ReaderViewModel
import com.ricardo.txtreader.ui.viewmodel.ReaderViewModelFactory
import com.ricardo.txtreader.model.SpeechChunk
import java.io.File
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun TxtReaderApp() {
    val navController = rememberNavController()
    val factory = remember { ReaderViewModelFactory() }
    val viewModel: ReaderViewModel = viewModel(factory = factory)
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    var ttsReady by remember { mutableStateOf(false) }
    var ttsSpeaking by remember { mutableStateOf(false) }
    var ttsMessage by remember { mutableStateOf<String?>(null) }
    var speechSourceText by remember { mutableStateOf("") }
    var speechChunks by remember { mutableStateOf<List<SpeechChunk>>(emptyList()) }
    var speechIndex by remember { mutableIntStateOf(0) }
    var speechSessionId by remember { mutableIntStateOf(0) }
    var speechLastOffset by remember { mutableIntStateOf(0) }
    var speechRate by remember { mutableStateOf(1.2f) }
    var ttsPaused by remember { mutableStateOf(false) }
    var ttsHighlightStart by remember { mutableStateOf<Int?>(null) }
    var ttsHighlightEnd by remember { mutableStateOf<Int?>(null) }
    var continuousPlayback by remember { mutableStateOf(false) }
    var pendingContinuousTargetUri by remember { mutableStateOf<String?>(null) }
    val importBackupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val rawJson = runCatching {
                context.contentResolver.openInputStream(uri)
                    ?.bufferedReader(Charsets.UTF_8)
                    ?.use { it.readText() }
            }.getOrNull()
            if (rawJson.isNullOrBlank()) {
                ttsMessage = "No se pudo leer el JSON."
            } else {
                viewModel.importBackupJson(rawJson)
                ttsMessage = "Importando backup"
            }
        }
    }

    fun saveSpeechPosition(chunk: SpeechChunk?, characterOffset: Int? = null) {
        if (speechSourceText.isBlank()) return
        val activeChunk = chunk ?: speechChunks.getOrNull(speechIndex) ?: return
        val offset = (
            characterOffset
                ?: speechLastOffset.takeIf { it in activeChunk.startOffset..activeChunk.endOffset }
                ?: activeChunk.startOffset
            ).coerceIn(0, speechSourceText.length)
        viewModel.saveReadingPosition(
            characterOffset = offset,
            sourceText = speechSourceText,
            chunkIndex = activeChunk.index,
            chunkStartOffset = activeChunk.startOffset,
            chunkEndOffset = activeChunk.endOffset,
            rangeOffset = characterOffset
        )
    }

    fun stopSpeech(savePosition: Boolean = true) {
        val activeChunk = speechChunks.getOrNull(speechIndex)
        if (savePosition) saveSpeechPosition(activeChunk, ttsHighlightStart ?: speechLastOffset)
        ttsSpeaking = false
        speechSessionId += 1
        tts?.stop()
        ttsPaused = false
        speechChunks = emptyList()
        speechIndex = 0
        speechLastOffset = 0
        ttsHighlightStart = null
        ttsHighlightEnd = null
    }

    fun speakChunk(index: Int) {
        val engine = tts ?: return
        val chunk = speechChunks.getOrNull(index)
        if (chunk == null) {
            ttsSpeaking = false
            ttsPaused = false
            speechChunks = emptyList()
            speechIndex = 0
            ttsHighlightStart = null
            ttsHighlightEnd = null
            return
        }
        speechIndex = index
        speechLastOffset = chunk.startOffset
        ttsSpeaking = true
        ttsPaused = false
        ttsHighlightStart = chunk.startOffset
        ttsHighlightEnd = chunk.endOffset
        ttsMessage = "Leyendo ${index + 1}/${speechChunks.size}"
        saveSpeechPosition(chunk)
        engine.setSpeechRate(speechRate)
        engine.speak(
            chunk.text,
            TextToSpeech.QUEUE_FLUSH,
            null,
            utteranceId(speechSessionId, chunk)
        )
    }

    fun startReadingText(text: String, startOffset: Int = 0) {
        if (!ttsReady) {
            ttsMessage = "El TTS del sistema aún no está listo."
            return
        }
        val safeOffset = startOffset.coerceIn(0, text.length)
        val chunks = splitSpeechText(text, startOffset = safeOffset)
        if (chunks.isEmpty()) {
            ttsMessage = "No hay texto para leer."
            return
        }
        stopSpeech(savePosition = false)
        speechSessionId += 1
        speechSourceText = text
        speechChunks = chunks
        speechIndex = 0
        speechLastOffset = safeOffset
        viewModel.saveReadingPosition(
            characterOffset = safeOffset,
            sourceText = text,
            chunkIndex = chunks.first().index,
            chunkStartOffset = chunks.first().startOffset,
            chunkEndOffset = chunks.first().endOffset,
            rangeOffset = safeOffset
        )
        speakChunk(0)
    }

    fun pauseSpeech() {
        if (!ttsSpeaking) return
        saveSpeechPosition(speechChunks.getOrNull(speechIndex), ttsHighlightStart ?: speechLastOffset)
        ttsSpeaking = false
        speechSessionId += 1
        tts?.stop()
        ttsPaused = speechChunks.isNotEmpty()
        ttsMessage = if (ttsPaused) {
            "Pausado ${speechIndex + 1}/${speechChunks.size}"
        } else {
            "Pausado"
        }
    }

    fun resumeSpeech() {
        val fallbackText = speechSourceText.ifBlank { state.content }
        startReadingText(fallbackText, state.currentCharacterOffset)
    }

    fun changeSpeechRate(nextRate: Float) {
        speechRate = nextRate.coerceIn(0.6f, 1.6f)
        tts?.setSpeechRate(speechRate)
        ttsMessage = "Velocidad ${"%.1f".format(Locale.US, speechRate)}x"
    }

    DisposableEffect(Unit) {
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context.applicationContext) { status ->
            scope.launch {
                if (status == TextToSpeech.SUCCESS) {
                    val result = engine?.setLanguage(Locale("es", "ES"))
                    ttsReady = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
                    ttsMessage = if (ttsReady) null else "El TTS español no está disponible en este móvil."
                } else {
                    ttsReady = false
                    ttsMessage = "No se pudo iniciar el TTS del sistema."
                }
            }
        }
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                scope.launch {
                    val parsed = parseUtteranceId(utteranceId)
                    if (parsed?.sessionId != null && parsed.sessionId != speechSessionId) return@launch
                    val chunk = speechChunks.getOrNull(parsed?.chunkIndex ?: speechIndex)
                    ttsHighlightStart = chunk?.startOffset
                    ttsHighlightEnd = chunk?.endOffset
                    saveSpeechPosition(chunk)
                }
            }

            override fun onDone(utteranceId: String?) {
                scope.launch {
                    val parsed = parseUtteranceId(utteranceId)
                    if (!ttsSpeaking) return@launch
                    if (parsed?.sessionId != null && parsed.sessionId != speechSessionId) return@launch
                    if (parsed?.chunkIndex != null && parsed.chunkIndex != speechIndex) return@launch
                    val next = speechIndex + 1
                    if (next < speechChunks.size) {
                        speakChunk(next)
                    } else {
                        val nextFile = state.allTxtFiles.getOrNull(state.selectedFileIndex + 1)
                        ttsSpeaking = false
                        ttsPaused = false
                        speechChunks = emptyList()
                        speechIndex = 0
                        ttsHighlightStart = null
                        ttsHighlightEnd = null
                        speechLastOffset = 0
                        if (continuousPlayback && nextFile != null) {
                            pendingContinuousTargetUri = nextFile.uri.toString()
                            ttsMessage = "Pasando al siguiente archivo"
                            viewModel.openNextFile()
                        } else {
                            if (continuousPlayback) viewModel.markCurrentAsRead()
                            ttsMessage = "Lectura terminada"
                        }
                    }
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                scope.launch {
                    saveSpeechPosition(speechChunks.getOrNull(speechIndex))
                    ttsSpeaking = false
                    ttsPaused = false
                    ttsMessage = "Error durante la lectura en voz alta."
                }
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                scope.launch {
                    if (!ttsSpeaking) return@launch
                    val parsed = parseUtteranceId(utteranceId)
                    if (parsed?.sessionId != null && parsed.sessionId != speechSessionId) return@launch
                    saveSpeechPosition(speechChunks.getOrNull(parsed?.chunkIndex ?: speechIndex))
                }
            }

            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                scope.launch {
                    val parsed = parseUtteranceId(utteranceId)
                    if (parsed?.sessionId != null && parsed.sessionId != speechSessionId) return@launch
                    val chunk = speechChunks.getOrNull(parsed?.chunkIndex ?: speechIndex) ?: return@launch
                    val absoluteStart = (chunk.startOffset + start).coerceIn(chunk.startOffset, chunk.endOffset)
                    val absoluteEnd = (chunk.startOffset + end).coerceIn(absoluteStart, chunk.endOffset)
                    speechLastOffset = absoluteStart
                    ttsHighlightStart = absoluteStart
                    ttsHighlightEnd = absoluteEnd
                    saveSpeechPosition(chunk, absoluteStart)
                }
            }
        })
        tts = engine
        onDispose {
            saveSpeechPosition(speechChunks.getOrNull(speechIndex))
            engine.stop()
            engine.shutdown()
        }
    }

    LaunchedEffect(state.selectedFile?.uri, state.content, pendingContinuousTargetUri, ttsReady) {
        val targetUri = pendingContinuousTargetUri ?: return@LaunchedEffect
        if (!ttsReady || state.selectedFile?.uri?.toString() != targetUri || state.content.isBlank()) {
            return@LaunchedEffect
        }
        pendingContinuousTargetUri = null
        startReadingText(speechTextFromContent(state.content, state.isMarkdownContent), 0)
    }

    LaunchedEffect(state.exportJsonText) {
        val json = state.exportJsonText ?: return@LaunchedEffect
        val exportFile = File(context.cacheDir, "ttsreader_backup.json")
        exportFile.writeText(json)
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", exportFile)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "TTS Reader - backup")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Enviar backup JSON"))
        viewModel.clearExportJson()
    }

    NavHost(navController = navController, startDestination = Destination.Library.route) {
        composable(Destination.Library.route) {
            LibraryScreen(
                state = state,
                onPickFolder = viewModel::onFolderSelected,
                onOpenFolder = viewModel::openFolder,
                onNavigateUp = viewModel::navigateUp,
                onOpenFile = {
                    viewModel.openFile(it)
                    navController.navigate(Destination.Reader.route)
                },
                onRetryRestore = viewModel::restoreLastState
            )
        }
        composable(Destination.Reader.route) {
            ReaderScreen(
                state = state,
                onBack = { navController.popBackStack() },
                onFontSizeChange = viewModel::setFontSize,
                onNextFile = {
                    stopSpeech(savePosition = true)
                    viewModel.openNextFile()
                },
                onPreviousFile = {
                    stopSpeech(savePosition = true)
                    viewModel.openPreviousFile()
                },
                onExportJson = viewModel::buildExportJson,
                onImportJson = { importBackupLauncher.launch(arrayOf("application/json", "text/*")) },
                onOpenLibrary = { navController.navigate(Destination.Library.route) },
                ttsReady = ttsReady,
                ttsSpeaking = ttsSpeaking,
                ttsPaused = ttsPaused,
                ttsHighlightStart = ttsHighlightStart,
                ttsHighlightEnd = ttsHighlightEnd,
                ttsPositionLocked = speechChunks.isNotEmpty() || ttsSpeaking || ttsPaused,
                speechRate = speechRate,
                continuousPlayback = continuousPlayback,
                onStartReading = { text, startOffset -> startReadingText(text, startOffset) },
                onStartReadingFromText = { selectedText, startOffset ->
                    startReadingText(selectedText, startOffset)
                },
                onPauseReading = ::pauseSpeech,
                onResumeReading = ::resumeSpeech,
                onSpeechRateChange = ::changeSpeechRate,
                onToggleContinuousPlayback = {
                    continuousPlayback = !continuousPlayback
                    if (!continuousPlayback) pendingContinuousTargetUri = null
                    ttsMessage = if (continuousPlayback) "Continuo activado" else "Continuo desactivado"
                },
                onManualPositionChanged = viewModel::rememberVisiblePosition
            )
        }
    }
}

private data class ParsedUtteranceId(
    val sessionId: Int?,
    val chunkIndex: Int?
)

private fun utteranceId(sessionId: Int, chunk: SpeechChunk): String =
    "txtreader-$sessionId-${chunk.index}-${chunk.startOffset}-${chunk.endOffset}"

private fun parseUtteranceId(utteranceId: String?): ParsedUtteranceId? {
    val parts = utteranceId?.split("-") ?: return null
    if (parts.size < 5 || parts[0] != "txtreader") return null
    return ParsedUtteranceId(
        sessionId = parts[1].toIntOrNull(),
        chunkIndex = parts[2].toIntOrNull()
    )
}

private fun speechTextFromContent(content: String, isMarkdown: Boolean): String {
    if (!isMarkdown) return content
    return content
        .replace(Regex("```[\\s\\S]*?```"), " ")
        .replace(Regex("`([^`]+)`"), "$1")
        .replace(Regex("^#{1,6}\\s*", RegexOption.MULTILINE), "")
        .replace(Regex("""!\[[^\]]*]\([^)]+\)"""), " ")
        .replace(Regex("""\[([^\]]+)]\([^)]+\)"""), "$1")
        .replace(Regex("""[*_>#~-]+"""), " ")
        .replace(Regex("""\|"""), " ")
}

private fun splitSpeechText(text: String, startOffset: Int = 0, maxChars: Int = 2800): List<SpeechChunk> {
    if (text.isBlank()) return emptyList()

    val chunks = mutableListOf<SpeechChunk>()
    var cursor = startOffset.coerceIn(0, text.length)
    while (cursor < text.length) {
        while (cursor < text.length && text[cursor].isWhitespace()) cursor++
        if (cursor >= text.length) break

        val hardEnd = (cursor + maxChars).coerceAtMost(text.length)
        var end = if (hardEnd == text.length) {
            hardEnd
        } else {
            preferredBreak(text, cursor, hardEnd)
        }
        if (end <= cursor) end = hardEnd
        while (end > cursor && text[end - 1].isWhitespace()) end--
        if (end <= cursor) {
            cursor = hardEnd
            continue
        }
        chunks += SpeechChunk(
            index = chunks.size,
            text = text.substring(cursor, end),
            startOffset = cursor,
            endOffset = end
        )
        cursor = end
    }
    return chunks
}

private fun preferredBreak(text: String, start: Int, hardEnd: Int): Int {
    val minUseful = start + 240
    val sentenceBreak = (hardEnd - 1 downTo minUseful).firstOrNull { index ->
        val char = text[index]
        (char == '.' || char == '!' || char == '?' || char == '…' || char == '\n') &&
            index + 1 < text.length &&
            text[index + 1].isWhitespace()
    }
    if (sentenceBreak != null) return sentenceBreak + 1

    val whitespaceBreak = (hardEnd - 1 downTo minUseful).firstOrNull { text[it].isWhitespace() }
    return whitespaceBreak ?: hardEnd
}
