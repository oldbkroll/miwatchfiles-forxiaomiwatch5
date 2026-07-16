# M1B Safe Copy and Move Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add foreground-only, single-task recursive copy and move with target-folder selection, progress, cooperative cancellation, task-owned temporary files, and replace-all conflict handling.

**Architecture:** A filesystem-focused `FileOperationEngine` scans and mutates paths without UI dependencies. A ViewModel-based `FileOperationCoordinator` owns the single active task and exposes `StateFlow`; separate target-picker and operation composables integrate it with the existing browser while keeping `FileBrowserViewModel` focused on browsing.

**Tech Stack:** Kotlin 2.0.21, coroutines 1.9.0, Java NIO on Android API 29+, StateFlow, Compose/Wear Compose, JUnit 4, kotlinx-coroutines-test.

## Global Constraints

- Preserve `compileSdk 34`, `minSdk 29`, and `targetSdk 29`.
- Preserve `android:requestLegacyExternalStorage="true"` and the existing legacy Files & Media permission flow.
- Preserve `rotaryScrollableBehavior = null` and the custom `onRotaryScrollEvent` implementation.
- Keep `armeabi-v7a` as the only packaged ABI.
- Run scanning and filesystem mutation off the main thread.
- Do not add WorkManager, a foreground service, process recovery, permanent deletion, or directory merging.
- Conflict choices are exactly replace all remaining conflicts or cancel the task.
- Cancellation retains already published items and removes only unpublished paths owned by the current task ID.
- Build and install Debug only; do not build Release or update `releases` during M1B.
- Perform device writes only below `/storage/emulated/0/Download/WatchFilesTest/M1Sandbox`.
- Rediscover ADB before every device session; never assume an IP, port, or serial remains valid.

---

## Checkpoint 1: Safe Copy

### Task 1: Define Operation Models and Preflight Scanning

**Files:**

- Create: `app/src/main/java/com/example/watchfiles/fileops/FileOperationModels.kt`
- Create: `app/src/main/java/com/example/watchfiles/fileops/OperationCancellation.kt`
- Create: `app/src/main/java/com/example/watchfiles/fileops/FileOperationScanner.kt`
- Create: `app/src/test/java/com/example/watchfiles/fileops/FileOperationScannerTest.kt`

**Interfaces:**

- Produces `FileOperationType`, `FileOperationRequest`, `OperationProgress`, `FileConflict`, `FileOperationFailure`, `FileOperationResult`, and `FileOperationState`.
- Produces `FileOperationScanner.scan(request, cancellation): ScanOutcome`.
- Later tasks consume these exact model names.

- [ ] **Step 1: Write failing scanner tests**

Create `FileOperationScannerTest.kt` with temporary directories and these cases:

```kotlin
package com.example.watchfiles.fileops

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import kotlinx.coroutines.test.runTest

class FileOperationScannerTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun countsNestedFilesAndKnownBytes() = runTest {
        val root = temporaryFolder.newFolder("root").toPath()
        val source = Files.createDirectories(root.resolve("source/nested"))
        Files.write(root.resolve("source/a.txt"), byteArrayOf(1, 2, 3))
        Files.write(source.resolve("b.txt"), byteArrayOf(4, 5))
        val target = Files.createDirectory(root.resolve("target"))

        val outcome = FileOperationScanner().scan(
            FileOperationRequest("task-1", FileOperationType.COPY, listOf(root.resolve("source")), target),
            OperationCancellation(),
        )

        assertEquals(ScanOutcome.Ready(itemCount = 4, totalBytes = 5), outcome)
    }

    @Test fun rejectsTargetInsideSourceDirectory() = runTest {
        val source = temporaryFolder.newFolder("source").toPath()
        val target = Files.createDirectories(source.resolve("nested/target"))
        val outcome = FileOperationScanner().scan(
            FileOperationRequest("task-2", FileOperationType.COPY, listOf(source), target),
            OperationCancellation(),
        )
        assertTrue(outcome is ScanOutcome.Rejected)
        assertEquals("目标目录不能位于源文件夹内部", (outcome as ScanOutcome.Rejected).failure.userMessage)
    }

    @Test fun rejectsObviouslyInsufficientSpace() = runTest {
        val source = temporaryFolder.newFile("large.bin").toPath()
        Files.write(source, byteArrayOf(1, 2, 3))
        val target = temporaryFolder.newFolder("target").toPath()
        val outcome = FileOperationScanner(usableSpace = { 2L }).scan(
            FileOperationRequest("task-3", FileOperationType.COPY, listOf(source), target),
            OperationCancellation(),
        )
        assertEquals("可用空间明显不足", (outcome as ScanOutcome.Rejected).failure.userMessage)
    }

    @Test fun rejectsDestinationThatResolvesToTheSourceItself() = runTest {
        val parent = temporaryFolder.newFolder("same-parent").toPath()
        val source = Files.write(parent.resolve("same.txt"), byteArrayOf(1))
        val outcome = FileOperationScanner().scan(
            FileOperationRequest("task-4", FileOperationType.COPY, listOf(source), parent),
            OperationCancellation(),
        )
        assertEquals("目标与源项目相同", (outcome as ScanOutcome.Rejected).failure.userMessage)
    }
}
```

