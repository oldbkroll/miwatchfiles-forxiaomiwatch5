package com.example.watchfiles.fileops

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun interface OperationScannerGateway {
    suspend fun scan(request: FileOperationRequest, cancellation: OperationCancellation): ScanOutcome
}

class FileOperationScanner(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val usableSpace: (Path) -> Long = { Files.getFileStore(it).usableSpace },
    private val storageRoot: () -> Path = { Paths.get("/storage/emulated/0") },
) : OperationScannerGateway {
    override suspend fun scan(
        request: FileOperationRequest,
        cancellation: OperationCancellation,
    ): ScanOutcome = withContext(ioDispatcher) {
        scanBlocking(request, cancellation)
    }

    private fun scanBlocking(
        request: FileOperationRequest,
        cancellation: OperationCancellation,
    ): ScanOutcome {
        fun reject(path: Path?, message: String, cause: String? = null) = ScanOutcome.Rejected(
            FileOperationFailure(path, message, cause),
        )
        if (request.sources.isEmpty()) return reject(null, "没有选择源项目")
        val target = request.targetDirectory?.toAbsolutePath()?.normalize()
        when (request.type) {
            FileOperationType.DELETE -> {
                if (target != null) return reject(null, "删除请求不应包含目标目录")
            }
            FileOperationType.COPY, FileOperationType.MOVE -> {
                if (target == null) return reject(null, "复制或移动必须指定目标目录")
                if (!Files.exists(target, NOFOLLOW_LINKS) || !Files.isDirectory(target, NOFOLLOW_LINKS)) {
                    return reject(target, "目标文件夹已不存在")
                }
                if (!Files.isWritable(target)) return reject(target, "目标文件夹不可写")
            }
        }

        var itemCount = 0
        var totalBytes = 0L
        var allSizesKnown = true
        val normalizedStorageRoot = storageRoot().toAbsolutePath().normalize()
        for (source in request.sources.map { it.toAbsolutePath().normalize() }.distinct()) {
            cancellation.throwIfRequested()
            if (request.type == FileOperationType.DELETE && source == normalizedStorageRoot) {
                return reject(source, "不能删除内部存储根目录")
            }
            if (!Files.exists(source, NOFOLLOW_LINKS)) return reject(source, "源项目已不存在")
            if (!Files.isSymbolicLink(source) && !Files.isReadable(source)) {
                return reject(source, "源项目不可读")
            }
            if (request.type != FileOperationType.DELETE) {
                val transferTarget = requireNotNull(target)
                val sourceName = source.fileName
                if (sourceName != null && transferTarget.resolve(sourceName).normalize() == source) {
                    return reject(transferTarget, "目标与源项目相同")
                }
                if (Files.isDirectory(source, NOFOLLOW_LINKS) && transferTarget.startsWith(source)) {
                    return reject(transferTarget, "目标目录不能位于源文件夹内部")
                }
            }
            try {
                Files.walkFileTree(source, object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(
                        dir: Path,
                        attrs: BasicFileAttributes,
                    ): FileVisitResult {
                        cancellation.throwIfRequested()
                        itemCount += 1
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        cancellation.throwIfRequested()
                        itemCount += 1
                        if (attrs.isRegularFile) {
                            try {
                                totalBytes = Math.addExact(totalBytes, attrs.size())
                            } catch (_: Exception) {
                                allSizesKnown = false
                            }
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, error: IOException): FileVisitResult {
                        cancellation.throwIfRequested()
                        itemCount += 1
                        allSizesKnown = false
                        return FileVisitResult.CONTINUE
                    }
                })
            } catch (error: OperationCancelledException) {
                throw error
            } catch (error: Exception) {
                return reject(source, "无法扫描源项目", error.message ?: error.javaClass.simpleName)
            }
        }
        if (request.type != FileOperationType.DELETE && allSizesKnown) {
            val available = runCatching { usableSpace(requireNotNull(target)) }.getOrNull()
            if (available != null && available < totalBytes) {
                return reject(target, "可用空间明显不足")
            }
        }
        return ScanOutcome.Ready(itemCount, totalBytes.takeIf { allSizesKnown })
    }
}
