package com.example.watchfiles.browser

import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.watchfiles.data.DirectPathRepository
import com.example.watchfiles.data.FileEntry
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.nio.file.Path

data class BrowserUiState(
    val currentPath: Path = Environment.getExternalStorageDirectory().toPath(),
    val entries: List<FileEntry> = emptyList(),
    val isLoading: Boolean = false,
    val showHidden: Boolean = false,
    val errorMessage: String? = null,
)

class FileBrowserViewModel(
    private val repository: DirectPathRepository = DirectPathRepository(),
) : ViewModel() {
    private val _state = MutableStateFlow(BrowserUiState())
    val state: StateFlow<BrowserUiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    fun open(path: Path) {
        loadJob?.cancel()
        _state.update {
            it.copy(
                currentPath = path,
                entries = emptyList(),
                isLoading = true,
                errorMessage = null,
            )
        }
        loadJob = viewModelScope.launch {
            runCatching { repository.list(path) }
                .onSuccess { entries ->
                    _state.update { current ->
                        current.copy(entries = entries, isLoading = false)
                    }
                }
                .onFailure { error ->
                    _state.update { current ->
                        current.copy(
                            isLoading = false,
                            errorMessage = error.message ?: error.javaClass.simpleName,
                        )
                    }
                }
        }
    }

    fun navigateUp(): Boolean {
        val parent = _state.value.currentPath.parent ?: return false
        open(parent)
        return true
    }

    fun toggleHidden() {
        _state.update { it.copy(showHidden = !it.showHidden) }
    }

    fun refresh() = open(_state.value.currentPath)
}