- [ ] **Step 2: Run the scanner tests and verify RED**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.watchfiles.fileops.FileOperationScannerTest" --no-daemon --console=plain
```

Expected: compilation fails because the operation models and scanner do not exist.

- [ ] **Step 3: Add immutable models**

Create `FileOperationModels.kt` with these public contracts:

```kotlin
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
```

Create `OperationCancellation.kt` now so both scanning and copying share the same cooperative token:

```kotlin
package com.example.watchfiles.fileops

import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

class OperationCancellation {
    private val requested = AtomicBoolean(false)
    fun request() { requested.set(true) }
    fun isRequested(): Boolean = requested.get()
    fun throwIfRequested() {
        if (requested.get()) throw OperationCancelledException()
    }
}

class OperationCancelledException : CancellationException("file operation cancelled")
```

- [ ] **Step 4: Implement no-follow preflight scanning**

Create `FileOperationScanner.kt`. Normalize absolute paths, reject an empty source list, missing/unreadable sources, missing/unwritable targets, a target equal to or nested below a source directory, and an already occupied task at the coordinator layer. Walk with `Files.walkFileTree` without `FOLLOW_LINKS`; count every source root, directory, and file once. Add regular-file sizes with `Files.size`; if any size read fails, return `totalBytes = null` while retaining the item count.

Use this implementation shape and keep every rejection message stable for UI/tests:

```kotlin
fun interface OperationScannerGateway {
    suspend fun scan(request: FileOperationRequest, cancellation: OperationCancellation): ScanOutcome
}

class FileOperationScanner(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val usableSpace: (Path) -> Long = { Files.getFileStore(it).usableSpace },
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
        val target = request.targetDirectory.toAbsolutePath().normalize()
        if (!Files.exists(target, NOFOLLOW_LINKS) || !Files.isDirectory(target, NOFOLLOW_LINKS)) {
            return reject(target, "目标文件夹已不存在")
        }
        if (!Files.isWritable(target)) return reject(target, "目标文件夹不可写")

        var itemCount = 0
        var totalBytes = 0L
        var allSizesKnown = true
        for (source in request.sources.map { it.toAbsolutePath().normalize() }.distinct()) {
            cancellation.throwIfRequested()
            if (!Files.exists(source, NOFOLLOW_LINKS)) return reject(source, "源项目已不存在")
            if (!Files.isReadable(source)) return reject(source, "源项目不可读")
            if (target.resolve(source.fileName).normalize() == source) {
                return reject(target, "目标与源项目相同")
            }
            if (Files.isDirectory(source, NOFOLLOW_LINKS) && target.startsWith(source)) {
                return reject(target, "目标目录不能位于源文件夹内部")
            }
            try {
                Files.walkFileTree(source, object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
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
        if (allSizesKnown) {
            val available = runCatching { usableSpace(target) }.getOrNull()
            if (available != null && available < totalBytes) {
                return reject(target, "可用空间明显不足")
            }
        }
        return ScanOutcome.Ready(itemCount, totalBytes.takeIf { allSizesKnown })
    }
}
```

Do not call `toRealPath()` for a target that may not exist; compare `toAbsolutePath().normalize()` values and use `NOFOLLOW_LINKS` for existence/type checks.

- [ ] **Step 5: Run scanner tests and the full unit suite**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.watchfiles.fileops.FileOperationScannerTest" --no-daemon --console=plain
.\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain
```

Expected: scanner tests and all existing M1A tests pass.

- [ ] **Step 6: Commit the scanner boundary**

```powershell
git add app/src/main/java/com/example/watchfiles/fileops/FileOperationModels.kt app/src/main/java/com/example/watchfiles/fileops/OperationCancellation.kt app/src/main/java/com/example/watchfiles/fileops/FileOperationScanner.kt app/src/test/java/com/example/watchfiles/fileops/FileOperationScannerTest.kt
git commit -m "feat: add file operation preflight scanner"
```

---

### Task 2: Copy Files Through Task-Owned Temporary Paths

**Files:**

- Create: `app/src/main/java/com/example/watchfiles/fileops/FileOperationEngine.kt`
- Create: `app/src/test/java/com/example/watchfiles/fileops/FileOperationEngineFileTest.kt`

**Interfaces:**

- Consumes the Task 1 operation models.
- Consumes `OperationCancellation.request()` and `throwIfRequested()` from Task 1.
- Produces `FileOperationEngine.execute(request, scan, cancellation, onProgress, onConflict): EngineOutcome`.

- [ ] **Step 1: Write failing file-copy safety tests**

Create `FileOperationEngineFileTest.kt` using `TemporaryFolder` and `runTest`. The test methods are:

```kotlin
@Test fun copiesFileAndLeavesNoTaskTemporaryPath()
@Test fun copyFailureLeavesSourceUnchangedAndRemovesTaskTemporaryPath()
@Test fun neverDeletesAUserOwnedDotPartFile()
@Test fun sourceMissingAfterScanReportsSourceDisappeared()
@Test fun accessDeniedReportsPermissionFailure()
@Test fun noSpaceWriteFailureReportsInsufficientSpace()
```

The success test writes known bytes, executes a COPY request, asserts target bytes match, source bytes remain, and no target child contains `.watchfiles-task-1.part`. The user-owned test creates `notes.part` before execution and asserts it remains byte-for-byte unchanged.

Inject a copy hook for the failure test with this constructor seam:

```kotlin
fun interface FileByteCopier {
    fun copy(
        source: Path,
        temporaryTarget: Path,
        cancellation: OperationCancellation,
        onBytes: (Long) -> Unit,
    )
}
```

The generic-failure fake copier writes one byte and throws `IOException("forced write failure")`. Separate fakes throw `AccessDeniedException(source.toString())` and `IOException("No space left on device")`; assert the stable messages `"没有权限写入目标"` and `"可用空间不足"`.

- [ ] **Step 2: Run the focused tests and verify RED**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.watchfiles.fileops.FileOperationEngineFileTest" --no-daemon --console=plain
```

Expected: compilation fails because the engine contracts do not exist.

- [ ] **Step 3: Implement engine result and gateway contracts**

Reuse `OperationCancellation` from Task 1 and add:

```kotlin
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
}
```

- [ ] **Step 4: Implement block-copy, sync, and publish**

The default `NioFileByteCopier` must use a 64 KiB buffer, call `cancellation.throwIfRequested()` between blocks, report each block size, flush, and call `FileDescriptor.sync()` before close:

```kotlin
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
```

Generate temporary names only through:

```kotlin
internal fun temporaryFile(target: Path, taskId: String): Path =
    target.resolveSibling(".${target.fileName}.watchfiles-$taskId.part")

internal fun backupPath(target: Path, taskId: String): Path =
    target.resolveSibling(".${target.fileName}.watchfiles-$taskId.backup")
```

For Task 2, implement `executeInternal` for regular files with this control flow; Task 3 replaces the directory rejection with recursive staging:

```kotlin
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
                    FileOperationResult(completed, 1, listOf(FileOperationFailure(source, "文件夹复制尚未启用"))),
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
                onProgress(OperationProgress(source.fileName.toString(), completed, scan.itemCount, processedBytes, scan.totalBytes))
            }
            if (Files.exists(target, NOFOLLOW_LINKS)) publishReplacement(staged, target, request.taskId)
            else publishNew(staged, target)
            staged = null
            completed += 1
            onProgress(OperationProgress(source.fileName.toString(), completed, scan.itemCount, processedBytes, scan.totalBytes))
        }
        EngineOutcome.Completed(FileOperationResult(completed, 0))
    } catch (_: OperationCancelledException) {
        staged?.let { runCatching { Files.deleteIfExists(it) } }
        EngineOutcome.Cancelled(FileOperationResult(completed, 0))
    } catch (error: PublishedWithBackupCleanupFailure) {
        EngineOutcome.Partial(
            FileOperationResult(
                completed + 1,
                1,
                listOf(FileOperationFailure(error.target, "新目标已完成，但旧目标备份清理失败", error.message)),
            ),
        )
    } catch (error: Exception) {
        staged?.let { runCatching { Files.deleteIfExists(it) } }
        EngineOutcome.Failed(
            FileOperationResult(
                completed,
                1,
                listOf(operationFailure(request.sources.getOrNull(completed), error)),
            ),
        )
    }
}

