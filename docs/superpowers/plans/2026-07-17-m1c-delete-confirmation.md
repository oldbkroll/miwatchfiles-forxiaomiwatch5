# M1C Delete Confirmation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a round-screen, permanently destructive DELETE workflow with pre-scan, explicit confirmation, recursive no-follow deletion, cooperative cancellation, and structured partial-failure results.

**Architecture:** Extend the existing `FileOperationEngine`, `FileOperationCoordinator`, and `FileOperationState` used by COPY/MOVE. DELETE uses a request with no target directory, a separate pre-scan confirmation state, and the same single-task progress/result pipeline. The browser adds a DELETE action and routes through a dedicated round-screen confirmation page before the engine can mutate anything.

**Tech Stack:** Kotlin 2.0.21, Java NIO `Path`/`Files.walkFileTree`, Kotlin coroutines and `StateFlow`, Android ViewModel, Jetpack Compose/Wear Compose, JUnit 4, `kotlinx-coroutines-test`, Gradle Debug build and Android Lint.

## Global Constraints

- Preserve `compileSdk 34`, `minSdk 29`, and `targetSdk 29`.
- Preserve `android:requestLegacyExternalStorage="true"` and the existing legacy Files & Media permission flow.
- Preserve `rotaryScrollableBehavior = null` and the existing custom `onRotaryScrollEvent` implementation.
- Keep `armeabi-v7a` as the only packaged ABI.
- Keep all filesystem scanning and mutation on `Dispatchers.IO` or the existing injected IO dispatcher.
- Keep COPY/MOVE behavior unchanged, including `.part`, `.part-dir`, `.backup`, replace-all, fast move, and copy-then-delete fallback semantics.
- DELETE must never use a target directory, follow a symbolic link, or perform wildcard cleanup.
- DELETE must reject `/storage/emulated/0` itself and must repeat that check in both scanner and engine.
- DELETE confirmation must precede every destructive delete call.
- Cancellation is cooperative; deleted content is never restored.
- Real-device writes are limited to `/storage/emulated/0/Download/WatchFilesTest/M1Sandbox`.
- Rediscover wireless ADB before every device session; never assume a historical address or serial.
- During M1C use Debug unit tests, `assembleDebug`, and `lintDebug` only; do not build Release or update `releases`.
- Use TDD for scanner, coordinator, engine, root protection, and cancellation safety.

---

## File Map

| File | Responsibility in M1C |
| --- | --- |
| `app/src/main/java/com/example/watchfiles/fileops/FileOperationModels.kt` | Add DELETE request/type, preview summary, and confirmation state while preserving transfer contracts. |
| `app/src/main/java/com/example/watchfiles/fileops/FileOperationScanner.kt` | Validate DELETE sources, reject storage root, and calculate recursive item/size preview without mutation. |
| `app/src/main/java/com/example/watchfiles/fileops/FileOperationEngine.kt` | Delete selected paths recursively, no-follow, with injected filesystem operations and structured outcomes. |
| `app/src/main/java/com/example/watchfiles/fileops/FileOperationCoordinator.kt` | Hold the single task through scan, confirmation, execution, cancellation, and terminal state. |
| `app/src/main/java/com/example/watchfiles/FileOperationScreens.kt` | Render DELETE confirmation, progress, cancellation, and result text on the round screen. |
| `app/src/main/java/com/example/watchfiles/MainActivity.kt` | Add DELETE selection action, confirmation routing, Back behavior, and result refresh. |
| `app/src/test/java/com/example/watchfiles/fileops/FileOperationDeleteScannerTest.kt` | TDD for DELETE request validation, root protection, recursive counts, and symlinks. |
| `app/src/test/java/com/example/watchfiles/fileops/FileOperationEngineDeleteTest.kt` | TDD for recursive deletion, no-follow behavior, failure, cancellation, and unrelated files. |
| `app/src/test/java/com/example/watchfiles/fileops/FileOperationCoordinatorTest.kt` | TDD for confirmation gating, cancellation before confirmation, and DELETE state mapping. |
| `docs/superpowers/checkpoints/2026-07-17-m1c-closeout.md` | Device acceptance evidence and M1C handoff. |
| `docs/superpowers/context/PROJECT_CONTEXT.md` | Update current stage only after acceptance. |
| `docs/superpowers/roadmap/PROJECT_PLAN.md` | Mark DELETE complete only after all evidence passes. |
| `README.md` | Update user-facing capability summary after acceptance. |
| `docs/superpowers/checkpoints/TESTING.md` | Add the repeatable M1C device checklist after acceptance. |

---

### Task 1: Add DELETE Models and Preflight Scanner

**Files:**

- Modify: `app/src/main/java/com/example/watchfiles/fileops/FileOperationModels.kt`
- Modify: `app/src/main/java/com/example/watchfiles/fileops/FileOperationScanner.kt`
- Create: `app/src/test/java/com/example/watchfiles/fileops/FileOperationDeleteScannerTest.kt`

**Interfaces:**

- Preserve `FileOperationRequest(taskId, type, sources, targetDirectory)` parameter order, changing only `targetDirectory` to `Path?` with a default of `null`.
- Produce `FileOperationRequest.transfer(taskId, type, sources, targetDirectory)` and `FileOperationRequest.delete(taskId, sources)` factory functions.
- Add `FileOperationType.DELETE`.
- Add `DeletePreview(topLevelCount: Int, itemCount: Int, totalBytes: Long?)`.
- Add `FileOperationState.WaitingForDeleteConfirmation(preview: DeletePreview)`.
- Extend `FileOperationScanner` with `storageRoot: () -> Path = { Paths.get("/storage/emulated/0") }`; keep existing `usableSpace` behavior for COPY/MOVE.

- [ ] **Step 1: Write failing model/scanner tests**

Add these tests to `FileOperationDeleteScannerTest.kt`:

