package com.example.apkextractor

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var themeMode by remember { mutableStateOf(ThemeStore.load(this)) }
            MaterialTheme(colorScheme = colorSchemeFor(themeMode)) {
                Surface(Modifier.fillMaxSize()) {
                    AppScreen(
                        themeMode = themeMode,
                        onPickTheme = { mode ->
                            themeMode = mode
                            ThemeStore.save(this, mode)
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScreen(
    vm: MainViewModel = viewModel(),
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    onPickTheme: (ThemeMode) -> Unit = {},
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    var treeUri by remember { mutableStateOf<Uri?>(null) }
    // Action to run once we have a destination folder.
    var pendingFolderAction by remember { mutableStateOf<((Uri) -> Unit)?>(null) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    var overflowOpen by remember { mutableStateOf(false) }
    var themeDialogOpen by remember { mutableStateOf(false) }

    fun copyPackage(pkg: String) {
        clipboard.setText(AnnotatedString(pkg))
        scope.launch { snackbar.showSnackbar("Copied $pkg") }
    }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            treeUri = uri
            pendingFolderAction?.invoke(uri)
        }
        pendingFolderAction = null
    }

    // Run [action] with a destination folder, asking for one if we don't have it.
    fun withFolder(action: (Uri) -> Unit) {
        val uri = treeUri
        if (uri == null) {
            pendingFolderAction = action
            folderPicker.launch(null)
        } else action(uri)
    }

    // Turn a staged share URI into a system share chooser.
    LaunchedEffect(state.shareUri) {
        state.shareUri?.let { uri ->
            context.startActivity(
                Intent.createChooser(Sharing.buildSendIntent(uri), "Share APK")
            )
            vm.consumeShare()
        }
    }

    LaunchedEffect(state.lastMessage) {
        state.lastMessage?.let { snackbar.showSnackbar(it); vm.clearMessage() }
    }

    // Detail bottom sheet.
    state.detailApp?.let { app ->
        AppDetailSheet(
            app = app,
            onExtract = { withFolder { uri -> vm.extract(uri, app) }; vm.closeDetail() },
            onShare = { vm.requestShare(app); vm.closeDetail() },
            onSaveIcon = { withFolder { uri -> vm.extractIcon(uri, app) }; vm.closeDetail() },
            onCopyPackage = { copyPackage(app.packageName) },
            onDismiss = vm::closeDetail,
        )
    }

    // Extraction progress window.
    state.progressFraction?.let { fraction ->
        ExtractionProgressDialog(
            fraction = fraction,
            label = state.progressLabel.orEmpty(),
            destination = state.progressDest,
        )
    }

    // Theme picker.
    if (themeDialogOpen) {
        ThemeDialog(
            current = themeMode,
            onPick = { onPickTheme(it); themeDialogOpen = false },
            onDismiss = { themeDialogOpen = false },
        )
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
                        // Sort menu
                        Box {
                            IconButton(onClick = { sortMenuOpen = true }) {
                                Icon(Icons.Default.Sort, contentDescription = "Sort")
                            }
                            DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                                SortOrder.values().forEach { order ->
                                    DropdownMenuItem(
                                        text = { Text(order.label) },
                                        onClick = { vm.setSortOrder(order); sortMenuOpen = false },
                                        leadingIcon = {
                                            if (order == state.sortOrder)
                                                Icon(Icons.Default.Check, contentDescription = null)
                                        }
                                    )
                                }
                            }
                        }
                        IconButton(onClick = vm::toggleSystemApps) {
                            Icon(
                                if (state.showSystemApps) Icons.Default.Android else Icons.Default.Person,
                                contentDescription = "Toggle system apps"
                            )
                        }
                        IconButton(onClick = vm::refresh) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                        }
                        Box {
                            IconButton(onClick = { overflowOpen = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "More")
                            }
                            DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("Theme") },
                                    leadingIcon = { Icon(Icons.Default.Palette, contentDescription = null) },
                                    onClick = { overflowOpen = false; themeDialogOpen = true },
                                )
                            }
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            if (state.inSelectionMode) {
                ExtendedFloatingActionButton(
                    onClick = { withFolder { uri -> vm.extract(uri) } },
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

            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                state.visibleApps.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No apps found")
                }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(state.visibleApps, key = { it.packageName }) { app ->
                        AppRow(
                            app = app,
                            selected = app.packageName in state.selected,
                            selectionMode = state.inSelectionMode,
                            onClick = {
                                if (state.inSelectionMode) vm.toggleSelection(app.packageName)
                                else vm.openDetail(app)
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppRow(
    app: AppInfo,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val iconBitmap = remember(app.packageName) { app.icon.toBitmap(96, 96).asImageBitmap() }
    ListItem(
        headlineContent = { Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                "${app.packageName}\nv${app.versionName} · ${app.readableSize}" +
                    if (app.isSplit) " · split" else "",
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = {
            Image(bitmap = iconBitmap, contentDescription = null, modifier = Modifier.size(44.dp))
        },
        trailingContent = {
            if (selectionMode) Checkbox(checked = selected, onCheckedChange = { onClick() })
        },
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun AppDetailSheet(
    app: AppInfo,
    onExtract: () -> Unit,
    onShare: () -> Unit,
    onSaveIcon: () -> Unit,
    onCopyPackage: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val dateFmt = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    bitmap = remember(app.packageName) { app.icon.toBitmap(120, 120).asImageBitmap() },
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(app.label, style = MaterialTheme.typography.titleLarge, maxLines = 2)
                    // Tap or long-press the package name to copy it.
                    Text(
                        app.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.combinedClickable(
                            onClick = onCopyPackage,
                            onLongClick = onCopyPackage,
                        ),
                    )
                }
                IconButton(onClick = onCopyPackage) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy package name")
                }
            }

            Spacer(Modifier.height(16.dp))

            // Action buttons
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onExtract, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Download, contentDescription = null); Spacer(Modifier.width(6.dp)); Text("Extract")
                }
                OutlinedButton(onClick = onShare, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Share, contentDescription = null); Spacer(Modifier.width(6.dp)); Text("Share")
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onSaveIcon, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Image, contentDescription = null); Spacer(Modifier.width(6.dp)); Text("Save icon as PNG")
            }

            Spacer(Modifier.height(20.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))

            // Facts
            InfoRow("Version", "${app.versionName} (${app.versionCode})")
            InfoRow("Size", app.readableSize + if (app.isSplit) "  (${app.sourceApks.size} APKs)" else "")
            InfoRow("Min / Target SDK", "${app.minSdk} / ${app.targetSdk}")
            InfoRow("Type", if (app.isSystemApp) "System app" else "User app")
            if (app.lastUpdateTime > 0) InfoRow("Updated", dateFmt.format(Date(app.lastUpdateTime)))
            if (app.firstInstallTime > 0) InfoRow("Installed", dateFmt.format(Date(app.firstInstallTime)))

            Spacer(Modifier.height(16.dp))
            Text("File location", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            app.sourceApks.forEach { path ->
                Text(
                    path,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(vertical = 1.dp),
                )
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "Permissions (${app.permissions.size})",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            if (app.permissions.isEmpty()) {
                Text("None declared", style = MaterialTheme.typography.bodyMedium)
            } else {
                app.permissions.forEach { perm ->
                    Text(
                        "• " + perm.substringAfterLast('.'),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 1.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ExtractionProgressDialog(
    fraction: Float,
    label: String,
    destination: String?,
) {
    val percent = (fraction * 100).toInt()
    AlertDialog(
        onDismissRequest = { /* block dismiss while extracting */ },
        confirmButton = {},
        title = { Text("Extracting") },
        text = {
            Column {
                Text(label, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text("$percent% complete · ${100 - percent}% remaining")
                destination?.let {
                    Spacer(Modifier.height(4.dp))
                    Text("→ $it", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
    )
}

@Composable
private fun ThemeDialog(
    current: ThemeMode,
    onPick: (ThemeMode) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
        title = { Text("Theme") },
        text = {
            Column {
                ThemeMode.entries.forEach { mode ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = mode == current, onClick = { onPick(mode) })
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = mode == current, onClick = { onPick(mode) })
                        Spacer(Modifier.width(8.dp))
                        Text(mode.label)
                    }
                }
            }
        },
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(140.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
