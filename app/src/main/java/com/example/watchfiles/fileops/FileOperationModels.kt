package com.example.watchfiles.fileops

import java.nio.file.Path

enum class FileOperationType { COPY, MOVE }

data class FileOperationRequest(
    val taskId: String,
    val type: FileOperationType,
    val sources: List<Path>,
    val targetDirectory: Path,
)

data class OperationProgress(
    val currentName: String?,
    val processedItems: Int,
    val totalItems: Int,
    val processedBytes: Long,
    val totalBytes: Long?,
)

data class FileConflict(val source: Path, val target: Path)

data class FileOperationFailure(
    val source: Path?,
    val userMessage: String,
    val technicalMessage: String? = null,
)

data class FileOperationResult(
    val completedItems: Int,
    val failedItems: Int,
    val failures: List<FileOperationFailure> = emptyList(),
)

sealed interface ScanOutcome {
    data class Ready(val itemCount: Int, val totalBytes: Long?) : ScanOutcome
    data class Rejected(val failure: FileOperationFailure) : ScanOutcome
}

sealed interface FileOperationState {
    data object Idle : FileOperationState
    data class Scanning(val type: FileOperationType) : FileOperationState
    data class Running(val type: FileOperationType, val progress: OperationProgress) : FileOperationState
    data class WaitingForReplacement(
        val type: FileOperationType,
        val conflict: FileConflict,
        val progress: OperationProgress,
    ) : FileOperationState
    data class Cancelling(val type: FileOperationType, val progress: OperationProgress?) : FileOperationState
    data class Succeeded(val type: FileOperationType, val result: FileOperationResult) : FileOperationState
    data class PartiallySucceeded(val type: FileOperationType, val result: FileOperationResult) : FileOperationState
    data class Failed(val type: FileOperationType, val result: FileOperationResult) : FileOperationState
    data class Cancelled(val type: FileOperationType, val result: FileOperationResult) : FileOperationState
}

enum class ReplacementDecision { REPLACE_ALL, CANCEL }
