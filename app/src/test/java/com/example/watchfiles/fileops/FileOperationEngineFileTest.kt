package com.example.watchfiles.fileops

import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileOperationEngineFileTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun copiesFileAndLeavesNoTaskTemporaryPath() = runTest {
        val root = temporaryFolder.newFolder("copy-success").toPath()
        val sourceBytes = "watchfiles-copy".toByteArray()
        val source = Files.write(root.resolve("notes.txt"), sourceBytes)
        val targetDirectory = Files.createDirectory(root.resolve("target"))

        val outcome = executeCopy(source, targetDirectory)

        assertEquals(FileOperationResult(completedItems = 1, failedItems = 0), (outcome as EngineOutcome.Completed).result)
        assertArrayEquals(sourceBytes, Files.readAllBytes(targetDirectory.resolve("notes.txt")))
        assertArrayEquals(sourceBytes, Files.readAllBytes(source))
        Files.list(targetDirectory).use { children ->
            assertFalse(children.anyMatch { it.fileName.toString().contains(".watchfiles-task-1.part") })
        }
    }

    @Test
    fun copyFailureLeavesSourceUnchangedAndRemovesTaskTemporaryPath() = runTest {
        val root = temporaryFolder.newFolder("copy-failure").toPath()
        val sourceBytes = "source-must-survive".toByteArray()
        val source = Files.write(root.resolve("source.txt"), sourceBytes)
        val targetDirectory = Files.createDirectory(root.resolve("target"))
        val copier = FileByteCopier { _, temporaryTarget, _, _ ->
            Files.write(temporaryTarget, byteArrayOf(99))
            throw IOException("forced write failure")
        }

        val outcome = executeCopy(source, targetDirectory, FileOperationEngine(byteCopier = copier))

        assertEquals("复制失败", (outcome as EngineOutcome.Failed).result.failures.single().userMessage)
        assertArrayEquals(sourceBytes, Files.readAllBytes(source))
        assertFalse(Files.exists(targetDirectory.resolve(".source.txt.watchfiles-task-1.part")))
        assertFalse(Files.exists(targetDirectory.resolve("source.txt")))
    }

    @Test
    fun neverDeletesAUserOwnedDotPartFile() = runTest {
        val root = temporaryFolder.newFolder("user-part").toPath()
        val source = Files.write(root.resolve("source.txt"), "source".toByteArray())
        val targetDirectory = Files.createDirectory(root.resolve("target"))
        val userPartBytes = "user-owned".toByteArray()
        val userPart = Files.write(targetDirectory.resolve("notes.part"), userPartBytes)
        val copier = FileByteCopier { _, temporaryTarget, _, _ ->
            Files.write(temporaryTarget, byteArrayOf(1))
            throw IOException("forced write failure")
        }

        val outcome = executeCopy(source, targetDirectory, FileOperationEngine(byteCopier = copier))

        assertTrue(outcome is EngineOutcome.Failed)
        assertArrayEquals(userPartBytes, Files.readAllBytes(userPart))
    }

    @Test
    fun sourceMissingAfterScanReportsSourceDisappeared() = runTest {
        val root = temporaryFolder.newFolder("missing-source").toPath()
        val source = Files.write(root.resolve("gone.txt"), byteArrayOf(1, 2, 3))
        val targetDirectory = Files.createDirectory(root.resolve("target"))
        Files.delete(source)

        val outcome = executeCopy(source, targetDirectory)

        assertEquals("源项目已消失", (outcome as EngineOutcome.Failed).result.failures.single().userMessage)
    }

    @Test
    fun accessDeniedReportsPermissionFailure() = runTest {
        val root = temporaryFolder.newFolder("access-denied").toPath()
        val source = Files.write(root.resolve("source.txt"), byteArrayOf(1))
        val targetDirectory = Files.createDirectory(root.resolve("target"))
        val copier = FileByteCopier { sourcePath, _, _, _ ->
            throw AccessDeniedException(sourcePath.toString())
        }

        val outcome = executeCopy(source, targetDirectory, FileOperationEngine(byteCopier = copier))

        assertEquals("没有权限写入目标", (outcome as EngineOutcome.Failed).result.failures.single().userMessage)
    }

    @Test
    fun noSpaceWriteFailureReportsInsufficientSpace() = runTest {
        val root = temporaryFolder.newFolder("no-space").toPath()
        val source = Files.write(root.resolve("source.txt"), byteArrayOf(1))
        val targetDirectory = Files.createDirectory(root.resolve("target"))
        val copier = FileByteCopier { _, _, _, _ ->
            throw IOException("No space left on device")
        }

        val outcome = executeCopy(source, targetDirectory, FileOperationEngine(byteCopier = copier))

        assertEquals("可用空间不足", (outcome as EngineOutcome.Failed).result.failures.single().userMessage)
    }

    @Test
    fun targetCreatedDuringCopyRequiresConflictDecision() = runTest {
        val root = temporaryFolder.newFolder("late-conflict").toPath()
        val sourceBytes = "source".toByteArray()
        val source = Files.write(root.resolve("source.txt"), sourceBytes)
        val targetDirectory = Files.createDirectory(root.resolve("target"))
        val target = targetDirectory.resolve("source.txt")
        val lateTargetBytes = "late-target".toByteArray()
        val copier = FileByteCopier { sourcePath, temporaryTarget, _, onBytes ->
            Files.copy(sourcePath, temporaryTarget, REPLACE_EXISTING)
            Files.write(target, lateTargetBytes)
            onBytes(sourceBytes.size.toLong())
        }
        var conflictCalls = 0

        val outcome = executeCopy(source, targetDirectory, FileOperationEngine(byteCopier = copier)) {
            conflictCalls += 1
            ReplacementDecision.CANCEL
        }

        assertTrue(outcome is EngineOutcome.Cancelled)
        assertEquals(1, conflictCalls)
        assertArrayEquals(lateTargetBytes, Files.readAllBytes(target))
        assertArrayEquals(sourceBytes, Files.readAllBytes(source))
        assertFalse(Files.exists(temporaryFile(target, "task-1")))
    }

    @Test
    fun moveRequestIsRejectedWithoutCopying() = runTest {
        val root = temporaryFolder.newFolder("reject-move").toPath()
        val sourceBytes = "move-source".toByteArray()
        val source = Files.write(root.resolve("source.txt"), sourceBytes)
        val targetDirectory = Files.createDirectory(root.resolve("target"))

        val outcome = executeCopy(source, targetDirectory, type = FileOperationType.MOVE)

        assertEquals("当前仅支持复制文件", (outcome as EngineOutcome.Failed).result.failures.single().userMessage)
        assertArrayEquals(sourceBytes, Files.readAllBytes(source))
        assertFalse(Files.exists(targetDirectory.resolve("source.txt")))
    }

    @Test
    fun directoryIsCopiedRecursively() = runTest {
        val root = temporaryFolder.newFolder("reject-directory").toPath()
        val source = Files.createDirectory(root.resolve("source"))
        Files.write(source.resolve("nested.txt"), byteArrayOf(1))
        val targetDirectory = Files.createDirectory(root.resolve("target"))

        val outcome = executeCopy(source, targetDirectory)

        assertTrue(outcome is EngineOutcome.Completed)
        assertArrayEquals(byteArrayOf(1), Files.readAllBytes(targetDirectory.resolve("source/nested.txt")))
    }

    @Test
    fun symbolicLinkIsRejectedWithoutFollowingIt() = runTest {
        val root = temporaryFolder.newFolder("reject-symbolic-link").toPath()
        val originalBytes = "linked-source".toByteArray()
        val original = Files.write(root.resolve("original.txt"), originalBytes)
        val source = try {
            Files.createSymbolicLink(root.resolve("source-link.txt"), original)
        } catch (error: Exception) {
            assumeNoException(error)
            error("symbolic link assumption should abort the test")
        }
        val targetDirectory = Files.createDirectory(root.resolve("target"))

        val outcome = executeCopy(source, targetDirectory)

        assertEquals("仅支持普通文件复制", (outcome as EngineOutcome.Failed).result.failures.single().userMessage)
        assertArrayEquals(originalBytes, Files.readAllBytes(original))
        assertFalse(Files.exists(targetDirectory.resolve("source-link.txt")))
    }

    @Test
    fun externalCoroutineCancellationIsRethrown() = runTest {
        val root = temporaryFolder.newFolder("external-cancellation").toPath()
        val source = Files.write(root.resolve("source.txt"), byteArrayOf(1))
        val targetDirectory = Files.createDirectory(root.resolve("target"))
        val expected = CancellationException("external cancellation")
        val copier = FileByteCopier { _, _, _, _ -> throw expected }

        val actual = try {
            executeCopy(source, targetDirectory, FileOperationEngine(byteCopier = copier))
            null
        } catch (error: CancellationException) {
            error
        }

        assertEquals(expected.message, actual?.message)
        assertFalse(Files.exists(temporaryFile(targetDirectory.resolve("source.txt"), "task-1")))
    }

    @Test
    fun replacesExistingTargetAfterApproval() = runTest {
        val root = temporaryFolder.newFolder("replace-existing").toPath()
        val sourceBytes = "new-target".toByteArray()
        val source = Files.write(root.resolve("source.txt"), sourceBytes)
        val targetDirectory = Files.createDirectory(root.resolve("target"))
        val target = Files.write(targetDirectory.resolve("source.txt"), "old-target".toByteArray())
        var conflictCalls = 0

        val outcome = executeCopy(source, targetDirectory) {
            conflictCalls += 1
            ReplacementDecision.REPLACE_ALL
        }

        assertTrue(outcome is EngineOutcome.Completed)
        assertEquals(1, conflictCalls)
        assertArrayEquals(sourceBytes, Files.readAllBytes(target))
        assertArrayEquals(sourceBytes, Files.readAllBytes(source))
        assertFalse(Files.exists(backupPath(target, "task-1")))
    }

    @Test
    fun existingTargetCancelLeavesBothFilesUnchanged() = runTest {
        val root = temporaryFolder.newFolder("cancel-existing").toPath()
        val sourceBytes = "source".toByteArray()
        val oldTargetBytes = "old-target".toByteArray()
        val source = Files.write(root.resolve("source.txt"), sourceBytes)
        val targetDirectory = Files.createDirectory(root.resolve("target"))
        val target = Files.write(targetDirectory.resolve("source.txt"), oldTargetBytes)

        val outcome = executeCopy(source, targetDirectory) { ReplacementDecision.CANCEL }

        assertTrue(outcome is EngineOutcome.Cancelled)
        assertArrayEquals(sourceBytes, Files.readAllBytes(source))
        assertArrayEquals(oldTargetBytes, Files.readAllBytes(target))
        assertFalse(Files.exists(temporaryFile(target, "task-1")))
    }

    @Test
    fun backupCleanupFailureReturnsPartialWithPublishedTarget() = runTest {
        val root = temporaryFolder.newFolder("backup-cleanup-failure").toPath()
        val sourceBytes = "new-target".toByteArray()
        val oldTargetBytes = "old-target".toByteArray()
        val source = Files.write(root.resolve("source.txt"), sourceBytes)
        val targetDirectory = Files.createDirectory(root.resolve("target"))
        val target = Files.write(targetDirectory.resolve("source.txt"), oldTargetBytes)
        val backup = backupPath(target, "task-1")
        val operations = object : DelegatingFileSystemOperations() {
            override fun delete(path: Path) {
                if (path == backup) throw IOException("forced backup cleanup failure")
                super.delete(path)
            }
        }

        val outcome = executeCopy(source, targetDirectory, FileOperationEngine(fileSystem = operations))

        val failure = (outcome as EngineOutcome.Partial).result.failures.single()
        assertEquals("新目标已完成，但旧目标备份清理失败", failure.userMessage)
        assertArrayEquals(sourceBytes, Files.readAllBytes(target))
        assertArrayEquals(oldTargetBytes, Files.readAllBytes(backup))
    }

    @Test
    fun publishFailureRestoresOldTarget() = runTest {
        val root = temporaryFolder.newFolder("publish-failure-restore").toPath()
        val oldTargetBytes = "old-target".toByteArray()
        val source = Files.write(root.resolve("source.txt"), "new-target".toByteArray())
        val targetDirectory = Files.createDirectory(root.resolve("target"))
        val target = Files.write(targetDirectory.resolve("source.txt"), oldTargetBytes)
        val staged = temporaryFile(target, "task-1")
        val backup = backupPath(target, "task-1")
        val operations = object : DelegatingFileSystemOperations() {
            override fun moveNoReplace(sourcePath: Path, targetPath: Path) {
                if (sourcePath == staged && targetPath == target) throw IOException("forced publish failure")
                super.moveNoReplace(sourcePath, targetPath)
            }
        }

        val outcome = executeCopy(source, targetDirectory, FileOperationEngine(fileSystem = operations))

        assertTrue(outcome is EngineOutcome.Failed)
        assertArrayEquals(oldTargetBytes, Files.readAllBytes(target))
        assertFalse(Files.exists(backup))
        assertFalse(Files.exists(staged))
    }

    @Test
    fun publishAndRestoreFailureReturnsPartialWithBackupLocation() = runTest {
        val root = temporaryFolder.newFolder("publish-restore-failure").toPath()
        val oldTargetBytes = "old-target".toByteArray()
        val source = Files.write(root.resolve("source.txt"), "new-target".toByteArray())
        val targetDirectory = Files.createDirectory(root.resolve("target"))
        val target = Files.write(targetDirectory.resolve("source.txt"), oldTargetBytes)
        val staged = temporaryFile(target, "task-1")
        val backup = backupPath(target, "task-1")
        val operations = object : DelegatingFileSystemOperations() {
            override fun moveNoReplace(sourcePath: Path, targetPath: Path) {
                when {
                    sourcePath == staged && targetPath == target -> throw IOException("forced publish failure")
                    sourcePath == backup && targetPath == target -> throw IOException("forced restore failure")
                    else -> super.moveNoReplace(sourcePath, targetPath)
                }
            }
        }

        val outcome = executeCopy(source, targetDirectory, FileOperationEngine(fileSystem = operations))

        val failure = (outcome as EngineOutcome.Partial).result.failures.single()
        assertEquals("目标发布失败，旧目标保留在备份", failure.userMessage)
        assertTrue(failure.technicalMessage.orEmpty().contains(backup.toString()))
        assertTrue(failure.technicalMessage.orEmpty().contains("forced restore failure"))
        assertFalse(Files.exists(target))
        assertArrayEquals(oldTargetBytes, Files.readAllBytes(backup))
        assertFalse(Files.exists(staged))
    }

    @Test
    fun coroutineCancellationDuringRestoreIsRethrown() = runTest {
        val root = temporaryFolder.newFolder("restore-cancellation").toPath()
        val oldTargetBytes = "old-target".toByteArray()
        val source = Files.write(root.resolve("source.txt"), "new-target".toByteArray())
        val targetDirectory = Files.createDirectory(root.resolve("target"))
        val target = Files.write(targetDirectory.resolve("source.txt"), oldTargetBytes)
        val staged = temporaryFile(target, "task-1")
        val backup = backupPath(target, "task-1")
        val expected = CancellationException("restore cancelled")
        val operations = object : DelegatingFileSystemOperations() {
            override fun moveNoReplace(sourcePath: Path, targetPath: Path) {
                when {
                    sourcePath == staged && targetPath == target -> throw IOException("forced publish failure")
                    sourcePath == backup && targetPath == target -> throw expected
                    else -> super.moveNoReplace(sourcePath, targetPath)
                }
            }
        }

        val actual = try {
            executeCopy(source, targetDirectory, FileOperationEngine(fileSystem = operations))
            null
        } catch (error: CancellationException) {
            error
        }

        assertEquals(expected.message, actual?.message)
        assertFalse(Files.exists(target))
        assertArrayEquals(oldTargetBytes, Files.readAllBytes(backup))
        assertFalse(Files.exists(staged))
    }

    @Test
    fun newTargetPublicationUsesNoClobberMove() = runTest {
        val root = temporaryFolder.newFolder("no-clobber-new-target").toPath()
        val sourceBytes = "source".toByteArray()
        val source = Files.write(root.resolve("source.txt"), sourceBytes)
        val targetDirectory = Files.createDirectory(root.resolve("target"))
        val target = targetDirectory.resolve("source.txt")
        val moves = mutableListOf<Pair<Path, Path>>()
        val operations = object : DelegatingFileSystemOperations() {
            override fun moveNoReplace(sourcePath: Path, targetPath: Path) {
                moves += sourcePath to targetPath
                super.moveNoReplace(sourcePath, targetPath)
            }
        }

        val outcome = executeCopy(source, targetDirectory, FileOperationEngine(fileSystem = operations))

        assertTrue(outcome is EngineOutcome.Completed)
        assertEquals(listOf(temporaryFile(target, "task-1") to target), moves)
        assertArrayEquals(sourceBytes, Files.readAllBytes(target))
    }

    @Test
    fun replacementTransactionUsesOnlyNoClobberMoves() = runTest {
        val root = temporaryFolder.newFolder("no-clobber-replacement").toPath()
        val source = Files.write(root.resolve("source.txt"), "new".toByteArray())
        val targetDirectory = Files.createDirectory(root.resolve("target"))
        val target = Files.write(targetDirectory.resolve("source.txt"), "old".toByteArray())
        val staged = temporaryFile(target, "task-1")
        val backup = backupPath(target, "task-1")
        val moves = mutableListOf<Pair<Path, Path>>()
        val operations = object : DelegatingFileSystemOperations() {
            override fun moveNoReplace(sourcePath: Path, targetPath: Path) {
                moves += sourcePath to targetPath
                super.moveNoReplace(sourcePath, targetPath)
            }
        }

        val outcome = executeCopy(source, targetDirectory, FileOperationEngine(fileSystem = operations))

        assertTrue(outcome is EngineOutcome.Completed)
        assertEquals(listOf(target to backup, staged to target), moves)
    }

    @Test
    fun ordinaryFileReplacesExistingDirectoryAsAWhole() = runTest {
        val root = temporaryFolder.newFolder("reject-directory-target").toPath()
        val source = Files.write(root.resolve("source.txt"), "source".toByteArray())
        val targetDirectory = Files.createDirectory(root.resolve("target"))
        val existingDirectory = Files.createDirectory(targetDirectory.resolve("source.txt"))
        val nested = Files.write(existingDirectory.resolve("keep.txt"), "keep".toByteArray())
        var conflictCalls = 0

        val outcome = executeCopy(source, targetDirectory) {
            conflictCalls += 1
            ReplacementDecision.REPLACE_ALL
        }

        assertTrue(outcome is EngineOutcome.Completed)
        assertEquals(1, conflictCalls)
        assertTrue(Files.isRegularFile(existingDirectory))
        assertArrayEquals("source".toByteArray(), Files.readAllBytes(existingDirectory))
        assertFalse(Files.exists(nested))
    }

    @Test
    fun fileAlreadyExistsDuringPublishUsesConflictDecision() = runTest {
        val root = temporaryFolder.newFolder("publish-race").toPath()
        val source = Files.write(root.resolve("source.txt"), "source".toByteArray())
        val targetDirectory = Files.createDirectory(root.resolve("target"))
        val target = targetDirectory.resolve("source.txt")
        val lateTargetBytes = "late-target".toByteArray()
        val staged = temporaryFile(target, "task-1")
        val operations = object : DelegatingFileSystemOperations() {
            var injected = false

            override fun moveNoReplace(sourcePath: Path, targetPath: Path) {
                if (!injected && sourcePath == staged && targetPath == target) {
                    injected = true
                    Files.write(target, lateTargetBytes)
                    throw FileAlreadyExistsException(target.toString())
                }
                super.moveNoReplace(sourcePath, targetPath)
            }
        }
        var conflictCalls = 0

        val outcome = executeCopy(source, targetDirectory, FileOperationEngine(fileSystem = operations)) {
            conflictCalls += 1
            ReplacementDecision.CANCEL
        }

        assertTrue(outcome is EngineOutcome.Cancelled)
        assertEquals(1, conflictCalls)
        assertArrayEquals(lateTargetBytes, Files.readAllBytes(target))
        assertFalse(Files.exists(staged))
    }

    @Test
    fun missingTargetDuringPublishIsNotReportedAsMissingSource() = runTest {
        val root = temporaryFolder.newFolder("missing-publish-target").toPath()
        val source = Files.write(root.resolve("source.txt"), "source".toByteArray())
        val targetDirectory = Files.createDirectory(root.resolve("target"))
        val target = targetDirectory.resolve("source.txt")
        val operations = object : DelegatingFileSystemOperations() {
            override fun moveNoReplace(sourcePath: Path, targetPath: Path) {
                throw NoSuchFileException(target.toString())
            }
        }

        val outcome = executeCopy(source, targetDirectory, FileOperationEngine(fileSystem = operations))

        assertEquals("目标发布失败", (outcome as EngineOutcome.Failed).result.failures.single().userMessage)
    }

    @Test
    fun stagedCleanupFailureIsReportedWithExactPath() = runTest {
        val root = temporaryFolder.newFolder("staged-cleanup-failure").toPath()
        val source = Files.write(root.resolve("source.txt"), "source".toByteArray())
        val targetDirectory = Files.createDirectory(root.resolve("target"))
        val target = targetDirectory.resolve("source.txt")
        val staged = temporaryFile(target, "task-1")
        val copier = FileByteCopier { _, temporaryTarget, _, _ ->
            Files.write(temporaryTarget, byteArrayOf(1))
            throw IOException("forced copy failure")
        }
        val operations = object : DelegatingFileSystemOperations() {
            override fun delete(path: Path) {
                if (path == staged) throw IOException("forced staged cleanup failure")
                super.delete(path)
            }
        }

        val outcome = executeCopy(
            source,
            targetDirectory,
            FileOperationEngine(byteCopier = copier, fileSystem = operations),
        )

        val failure = (outcome as EngineOutcome.Failed).result.failures.single()
        assertTrue(failure.userMessage.contains("任务临时文件清理失败"))
        assertTrue(failure.technicalMessage.orEmpty().contains(staged.toString()))
        assertTrue(failure.technicalMessage.orEmpty().contains("forced staged cleanup failure"))
        assertTrue(Files.exists(staged))
    }

    private suspend fun executeCopy(
        source: Path,
        targetDirectory: Path,
        engine: FileOperationEngine = FileOperationEngine(),
        type: FileOperationType = FileOperationType.COPY,
        cancellation: OperationCancellation = OperationCancellation(),
        onConflict: suspend (FileConflict) -> ReplacementDecision = { ReplacementDecision.REPLACE_ALL },
    ): EngineOutcome = engine.execute(
        request = FileOperationRequest(
            taskId = "task-1",
            type = type,
            sources = listOf(source),
            targetDirectory = targetDirectory,
        ),
        scan = ScanOutcome.Ready(
            itemCount = 1,
            totalBytes = if (Files.exists(source)) Files.size(source) else 3L,
        ),
        cancellation = cancellation,
        onProgress = {},
        onConflict = onConflict,
    )

    private open class DelegatingFileSystemOperations : FileSystemOperations {
        override fun moveNoReplace(sourcePath: Path, targetPath: Path) {
            Files.move(sourcePath, targetPath)
        }

        override fun delete(path: Path) {
            Files.deleteIfExists(path)
        }
    }
}
