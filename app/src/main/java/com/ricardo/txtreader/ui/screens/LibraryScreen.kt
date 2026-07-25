package com.ricardo.txtreader.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ricardo.txtreader.ui.components.EmptyStateCard
import com.ricardo.txtreader.ui.viewmodel.ReaderUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    state: ReaderUiState,
    onPickFolder: (Uri) -> Unit,
    onOpenFolder: (Uri) -> Unit,
    onNavigateUp: () -> Unit,
    onOpenFile: (Uri) -> Unit,
    onRetryRestore: () -> Unit
) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(onPickFolder)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("TTS Reader")
                        Text(
                            text = state.currentFolderName,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    if (state.currentFolderUri != null && state.currentFolderUri != state.treeUri) {
                        IconButton(onClick = onNavigateUp) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Subir")
                        }
                    }
                },
                actions = {
                    if (state.restoreAvailable) {
                        IconButton(onClick = onRetryRestore) {
                            Icon(Icons.Default.Refresh, contentDescription = "Restaurar")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                state.isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                state.treeUri == null -> {
                    EmptyStateCard(
                        title = "Selecciona una carpeta",
                        description = "La app usa Storage Access Framework para abrir una carpeta del móvil y navegar por sus subcarpetas y archivos .txt o .md sin pedir permisos de almacenamiento clásicos.",
                        actionLabel = "Abrir carpeta",
                        onAction = { launcher.launch(null) },
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(20.dp)
                    )
                }
                else -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(onClick = { launcher.launch(null) }) {
                                Icon(Icons.Default.FolderOpen, contentDescription = null)
                                Text(" Cambiar carpeta")
                            }
                            Text("${state.allTxtFiles.size} txt/md", style = MaterialTheme.typography.labelLarge)
                        }

                        state.errorMessage?.let {
                            Text(
                                text = it,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }

                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 24.dp)
                        ) {
                            items(state.folderEntries, key = { it.uri.toString() }) { entry ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            if (entry.isDirectory) onOpenFolder(entry.uri) else onOpenFile(entry.uri)
                                        }
                                        .padding(horizontal = 16.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Icon(
                                        imageVector = if (entry.isDirectory) Icons.Default.Folder else Icons.Default.Description,
                                        contentDescription = null
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(entry.name, style = MaterialTheme.typography.bodyLarge)
                                        Text(
                                            text = if (entry.isDirectory) "Carpeta" else if (entry.name.lowercase().endsWith(".md") || entry.name.lowercase().endsWith(".markdown")) "Markdown" else "Archivo de texto",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (!entry.isDirectory && state.readEntries.contains(entry.uri.toString())) {
                                        Icon(
                                            Icons.Default.CheckCircle,
                                            contentDescription = "Leído",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }
}