private class PublishedWithBackupCleanupFailure(
    val target: Path,
    cause: Exception,
) : IOException(cause.message, cause)
```

`publishNew` performs atomic move with a non-atomic retry only for `AtomicMoveNotSupportedException`. `publishReplacement` performs the backup, publish, restore, and backup cleanup transaction specified in Task 3; implement the file case now and extend the same helpers to directories in Task 3. If backup deletion fails after successful publication, throw `PublishedWithBackupCleanupFailure` so `executeInternal` returns `EngineOutcome.Partial` rather than swallowing it.

Map errors through one helper so UI strings remain testable:

```kotlin
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
    return FileOperationFailure(source, userMessage, message.ifBlank { error.javaClass.simpleName })
}

private class FileSyncException(cause: Exception) : IOException(cause.message, cause)
```

Wrap `output.fd.sync()` failures in `FileSyncException`; do not classify every `IOException` as a sync failure.

For a non-conflicting file: create the temporary file with `CREATE_NEW`, copy and sync, then move it to the final target with `ATOMIC_MOVE`; if atomic move is unsupported, retry without `ATOMIC_MOVE`. On any exception, delete only the exact task temporary path and return a structured failure. Never use a glob or suffix scan for cleanup.

- [ ] **Step 5: Run RED-to-GREEN tests and commit**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.watchfiles.fileops.FileOperationEngineFileTest" --no-daemon --console=plain
.\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain
git add app/src/main/java/com/example/watchfiles/fileops/FileOperationEngine.kt app/src/test/java/com/example/watchfiles/fileops/FileOperationEngineFileTest.kt
git commit -m "feat: copy files through owned temporary paths"
```

Expected: focused and full suites pass; commit contains no UI changes.

---

### Task 3: Add Recursive Directory Copy, Replacement, and Cancellation Cleanup

**Files:**

- Modify: `app/src/main/java/com/example/watchfiles/fileops/FileOperationEngine.kt`
- Create: `app/src/test/java/com/example/watchfiles/fileops/FileOperationEngineDirectoryTest.kt`

**Interfaces:**

