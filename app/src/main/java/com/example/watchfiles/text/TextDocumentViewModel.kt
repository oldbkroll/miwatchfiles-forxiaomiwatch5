package com.example.watchfiles.text

import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.watchfiles.fileops.FileNameRules
import com.example.watchfiles.fileops.FileNameValidation
import kotlinx.coroutines.CancellationException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class TextDocumentMode { IDLE, LOADING, VIEWING, EDITING, SAVING, FAILED }

enum class TextSaveConfirmation { NONE, OVERWRITE, SAVE_AS }

data class TextDocumentUiState(
    val path: Path? = null,
    val mode: TextDocumentMode = TextDocumentMode.IDLE,
    val segment: TextSegment? = null,
    val sizeBytes: Long = 0L,
    val editable: Boolean = false,
    val editDisabledReason: String? = null,
    val draft: String = "",
    val originalContent: String? = null,
    val originalSha256: String? = null,
    val isDirty: Boolean = false,
    val pendingTargetName: String? = null,
    val targetExists: Boolean = false,
    val saveConfirmation: TextSaveConfirmation = TextSaveConfirmation.NONE,
    val message: String? = null,
)

class TextDocumentViewModel(
    private val reader: TextReaderGateway,
    private val writer: TextWriteGateway,
    private val digestProvider: TextDigestProvider = TextDigestProvider { path ->
        withContext(Dispatchers.IO) {
            MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(path))
                .joinToString("") { "%02x".format(it) }
        }
    },
) : ViewModel() {
    private val _state = MutableStateFlow(TextDocumentUiState())
    val state: StateFlow<TextDocumentUiState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var segmentStarts = mutableListOf<Long>()

    fun open(path: Path) {
        loadJob?.cancel()
        segmentStarts = mutableListOf(0L)
        _state.value = TextDocumentUiState(path = path, mode = TextDocumentMode.LOADING)
        loadJob = viewModelScope.launch {
            try {
                val recoveryMessage = recoveryMessage()
                when (val result = reader.open(path)) {
                    is TextOpenResult.Ready -> {
                        val sha = if (result.editable) digestProvider.digest(path) else null
                        _state.update {
                            it.copy(
                                mode = TextDocumentMode.VIEWING,
                                segment = result.firstSegment,
                                sizeBytes = result.sizeBytes,
                                editable = result.editable,
                                editDisabledReason = result.editDisabledReason,
                                originalSha256 = sha,
                                message = recoveryMessage,
                            )
                        }
                    }
                    is TextOpenResult.Unsupported -> {
                        _state.update {
                            it.copy(mode = TextDocumentMode.FAILED, message = result.message)
                        }
                    }
                    is TextOpenResult.Failed -> {
                        _state.update {
                            it.copy(mode = TextDocumentMode.FAILED, message = result.message)
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _state.update {
                    it.copy(mode = TextDocumentMode.FAILED, message = error.userMessage("无法读取文本文件"))
                }
            }
        }
    }

    fun nextSegment() {
        val current = _state.value
        val path = current.path ?: return
        val segment = current.segment ?: return
        if (!segment.hasNext || current.mode != TextDocumentMode.VIEWING) return
        viewModelScope.launch {
            try {
                val next = reader.readSegment(path, segment.endByte)
                segmentStarts += next.startByte
                _state.update { it.copy(segment = next, message = null) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _state.update { it.copy(message = error.userMessage("无法读取下一段文本")) }
            }
        }
    }

    fun previousSegment() {
        val current = _state.value
        val path = current.path ?: return
        if (current.mode != TextDocumentMode.VIEWING || segmentStarts.size < 2) return
        val previousStart = segmentStarts[segmentStarts.lastIndex - 1]
        viewModelScope.launch {
            try {
                val previous = reader.readSegment(path, previousStart)
                segmentStarts.removeAt(segmentStarts.lastIndex)
                _state.update { it.copy(segment = previous, message = null) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _state.update { it.copy(message = error.userMessage("无法读取上一段文本")) }
            }
        }
    }

    fun beginEditing() {
        val current = _state.value
        val path = current.path ?: return
        if (!current.editable || current.mode != TextDocumentMode.VIEWING) return
        viewModelScope.launch {
            try {
                val content = reader.readEditable(path)
                val digest = digestProvider.digest(path)
                _state.update {
                    it.copy(
                        mode = TextDocumentMode.EDITING,
                        draft = content,
                        originalContent = content,
                        originalSha256 = digest,
                        isDirty = false,
                        message = null,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _state.update { it.copy(message = error.userMessage("无法载入可编辑文本")) }
            }
        }
    }

    fun updateDraft(content: String) {
        if (_state.value.mode != TextDocumentMode.EDITING) return
        _state.update {
            it.copy(
                draft = content,
                isDirty = content != it.originalContent,
                message = null,
            )
        }
    }

    fun requestOverwriteConfirmation() {
        val current = _state.value
        val path = current.path ?: return
        if (!current.isDirty || current.mode != TextDocumentMode.EDITING) return
        _state.update {
            it.copy(
                pendingTargetName = path.fileName?.toString(),
                targetExists = true,
                saveConfirmation = TextSaveConfirmation.OVERWRITE,
                message = null,
            )
        }
    }

    fun requestSaveAs(name: String) {
        val current = _state.value
        if (!current.isDirty || current.mode != TextDocumentMode.EDITING) return
        val validation = FileNameRules.validate(name)
        if (validation is FileNameValidation.Invalid) {
            _state.update { it.copy(message = validation.message) }
            return
        }
        val directory = current.path?.parent
        if (directory == null) {
            _state.update { it.copy(message = "当前文件没有可用目录") }
            return
        }
        val target = directory.resolve(name)
        if (Files.exists(target, NOFOLLOW_LINKS) && Files.isSymbolicLink(target)) {
            _state.update { it.copy(message = "不能覆盖符号链接") }
            return
        }
        _state.update {
            it.copy(
                pendingTargetName = name,
                targetExists = Files.exists(target, NOFOLLOW_LINKS),
                saveConfirmation = TextSaveConfirmation.SAVE_AS,
                message = null,
            )
        }
    }

    fun confirmSave(overwriteConfirmed: Boolean) {
        val current = _state.value
        val source = current.path ?: return
        val targetName = current.pendingTargetName ?: return
        val expectedDigest = current.originalSha256 ?: return
        if (!current.isDirty || current.mode != TextDocumentMode.EDITING) return
        _state.update {
            it.copy(mode = TextDocumentMode.SAVING, saveConfirmation = TextSaveConfirmation.NONE)
        }
        viewModelScope.launch {
            val recoveryMessage = recoveryMessage()
            if (recoveryMessage != null) {
                _state.update { it.copy(message = recoveryMessage) }
                return@launch
            }
            when (val result = writer.save(
                TextWriteRequest(
                    source = source,
                    currentDirectory = source.parent ?: source,
                    targetName = targetName,
                    content = current.draft,
                    expectedSourceDigest = expectedDigest,
                    overwriteConfirmed = overwriteConfirmed,
                ),
            )) {
                is TextWriteResult.Success -> {
                    _state.update {
                        it.copy(
                            mode = TextDocumentMode.VIEWING,
                            isDirty = false,
                            originalContent = current.draft,
                            pendingTargetName = null,
                            targetExists = false,
                            message = if (result.target == source) "已保存" else "已另存为 ${result.target.fileName}",
                        )
                    }
                }
                is TextWriteResult.Failure -> {
                    _state.update {
                        it.copy(
                            mode = TextDocumentMode.EDITING,
                            pendingTargetName = null,
                            targetExists = false,
                            message = result.userMessage,
                        )
                    }
                }
                TextWriteResult.Cancelled -> {
                    _state.update {
                        it.copy(
                            mode = TextDocumentMode.EDITING,
                            pendingTargetName = null,
                            targetExists = false,
                            message = "保存已取消",
                        )
                    }
                }
            }
        }
    }

    fun cancelSave() {
        _state.update {
            it.copy(
                saveConfirmation = TextSaveConfirmation.NONE,
                pendingTargetName = null,
                targetExists = false,
            )
        }
    }

    fun discardChanges() {
        val current = _state.value
        _state.update {
            it.copy(
                mode = TextDocumentMode.VIEWING,
                draft = current.originalContent.orEmpty(),
                isDirty = false,
                pendingTargetName = null,
                targetExists = false,
                saveConfirmation = TextSaveConfirmation.NONE,
                message = null,
            )
        }
    }

    fun recoverTransactions() {
        viewModelScope.launch {
            recoveryMessage()?.let { message ->
                _state.update {
                    it.copy(message = message)
                }
            }
        }
    }

    private suspend fun recoveryMessage(): String? {
        return try {
            val failures = writer.recover().filterIsInstance<TextRecoveryResult.Failed>()
            if (failures.isEmpty()) null else "有 ${failures.size} 个文本保存事务需要检查"
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            "文本事务恢复失败，请检查后重试"
        }
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val appContext = context.applicationContext
            val preferences = appContext.getSharedPreferences(
                "watchfiles_text_transactions",
                Context.MODE_PRIVATE,
            )
            val journal = SharedPreferencesTextTransactionJournal(preferences)
            return TextDocumentViewModel(
                reader = TextFileReader(),
                writer = SafeTextWriteRepository(journal),
            ) as T
        }
    }
}

private fun Throwable.userMessage(fallback: String): String =
    message?.takeIf { it.isNotBlank() } ?: fallback
