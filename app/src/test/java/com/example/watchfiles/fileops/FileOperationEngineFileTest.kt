package com.example.watchfiles.fileops

import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    private suspend fun executeCopy(
        source: Path,
        targetDirectory: Path,
        engine: FileOperationEngine = FileOperationEngine(),
    ): EngineOutcome = engine.execute(
        request = FileOperationRequest(
            taskId = "task-1",
            type = FileOperationType.COPY,
            sources = listOf(source),
            targetDirectory = targetDirectory,
        ),
        scan = ScanOutcome.Ready(
            itemCount = 1,
            totalBytes = if (Files.exists(source)) Files.size(source) else 3L,
        ),
        cancellation = OperationCancellation(),
        onProgress = {},
        onConflict = { ReplacementDecision.REPLACE_ALL },
    )
}