```kotlin
@Test fun deleteScanCountsNestedItemsAndKnownBytes() = runTest {
    val root = temporaryFolder.newFolder("root").toPath()
    val source = Files.createDirectories(root.resolve("source/nested"))
    Files.write(root.resolve("source/a.txt"), byteArrayOf(1, 2, 3))
    Files.write(source.resolve("b.txt"), byteArrayOf(4, 5))

    val outcome = scanner(root).scan(
        FileOperationRequest.delete("delete-1", listOf(root.resolve("source"))),
        OperationCancellation(),
    )

    assertEquals(ScanOutcome.Ready(itemCount = 4, totalBytes = 5), outcome)
}

@Test fun deleteScanRejectsStorageRoot() = runTest {
    val root = temporaryFolder.newFolder("storage").toPath()
    val outcome = scanner(root).scan(
        FileOperationRequest.delete("delete-root", listOf(root)),
        OperationCancellation(),
    )

    assertEquals("不能删除内部存储根目录", (outcome as ScanOutcome.Rejected).failure.userMessage)
}

@Test fun deleteScanRejectsTransferWithoutTarget() = runTest {
    val source = temporaryFolder.newFile("source.txt").toPath()
    val request = FileOperationRequest("bad-transfer", FileOperationType.COPY, listOf(source), null)

    val outcome = scanner(temporaryFolder.root.toPath()).scan(request, OperationCancellation())

    assertEquals("复制或移动必须指定目标目录", (outcome as ScanOutcome.Rejected).failure.userMessage)
}

@Test fun deleteScanRejectsDeleteWithTarget() = runTest {
    val source = temporaryFolder.newFile("source.txt").toPath()
    val request = FileOperationRequest(
        "bad-delete",
        FileOperationType.DELETE,
        listOf(source),
        temporaryFolder.root.toPath(),
    )

    val outcome = scanner(temporaryFolder.root.toPath()).scan(request, OperationCancellation())

    assertEquals("删除请求不应包含目标目录", (outcome as ScanOutcome.Rejected).failure.userMessage)
}

@Test fun deleteScanCountsSymlinkWithoutWalkingItsTarget() = runTest {
    val root = temporaryFolder.newFolder("symlink").toPath()
    val target = Files.createDirectory(root.resolve("target"))
    Files.write(target.resolve("hidden.txt"), byteArrayOf(1, 2, 3))
    val link = root.resolve("link")
    try {
        Files.createSymbolicLink(link, target)
    } catch (_: UnsupportedOperationException) {
        Assume.assumeTrue("symlink unsupported", false)
    } catch (_: IOException) {
        Assume.assumeTrue("symlink unavailable", false)
    }

    val outcome = scanner(root).scan(
        FileOperationRequest.delete("delete-link", listOf(link)),
        OperationCancellation(),
    )

    assertEquals(ScanOutcome.Ready(itemCount = 1, totalBytes = 0), outcome)
    assertTrue(Files.exists(target.resolve("hidden.txt")))
}
```

Use a helper with an injected root:

```kotlin
private fun scanner(root: Path) = FileOperationScanner(
    storageRoot = { root },
    usableSpace = { Long.MAX_VALUE },
)
```

- [ ] **Step 2: Run the focused tests and verify RED**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.watchfiles.fileops.FileOperationDeleteScannerTest" --no-daemon --console=plain
```

Expected: compilation fails because `DELETE`, `FileOperationRequest.delete`, and the confirmation preview/state do not exist.

- [ ] **Step 3: Add the DELETE model contracts**

Keep existing transfer constructors source-compatible and add the following shape:

```kotlin
enum class FileOperationType { COPY, MOVE, DELETE }

data class FileOperationRequest(
    val taskId: String,
    val type: FileOperationType,
    val sources: List<Path>,
    val targetDirectory: Path? = null,
) {
    companion object {
        fun transfer(
            taskId: String,
            type: FileOperationType,
            sources: List<Path>,
            targetDirectory: Path,
        ) = FileOperationRequest(taskId, type, sources, targetDirectory)

        fun delete(taskId: String, sources: List<Path>) =
            FileOperationRequest(taskId, FileOperationType.DELETE, sources, null)
    }
}

data class DeletePreview(
    val topLevelCount: Int,
    val itemCount: Int,
    val totalBytes: Long?,
)

sealed interface FileOperationState {
    // existing states remain unchanged
    data class WaitingForDeleteConfirmation(
        val preview: DeletePreview,
    ) : FileOperationState
}
```

Do not replace the existing `ScanOutcome.Ready(itemCount, totalBytes)` contract; `topLevelCount` comes from the distinct source list when the coordinator creates `DeletePreview`.

- [ ] **Step 4: Implement DELETE scanner branching**

At the start of `scanBlocking`, branch the request validation before dereferencing the target:

```kotlin
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

val storageRoot = storageRoot().toAbsolutePath().normalize()
for (source in normalizedSources) {
    cancellation.throwIfRequested()
    if (request.type == FileOperationType.DELETE && source == storageRoot) {
        return reject(source, "不能删除内部存储根目录")
    }
    // Existing COPY/MOVE target checks stay inside their existing branch.
    // The no-follow recursive counter is shared by all operation types.
}
```

The shared walk must use `Files.walkFileTree(source, visitor)` without `FOLLOW_LINKS`. For a symlink, count the link once, do not descend, and do not add its target's bytes. For an unreadable size, set `allSizesKnown = false` while preserving the item count. DELETE skips usable-space checks because it writes no bytes.

- [ ] **Step 5: Run the focused and full unit suites**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.watchfiles.fileops.FileOperationDeleteScannerTest" --no-daemon --console=plain
.\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain
```

Expected: all DELETE scanner tests and all existing M0/M1A/M1B tests pass.

- [ ] **Step 6: Commit the model and scanner boundary**

