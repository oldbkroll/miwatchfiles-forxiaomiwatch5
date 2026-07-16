package com.example.watchfiles.fileops

import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.watchfiles.data.DirectoryReader
import com.example.watchfiles.data.DirectPathRepository
import com.example.watchfiles.data.FileEntry
import java.nio.file.Path
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TargetDirectoryUiState(
    val currentPath: Path,
    val directories: List<FileEntry> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)

class TargetDirectoryViewModel(
    private val repository: DirectoryReader = DirectPathRepository(),
    storageRoot: Path = Environment.getExternalStorageDirectory().toPath(),
    initialPath: Path = storageRoot,
) : ViewModel() {
    private val storageRoot = storageRoot.toAbsolutePath().normalize()
    private val initialPath = initialPath.toAbsolutePath().normalize()
        .takeIf { it.startsWith(this.storageRoot) } ?: this.storageRoot
    private val _state = MutableStateFlow(TargetDirectoryUiState(this.initialPath))
    val state: StateFlow<TargetDirectoryUiState> = _state.asStateFlow()
    private var loadJob: Job? = null

    fun open(path: Path) {
        val candidate = path.toAbsolutePath().normalize()
        if (!candidate.startsWith(storageRoot)) {
            _state.update { it.copy(errorMessage = "目标目录超出内部存储范围") }
            return
        }
        loadJob?.cancel()
        _state.update { it.copy(currentPath = candidate, directories = emptyList(), isLoading = true, errorMessage = null) }
        loadJob = viewModelScope.launch {
            runCatching { repository.list(candidate) }
                .onSuccess { entries ->
                    _state.update { it.copy(directories = entries.filter(FileEntry::isDirectory), isLoading = false) }
                }
                .onFailure { error ->
                    _state.update { it.copy(isLoading = false, errorMessage = error.message ?: error.javaClass.simpleName) }
                }
        }
    }

    fun navigateUp(): Boolean {
        val current = _state.value.currentPath.toAbsolutePath().normalize()
        if (current == storageRoot || !current.startsWith(storageRoot)) return false
        val parent = current.parent ?: return false
        if (!parent.startsWith(storageRoot)) return false
        open(parent)
        return true
    }
}