- Extends the Task 2 engine without changing its public execute signature.
- Uses `onConflict: suspend (FileConflict) -> ReplacementDecision` exactly once; `REPLACE_ALL` is cached for the remainder of the request.

- [ ] **Step 1: Write failing recursive and replacement tests**

Add these tests using real temporary directories:

```kotlin
@Test fun copiesThreeLevelMixedDirectoryAndPublishesOnlyWhenComplete()
@Test fun replacementKeepsOldTargetUntilNewTemporaryDirectoryIsComplete()
@Test fun replaceAllCallbackRunsOnlyForFirstConflict()
@Test fun cancellationKeepsPublishedItemsAndRemovesUnpublishedTaskDirectory()
@Test fun fileDirectoryTypeConflictUsesReplacementFlow()
```

For callback-count testing, copy two sources whose target names already exist and increment an `AtomicInteger` in `onConflict`; assert it equals one. For cancellation, copy a small source first and a large source second, request cancellation from `onProgress` during the second, assert the first final target remains, the second final target is absent, all sources remain, and the exact task-owned `.part-dir` is gone.

- [ ] **Step 2: Verify the new tests fail**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.watchfiles.fileops.FileOperationEngineDirectoryTest" --no-daemon --console=plain
```

Expected: failures show directory copy and replace-all behavior are not implemented.

- [ ] **Step 3: Implement private directory staging**

Generate the top-level temporary directory with:

```kotlin
internal fun temporaryDirectory(target: Path, taskId: String): Path =
    target.resolveSibling(".${target.fileName}.watchfiles-$taskId.part-dir")
```

Create it with `CREATE_NEW` semantics, walk the source without following links, create descendants inside the private staging directory, and copy regular files with the Task 2 block copier. Because the whole top-level directory is unpublished, nested files do not need separate visible `.part` names. Treat a symbolic link as a single unsupported item and fail with `"暂不支持复制符号链接"`; never traverse it.

- [ ] **Step 4: Implement replace-all publication**

On the first existing target, invoke `onConflict(FileConflict(source, target))`. If it returns `CANCEL`, request cancellation and stop. If it returns `REPLACE_ALL`, cache that decision for all later conflicts.

For every replacement:

```kotlin
moveWithAtomicFallback(target, backup)
try {
    publishNew(staged, target)
} catch (publishFailure: Exception) {
    moveWithAtomicFallback(backup, target)
    throw publishFailure
}
try {
    deleteRecursivelyNoFollow(backup)
} catch (cleanupFailure: Exception) {
    throw PublishedWithBackupCleanupFailure(target, cleanupFailure)
}
```

`moveWithAtomicFallback` retries without `ATOMIC_MOVE` only for `AtomicMoveNotSupportedException`; it never uses `REPLACE_EXISTING`. Do not delete the old target first. `deleteRecursivelyNoFollow` must only receive the exact task backup or staging path and must visit children before their parent.

- [ ] **Step 5: Implement progress and cooperative cancellation**

Initialize progress from `ScanOutcome.Ready`; update bytes from each copied block and item count after each fully published file/directory entry. Check cancellation before each source, directory entry, data block, and publication. Convert `OperationCancelledException` to `EngineOutcome.Cancelled` with the number of already published top-level items.

- [ ] **Step 6: Run directory, engine, and full tests; commit**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.watchfiles.fileops.FileOperationEngineDirectoryTest" --no-daemon --console=plain
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.watchfiles.fileops.*" --no-daemon --console=plain
.\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain
git add app/src/main/java/com/example/watchfiles/fileops/FileOperationEngine.kt app/src/test/java/com/example/watchfiles/fileops/FileOperationEngineDirectoryTest.kt
git commit -m "feat: add safe recursive directory copy"
```

---

### Task 4: Coordinate One Foreground Task and Pause for Replacement

**Files:**

- Create: `app/src/main/java/com/example/watchfiles/fileops/FileOperationCoordinator.kt`
- Create: `app/src/test/java/com/example/watchfiles/fileops/FileOperationCoordinatorTest.kt`

**Interfaces:**

- Produces `FileOperationCoordinator.state: StateFlow<FileOperationState>`.
- Produces `start(type, sources, targetDirectory)`, `replaceAll()`, `cancel()`, and `consumeResult()`.
- Coordinator generates task IDs; callers never supply them.

- [ ] **Step 1: Write failing coordinator tests**

Use `MainDispatcherRule` and fake scanner/engine gateways to verify:

```kotlin
@Test fun refusesSecondTaskWhileFirstIsRunning()
@Test fun cancelDuringScanningRequestsTokenAndEndsCancelled()
@Test fun exposesWaitingConflictUntilReplaceAllIsChosen()
@Test fun cancelWhileWaitingCompletesDeferredWithCancel()
@Test fun mapsCompletedPartialFailedAndCancelledOutcomes()
@Test fun consumeResultReturnsToIdle()
```

Reuse the injectable `OperationScannerGateway` and `OperationEngineGateway` contracts created in Tasks 1 and 2:

```kotlin
val scanner: OperationScannerGateway = OperationScannerGateway { request, token -> scanResult }
val engine: OperationEngineGateway = OperationEngineGateway { request, scan, token, progress, conflict ->
    engineOutcome
}
```