```powershell
git add app/src/main/java/com/example/watchfiles/fileops/FileOperationModels.kt app/src/main/java/com/example/watchfiles/fileops/FileOperationScanner.kt app/src/test/java/com/example/watchfiles/fileops/FileOperationDeleteScannerTest.kt
git commit -m "feat: add delete preflight scanning"
```

---

### Task 2: Implement Recursive No-Follow DELETE in the Engine

**Files:**

- Modify: `app/src/main/java/com/example/watchfiles/fileops/FileOperationEngine.kt`
- Create: `app/src/test/java/com/example/watchfiles/fileops/FileOperationEngineDeleteTest.kt`

**Interfaces:**

- Consume `FileOperationRequest.delete`, `ScanOutcome.Ready`, `OperationCancellation`, and `OperationProgress` from Task 1.
- Preserve the existing `OperationEngineGateway.execute` signature.
- Add an injectable `storageRoot: () -> Path` to the engine and reuse the existing `FileSystemOperations.delete` seam for deletion-failure tests.
- Produce `EngineOutcome.Completed`, `Partial`, `Failed`, or `Cancelled` with top-level counts and `FileOperationFailure` details.

- [ ] **Step 1: Write failing engine tests**

Add these tests:

```kotlin
@Test fun deletesRegularFileAndLeavesUnrelatedPartFile() = runTest {
    val root = temporaryFolder.newFolder("file").toPath()
    val source = Files.write(root.resolve("source.txt"), byteArrayOf(1, 2, 3))
    val userPart = Files.write(root.resolve("notes.part"), byteArrayOf(9, 8))

    val outcome = executeDelete(source, root)

    assertTrue(outcome is EngineOutcome.Completed)
    assertFalse(Files.exists(source))
    assertArrayEquals(byteArrayOf(9, 8), Files.readAllBytes(userPart))
}

@Test fun deletesThreeLevelDirectoryWithoutFollowingLinks() = runTest {
    val root = temporaryFolder.newFolder("tree").toPath()
    val source = Files.createDirectories(root.resolve("source/a/b"))
    Files.write(root.resolve("source/file.txt"), byteArrayOf(1))
    Files.write(source.resolve("nested.txt"), byteArrayOf(2))
    val external = Files.createDirectory(root.resolve("external"))
    Files.write(external.resolve("keep.txt"), byteArrayOf(3))
    val link = root.resolve("source/link")
    try {
        Files.createSymbolicLink(link, external)
    } catch (_: UnsupportedOperationException) {
        Assume.assumeTrue("symlink unsupported", false)
    } catch (_: IOException) {
        Assume.assumeTrue("symlink unavailable", false)
    }

    val outcome = executeDelete(root.resolve("source"), root)

    assertTrue(outcome is EngineOutcome.Completed)
    assertFalse(Files.exists(root.resolve("source")))
    assertTrue(Files.exists(external.resolve("keep.txt")))
}

@Test fun deleteFailureDoesNotReportTopLevelSuccess() = runTest {
    val root = temporaryFolder.newFolder("failure").toPath()
    val source = Files.write(root.resolve("source.txt"), byteArrayOf(1))
    val engine = engineWithFileSystem(FailingDeleteFileSystem(source))

    val outcome = executeDelete(source, root, engine)

    assertTrue(outcome is EngineOutcome.Failed)
    assertEquals("删除失败", (outcome as EngineOutcome.Failed).result.failures.single().userMessage)
    assertTrue(Files.exists(source))
}

@Test fun cancellationStopsBeforeNextEntryAndReportsUnrecoverableProgress() = runTest {
    val root = temporaryFolder.newFolder("cancel").toPath()
    val source = Files.createDirectories(root.resolve("source"))
    Files.write(source.resolve("first.txt"), byteArrayOf(1))
    Files.write(source.resolve("second.txt"), byteArrayOf(2))
    val cancellation = OperationCancellation()
    var progressCalls = 0

    val outcome = executeDelete(root.resolve("source"), root, cancellation) { progress ->
        if (++progressCalls == 1) cancellation.request()
        assertTrue(progress.processedItems >= 1)
    }

    assertTrue(outcome is EngineOutcome.Cancelled)
    assertTrue(Files.exists(root.resolve("source/second.txt")))
    assertTrue((outcome as EngineOutcome.Cancelled).result.failures.any {
        it.userMessage == "删除已取消，部分内容可能已删除"
    })
}

@Test fun engineRejectsStorageRootEvenWhenCalledWithoutScanner() = runTest {
    val root = temporaryFolder.newFolder("root-guard").toPath()
    val outcome = executeDelete(root, root)

    assertTrue(outcome is EngineOutcome.Failed)
    assertEquals("不能删除内部存储根目录", (outcome as EngineOutcome.Failed).result.failures.single().userMessage)
}
```

The test helper must pass an explicit `ScanOutcome.Ready` and never use a real device path:

```kotlin
private suspend fun executeDelete(
    source: Path,
    root: Path,
    cancellation: OperationCancellation = OperationCancellation(),
    onProgress: (OperationProgress) -> Unit = {},
    engine: FileOperationEngine = FileOperationEngine(storageRoot = { root }),
): EngineOutcome = engine.execute(
    request = FileOperationRequest.delete("delete-test", listOf(source)),
    scan = ScanOutcome.Ready(itemCount = countItems(source), totalBytes = null),
    cancellation = cancellation,
    onProgress = onProgress,
    onConflict = { error("DELETE must not request replacement") },
)

private fun countItems(path: Path): Int {
    var count = 0
    Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
            count += 1
            return FileVisitResult.CONTINUE
        }

        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            count += 1
            return FileVisitResult.CONTINUE
        }
    })
    return count
}

private fun engineWithFileSystem(fileSystem: FileSystemOperations) =
    FileOperationEngine(fileSystem = fileSystem)

private class FailingDeleteFileSystem(
    private val blocked: Path,
) : FileSystemOperations {
    override fun createNewFile(path: Path): OutputStream =
        Files.newOutputStream(path, StandardOpenOption.CREATE_NEW)

    override fun moveNoReplace(source: Path, target: Path) = Files.move(source, target)

    override fun delete(path: Path) {
        if (path == blocked) throw AccessDeniedException(path.toString())
        Files.deleteIfExists(path)
    }
}
```

