package com.example.watchfiles.fileops

import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface EngineOutcome {
    data class Completed(val result: FileOperationResult) : EngineOutcome
    data class Partial(val result: FileOperationResult) : EngineOutcome
    data class Failed(val result: FileOperationResult) : EngineOutcome
    data class Cancelled(val result: FileOperationResult) : EngineOutcome
}

fun interface OperationEngineGateway {
    suspend fun execute(
        request: FileOperationRequest,
        scan: ScanOutcome.Ready,
        cancellation: OperationCancellation,
        onProgress: (OperationProgress) -> Unit,
        onConflict: suspend (FileConflict) -> ReplacementDecision,
    ): EngineOutcome
}

fun interface FileByteCopier {
    fun copy(
        source: Path,
        temporaryTarget: Path,
        cancellation: OperationCancellation,
        onBytes: (Long) -> Unit,
    )
}

class FileOperationEngine(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val byteCopier: FileByteCopier = NioFileByteCopier(),
) : OperationEngineGateway {
    override suspend fun execute(
        request: FileOperationRequest,
        scan: ScanOutcome.Ready,
        cancellation: OperationCancellation,
        onProgress: (OperationProgress) -> Unit,
        onConflict: suspend (FileConflict) -> ReplacementDecision,
    ): EngineOutcome = withContext(ioDispatcher) {
        executeInternal(request, scan, cancellation, onProgress, onConflict)
    }

    private suspend fun executeInternal(
        request: FileOperationRequest,
        scan: ScanOutcome.Ready,
        cancellation: OperationCancellation,
        onProgress: (OperationProgress) -> Unit,
        onConflict: suspend (FileConflict) -> ReplacementDecision,
    ): EngineOutcome {
        var completed = 0
        var processedBytes = 0L
        var replaceAll = false
        var staged: Path? = null
        return try {
            for (source in request.sources) {
                cancellation.throwIfRequested()
                if (Files.isDirectory(source, NOFOLLOW_LINKS)) {
                    return EngineOutcome.Failed(
                        FileOperationResult(
                            completedItems = completed,
                            failedItems = 1,
                            failures = listOf(FileOperationFailure(source, "文件夹复制尚未启用")),
                        ),
                    )
                }
                val target = request.targetDirectory.resolve(source.fileName)
                if (Files.exists(target, NOFOLLOW_LINKS) && !replaceAll) {
                    if (onConflict(FileConflict(source, target)) == ReplacementDecision.CANCEL) {
                        throw OperationCancelledException()
                    }
                    replaceAll = true
                }
                staged = temporaryFile(target, request.taskId)
                Files.newOutputStream(staged, StandardOpenOption.CREATE_NEW).close()
                byteCopier.copy(source, staged, cancellation) { count ->
                    processedBytes += count
                    onProgress(
                        OperationProgress(
                            currentName = source.fileName.toString(),
                            processedItems = completed,
                            totalItems = scan.itemCount,
                            processedBytes = processedBytes,
                            totalBytes = scan.totalBytes,
                        ),
                    )
                }
                if (Files.exists(target, NOFOLLOW_LINKS)) {
                    publishReplacement(staged, target, request.taskId)
                } else {
                    publishNew(staged, target)
                }
                staged = null
                completed += 1
                onProgress(
                    OperationProgress(
                        currentName = source.fileName.toString(),
                        processedItems = completed,
                        totalItems = scan.itemCount,
                        processedBytes = processedBytes,
                        totalBytes = scan.totalBytes,
                    ),
                )
            }
            EngineOutcome.Completed(FileOperationResult(completed, 0))
        } catch (_: OperationCancelledException) {
            staged?.let { runCatching { Files.deleteIfExists(it) } }
            EngineOutcome.Cancelled(FileOperationResult(completed, 0))
        } catch (error: PublishedWithBackupCleanupFailure) {
            EngineOutcome.Partial(
                FileOperationResult(
                    completedItems = completed + 1,
                    failedItems = 1,
                    failures = listOf(
                        FileOperationFailure(
                            source = error.target,
                            userMessage = "新目标已完成，但旧目标备份清理失败",
                            technicalMessage = error.message,
                        ),
                    ),
                ),
            )
        } catch (error: Exception) {
            staged?.let { runCatching { Files.deleteIfExists(it) } }
            EngineOutcome.Failed(
                FileOperationResult(
                    completedItems = completed,
                    failedItems = 1,
                    failures = listOf(operationFailure(request.sources.getOrNull(completed), error)),
                ),
            )
        }
    }
}

internal fun temporaryFile(target: Path, taskId: String): Path =
    target.resolveSibling(".${target.fileName}.watchfiles-$taskId.part")

internal fun backupPath(target: Path, taskId: String): Path =
    target.resolveSibling(".${target.fileName}.watchfiles-$taskId.backup")

private fun publishNew(staged: Path, target: Path) {
    try {
        Files.move(staged, target, ATOMIC_MOVE)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(staged, target)
    }
}

private fun publishReplacement(staged: Path, target: Path, taskId: String) {
    val backup = backupPath(target, taskId)
    publishNew(target, backup)
    try {
        publishNew(staged, target)
    } catch (publishError: Exception) {
        try {
            publishNew(backup, target)
        } catch (restoreError: Exception) {
            publishError.addSuppressed(restoreError)
        }
        throw publishError
    }
    try {
        Files.delete(backup)
    } catch (error: Exception) {
        throw PublishedWithBackupCleanupFailure(target, error)
    }
}

private fun operationFailure(source: Path?, error: Exception): FileOperationFailure {
    val message = error.message.orEmpty()
    val userMessage = when {
        error is NoSuchFileException -> "源项目已消失"
        error is AccessDeniedException || error is SecurityException -> "没有权限写入目标"
        message.contains("ENOSPC", ignoreCase = true) ||
            message.contains("No space left", ignoreCase = true) -> "可用空间不足"
        error is FileSyncException -> "文件同步失败"
        error is AtomicMoveNotSupportedException -> "目标发布失败"
        else -> "复制失败"
    }
    return FileOperationFailure(
        source = source,
        userMessage = userMessage,
        technicalMessage = message.ifBlank { error.javaClass.simpleName },
    )
}

private class NioFileByteCopier : FileByteCopier {
    override fun copy(
        source: Path,
        temporaryTarget: Path,
        cancellation: OperationCancellation,
        onBytes: (Long) -> Unit,
    ) {
        Files.newInputStream(source, StandardOpenOption.READ).use { input ->
            FileOutputStream(temporaryTarget.toFile()).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    cancellation.throwIfRequested()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    onBytes(count.toLong())
                }
                output.flush()
                try {
                    output.fd.sync()
                } catch (error: IOException) {
                    throw FileSyncException(error)
                }
            }
        }
    }
}

private class FileSyncException(cause: Exception) : IOException(cause.message, cause)

private class PublishedWithBackupCleanupFailure(
    val target: Path,
    cause: Exception,
) : IOException(cause.message, cause)
