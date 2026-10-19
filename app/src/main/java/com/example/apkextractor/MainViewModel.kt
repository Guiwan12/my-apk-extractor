package com.example.apkextractor

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Immutable snapshot of everything the screen needs to render. */
data class UiState(
    val loading: Boolean = true,
    val allApps: List<AppInfo> = emptyList(),
    val query: String = "",
    val showSystemApps: Boolean = false,
    val selected: Set<String> = emptySet(), // package names
    val extracting: Boolean = false,
    val lastMessage: String? = null,
) {
    /** The filtered list actually shown, derived from the raw list + filters. */
    val visibleApps: List<AppInfo>
        get() = allApps
            .filter { showSystemApps || !it.isSystemApp }
            .filter {
                query.isBlank() ||
                    it.label.contains(query, ignoreCase = true) ||
                    it.packageName.contains(query, ignoreCase = true)
            }

    val inSelectionMode: Boolean get() = selected.isNotEmpty()
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = AppRepository(app)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.value = _state.value.copy(loading = true)
        viewModelScope.launch {
            val apps = withContext(Dispatchers.IO) { repo.loadInstalledApps() }
            _state.value = _state.value.copy(loading = false, allApps = apps)
        }
    }

    fun onQueryChange(q: String) {
        _state.value = _state.value.copy(query = q)
    }

    fun toggleSystemApps() {
        _state.value = _state.value.copy(showSystemApps = !_state.value.showSystemApps)
    }

    fun toggleSelection(pkg: String) {
        val current = _state.value.selected
        _state.value = _state.value.copy(
            selected = if (pkg in current) current - pkg else current + pkg
        )
    }

    fun selectAllVisible() {
        _state.value = _state.value.copy(
            selected = _state.value.visibleApps.map { it.packageName }.toSet()
        )
    }

    fun clearSelection() {
        _state.value = _state.value.copy(selected = emptySet())
    }

    fun clearMessage() {
        _state.value = _state.value.copy(lastMessage = null)
    }

    /**
     * Extract either the current selection, or a single app if [single] is given
     * (a tap-and-hold-free "extract this one" path).
     */
    fun extract(treeUri: Uri, single: AppInfo? = null) {
        val targets: List<AppInfo> = when {
            single != null -> listOf(single)
            else -> _state.value.allApps.filter { it.packageName in _state.value.selected }
        }
        if (targets.isEmpty()) return

        _state.value = _state.value.copy(extracting = true)
        viewModelScope.launch {
            val app = getApplication<Application>()
            var ok = 0
            var failed = 0
            withContext(Dispatchers.IO) {
                targets.forEach { target ->
                    when (ApkExtractor.extract(app, treeUri, target)) {
                        is ApkExtractor.Result.Success -> ok++
                        is ApkExtractor.Result.Failure -> failed++
                    }
                }
            }
            val msg = buildString {
                append("Extracted $ok APK")
                if (ok != 1) append("s")
                if (failed > 0) append(", $failed failed")
            }
            _state.value = _state.value.copy(
                extracting = false,
                selected = emptySet(),
                lastMessage = msg,
            )
        }
    }
}