- [ ] **Step 2: Run the focused tests and verify RED**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.watchfiles.fileops.FileOperationEngineDeleteTest" --no-daemon --console=plain
```

Expected: compilation or assertions fail because `FileOperationEngine` has no DELETE branch.

- [ ] **Step 3: Add the engine DELETE seam and dispatch**

Extend the constructor without removing existing M1B parameters:

```kotlin
class FileOperationEngine(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val byteCopier: FileByteCopier = NioFileByteCopier(),
    private val fastMover: FastMover = NioFastMover(),
    private val sourceDeleter: SourceDeleter = NioSourceDeleter(),
    private val storageRoot: () -> Path = { Paths.get("/storage/emulated/0") },
) : OperationEngineGateway {
    // existing internal fileSystem and sourceProgressMeasurer seams remain
}
```

At the beginning of `executeInternal`, dispatch DELETE before any transfer target logic:

```kotlin
if (request.type == FileOperationType.DELETE) {
    return executeDelete(request, scan, cancellation, onProgress)
}
```

- [ ] **Step 4: Implement no-follow recursive deletion**

Implement these helpers inside `FileOperationEngine`:

```kotlin
private fun executeDelete(
    request: FileOperationRequest,
    scan: ScanOutcome.Ready,
    cancellation: OperationCancellation,
    onProgress: (OperationProgress) -> Unit,
): EngineOutcome {
    val root = storageRoot().toAbsolutePath().normalize()
    var completed = 0
    var processedItems = 0
    var processedBytes = 0L
    val failures = mutableListOf<FileOperationFailure>()
    var currentSource: Path? = null

    return try {
        for (source in request.sources.map { it.toAbsolutePath().normalize() }.distinct()) {
            cancellation.throwIfRequested()
            currentSource = source
            if (source == root) {
                failures += FileOperationFailure(source, "不能删除内部存储根目录")
                continue
            }
            if (!Files.exists(source, NOFOLLOW_LINKS)) {
                failures += FileOperationFailure(source, "源项目已不存在")
                continue
            }

            val treeFailures = mutableListOf<DeleteFailure>()
            val deleted = deleteTreeNoFollow(
                path = source,
                cancellation = cancellation,
                onDeleted = { path, bytes ->
                    processedItems += 1
                    processedBytes += bytes
                    onProgress(OperationProgress(
                        path.fileName?.toString(),
                        processedItems,
                        scan.itemCount,
                        processedBytes,
                        scan.totalBytes,
                    ))
                },
                onFailure = { path, error -> treeFailures += DeleteFailure(path, error) },
            )
            if (treeFailures.isNotEmpty()) {
                val first = treeFailures.first()
                failures += FileOperationFailure(
                    source,
                    "目录删除未完成，部分内容可能已删除",
                    "${first.path}: ${first.error.message ?: first.error.javaClass.simpleName}",
                )
            } else if (deleted && !Files.exists(source, NOFOLLOW_LINKS)) {
                completed += 1
            } else {
                failures += FileOperationFailure(
                    source,
                    "目录删除未完成，部分内容可能已删除",
                )
            }
            currentSource = null
        }
        resultForFailures(completed, failures)
    } catch (_: OperationCancelledException) {
        currentSource?.let { source ->
            if (Files.exists(source, NOFOLLOW_LINKS)) {
                failures += FileOperationFailure(source, "删除已取消，部分内容可能已删除")
            }
        }
        EngineOutcome.Cancelled(FileOperationResult(completed, failures.size, failures))
    } catch (error: Exception) {
        val source = currentSource
        failures += FileOperationFailure(
            source,
            if (error is AccessDeniedException || error is SecurityException) "没有权限删除" else "删除失败",
            error.message ?: error.javaClass.simpleName,
        )
        resultForFailures(completed, failures)
    }
}

private fun deleteTreeNoFollow(
    path: Path,
    cancellation: OperationCancellation,
    onDeleted: (Path, Long) -> Unit,
    onFailure: (Path, Exception) -> Unit,
): Boolean {
    cancellation.throwIfRequested()
    if (!Files.isDirectory(path, NOFOLLOW_LINKS)) {
        return try {
            val size = if (Files.isRegularFile(path, NOFOLLOW_LINKS)) Files.size(path) else 0L
            fileSystem.delete(path)
            onDeleted(path, size)
            true
        } catch (error: OperationCancelledException) {
            throw error
        } catch (error: Exception) {
            onFailure(path, error)
            false
        }
    }

    var allChildrenDeleted = true
    try {
        Files.newDirectoryStream(path).use { children ->
            for (child in children) {
                cancellation.throwIfRequested()
                if (!deleteTreeNoFollow(child, cancellation, onDeleted, onFailure)) {
                    allChildrenDeleted = false
                }
            }
        }
    } catch (error: OperationCancelledException) {
        throw error
    } catch (error: Exception) {
        onFailure(path, error)
        allChildrenDeleted = false
    }
    if (allChildrenDeleted) {
        return try {
            fileSystem.delete(path)
            onDeleted(path, 0L)
            true
        } catch (error: OperationCancelledException) {
            throw error
        } catch (error: Exception) {
            onFailure(path, error)
            false
        }
    }
    return false
}

private fun resultForFailures(
    completed: Int,
    failures: List<FileOperationFailure>,
): EngineOutcome = when {
    failures.isEmpty() -> EngineOutcome.Completed(FileOperationResult(completed, 0))
    completed == 0 -> EngineOutcome.Failed(FileOperationResult(completed, failures.size, failures))
    else -> EngineOutcome.Partial(FileOperationResult(completed, failures.size, failures))
}

