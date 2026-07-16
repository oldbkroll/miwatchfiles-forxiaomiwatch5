package com.example.watchfiles.fileops

import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
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

internal interface FileSystemOperations {
    fun moveNoReplace(source: Path, target: Path)
    fun delete(path: Path)
}

class FileOperationEngine(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val byteCopier: FileByteCopier = NioFileByteCopier(),
) : OperationEngineGateway {
    private var fileSystem: FileSystemOperations = NioFileSystemOperations()

    internal constructor(
        byteCopier: FileByteCopier = NioFileByteCopier(),
        fileSystem: FileSystemOperations,
    ) : this(Dispatchers.IO, byteCopier) {
        this.fileSystem = fileSystem
    }

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
            if (request.type != FileOperationType.COPY) {
                return EngineOutcome.Failed(
                    FileOperationResult(
                        completedItems = 0,
                        failedItems = 1,
                        failures = listOf(FileOperationFailure(request.sources.firstOrNull(), "当前仅支持复制文件")),
                    ),
                )
            }

            suspend fun approveReplacement(source: Path, target: Path) {
                if (replaceAll) return
                if (onConflict(FileConflict(source, target)) == ReplacementDecision.CANCEL) {
                    throw OperationCancelledException()
                }
                replaceAll = true
            }

            for (source in request.sources) {
                cancellation.throwIfRequested()
                if (!Files.exists(source, NOFOLLOW_LINKS)) {
                    throw NoSuchFileException(source.toString())
                }
                if (Files.isDirectory(source, NOFOLLOW_LINKS)) {
                    return EngineOutcome.Failed(
                        FileOperationResult(
                            completedItems = completed,
                            failedItems = 1,
                            failures = listOf(FileOperationFailure(source, "文件夹复制尚未启用")),
                        ),
                    )
                }
                if (!Files.isRegularFile(source, NOFOLLOW_LINKS)) {
                    return EngineOutcome.Failed(
                        FileOperationResult(
                            completedItems = completed,
                            failedItems = 1,
                            failures = listOf(FileOperationFailure(source, "仅支持普通文件复制")),
                        ),
                    )
                }
                val target = request.targetDirectory.resolve(source.fileName)
                if (Files.exists(target, NOFOLLOW_LINKS)) {
                    if (!Files.isRegularFile(target, NOFOLLOW_LINKS)) {
                        return EngineOutcome.Failed(
                            FileOperationResult(
                                completedItems = completed,
                                failedItems = 1,
                                failures = listOf(
                                    FileOperationFailure(target, "当前仅支持替换普通文件"),
                                ),
                            ),
                        )
                    }
                    approveReplacement(source, target)
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
                cancellation.throwIfRequested()
                while (true) {
                    if (Files.exists(target, NOFOLLOW_LINKS)) {
                        if (!Files.isRegularFile(target, NOFOLLOW_LINKS)) {
                            throw UnsupportedReplacementTargetException(target)
                        }
                        approveReplacement(source, target)
                        publishReplacement(staged, target, request.taskId)
                        break
                    }
                    try {
                        publishStagedToNewTarget(staged, target)
                        break
                    } catch (error: FileAlreadyExistsException) {
                        if (!Files.exists(target, NOFOLLOW_LINKS)) throw error
                        approveReplacement(source, target)
                    }
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
            val cleanupFailure = cleanupStaged(staged)
            if (cleanupFailure == null) {
                EngineOutcome.Cancelled(FileOperationResult(completed, 0))
            } else {
                EngineOutcome.Cancelled(
                    FileOperationResult(
                        completedItems = completed,
                        failedItems = 1,
                        failures = listOf(
                            FileOperationFailure(
                                source = cleanupFailure.path,
                                userMessage = "操作已取消，但任务临时文件清理失败",
                                technicalMessage = cleanupFailure.technicalMessage(),
                            ),
                        ),
                    ),
                )
            }
        } catch (error: CancellationException) {
            cleanupStaged(staged)?.let { error.addSuppressed(it) }
            throw error
        } catch (error: PublishedWithBackupCleanupFailure) {
            EngineOutcome.Partial(
                FileOperationResult(
                    completedItems = completed + 1,
                    failedItems = 1,
                    failures = listOf(
                        FileOperationFailure(
                            source = error.target,
                            userMessage = "新目标已完成，但旧目标备份清理失败",
                            technicalMessage = "backup=${error.backup}; cleanup=${error.causeDescription()}",
                        ),
                    ),
                ),
            )
        } catch (error: PublishedWithBackupRestoreFailure) {
            val cleanupFailure = cleanupStaged(staged)
            val cleanupSuffix = cleanupFailure?.let { "；任务临时文件清理失败" }.orEmpty()
            val technicalSuffix = cleanupFailure?.let { "; ${it.technicalMessage()}" }.orEmpty()
            EngineOutcome.Partial(
                FileOperationResult(
                    completedItems = completed,
                    failedItems = 1,
                    failures = listOf(
                        FileOperationFailure(
                            source = error.target,
                            userMessage = "目标发布失败，旧目标保留在备份$cleanupSuffix",
                            technicalMessage = error.message + technicalSuffix,
                        ),
                    ),
                ),
            )
        } catch (error: Exception) {
            val cleanupFailure = cleanupStaged(staged)
            EngineOutcome.Failed(
                FileOperationResult(
                    completedItems = completed,
                    failedItems = 1,
                    failures = listOf(
                        operationFailure(request.sources.getOrNull(completed), error, cleanupFailure),
                    ),
                ),
            )
        }
    }

    private fun publishStagedToNewTarget(staged: Path, target: Path) {
        fileSystem.moveNoReplace(staged, target)
    }

    private fun publishReplacement(staged: Path, target: Path, taskId: String) {
        val backup = backupPath(target, taskId)
        if (Files.exists(backup, NOFOLLOW_LINKS)) throw FileAlreadyExistsException(backup.toString())
        moveExistingTargetToBackup(target, backup)
        try {
            if (!Files.isRegularFile(backup, NOFOLLOW_LINKS)) {
                throw UnsupportedReplacementTargetException(target)
            }
            publishStagedOverBackedUpTarget(staged, target)
        } catch (publishError: Exception) {
            try {
                restoreBackupToTarget(backup, target)
            } catch (restoreError: Exception) {
                if (restoreError is CancellationException) {
                    restoreError.addSuppressed(
                        IOException(
                            "backup=$backup; publish=${publishError.message ?: publishError.javaClass.simpleName}",
                            publishError,
                        ),
                    )
                    throw restoreError
                }
                if (publishError is CancellationException) {
                    publishError.addSuppressed(restoreError)
                    throw publishError
                }
                throw PublishedWithBackupRestoreFailure(target, backup, publishError, restoreError)
            }
            throw publishError
        }
        try {
            fileSystem.delete(backup)
        } catch (error: CancellationException) {
            error.addSuppressed(IOException("new target published; backup cleanup pending at $backup"))
            throw error
        } catch (error: Exception) {
            throw PublishedWithBackupCleanupFailure(target, backup, error)
        }
    }

    private fun moveExistingTargetToBackup(target: Path, backup: Path) {
        fileSystem.moveNoReplace(target, backup)
    }

    private fun publishStagedOverBackedUpTarget(staged: Path, target: Path) {
        fileSystem.moveNoReplace(staged, target)
    }

    private fun restoreBackupToTarget(backup: Path, target: Path) {
        fileSystem.moveNoReplace(backup, target)
    }

    private fun cleanupStaged(staged: Path?): StagedCleanupFailure? {
        if (staged == null) return null
        return try {
            fileSystem.delete(staged)
            null
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            StagedCleanupFailure(staged, error)
        }
    }
}

internal fun temporaryFile(target: Path, taskId: String): Path =
    target.resolveSibling(".${target.fileName}.watchfiles-$taskId.part")

internal fun backupPath(target: Path, taskId: String): Path =
    target.resolveSibling(".${target.fileName}.watchfiles-$taskId.backup")

private fun operationFailure(
    source: Path?,
    error: Exception,
    cleanupFailure: StagedCleanupFailure? = null,
): FileOperationFailure {
    val message = error.message.orEmpty()
    val baseUserMessage = when {
        error is NoSuchFileException && error.refersTo(source) -> "源项目已消失"
        error is AccessDeniedException || error is SecurityException -> "没有权限写入目标"
        message.contains("ENOSPC", ignoreCase = true) ||
            message.contains("No space left", ignoreCase = true) -> "可用空间不足"
        error is FileSyncException -> "文件同步失败"
        error is FileAlreadyExistsException ||
            error is NoSuchFileException -> "目标发布失败"
        error is UnsupportedReplacementTargetException -> "当前仅支持替换普通文件"
        else -> "复制失败"
    }
    val userMessage = if (cleanupFailure == null) baseUserMessage
    else "$baseUserMessage，任务临时文件清理失败"
    val technicalMessage = buildList {
        add(message.ifBlank { error.javaClass.simpleName })
        error.suppressed.forEach { suppressed ->
            add("suppressed=${suppressed.message ?: suppressed.javaClass.simpleName}")
        }
        cleanupFailure?.let { add(it.technicalMessage()) }
    }.joinToString("; ")
    return FileOperationFailure(
        source = source,
        userMessage = userMessage,
        technicalMessage = technicalMessage,
    )
}

private fun NoSuchFileException.refersTo(source: Path?): Boolean {
    if (source == null) return false
    val missing = runCatching { Path.of(file) }.getOrNull() ?: return false
    return missing.toAbsolutePath().normalize() == source.toAbsolutePath().normalize()
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

private class NioFileSystemOperations : FileSystemOperations {
    override fun moveNoReplace(source: Path, target: Path) {
        Files.move(source, target)
    }

    override fun delete(path: Path) {
        Files.deleteIfExists(path)
    }
}

private class FileSyncException(cause: Exception) : IOException(cause.message, cause)

private class UnsupportedReplacementTargetException(val target: Path) :
    IOException("unsupported replacement target: $target")

private class StagedCleanupFailure(
    val path: Path,
    cause: Exception,
) : IOException(cause.message, cause) {
    fun technicalMessage(): String =
        "staged=$path; cleanup=${causeDescription()}"
}

private fun Exception.causeDescription(): String =
    cause?.message ?: message ?: javaClass.simpleName

private class PublishedWithBackupCleanupFailure(
    val target: Path,
    val backup: Path,
    cause: Exception,
) : IOException(cause.message, cause)

private class PublishedWithBackupRestoreFailure(
    val target: Path,
    val backup: Path,
    publishError: Exception,
    restoreError: Exception,
) : IOException(
    "backup=$backup; publish=${publishError.message ?: publishError.javaClass.simpleName}; " +
        "restore=${restoreError.message ?: restoreError.javaClass.simpleName}",
    publishError,
) {
    init {
        addSuppressed(restoreError)
    }
}
