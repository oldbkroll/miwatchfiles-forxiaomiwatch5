package com.example.watchfiles.fileops

import java.io.IOException
import java.io.OutputStream
import java.nio.file.AccessDeniedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileOperationEngineDeleteTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun deletesRegularFileAndLeavesUnrelatedPartFile() = runTest {
        val root = temporaryFolder.newFolder("file").toPath()
        val source = Files.write(root.resolve("source.txt"), byteArrayOf(1, 2, 3))
        val userPart = Files.write(root.resolve("notes.part"), byteArrayOf(9, 8))

        val outcome = executeDelete(source, root)

        assertTrue(outcome is EngineOutcome.Completed)
        assertFalse(Files.exists(source))
        assertArrayEquals(byteArrayOf(9, 8), Files.readAllBytes(userPart))
    }

    @Test
    fun deletesThreeLevelDirectoryWithoutFollowingLinks() = runTest {
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

    @Test
    fun deleteFailureDoesNotReportTopLevelSuccess() = runTest {
        val root = temporaryFolder.newFolder("failure").toPath()
        val source = Files.write(root.resolve("source.txt"), byteArrayOf(1))
        val engine = engineWithFileSystem(FailingDeleteFileSystem(source))

        val outcome = executeDelete(source, root, engine = engine)

        assertTrue(outcome is EngineOutcome.Failed)
        assertEquals("没有权限删除", (outcome as EngineOutcome.Failed).result.failures.single().userMessage)
        assertTrue(Files.exists(source))
    }

    @Test
    fun genericDeleteFailureReportsDeleteFailed() = runTest {
        val root = temporaryFolder.newFolder("generic-failure").toPath()
        val source = Files.write(root.resolve("source.txt"), byteArrayOf(1))
        val engine = engineWithFileSystem(
            FailingDeleteFileSystem(source) { IOException("disk error") },
        )

        val outcome = executeDelete(source, root, engine = engine)

        assertTrue(outcome is EngineOutcome.Failed)
        assertEquals("删除失败", (outcome as EngineOutcome.Failed).result.failures.single().userMessage)
        assertTrue(Files.exists(source))
    }

    @Test
    fun cancellationStopsBeforeNextEntryAndReportsUnrecoverableProgress() = runTest {
        val root = temporaryFolder.newFolder("cancel").toPath()
        val source = Files.createDirectories(root.resolve("source"))
        Files.write(source.resolve("first.txt"), byteArrayOf(1))
        Files.write(source.resolve("second.txt"), byteArrayOf(2))
        val cancellation = OperationCancellation()
        var progressCalls = 0

        val outcome = executeDelete(
            root.resolve("source"),
            root,
            cancellation,
            onProgress = { progress ->
                if (++progressCalls == 1) cancellation.request()
                assertTrue(progress.processedItems >= 1)
            },
        )

        assertTrue(outcome is EngineOutcome.Cancelled)
        assertTrue(Files.exists(root.resolve("source")))
        assertTrue(
            Files.exists(root.resolve("source/first.txt")) ||
                Files.exists(root.resolve("source/second.txt")),
        )
        assertTrue((outcome as EngineOutcome.Cancelled).result.failures.any {
            it.userMessage == "删除已取消，部分内容可能已删除"
        })
    }

    @Test
    fun cancellationAfterOnlyChildDeletionLeavesParentUndeleted() = runTest {
        val root = temporaryFolder.newFolder("cancel-single-child").toPath()
        val source = Files.createDirectory(root.resolve("source"))
        val child = Files.write(source.resolve("child.txt"), byteArrayOf(1))
        val cancellation = OperationCancellation()
        var progressCalls = 0

        val outcome = executeDelete(
            source,
            root,
            cancellation,
            onProgress = {
                if (progressCalls++ == 0) cancellation.request()
            },
        )

        assertTrue(outcome is EngineOutcome.Cancelled)
        assertTrue(Files.exists(source))
        assertFalse(Files.exists(child))
    }

    @Test
    fun engineRejectsStorageRootEvenWhenCalledWithoutScanner() = runTest {
        val root = temporaryFolder.newFolder("root-guard").toPath()
        val outcome = executeDelete(root, root)

        assertTrue(outcome is EngineOutcome.Failed)
        assertEquals("不能删除内部存储根目录", (outcome as EngineOutcome.Failed).result.failures.single().userMessage)
    }

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
        private val failure: (Path) -> Exception = { path -> AccessDeniedException(path.toString()) },
    ) : FileSystemOperations {
        override fun createNewFile(path: Path): OutputStream =
            Files.newOutputStream(path, StandardOpenOption.CREATE_NEW)

        override fun moveNoReplace(source: Path, target: Path) {
            Files.move(source, target)
        }

        override fun delete(path: Path) {
            if (path == blocked) throw failure(path)
            Files.deleteIfExists(path)
        }
    }
}