private data class DeleteFailure(val path: Path, val error: Exception)
```

`resultForFailures` returns `Completed` when `failures` is empty, `Failed` when no top-level item completed, and `Partial` otherwise. Catch `NoSuchFileException` as `源项目已不存在` when it refers to the current source. The helper must not call `deleteOwnedRecursively`, search for suffixes, or delete paths outside the explicit recursion.

- [ ] **Step 5: Run focused, file-operation, and full suites**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.watchfiles.fileops.FileOperationEngineDeleteTest" --no-daemon --console=plain
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.watchfiles.fileops.*" --no-daemon --console=plain
.\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain
```

Expected: DELETE tests pass and all existing COPY/MOVE tests remain green.

- [ ] **Step 6: Commit the engine boundary**

```powershell
git add app/src/main/java/com/example/watchfiles/fileops/FileOperationEngine.kt app/src/test/java/com/example/watchfiles/fileops/FileOperationEngineDeleteTest.kt
git commit -m "feat: add safe recursive delete engine"
```

---

### Task 3: Add Coordinator Confirmation Gating

**Files:**

- Modify: `app/src/main/java/com/example/watchfiles/fileops/FileOperationCoordinator.kt`
- Modify: `app/src/test/java/com/example/watchfiles/fileops/FileOperationCoordinatorTest.kt`

**Interfaces:**

- Produce `prepareDelete(sources: List<Path>): Boolean`.
- Produce `confirmDelete(): Boolean`.
- Keep `start`, `cancel`, `replaceAll`, and `consumeResult` behavior unchanged for COPY/MOVE.
- Maintain one active job from DELETE scan through confirmation and execution.

- [ ] **Step 1: Write failing coordinator tests**

Add tests using the existing fake gateway pattern and `MainDispatcherRule`:

```kotlin
@Test fun deleteDoesNotCallEngineBeforeConfirmation() = runTest {
    var engineCalls = 0
    val engine = OperationEngineGateway { _, _, _, _, _ ->
        engineCalls += 1
        EngineOutcome.Completed(FileOperationResult(1, 0))
    }
    val coordinator = coordinator(
        scanner = OperationScannerGateway { _, _ -> ScanOutcome.Ready(3, 12) },
        engine = engine,
    )

    assertTrue(coordinator.prepareDelete(listOf(source)))
    advanceUntilIdle()

    assertTrue(coordinator.state.value is FileOperationState.WaitingForDeleteConfirmation)
    assertEquals(0, engineCalls)
}

@Test fun deleteCancelBeforeConfirmationReturnsIdleWithoutDeleting() = runTest {
    var engineCalls = 0
    val coordinator = coordinator(
        scanner = OperationScannerGateway { _, _ -> ScanOutcome.Ready(1, 1) },
        engine = OperationEngineGateway { _, _, _, _, _ ->
            engineCalls += 1
            EngineOutcome.Completed(FileOperationResult(1, 0))
        },
    )

    coordinator.prepareDelete(listOf(source))
    advanceUntilIdle()
    coordinator.cancel()
    advanceUntilIdle()

    assertEquals(FileOperationState.Idle, coordinator.state.value)
    assertEquals(0, engineCalls)
}

@Test fun deleteConfirmationStartsEngineAndMapsResult() = runTest {
    val coordinator = coordinator(
        scanner = OperationScannerGateway { _, _ -> ScanOutcome.Ready(1, 1) },
        engine = OperationEngineGateway { _, _, _, _, _ ->
            EngineOutcome.Partial(FileOperationResult(0, 1))
        },
    )

    coordinator.prepareDelete(listOf(source))
    advanceUntilIdle()
    assertTrue(coordinator.confirmDelete())
    advanceUntilIdle()

    assertTrue(coordinator.state.value is FileOperationState.PartiallySucceeded)
    assertEquals(FileOperationType.DELETE, (coordinator.state.value as FileOperationState.PartiallySucceeded).type)
}

@Test fun secondTaskIsRejectedDuringDeleteConfirmation() = runTest {
    val coordinator = coordinator(
        scanner = OperationScannerGateway { _, _ -> ScanOutcome.Ready(1, 1) },
    )

    assertTrue(coordinator.prepareDelete(listOf(source)))
    advanceUntilIdle()

    assertFalse(coordinator.start(FileOperationType.COPY, listOf(source), target))
}
```

- [ ] **Step 2: Run the focused tests and verify RED**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.watchfiles.fileops.FileOperationCoordinatorTest" --no-daemon --console=plain
```

Expected: compilation fails because `prepareDelete`, `confirmDelete`, and `WaitingForDeleteConfirmation` do not exist.

- [ ] **Step 3: Implement the confirmation deferred**

Add coordinator fields:

```kotlin
private var deleteConfirmation: CompletableDeferred<Boolean>? = null
```

Implement `prepareDelete` with the same single-task guard as `start`:

```kotlin
fun prepareDelete(sources: List<Path>): Boolean {
    if (_state.value != FileOperationState.Idle) return false
    val request = FileOperationRequest.delete(taskIdFactory(), sources)
    val token = OperationCancellation()
    cancellation = token
    _state.value = FileOperationState.Scanning(FileOperationType.DELETE)
    activeJob = viewModelScope.launch {
        try {
            when (val scan = scanner.scan(request, token)) {
                is ScanOutcome.Rejected -> _state.value = FileOperationState.Failed(
                    FileOperationType.DELETE,
                    FileOperationResult(0, 1, listOf(scan.failure)),
                )
                is ScanOutcome.Ready -> {
                    val preview = DeletePreview(
                        topLevelCount = request.sources.distinct().size,
                        itemCount = scan.itemCount,
                        totalBytes = scan.totalBytes,
                    )
                    val gate = CompletableDeferred<Boolean>()
                    deleteConfirmation = gate
                    _state.value = FileOperationState.WaitingForDeleteConfirmation(preview)
                    if (gate.await()) runEngine(request, scan, token)
                }
            }
        } catch (_: OperationCancelledException) {
            if (_state.value is FileOperationState.Scanning ||
                _state.value is FileOperationState.WaitingForDeleteConfirmation
            ) {
                _state.value = FileOperationState.Idle
            }
        } finally {
            activeJob = null
            cancellation = null
            deleteConfirmation = null
        }
    }
    return true
}

