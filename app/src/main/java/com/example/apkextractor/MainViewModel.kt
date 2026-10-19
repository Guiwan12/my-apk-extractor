package com.example.apkextractor

import android.app.Application
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SortOrder(val label: String) {
    NAME("Name"),
    SIZE_DESC("Size"),
    UPDATED_DESC("Last updated"),
}

/** Immutable snapshot of everything the screen needs to render. */
data class UiState(
    val loading: Boolean = true,
    val allApps: List<AppInfo> = emptyList(),
    val query: String = "",
    val showSystemApps: Boolean = false,
    val sortOrder: SortOrder = SortOrder.NAME,
    val selected: Set<String> = emptySet(), // package names
    val detailApp: AppInfo? = null,          // app shown in the detail sheet
    val extracting: Boolean = false,
    // Live extraction progress (null when idle) — drives the progress window.
    val progressFraction: Float? = null,     // 0f..1f
    val progressLabel: String? = null,       // e.g. "Extracting Maps (2/5)"
    val progressDest: String? = null,        // destination folder name
    val shareUri: Uri? = null,               // one-shot: launch a share chooser
    val lastMessage: String? = null,
) {
    val visibleApps: List<AppInfo>
        get() = allApps
            .filter { showSystemApps || !it.isSystemApp }
            .filter {
                query.isBlank() ||
                    it.label.contains(query, ignoreCase = true) ||
                    it.packageName.contains(query, ignoreCase = true)
            }
            .let { list ->
                when (sortOrder) {
                    SortOrder.NAME -> list.sortedBy { it.label.lowercase() }
                    SortOrder.SIZE_DESC -> list.sortedByDescending { it.totalSizeBytes }
                    SortOrder.UPDATED_DESC -> list.sortedByDescending { it.lastUpdateTime }
                }
            }

    val inSelectionMode: Boolean get() = selected.isNotEmpty()
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = AppRepository(app)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        _state.value = _state.value.copy(loading = true)
        viewModelScope.launch {
            val apps = withContext(Dispatchers.IO) { repo.loadInstalledApps() }
            _state.value = _state.value.copy(loading = false, allApps = apps)
        }
    }

    fun onQueryChange(q: String) { _state.value = _state.value.copy(query = q) }
    fun toggleSystemApps() { _state.value = _state.value.copy(showSystemApps = !_state.value.showSystemApps) }
    fun setSortOrder(order: SortOrder) { _state.value = _state.value.copy(sortOrder = order) }

    fun toggleSelection(pkg: String) {
        val current = _state.value.selected
        _state.value = _state.value.copy(
            selected = if (pkg in current) current - pkg else current + pkg
        )
    }

    fun selectAllVisible() {
        _state.value = _state.value.copy(selected = _state.value.visibleApps.map { it.packageName }.toSet())
    }

    fun clearSelection() { _state.value = _state.value.copy(selected = emptySet()) }
    fun openDetail(app: AppInfo) { _state.value = _state.value.copy(detailApp = app) }
    fun closeDetail() { _state.value = _state.value.copy(detailApp = null) }
    fun clearMessage() { _state.value = _state.value.copy(lastMessage = null) }
    fun consumeShare() { _state.value = _state.value.copy(shareUri = null) }

    /** Extract the current selection, or a single app if [single] is given. */
    fun extract(treeUri: Uri, single: AppInfo? = null) {
        val targets: List<AppInfo> = when {
            single != null -> listOf(single)
            else -> _state.value.allApps.filter { it.packageName in _state.value.selected }
        }
        if (targets.isEmpty()) return

        val total = targets.sumOf { it.totalSizeBytes }.coerceAtLeast(1L)
        val app = getApplication<Application>()
        val destName = DocumentFile.fromTreeUri(app, treeUri)?.name ?: "selected folder"

        _state.value = _state.value.copy(
            extracting = true,
            progressFraction = 0f,
            progressLabel = "Starting…",
            progressDest = destName,
        )
        viewModelScope.launch {
            var ok = 0; var failed = 0
            var copied = 0L
            var lastEmit = 0L
            withContext(Dispatchers.IO) {
                targets.forEachIndexed { index, target ->
                    _state.value = _state.value.copy(
                        progressLabel = "Extracting ${target.label} (${index + 1}/${targets.size})"
                    )
                    val result = ApkExtractor.extract(app, treeUri, target) { delta ->
                        copied += delta
                        // Throttle UI updates to ~every 256 KB so we don't flood state.
                        if (copied - lastEmit >= 256 * 1024 || copied >= total) {
                            lastEmit = copied
                            _state.value = _state.value.copy(
                                progressFraction = (copied.toFloat() / total).coerceIn(0f, 1f)
                            )
                        }
                    }
                    when (result) {
                        is ApkExtractor.Result.Success -> ok++
                        is ApkExtractor.Result.Failure -> failed++
                    }
                }
            }
            val msg = buildString {
                append("Extracted $ok APK"); if (ok != 1) append("s")
                append(" to $destName")
                if (failed > 0) append(" · $failed failed")
            }
            _state.value = _state.value.copy(
                extracting = false,
                progressFraction = null,
                progressLabel = null,
                progressDest = null,
                selected = emptySet(),
                lastMessage = msg,
            )
        }
    }

    /** Save a single app's icon as a PNG into the chosen folder. */
    fun extractIcon(treeUri: Uri, app: AppInfo) {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val result = withContext(Dispatchers.IO) { ApkExtractor.extractIcon(ctx, treeUri, app) }
            val msg = when (result) {
                is ApkExtractor.Result.Success -> "Saved ${result.fileName}"
                is ApkExtractor.Result.Failure -> "Couldn't save icon"
            }
            _state.value = _state.value.copy(lastMessage = msg)
        }
    }

    /** Stage the APK for sharing, then emit a URI the UI turns into a chooser. */
    fun requestShare(app: AppInfo) {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            try {
                val uri = withContext(Dispatchers.IO) { Sharing.prepareShareUri(ctx, app) }
                _state.value = _state.value.copy(shareUri = uri)
            } catch (e: Exception) {
                _state.value = _state.value.copy(lastMessage = "Couldn't prepare APK to share")
            }
        }
    }
}
