package com.example.watchfiles.browser

import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.watchfiles.data.DirectoryReader
import com.example.watchfiles.data.DirectPathRepository
import com.example.watchfiles.data.FileEntry
import com.example.watchfiles.fileops.FileMutationGateway
import com.example.watchfiles.fileops.FileMutationRepository
import com.example.watchfiles.fileops.FileMutationResult
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
    val selection: BrowserSelection = BrowserSelection(),
    val mutation: BrowserMutationState = BrowserMutationState.Idle,
)

sealed interface BrowserMutationState {
    data object Idle : BrowserMutationState
    data object Working : BrowserMutationState
    data class Succeeded(val path: Path) : BrowserMutationState
    data class Failed(
        val userMessage: String,
        val technicalMessage: String?,
    ) : BrowserMutationState
}

class FileBrowserViewModel(
    private val repository: DirectoryReader = DirectPathRepository(),
    private val mutationRepository: FileMutationGateway = FileMutationRepository(),
    initialPath: Path = Environment.getExternalStorageDirectory().toPath(),
) : ViewModel() {
    private val _state = MutableStateFlow(BrowserUiState(currentPath = initialPath))
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
                selection = BrowserSelection(),
                mutation = BrowserMutationState.Idle,
            )
        }
        loadJob = viewModelScope.launch {
            runCatching { repository.list(path) }
                .onSuccess { entries ->
                    _state.update { current ->
                        val availablePaths = entries.mapTo(HashSet(), FileEntry::path)
                        current.copy(
                            entries = entries,
                            isLoading = false,
                            selection = current.selection.copy(
                                selectedPaths = current.selection.selectedPaths.filterTo(
                                    LinkedHashSet(),
                                ) { it in availablePaths },
                            ),
                        )
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

    fun beginSelection(path: Path) {
        _state.update { it.copy(selection = it.selection.begin(path)) }
    }

    fun toggleSelection(path: Path) {
        _state.update { it.copy(selection = it.selection.toggle(path)) }
    }

    fun selectAll(entries: List<FileEntry>) {
        _state.update { it.copy(selection = it.selection.selectAll(entries.map(FileEntry::path))) }
    }

    fun clearSelection() {
        _state.update { it.copy(selection = it.selection.clear()) }
    }

    fun createDirectory(name: String) = runMutation {
        mutationRepository.createDirectory(_state.value.currentPath, name)
    }

    fun rename(source: Path, newName: String) = runMutation {
        mutationRepository.rename(source, newName)
    }

    fun consumeMutationResult() {
        _state.update { it.copy(mutation = BrowserMutationState.Idle) }
    }

    private fun runMutation(operation: suspend () -> FileMutationResult) {
        if (_state.value.mutation == BrowserMutationState.Working) return
        _state.update { it.copy(mutation = BrowserMutationState.Working) }
        viewModelScope.launch {
            when (val result = operation()) {
                is FileMutationResult.Success -> {
                    val currentPath = _state.value.currentPath
                    val refreshed = runCatching { repository.list(currentPath) }
                    _state.update {
                        it.copy(
                            entries = refreshed.getOrDefault(it.entries),
                            selection = BrowserSelection(),
                            mutation = BrowserMutationState.Succeeded(result.path),
                            errorMessage = refreshed.exceptionOrNull()?.message,
                        )
                    }
                }

                is FileMutationResult.Failure -> {
                    result.technicalMessage?.let { technical ->
                        runCatching {
                            android.util.Log.w("WatchFiles", result.userMessage + ": " + technical)
                        }
                    }
                    _state.update {
                        it.copy(
                            mutation = BrowserMutationState.Failed(
                                result.userMessage,
                                result.technicalMessage,
                            ),
                        )
                    }
                }
            }
        }
    }

    fun refresh() = open(_state.value.currentPath)
}