fun confirmDelete(): Boolean {
    val state = _state.value
    if (state !is FileOperationState.WaitingForDeleteConfirmation) return false
    val initial = OperationProgress(null, 0, state.preview.itemCount, 0, state.preview.totalBytes)
    _state.value = FileOperationState.Running(FileOperationType.DELETE, initial)
    return deleteConfirmation?.complete(true) == true
}
```

Update `cancel()` so a waiting DELETE confirmation completes `deleteConfirmation` with `false`, requests the token, sets `Idle`, and never maps to a destructive terminal result. For DELETE execution, retain the existing `Cancelling` and terminal mapping. `runEngine` must continue to pass `onConflict`, but the DELETE engine path must never invoke it.

- [ ] **Step 4: Run the coordinator and full unit suites**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.watchfiles.fileops.FileOperationCoordinatorTest" --no-daemon --console=plain
.\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain
```

Expected: all existing coordinator tests and new confirmation tests pass.

- [ ] **Step 5: Commit the coordinator boundary**

```powershell
git add app/src/main/java/com/example/watchfiles/fileops/FileOperationCoordinator.kt app/src/test/java/com/example/watchfiles/fileops/FileOperationCoordinatorTest.kt
git commit -m "feat: gate delete behind explicit confirmation"
```

---

### Task 4: Add Round-Screen DELETE Confirmation and Browser Action

**Files:**

- Modify: `app/src/main/java/com/example/watchfiles/MainActivity.kt`
- Modify: `app/src/main/java/com/example/watchfiles/FileOperationScreens.kt`

**Interfaces:**

- `BrowserScreen` gains `onDeleteSelected: () -> Unit`.
- Add `AppScreen.DELETE_CONFIRMATION`.
- Add `DeleteConfirmationScreen(state, onConfirm, onCancel, onDone)`.
- Reuse the existing `pendingOperationSources` list with `pendingOperationType = DELETE`.

- [ ] **Step 1: Add the selection action and route**

In `BrowserScreen`, add the DELETE chip after MOVE:

```kotlin
item {
    AppChip(
        label = "删除",
        secondary = "永久删除，无法恢复",
        onClick = onDeleteSelected,
    )
}
```

In `WatchFilesApp`, add:

```kotlin
AppScreen.DELETE_CONFIRMATION
```

and wire the browser callback:

```kotlin
onDeleteSelected = {
    pendingOperationType = FileOperationType.DELETE
    pendingOperationSources = browserState.selection.selectedPaths.toList()
    if (fileOperationCoordinator.prepareDelete(pendingOperationSources)) {
        screen = AppScreen.DELETE_CONFIRMATION
    }
}
```

- [ ] **Step 2: Implement the confirmation composable**

Add this interface in `FileOperationScreens.kt`:

```kotlin
@Composable
internal fun DeleteConfirmationScreen(
    state: FileOperationState,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
) {
    RoundList {
        when (state) {
            is FileOperationState.Scanning -> {
                item { ListHeader { Text("正在扫描删除内容…") } }
                item { AppChip("取消", "不删除任何文件", onClick = onCancel) }
            }
            is FileOperationState.WaitingForDeleteConfirmation -> {
                item { ListHeader { Text("确认永久删除") } }
                item {
                    AppChip(
                        "项目",
                        "${state.preview.topLevelCount} 项 · 共 ${state.preview.itemCount} 项",
                        onClick = {},
                    )
                }
                item {
                    AppChip(
                        "大小",
                        state.preview.totalBytes?.let(::formatBytes) ?: "大小未知",
                        onClick = {},
                    )
                }
                item { AppChip("警告", "永久删除，无法恢复", onClick = {}) }
                item { AppChip("永久删除", "开始删除任务", onClick = onConfirm) }
                item { AppChip("取消", "返回原目录", onClick = onCancel) }
            }
            is FileOperationState.Failed -> terminal("删除前检查失败", state.result, onDone)
            else -> item { Text("删除状态不可用") }
        }
    }
}
```

The confirmation page must never render a destructive action for `Failed`, `Cancelled`, or `Idle`. It must use the existing `RoundList`, `AppChip`, and crown modifier through the current screen helpers.

- [ ] **Step 3: Wire Back and terminal navigation**

Update `BackHandler`:

```kotlin
AppScreen.DELETE_CONFIRMATION -> {
    fileOperationCoordinator.cancel()
    screen = AppScreen.BROWSER
}
AppScreen.FILE_OPERATION -> Unit
```

For confirmation scan failures, `onDone` must call the same refresh/consume/clear flow already used by `FileOperationScreen`. For confirmation cancel, do not clear the selection before returning; the user can cancel selection separately.

- [ ] **Step 4: Compile and run unit tests**

```powershell
.\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain
.\gradlew.bat :app:assembleDebug --no-daemon --console=plain
```

Expected: the new route compiles, the DELETE chip is available only in selection mode, and all unit tests pass.

- [ ] **Step 5: Commit the confirmation UI**

```powershell
git add app/src/main/java/com/example/watchfiles/MainActivity.kt app/src/main/java/com/example/watchfiles/FileOperationScreens.kt
git commit -m "feat: add round-screen delete confirmation"
```

---

### Task 5: Add DELETE Progress, Cancellation Text, and Result Refresh

**Files:**

- Modify: `app/src/main/java/com/example/watchfiles/FileOperationScreens.kt`
- Modify: `app/src/main/java/com/example/watchfiles/MainActivity.kt`
- Modify: `app/src/test/java/com/example/watchfiles/fileops/FileOperationCoordinatorTest.kt`