- [ ] **Step 2: Run focused tests and verify RED**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.watchfiles.fileops.FileOperationCoordinatorTest" --no-daemon --console=plain
```

Expected: compilation fails because the coordinator does not exist.

- [ ] **Step 3: Implement the ViewModel coordinator**

Use this construction and state ownership:

```kotlin
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
}
```

`start` returns `false` unless state is `Idle`; otherwise it creates a request and cancellation token, emits `Scanning`, calls `scanner.scan(request, token)`, then calls the engine in `viewModelScope`. If scanning throws `OperationCancelledException`, map directly to `Cancelled`. The conflict callback emits `WaitingForReplacement` and awaits a new `CompletableDeferred`. `replaceAll()` completes it with `REPLACE_ALL`; `cancel()` completes a waiting conflict with `CANCEL` and requests the token. During a scanning or running cancel emit `Cancelling` with the last progress. Map every engine outcome to the matching terminal state.

Wrap `android.util.Log` calls with `runCatching`, matching the existing JVM-test-safe logging pattern.

- [ ] **Step 4: Run tests and commit**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.watchfiles.fileops.FileOperationCoordinatorTest" --no-daemon --console=plain
.\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain
git add app/src/main/java/com/example/watchfiles/fileops/FileOperationCoordinator.kt app/src/test/java/com/example/watchfiles/fileops/FileOperationCoordinatorTest.kt
git commit -m "feat: coordinate one foreground file task"
```

---

### Task 5: Add a Directory-Only Target Picker

**Files:**

- Create: `app/src/main/java/com/example/watchfiles/fileops/TargetDirectoryViewModel.kt`
- Create: `app/src/test/java/com/example/watchfiles/fileops/TargetDirectoryViewModelTest.kt`
- Create: `app/src/main/java/com/example/watchfiles/FileOperationScreens.kt`
- Modify: `app/src/main/java/com/example/watchfiles/MainActivity.kt`

**Interfaces:**

- Produces `TargetDirectoryUiState(currentPath, directories, isLoading, errorMessage)`.
- Produces `TargetDirectoryViewModel.open(path)` and `navigateUp(storageRoot)`.
- Produces `TargetDirectoryScreen(state, sourceCount, onOpenDirectory, onUseCurrent, onCancel)`.

- [ ] **Step 1: Write failing target picker tests**

Inject `DirectoryReader` and an initial path. Verify `open` filters out files, sorts directories through the existing reader order, and `navigateUp` never escapes the provided storage root. Reuse `MainDispatcherRule`.

- [ ] **Step 2: Verify RED**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.watchfiles.fileops.TargetDirectoryViewModelTest" --no-daemon --console=plain
```

- [ ] **Step 3: Implement the target ViewModel**

Use a `MutableStateFlow`, cancel the previous load job, call `DirectoryReader.list(path)` inside `viewModelScope`, and publish only `entry.isDirectory` entries. `navigateUp(storageRoot)` compares normalized absolute paths and returns `false` at the root.

- [ ] **Step 4: Implement the round target screen**

In root-package `FileOperationScreens.kt`, use the existing `ScalingLazyColumn`, `PositionIndicator`, and custom crown modifier pattern. Change the top-level `AppChip` and `RoundList` declarations in `MainActivity.kt` from `private` to `internal` so the new screen file reuses the exact controls instead of cloning them. The first items must be:

```kotlin
item { ListHeader { Text("选择目标 · $sourceCount 项") } }
item { AppChip("放到此处", state.currentPath.toString(), onClick = onUseCurrent) }
item { AppChip("返回上级", "浏览父目录", onClick = onNavigateUp) }
item { AppChip("取消", "返回原目录", onClick = onCancel) }
```

Render directory chips only; no ordinary file is clickable or visible in target mode.

- [ ] **Step 5: Add target-picker routing without starting copy yet**

In `MainActivity.kt`:

- add `TARGET_DIRECTORY` to `AppScreen`;
- add `private val targetDirectoryViewModel by viewModels<TargetDirectoryViewModel>()`;
- capture `pendingOperationSources: List<Path>` with `remember`;
- add a “复制” action only while selection is active;
- on copy, capture selected paths, open the current browser path in target picker, and enter `TARGET_DIRECTORY`;
- Back or Cancel returns to `BROWSER` without clearing the original selection;
- “放到此处” remains wired to a local callback that Task 6 connects to the coordinator.

- [ ] **Step 6: Test, build, inspect semantics, and commit**

```powershell
.\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain
.\gradlew.bat :app:assembleDebug --no-daemon --console=plain
git add app/src/main/java/com/example/watchfiles/fileops/TargetDirectoryViewModel.kt app/src/test/java/com/example/watchfiles/fileops/TargetDirectoryViewModelTest.kt app/src/main/java/com/example/watchfiles/FileOperationScreens.kt app/src/main/java/com/example/watchfiles/MainActivity.kt
git commit -m "feat: add copy target directory picker"
```

Expected: build succeeds; selection UI exposes copy; target screen lists only folders and fits the round display.

---

### Task 6: Wire Copy Progress, Replacement Confirmation, and Results

**Files:**

- Modify: `app/src/main/java/com/example/watchfiles/FileOperationScreens.kt`
- Modify: `app/src/main/java/com/example/watchfiles/MainActivity.kt`
- Modify: `app/src/main/java/com/example/watchfiles/browser/FileBrowserViewModel.kt`
- Modify: `app/src/test/java/com/example/watchfiles/browser/FileBrowserViewModelTest.kt`

**Interfaces:**

- Consumes `FileOperationCoordinator` and all `FileOperationState` variants.
- Produces `FileOperationScreen(state, onReplaceAll, onCancel, onDone)`.
- Produces `FileBrowserViewModel.refreshAfterOperation()`.

- [ ] **Step 1: Write the failing browser refresh test**

Add a test that begins selection, changes the fake reader result, calls `refreshAfterOperation()`, advances the dispatcher, and asserts entries refresh and selection becomes empty. Run it and verify `refreshAfterOperation` is missing.

- [ ] **Step 2: Implement operation-aware refresh**

Add:

```kotlin
fun refreshAfterOperation() {
    clearSelection()
    open(_state.value.currentPath)
}
```

Run the focused ViewModel test and confirm GREEN.

- [ ] **Step 3: Implement round operation screens**

`FileOperationScreen` renders by state:

- `Scanning`: “正在扫描…” and Cancel.
- `Running`: operation type, current name, `processedItems / totalItems`, formatted bytes when known, and Cancel.
- `WaitingForReplacement`: target name, warning that directories are replaced as a whole, “替换全部”, and “取消任务”.
- `Cancelling`: “正在停止并清理临时文件…”, with no second cancel action.
- terminal states: status title, completed/failed counts, first concise failure message, and “返回目录”.

For `WaitingForReplacement`, the replacement button must say exactly `替换全部` and the secondary text must say `本任务后续同名项目不再询问`.

- [ ] **Step 4: Wire the coordinator into the Activity**

Add:

```kotlin
private val fileOperationCoordinator by viewModels<FileOperationCoordinator>()
```

Collect its state in `WatchFilesApp`. From “放到此处”, call:

```kotlin
fileOperationCoordinator.start(
    type = FileOperationType.COPY,
    sources = pendingOperationSources,
    targetDirectory = targetState.currentPath,
)
screen = AppScreen.FILE_OPERATION
```

Add `FILE_OPERATION` to `AppScreen`; route it to `FileOperationScreen`. Disable Back while state is Scanning, Running, Waiting, or Cancelling; terminal “返回目录” must call `browserViewModel.refreshAfterOperation()`, `fileOperationCoordinator.consumeResult()`, clear pending sources, and return to `BROWSER`.

- [ ] **Step 5: Run full local verification**

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --no-daemon --console=plain
```

