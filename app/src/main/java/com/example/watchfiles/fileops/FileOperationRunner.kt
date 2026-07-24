package com.example.watchfiles.fileops

import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

interface FileOperationRunnerPort {
    val state: StateFlow<FileOperationState>

    fun start(type: FileOperationType, sources: List<Path>, targetDirectory: Path): Boolean
    fun prepareDelete(sources: List<Path>): Boolean
    fun confirmLargeOperation(): Boolean = false
    fun confirmDelete(): Boolean
    fun replaceAll()
    fun cancel()
    fun consumeResult()
}

class FileOperationRunner(
    private val scanner: OperationScannerGateway = FileOperationScanner(),
    private val engine: OperationEngineGateway = FileOperationEngine(),
    private val taskIdFactory: () -> String = { UUID.randomUUID().toString() },
    private val errorLogger: (String, Throwable) -> Unit = { _, _ -> },
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : FileOperationRunnerPort, AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val _state = MutableStateFlow<FileOperationState>(FileOperationState.Idle)
    override val state: StateFlow<FileOperationState> = _state.asStateFlow()
    private var activeJob: Job? = null
    private var cancellation: OperationCancellation? = null
    private var conflictDecision: CompletableDeferred<ReplacementDecision>? = null
    private var largeOperationConfirmation: CompletableDeferred<Boolean>? = null
    private var deleteConfirmation: CompletableDeferred<Boolean>? = null
    private var lastProgress: OperationProgress? = null

    override fun start(
        type: FileOperationType,
        sources: List<Path>,
        targetDirectory: Path,
    ): Boolean {
        if (!scope.isActive || sources.isEmpty() || _state.value != FileOperationState.Idle) return false
        val request = FileOperationRequest(taskIdFactory(), type, sources, targetDirectory)
        val token = OperationCancellation()
        cancellation = token
        lastProgress = null
        _state.value = FileOperationState.Scanning(type)
        activeJob = scope.launch {
            var returnToIdle = false
            try {
                when (val scan = scanner.scan(request, token)) {
                    is ScanOutcome.Rejected -> {
                        _state.value = FileOperationState.Failed(
                            type,
                            FileOperationResult(0, 1, listOf(scan.failure)),
                        )
                    }
                    is ScanOutcome.Ready -> {
                        token.throwIfRequested()
                        if (!awaitLargeOperationConfirmationIfNeeded(request, scan)) {
                            returnToIdle = true
                        } else {
                            runEngine(request, scan, token)
                        }
                    }
                }
            } catch (_: OperationCancelledException) {
                _state.value = FileOperationState.Cancelled(type, FileOperationResult(0, 0))
            } catch (_: CancellationException) {
                // Runner shutdown is not a user cancellation and must not fabricate a terminal state.
            } catch (error: Exception) {
                runCatching { errorLogger("File task failed", error) }
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
                largeOperationConfirmation = null
                if (returnToIdle) _state.value = FileOperationState.Idle
            }
        }
        return true
    }

    override fun prepareDelete(sources: List<Path>): Boolean {
        if (!scope.isActive || sources.isEmpty() || _state.value != FileOperationState.Idle) return false
        val request = FileOperationRequest.delete(taskIdFactory(), sources)
        val token = OperationCancellation()
        cancellation = token
        lastProgress = null
        _state.value = FileOperationState.Scanning(FileOperationType.DELETE)
        activeJob = scope.launch {
            var confirmation: CompletableDeferred<Boolean>? = null
            var enteredExecution = false
            var returnToIdle = false
            try {
                when (val scan = scanner.scan(request, token)) {
                    is ScanOutcome.Rejected -> {
                        _state.value = FileOperationState.Failed(
                            FileOperationType.DELETE,
                            FileOperationResult(0, 1, listOf(scan.failure)),
                        )
                    }
                    is ScanOutcome.Ready -> {
                        token.throwIfRequested()
                        if (!awaitLargeOperationConfirmationIfNeeded(request, scan)) {
                            returnToIdle = true
                            return@launch
                        }
                        val preview = DeletePreview(
                            topLevelCount = request.sources.distinct().size,
                            itemCount = scan.itemCount,
                            totalBytes = scan.totalBytes,
                        )
                        val gate = CompletableDeferred<Boolean>()
                        confirmation = gate
                        deleteConfirmation = gate
                        _state.value = FileOperationState.WaitingForDeleteConfirmation(preview)
                        if (gate.await()) {
                            enteredExecution = true
                            runEngine(request, scan, token)
                        } else {
                            returnToIdle = true
                        }
                    }
                }
            } catch (_: OperationCancelledException) {
                if (enteredExecution) {
                    _state.value = FileOperationState.Cancelled(
                        FileOperationType.DELETE,
                        FileOperationResult(0, 0),
                    )
                } else {
                    returnToIdle = true
                }
            } catch (_: CancellationException) {
                // Runner shutdown is not a user cancellation and must not fabricate a terminal state.
            } catch (error: Exception) {
                runCatching { errorLogger("File task failed", error) }
                _state.value = FileOperationState.Failed(
                    FileOperationType.DELETE,
                    FileOperationResult(
                        0,
                        1,
                        listOf(FileOperationFailure(null, "文件操作失败", error.message)),
                    ),
                )
            } finally {
                if (activeJob === currentCoroutineContext()[Job]) activeJob = null
                if (cancellation === token) cancellation = null
                largeOperationConfirmation = null
                if (confirmation != null && deleteConfirmation === confirmation) {
                    deleteConfirmation = null
                }
                if (returnToIdle) _state.value = FileOperationState.Idle
            }
        }
        return true
    }

    override fun replaceAll() {
        conflictDecision?.complete(ReplacementDecision.REPLACE_ALL)
    }

    override fun confirmLargeOperation(): Boolean {
        if (_state.value !is FileOperationState.WaitingForLargeOperationConfirmation) return false
        return largeOperationConfirmation?.complete(true) == true
    }

    override fun confirmDelete(): Boolean {
        val current = _state.value
        if (current !is FileOperationState.WaitingForDeleteConfirmation) return false
        val initial = OperationProgress(null, 0, current.preview.itemCount, 0, current.preview.totalBytes)
        _state.value = FileOperationState.Running(FileOperationType.DELETE, initial)
        return deleteConfirmation?.complete(true) == true
    }

    override fun cancel() {
        val current = _state.value
        if (current is FileOperationState.Idle || current.isTerminal()) return
        if (current is FileOperationState.WaitingForLargeOperationConfirmation) {
            cancellation?.request()
            largeOperationConfirmation?.complete(false)
            return
        }
        if (current is FileOperationState.WaitingForDeleteConfirmation) {
            cancellation?.request()
            deleteConfirmation?.complete(false)
            return
        }
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

    override fun consumeResult() {
        if (_state.value.isTerminal()) _state.value = FileOperationState.Idle
    }

    override fun close() {
        activeJob?.cancel()
        scope.cancel()
    }

    private suspend fun awaitLargeOperationConfirmationIfNeeded(
        request: FileOperationRequest,
        scan: ScanOutcome.Ready,
    ): Boolean {
        if (!isLargeOperation(scan.itemCount, scan.totalBytes)) return true
        val gate = CompletableDeferred<Boolean>()
        largeOperationConfirmation = gate
        _state.value = FileOperationState.WaitingForLargeOperationConfirmation(
            type = request.type,
            itemCount = scan.itemCount,
            totalBytes = scan.totalBytes,
        )
        return gate.await()
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

    private fun FileOperationState.isTerminal(): Boolean =
        this is FileOperationState.Succeeded ||
            this is FileOperationState.PartiallySucceeded ||
            this is FileOperationState.Failed ||
            this is FileOperationState.Cancelled
}