**Interfaces:**

- Extend the existing `FileOperationScreen` state rendering to support DELETE.
- Keep the existing `FileBrowserViewModel.refreshAfterOperation()` contract.
- Preserve COPY/MOVE labels and navigation behavior.

- [ ] **Step 1: Add a coordinator regression test for DELETE cancellation**

Use a fake engine that suspends until the cancellation token is requested, then returns `EngineOutcome.Cancelled`. Assert that `cancel()` exposes `FileOperationState.Cancelling` first and the final state is `Cancelled` with `type == FileOperationType.DELETE`:

```kotlin
@Test fun deleteCancelDuringExecutionMapsToCancelled() = runTest {
    val engine = OperationEngineGateway { _, _, token, _, _ ->
        while (!token.isRequested()) yield()
        EngineOutcome.Cancelled(FileOperationResult(0, 1))
    }
    val coordinator = coordinator(
        scanner = OperationScannerGateway { _, _ -> ScanOutcome.Ready(2, 2) },
        engine = engine,
    )

    coordinator.prepareDelete(listOf(source))
    advanceUntilIdle()
    coordinator.confirmDelete()
    runCurrent()
    coordinator.cancel()
    advanceUntilIdle()

    val state = coordinator.state.value as FileOperationState.Cancelled
    assertEquals(FileOperationType.DELETE, state.type)
}
```

- [ ] **Step 2: Add DELETE progress and cancellation copy**

Use a single operation title helper so the existing COPY/MOVE text remains unchanged:

```kotlin
private fun operationTitle(type: FileOperationType): String = when (type) {
    FileOperationType.COPY -> "正在复制"
    FileOperationType.MOVE -> "正在移动"
    FileOperationType.DELETE -> "正在删除"
}

private fun cancellationText(type: FileOperationType): String = when (type) {
    FileOperationType.COPY -> "停止并清理未发布项目"
    FileOperationType.MOVE -> "停止；已完成项目保留在目标"
    FileOperationType.DELETE -> "停止；已删除内容无法恢复"
}
```

Update `Running` and `Cancelling` branches to use these helpers. DELETE must not render replacement controls or target-directory controls. Its result page must show the existing completed/failed counts and the first failure/warning message, including `删除已取消，部分内容可能已删除` when present.

- [ ] **Step 3: Keep terminal return behavior explicit**

The existing terminal callback remains:

```kotlin
onDone = {
    browserViewModel.refreshAfterOperation()
    fileOperationCoordinator.consumeResult()
    pendingOperationSources = emptyList()
    pendingOperationType = FileOperationType.COPY
    screen = AppScreen.BROWSER
}
```

The active DELETE operation cannot be left with system Back. Only the terminal “返回目录” action may refresh and consume the result.

- [ ] **Step 4: Run the complete local verification gate**

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --no-daemon --console=plain
$tests = Get-ChildItem app\build\test-results\testDebugUnitTest\TEST-*.xml
$tests | ForEach-Object { [xml](Get-Content -Raw $_.FullName) } | ForEach-Object { $_.testsuite }
[xml]$lint = Get-Content -Raw app\build\reports\lint-results-debug.xml
"LINT_ISSUE_NODES=$($lint.issues.issue.Count)"
git diff --check
```

Expected: zero test failures/errors, `LINT_ISSUE_NODES=0`, and no whitespace errors. Do not claim completion until the command output confirms these values.

- [ ] **Step 5: Commit the integrated DELETE workflow**

```powershell
git add app/src/main/java/com/example/watchfiles/FileOperationScreens.kt app/src/main/java/com/example/watchfiles/MainActivity.kt app/src/test/java/com/example/watchfiles/fileops/FileOperationCoordinatorTest.kt
git commit -m "feat: complete delete operation workflow"
```

---

### Task 6: M1C Debug Build and Xiaomi Watch 5 Acceptance

**Files:**

- Modify after device acceptance: `docs/superpowers/checkpoints/TESTING.md`
- Create after device acceptance: `docs/superpowers/checkpoints/2026-07-17-m1c-closeout.md`
- Modify after device acceptance: `docs/superpowers/context/PROJECT_CONTEXT.md`
- Modify after device acceptance: `docs/superpowers/roadmap/PROJECT_PLAN.md`
- Modify after device acceptance: `README.md`

**Interfaces:**

- Consume the complete local DELETE workflow from Tasks 1–5.
- Produce a repeatable M1C checkpoint with device evidence and a clean branch.
- Keep versionCode 6/versionName `0.3.1-dev-debug` unless the existing project documentation changes it after full M1 acceptance.

- [ ] **Step 1: Discover the current wireless ADB transport**

Run:

```powershell
adb devices -l
adb mdns services
```

Select only one currently online `M2505W1`/`grasslte` watch transport. Store that exact current serial in `$serial`; do not copy the previous M1B address. If discovery repeatedly fails, stop device operations and ask the user to reopen wireless debugging.

- [ ] **Step 2: Build and install Debug**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --no-daemon --console=plain
adb -s $serial install -r app\build\outputs\apk\debug\app-debug.apk
adb -s $serial shell dumpsys package com.example.watchfiles.debug | Select-String 'versionCode=|versionName=|targetSdk='
```

Expected: tests/build/Lint pass, installation succeeds, `targetSdk=29`, and the existing Debug version metadata remains unchanged.

- [ ] **Step 3: Prepare only M1Sandbox fixtures**

Create or reset only these paths under `/storage/emulated/0/Download/WatchFilesTest/M1Sandbox` using the same safe fixture workflow as M1B:

- `DeleteFile/source.txt`
- `DeleteFile/notes.part`
- `DeleteTree/empty/`
- `DeleteTree/nested/level1/level2/leaf.txt`
- `DeleteCancel/first.txt`
- `DeleteCancel/second.txt`

With the verified `$serial` from Step 1, the fixture setup commands are:

```powershell
$root = "/storage/emulated/0/Download/WatchFilesTest/M1Sandbox"
adb -s $serial shell "rm -rf $root/DeleteFile $root/DeleteTree $root/DeleteCancel"
adb -s $serial shell "mkdir -p $root/DeleteFile $root/DeleteTree/empty $root/DeleteTree/nested/level1/level2 $root/DeleteCancel"
adb -s $serial shell "printf 'delete-me' > $root/DeleteFile/source.txt"
adb -s $serial shell "printf 'keep-me' > $root/DeleteFile/notes.part"
adb -s $serial shell "printf 'leaf' > $root/DeleteTree/nested/level1/level2/leaf.txt"
adb -s $serial shell "printf 'first' > $root/DeleteCancel/first.txt"
adb -s $serial shell "printf 'second' > $root/DeleteCancel/second.txt"
```

Before running the destructive cases, verify every resolved fixture path starts with the exact `$root` prefix and list the fixtures with `adb -s $serial shell find $root -maxdepth 4 -type f -o -type d`.

Record the expected paths before each destructive test. Do not create, delete, or change permissions under system directories, personal media directories, or outside M1Sandbox.

- [ ] **Step 4: Verify cancellation before confirmation**

In the app, enter M1Sandbox, long-press a fixture, tap “删除”, and wait for the preview. Confirm the top-level and recursive counts. Tap “取消” and repeat with the system Back key. Expected: every source remains, the browser returns, selection remains available, and no deletion result is shown.

- [ ] **Step 5: Verify successful recursive deletion**

Select `DeleteFile/source.txt` and `DeleteTree/empty`, enter confirmation, tap “永久删除”, and wait for the terminal result. Then verify with ADB that the selected paths are absent, `DeleteFile/notes.part` remains unchanged, the browser list is refreshed, and no crash appears.

Select `DeleteTree/nested` and repeat. Verify all nested descendants are absent and the result reports complete success.

- [ ] **Step 6: Verify cancellation during deletion**

Use a sufficiently large fixture inside M1Sandbox so the operation page remains visible. Tap “取消” while deletion is active. Expected: the result explicitly states that deleted content cannot be restored; already deleted entries remain absent; entries not yet reached remain present; no task-owned temporary files are created.

- [ ] **Step 7: Verify safety and regressions**

Check:

- The root storage directory is not offered as a selectable project and an injected root-guard test rejects it.
- `notes.part` remains byte-for-byte unchanged.
- `AndroidRuntime` and `FATAL EXCEPTION` output is empty after clearing logcat before reproduction.
- M1A create/rename, M1B copy/move, target picker crown scrolling, browser crown scrolling, and the low-memory image viewer still work.

Use a local injected failure test for permission/child-delete partial success; do not modify real watch-directory permissions to force an error.

- [ ] **Step 8: Update canonical documentation**

After the device evidence is recorded:

- Mark M1 DELETE confirmation complete in `PROJECT_PLAN.md`.
- Update `PROJECT_CONTEXT.md` from “下一阶段为 M1C” to the next agreed stage, retaining all targetSdk, legacy storage, ABI, crown, and ADB constraints.
- Update `README.md` to state that recursive permanent deletion is available and that the current release policy remains Debug until the post-M1 Release handoff.
- Add the exact repeatable M1C steps and safety limits to `TESTING.md`.
- Create `2026-07-17-m1c-closeout.md` with test counts, Debug APK SHA-256, device model, dynamically discovered serial for that session, operation outcomes, and crash audit.

- [ ] **Step 9: Run final M1C evidence gate and commit documentation**

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --no-daemon --console=plain
[xml]$lint = Get-Content -Raw app\build\reports\lint-results-debug.xml
"LINT_ISSUE_NODES=$($lint.issues.issue.Count)"
Get-FileHash app\build\outputs\apk\debug\app-debug.apk -Algorithm SHA256
git diff --check
git status --short --branch
```

Expected: zero test failures/errors, `LINT_ISSUE_NODES=0`, a recorded Debug hash, no whitespace errors, and a clean branch after committing the documentation:

```powershell
git add README.md docs/superpowers/context/PROJECT_CONTEXT.md docs/superpowers/roadmap/PROJECT_PLAN.md docs/superpowers/checkpoints/TESTING.md docs/superpowers/checkpoints/2026-07-17-m1c-closeout.md
git commit -m "docs: record M1C delete acceptance"
git status --short --branch
```

Release packaging is a separate post-M1C handoff and is not part of this plan.

---

## Spec Coverage Check

| Approved spec requirement | Plan coverage |
| --- | --- |
| DELETE selection and independent confirmation | Tasks 3–4 |
| Preview item count and known/unknown size | Task 1 and Task 4 |
| Root directory protection | Tasks 1–2 and Task 6 |
| Recursive depth-first deletion | Task 2 |
| No symlink following | Tasks 1–2 |
| Cancellation without rollback | Tasks 2, 3, 5, and 6 |
| Partial success and failure detail | Tasks 2, 3, and 5 |
| Single foreground task | Task 3 |
| Round-screen crown compatibility | Task 4 and Task 6 |
| TDD and Debug/Lint verification | Tasks 1–5 |
| M1Sandbox-only device writes and dynamic ADB | Task 6 |
| Canonical documentation handoff | Task 6 |

## Self-Review Checklist

- [ ] Every spec section maps to at least one implementation or acceptance task.
- [ ] Every task contains concrete code, commands, expected results, and no unfinished instructions.
- [ ] `FileOperationRequest.targetDirectory` is nullable only for DELETE, and transfer validation is tested.
- [ ] `WaitingForDeleteConfirmation` is distinct from `Running`, so confirmation is testable as a destructive-action gate.
- [ ] The engine uses exact recursive paths and the existing injected filesystem seam; no broad cleanup is permitted.
- [ ] Existing COPY/MOVE tests run after every file-operation change.
- [ ] Release build remains outside M1C, consistent with the approved spec.