Expected: all tests pass, Debug builds, and lint XML contains zero issue nodes.

- [ ] **Step 6: Commit Checkpoint 1 UI integration**

```powershell
git add app/src/main/java/com/example/watchfiles/FileOperationScreens.kt app/src/main/java/com/example/watchfiles/MainActivity.kt app/src/main/java/com/example/watchfiles/browser/FileBrowserViewModel.kt app/src/test/java/com/example/watchfiles/browser/FileBrowserViewModelTest.kt
git commit -m "feat: add round-screen copy workflow"
```

---

### Task 7: Checkpoint 1 Real-Device Acceptance and Documentation

**Files:**

- Modify after acceptance: `docs/superpowers/context/PROJECT_CONTEXT.md`
- Modify after acceptance: `docs/superpowers/roadmap/PROJECT_PLAN.md`
- Modify after acceptance: `README.md`
- Modify after acceptance: `docs/superpowers/checkpoints/TESTING.md`

**Interfaces:**

- Consumes the complete safe-copy Debug build.
- Produces a documented, committed Checkpoint 1 while keeping the M1B COPY/MOVE status explicit until Checkpoint 2 closes.

- [ ] **Step 1: Discover exactly one current watch transport**

Run:

```powershell
adb devices -l
adb mdns services
```

Choose only a currently online transport whose model is `M2505W1`/device `grasslte`. If duplicate aliases represent the same watch, pass the selected serial with `adb -s`. If the watch disappears repeatedly, stop and ask the user to reopen wireless debugging. Never target other network devices.

- [ ] **Step 2: Prepare deterministic sandbox fixtures**

Under `M1Sandbox`, create `CopySource` and `CopyTarget` containing: empty file, known text file, a multi-megabyte file, empty directory, and three nested directory levels. Also create target conflicts for file/file, directory/directory, and file/directory cases plus a user-owned `notes.part`. Record source hashes before testing.

