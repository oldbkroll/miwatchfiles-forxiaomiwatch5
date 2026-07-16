package com.example.watchfiles.fileops

import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.AccessDeniedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
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
    fun createNewFile(path: Path): OutputStream
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
        var progressItems = 0
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
                    // handled below through private top-level directory staging
                } else if (!Files.isRegularFile(source, NOFOLLOW_LINKS)) {
                    throw UnsupportedSymbolicLinkException(source)
                }
                val target = request.targetDirectory.resolve(source.fileName)
                if (Files.exists(target, NOFOLLOW_LINKS)) approveReplacement(source, target)
                if (Files.isDirectory(source, NOFOLLOW_LINKS)) {
                    val directoryStage = temporaryDirectory(target, request.taskId)
                    Files.createDirectory(directoryStage)
                    staged = directoryStage
                    copyDirectory(
                        source = source,
                        staged = directoryStage,
                        cancellation = cancellation,
                        onEntry = { name ->
                            progressItems += 1
                            onProgress(progress(name, progressItems, scan, processedBytes))
                        },
                        onBytes = { name, count ->
                            processedBytes += count
                            onProgress(progress(name, progressItems, scan, processedBytes))
                        },
                    )
                } else {
                    val fileStage = temporaryFile(target, request.taskId)
                    val emptyStage = fileSystem.createNewFile(fileStage)
                    staged = fileStage
                    emptyStage.use { }
                    byteCopier.copy(source, fileStage, cancellation) { count ->
                        processedBytes += count
                        onProgress(progress(source.fileName.toString(), progressItems, scan, processedBytes))
                    }
                    progressItems += 1
                }
                cancellation.throwIfRequested()
                while (true) {
                    if (Files.exists(target, NOFOLLOW_LINKS)) {
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
                    progress(source.fileName.toString(), progressItems, scan, processedBytes),
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
            deleteOwnedRecursively(backup)
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
            deleteOwnedRecursively(staged)
            null
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            StagedCleanupFailure(staged, error)
        }
    }

    private fun progress(
        currentName: String,
        processedItems: Int,
        scan: ScanOutcome.Ready,
        processedBytes: Long,
    ) = OperationProgress(currentName, processedItems, scan.itemCount, processedBytes, scan.totalBytes)

    private fun copyDirectory(
        source: Path,
        staged: Path,
        cancellation: OperationCancellation,
        onEntry: (String) -> Unit,
        onBytes: (String, Long) -> Unit,
    ) {
        cancellation.throwIfRequested()
        onEntry(source.fileName?.toString().orEmpty())
        Files.walkFileTree(source, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                cancellation.throwIfRequested()
                if (dir != source) {
                    Files.createDirectory(staged.resolve(source.relativize(dir)))
                    onEntry(dir.fileName?.toString().orEmpty())
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                cancellation.throwIfRequested()
                if (!attrs.isRegularFile || Files.isSymbolicLink(file)) {
                    throw UnsupportedSymbolicLinkException(file)
                }
                val nestedTarget = staged.resolve(source.relativize(file))
                Files.newOutputStream(nestedTarget, StandardOpenOption.CREATE_NEW).close()
                val name = file.fileName?.toString().orEmpty()
                byteCopier.copy(file, nestedTarget, cancellation) { count -> onBytes(name, count) }
                cancellation.throwIfRequested()
                onEntry(name)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun deleteOwnedRecursively(path: Path) {
        if (!Files.exists(path, NOFOLLOW_LINKS)) return
        if (!Files.isDirectory(path, NOFOLLOW_LINKS)) {
            fileSystem.delete(path)
            return
        }
        Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                fileSystem.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, error: IOException?): FileVisitResult {
                if (error != null) throw error
                fileSystem.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }
}

internal fun temporaryFile(target: Path, taskId: String): Path =
    target.resolveSibling(".${target.fileName}.watchfiles-$taskId.part")

internal fun temporaryDirectory(target: Path, taskId: String): Path =
    target.resolveSibling(".${target.fileName}.watchfiles-$taskId.part-dir")

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
        error is UnsupportedSymbolicLinkException -> "暂不支持复制符号链接"
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
    val missing = runCatching { Paths.get(file) }.getOrNull() ?: return false
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
    override fun createNewFile(path: Path): OutputStream =
        Files.newOutputStream(path, StandardOpenOption.CREATE_NEW)

    override fun moveNoReplace(source: Path, target: Path) {
        Files.move(source, target)
    }

    override fun delete(path: Path) {
        Files.deleteIfExists(path)
    }
}

private class FileSyncException(cause: Exception) : IOException(cause.message, cause)

private class UnsupportedSymbolicLinkException(val path: Path) :
    IOException("unsupported symbolic link: $path")

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
