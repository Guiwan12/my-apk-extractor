package com.example.apkextractor

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    AppScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScreen(vm: MainViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    // Remember the destination folder so we only ask once per session.
    var treeUri by remember { mutableStateOf<Uri?>(null) }
    // What to run once we have a folder: extract selection, or a single app.
    var pending by remember { mutableStateOf<AppInfo?>(null) }
    var pendingIsSelection by remember { mutableStateOf(false) }

    val context = androidx.compose.ui.platform.LocalContext.current

    // SAF folder picker (ACTION_OPEN_DOCUMENT_TREE under the hood).
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            // Persist access so the URI keeps working after reboot.
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            treeUri = uri
            when {
                pendingIsSelection -> vm.extract(uri)
                pending != null -> vm.extract(uri, pending)
            }
        }
        pending = null
        pendingIsSelection = false
    }

    fun runExtraction(single: AppInfo?) {
        val uri = treeUri
        if (uri == null) {
            // No folder yet — remember intent, then ask for one.
            pending = single
            pendingIsSelection = single == null
            folderPicker.launch(null)
        } else {
            vm.extract(uri, single)
        }
    }

    LaunchedEffect(state.lastMessage) {
        state.lastMessage?.let {
            snackbar.showSnackbar(it)
            vm.clearMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (state.inSelectionMode) {
                TopAppBar(
                    title = { Text("${state.selected.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = vm::clearSelection) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel")
                        }
                    },
                    actions = {
                        IconButton(onClick = vm::selectAllVisible) {
                            Icon(Icons.Default.SelectAll, contentDescription = "Select all")
                        }
                    }
                )
            } else {
                TopAppBar(
                    title = { Text("My APK Extractor") },
                    actions = {
                        IconButton(onClick = vm::toggleSystemApps) {
                            Icon(
                                if (state.showSystemApps) Icons.Default.Android
                                else Icons.Default.Person,
                                contentDescription = "Toggle system apps"
                            )
                        }
                        IconButton(onClick = vm::refresh) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            if (state.inSelectionMode) {
                ExtendedFloatingActionButton(
                    onClick = { runExtraction(null) },
                    icon = { Icon(Icons.Default.Download, contentDescription = null) },
                    text = { Text(if (state.extracting) "Extracting…" else "Extract") },
                )
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {

            OutlinedTextField(
                value = state.query,
                onValueChange = vm::onQueryChange,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                placeholder = { Text("Search apps") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )

            if (state.loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                val apps = state.visibleApps
                if (apps.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No apps found")
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(apps, key = { it.packageName }) { app ->
                            AppRow(
                                app = app,
                                selected = app.packageName in state.selected,
                                selectionMode = state.inSelectionMode,
                                onClick = {
                                    if (state.inSelectionMode) vm.toggleSelection(app.packageName)
                                    else runExtraction(app)
                                },
                                onLongClick = { vm.toggleSelection(app.packageName) },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun AppRow(
    app: AppInfo,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val iconBitmap = remember(app.packageName) {
        app.icon.toBitmap(96, 96).asImageBitmap()
    }
    ListItem(
        headlineContent = {
            Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Text(
                "${app.packageName}\nv${app.versionName} · ${app.readableSize}" +
                    if (app.isSplit) " · split" else "",
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = {
            Image(bitmap = iconBitmap, contentDescription = null, modifier = Modifier.size(44.dp))
        },
        trailingContent = {
            if (selectionMode) {
                Checkbox(checked = selected, onCheckedChange = { onClick() })
            } else {
                Icon(Icons.Default.Download, contentDescription = "Extract")
            }
        },
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
    )
}