- [ ] **Step 3: Build, install, and verify compatibility metadata**

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --no-daemon --console=plain
$serial=Read-Host 'Paste the exact current M2505W1 serial shown by adb devices'
adb -s $serial install -r app\build\outputs\apk\debug\app-debug.apk
adb -s $serial shell dumpsys package com.example.watchfiles.debug | Select-String 'versionCode=|versionName=|targetSdk='
```

Expected: tests/build/lint pass; install succeeds; `targetSdk=29`, versionCode 6, versionName `0.3.1-dev-debug`.

- [ ] **Step 4: Perform watch copy acceptance**

Verify normal multi-source copy, nested directory copy, crown scrolling in target mode, invalid target-inside-source rejection, copy cancellation during the large file, replace-all across multiple conflicts, and cancel from the first conflict page. Confirm the operation page is understandable at 480×480 and never starts a second task.

- [ ] **Step 5: Verify filesystem safety by ADB**

Compare source hashes to the pre-test hashes. Confirm successful targets match, cancelled unpublished targets and task-owned `.part`/`.part-dir` paths are absent, completed pre-cancel items remain, `notes.part` remains unchanged, and no WatchFiles crash exists in logcat.

- [ ] **Step 6: Update Checkpoint 1 documentation**

Document safe copy, the exact sandbox results, test counts, Debug APK hash, and any device observations. Keep the canonical roadmap and checkpoint paths under `docs/superpowers/`; do not copy an APK into `releases`.

- [ ] **Step 7: Re-run verification and commit**

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --no-daemon --console=plain
Get-FileHash app\build\outputs\apk\debug\app-debug.apk -Algorithm SHA256
git add docs/superpowers/context/PROJECT_CONTEXT.md docs/superpowers/roadmap/PROJECT_PLAN.md README.md docs/superpowers/checkpoints/TESTING.md docs/superpowers/checkpoints/2026-07-16-m1b-closeout.md
git commit -m "docs: record M1B safe copy acceptance"
```

---

## Checkpoint 2: Safe Move

### Task 8: Add Fast Move and Copy-Then-Delete Fallback

**Files:**

- Modify: `app/src/main/java/com/example/watchfiles/fileops/FileOperationEngine.kt`
- Create: `app/src/test/java/com/example/watchfiles/fileops/FileOperationEngineMoveTest.kt`

**Interfaces:**

- Extends `FileOperationEngine.execute` to honor `FileOperationType.MOVE`.
- Produces the same `EngineOutcome` variants; source-delete failure maps to `Partial`.

- [ ] **Step 1: Write failing move tests**

Create `FileOperationEngineMoveTest.kt` with `TemporaryFolder`, `runTest`, and these test methods:

```kotlin
@Test fun fastMoveRelocatesSourceOnSameFileStore()
@Test fun atomicMoveUnsupportedFallsBackToCopyThenDelete()
@Test fun fallbackNeverDeletesSourceBeforeTargetPublication()
@Test fun sourceDeleteFailureKeepsTargetAndReturnsPartial()
@Test fun cancellationDuringFallbackCopyLeavesSourceUnchanged()
@Test fun moveReplacementRestoresOldTargetWhenPublicationFails()
```

Add focused seams to the engine constructor:

```kotlin
fun interface FastMover {
    fun move(source: Path, target: Path): Boolean
}

fun interface SourceDeleter {
    fun delete(source: Path)
}
```

The default fast mover attempts `Files.move` with `ATOMIC_MOVE`, returning `false` only for `AtomicMoveNotSupportedException` or a confirmed cross-filesystem condition. It must propagate permission, missing-source, and other failures instead of silently falling back.

The default `SourceDeleter` calls the same no-follow, children-first `deleteRecursivelyNoFollow(source)` helper used for owned staging cleanup. It receives a source path only after target publication and verification have completed.

- [ ] **Step 2: Verify RED**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.watchfiles.fileops.FileOperationEngineMoveTest" --no-daemon --console=plain
```

- [ ] **Step 3: Implement fast move and fallback ordering**

Add these private helpers with exact signatures:

```kotlin
private suspend fun stagePublishAndVerify(
    request: FileOperationRequest,
    source: Path,
    target: Path,
    cancellation: OperationCancellation,
    onProgress: (OperationProgress) -> Unit,
)

private fun verifyPublishedTarget(source: Path, target: Path)
```

For each top-level source, after the shared conflict decision has been resolved:

```kotlin
if (canUseFastMove(source, target) && fastMover.move(source, target)) {
    completed += 1
} else {
    stagePublishAndVerify(request, source, target, cancellation, onProgress)
    verifyPublishedTarget(source, target)
    try {
        sourceDeleter.delete(source)
    } catch (error: Exception) {
        return EngineOutcome.Partial(
            FileOperationResult(
                completedItems = completed + 1,
                failedItems = 1,
                failures = listOf(
                    FileOperationFailure(source, "目标已保留，但源项目删除失败", error.message),
                ),
            ),
        )
    }
    completed += 1
}
```

`verifyPublishedTarget` checks no-follow type equality and regular-file size equality when readable. For directories, successful publication of the completely staged top-level directory is the required integrity boundary; do not rescan the entire target merely to delete the source.

For replacement fast moves, reuse the backup-and-restore publication transaction. Never pass `REPLACE_EXISTING` directly against an existing directory tree.

- [ ] **Step 4: Run move, engine, and full suites; commit**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.watchfiles.fileops.FileOperationEngineMoveTest" --no-daemon --console=plain
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.watchfiles.fileops.*" --no-daemon --console=plain
.\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain
git add app/src/main/java/com/example/watchfiles/fileops/FileOperationEngine.kt app/src/test/java/com/example/watchfiles/fileops/FileOperationEngineMoveTest.kt
git commit -m "feat: add safe move with copy fallback"
```

---

### Task 9: Expose Move in the Existing Target and Operation Workflow

**Files:**

- Modify: `app/src/main/java/com/example/watchfiles/MainActivity.kt`
- Modify: `app/src/main/java/com/example/watchfiles/FileOperationScreens.kt`
- Modify: `app/src/test/java/com/example/watchfiles/fileops/FileOperationCoordinatorTest.kt`

