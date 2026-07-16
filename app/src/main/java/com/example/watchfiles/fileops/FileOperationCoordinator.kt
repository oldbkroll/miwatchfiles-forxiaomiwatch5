package com.example.watchfiles.fileops

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class FileOperationCoordinator(
    private val scanner: OperationScannerGateway = FileOperationScanner(),
    private val engine: OperationEngineGateway = FileOperationEngine(),
    private val taskIdFactory: () -> String = { UUID.randomUUID().toString() },
) : ViewModel() {
    private val _state = MutableStateFlow<FileOperationState>(FileOperationState.Idle)
    val state: StateFlow<FileOperationState> = _state.asStateFlow()
    private var activeJob: Job? = null
    private var cancellation: OperationCancellation? = null
    private var conflictDecision: CompletableDeferred<ReplacementDecision>? = null
    private var lastProgress: OperationProgress? = null

    fun start(type: FileOperationType, sources: List<Path>, targetDirectory: Path): Boolean {
        if (_state.value != FileOperationState.Idle) return false
        val request = FileOperationRequest(taskIdFactory(), type, sources, targetDirectory)
        val token = OperationCancellation()
        cancellation = token
        lastProgress = null
        _state.value = FileOperationState.Scanning(type)
        activeJob = viewModelScope.launch {
            try {
                when (val scan = scanner.scan(request, token)) {
                    is ScanOutcome.Rejected -> {
                        _state.value = FileOperationState.Failed(
                            type,
                            FileOperationResult(0, 1, listOf(scan.failure)),
                        )
                    }
                    is ScanOutcome.Ready -> runEngine(request, scan, token)
                }
            } catch (_: OperationCancelledException) {
                _state.value = FileOperationState.Cancelled(type, FileOperationResult(0, 0))
            } catch (error: Exception) {
                runCatching { android.util.Log.e("WatchFiles", "File task failed", error) }
                _state.value = FileOperationState.Failed(
                    type,
                    FileOperationResult(
                        0,
                        1,
                        listOf(FileOperationFailure(null, "文件操作失败", error.message)),
                    ),
                )
            } finally {
                activeJob = null
                cancellation = null
                conflictDecision = null
            }
        }
        return true
    }

    private suspend fun runEngine(
        request: FileOperationRequest,
        scan: ScanOutcome.Ready,
        token: OperationCancellation,
    ) {
        val initial = OperationProgress(null, 0, scan.itemCount, 0, scan.totalBytes)
        lastProgress = initial
        _state.value = FileOperationState.Running(request.type, initial)
        val outcome = engine.execute(
            request,
            scan,
            token,
            onProgress = { progress ->
                lastProgress = progress
                if (_state.value !is FileOperationState.Cancelling) {
                    _state.value = FileOperationState.Running(request.type, progress)
                }
            },
            onConflict = { conflict ->
                val deferred = CompletableDeferred<ReplacementDecision>()
                conflictDecision = deferred
                _state.value = FileOperationState.WaitingForReplacement(
                    request.type,
                    conflict,
                    lastProgress ?: initial,
                )
                deferred.await().also { decision ->
                    conflictDecision = null
                    if (decision == ReplacementDecision.REPLACE_ALL) {
                        _state.value = FileOperationState.Running(request.type, lastProgress ?: initial)
                    }
                }
            },
        )
        _state.value = when (outcome) {
            is EngineOutcome.Completed -> FileOperationState.Succeeded(request.type, outcome.result)
            is EngineOutcome.Partial -> FileOperationState.PartiallySucceeded(request.type, outcome.result)
            is EngineOutcome.Failed -> FileOperationState.Failed(request.type, outcome.result)
            is EngineOutcome.Cancelled -> FileOperationState.Cancelled(request.type, outcome.result)
        }
    }

    fun replaceAll() {
        conflictDecision?.complete(ReplacementDecision.REPLACE_ALL)
    }

    fun cancel() {
        val current = _state.value
        if (current is FileOperationState.Idle || current.isTerminal()) return
        cancellation?.request()
        conflictDecision?.complete(ReplacementDecision.CANCEL)
        val type = when (current) {
            is FileOperationState.Scanning -> current.type
            is FileOperationState.Running -> current.type
            is FileOperationState.WaitingForReplacement -> current.type
            is FileOperationState.Cancelling -> current.type
            else -> return
        }
        _state.value = FileOperationState.Cancelling(type, lastProgress)
    }

    fun consumeResult() {
        if (_state.value.isTerminal()) _state.value = FileOperationState.Idle
    }

    private fun FileOperationState.isTerminal(): Boolean =
        this is FileOperationState.Succeeded ||
            this is FileOperationState.PartiallySucceeded ||
            this is FileOperationState.Failed ||
            this is FileOperationState.Cancelled
}
