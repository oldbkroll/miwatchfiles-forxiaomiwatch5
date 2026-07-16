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
    initialPath: Path = Environment.getExternalStorageDirectory().toPath(),
) : ViewModel() {
    private val _state = MutableStateFlow(TargetDirectoryUiState(initialPath))
    val state: StateFlow<TargetDirectoryUiState> = _state.asStateFlow()
    private var loadJob: Job? = null

    fun open(path: Path) {
        loadJob?.cancel()
        _state.update { it.copy(currentPath = path, directories = emptyList(), isLoading = true, errorMessage = null) }
        loadJob = viewModelScope.launch {
            runCatching { repository.list(path) }
                .onSuccess { entries ->
                    _state.update { it.copy(directories = entries.filter(FileEntry::isDirectory), isLoading = false) }
                }
                .onFailure { error ->
                    _state.update { it.copy(isLoading = false, errorMessage = error.message ?: error.javaClass.simpleName) }
                }
        }
    }

    fun navigateUp(storageRoot: Path): Boolean {
        val root = storageRoot.toAbsolutePath().normalize()
        val currentPath = _state.value.currentPath.normalize()
        val current = currentPath.toAbsolutePath().normalize()
        if (current == root || !current.startsWith(root)) return false
        val parent = currentPath.parent ?: return false
        if (!parent.toAbsolutePath().normalize().startsWith(root)) return false
        open(parent)
        return true
    }
}