**Interfaces:**

- Reuses the target picker and operation page from Checkpoint 1.
- Adds no new coordinator state variants.

- [ ] **Step 1: Add a failing coordinator MOVE mapping test**

Start a MOVE request against the fake engine, emit progress, return `EngineOutcome.Partial`, and assert the state is `FileOperationState.PartiallySucceeded` with type `MOVE`. Run the focused test and confirm any missing mapping fails.

- [ ] **Step 2: Add the move selection action**

In selection mode render both:

```kotlin
AppChip("复制", "选择目标文件夹", onClick = onCopySelected)
AppChip("移动", "复制成功后删除源项目", onClick = onMoveSelected)
```

Capture the chosen `FileOperationType` together with pending sources. Both actions enter the same `TARGET_DIRECTORY` screen; “放到此处” passes the captured type to `coordinator.start`.

- [ ] **Step 3: Clarify move-specific progress and partial result text**

For MOVE, the operation title is `正在移动`; Cancelling explains that already completed items remain at the target. A source-delete failure result must show `目标已保留，但源项目删除失败` and must never be labeled success.

- [ ] **Step 4: Run full verification and commit**

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --no-daemon --console=plain
git add app/src/main/java/com/example/watchfiles/MainActivity.kt app/src/main/java/com/example/watchfiles/FileOperationScreens.kt app/src/test/java/com/example/watchfiles/fileops/FileOperationCoordinatorTest.kt
git commit -m "feat: add round-screen safe move workflow"
```

---

### Task 10: Checkpoint 2 Real-Device Acceptance and M1B Handoff

**Files:**

- Modify: `docs/superpowers/context/PROJECT_CONTEXT.md`
- Modify: `docs/superpowers/roadmap/PROJECT_PLAN.md`
- Modify: `README.md`
- Modify: `docs/superpowers/checkpoints/TESTING.md`

**Interfaces:**

- Consumes the complete M1B Debug build.
- Produces the final M1B documentation and leaves the feature branch ready for the user's chosen integration workflow.

- [ ] **Step 1: Rediscover the current watch connection**

Run `adb devices -l` and `adb mdns services`; select only a current `M2505W1`/`grasslte` transport. If discovery or commands fail repeatedly, ask the user to reopen wireless debugging before continuing.

- [ ] **Step 2: Recreate move fixtures and source hashes in M1Sandbox**

Create separate fast-move, fallback-move, conflict, cancellation, and nested-directory fixtures. No command may write outside `M1Sandbox`.

- [ ] **Step 3: Install Debug and perform move acceptance**

Verify same-storage fast move, recursive directory move, replace-all, cancellation during copy fallback, illegal target rejection, and correct directory refresh. Use an injected/local automated failure test—not permission changes to real user folders—to prove source-delete partial-success behavior.

- [ ] **Step 4: Verify safety and regressions**

Confirm moved target bytes/hashes, expected source removal only after success, intact sources after cancelled/failed fallback, no task-owned temporary residue, unchanged user `notes.part`, no crash, working crown scrolling, working M1A create/rename, and working low-memory image viewer.

- [ ] **Step 5: Update documentation**

- Mark the canonical roadmap “复制与移动”, “同名冲突处理”, “单任务操作队列”, `.part` cleanup, and implemented error prompts complete only if their exact acceptance checks passed.
- Record Checkpoint 1 and 2 test evidence in the canonical context and checkpoint files.
- Update `README.md` capabilities and retain that permanent delete is unavailable.
- Expand the canonical testing checklist with repeatable copy/move, replace-all, cancellation, and safety checks.
- Keep versionCode 6/versionName `0.3.1-dev-debug`; do not build Release or update `releases` until all of M1, including M1C, is complete.

- [ ] **Step 6: Run the final evidence gate**

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --no-daemon --console=plain
$tests=Get-ChildItem app\build\test-results\testDebugUnitTest\TEST-*.xml
$tests | ForEach-Object { [xml](Get-Content -Raw $_.FullName) } | ForEach-Object { $_.testsuite }
[xml]$lint=Get-Content -Raw app\build\reports\lint-results-debug.xml
"LINT_ISSUE_NODES=$($lint.issues.issue.Count)"
Get-FileHash app\build\outputs\apk\debug\app-debug.apk -Algorithm SHA256
git diff --check
```

Expected: zero test failures/errors, zero lint issue nodes, Debug APK hash recorded, and no whitespace errors.

- [ ] **Step 7: Commit the M1B handoff**

```powershell
git add docs/superpowers/context/PROJECT_CONTEXT.md docs/superpowers/roadmap/PROJECT_PLAN.md README.md docs/superpowers/checkpoints/TESTING.md docs/superpowers/checkpoints/2026-07-16-m1b-closeout.md docs/superpowers/workflow/personal-project-simplified-workflow.md docs/superpowers/README.md
git commit -m "docs: record M1B copy and move acceptance"
git status --short --branch
```

Expected: clean `m1-file-operations` branch. Use `superpowers:verification-before-completion` before claiming M1B complete, then `superpowers:finishing-a-development-branch` to let the user choose merge, push/PR, keep, or discard.
