package com.example.watchfiles.fileops

import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileOperationEngineMoveTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun defaultFastMoverRelocatesOnTheSameFileStore() = runTest {
        val root = temporaryFolder.newFolder("default-fast-move").toPath()
        val bytes = "default-fast".toByteArray()
        val source = Files.write(root.resolve("source.txt"), bytes)
        val targetDirectory = Files.createDirectory(root.resolve("target"))

        val outcome = executeMove(source, targetDirectory, FileOperationEngine())

        assertTrue(outcome is EngineOutcome.Completed)
        assertFalse(Files.exists(source))
        assertArrayEquals(bytes, Files.readAllBytes(targetDirectory.resolve("source.txt")))
    }

    @Test fun fastMoveRelocatesSourceWithoutCallingFallbackDelete() = runTest {
        val root = temporaryFolder.newFolder("fast-move").toPath()
        val bytes = "fast".toByteArray()
        val source = Files.write(root.resolve("source.txt"), bytes)
        val targetDirectory = Files.createDirectory(root.resolve("target"))
        var deleteCalls = 0
        val engine = engine(
            fastMover = FastMover { from, to -> Files.move(from, to); true },
            sourceDeleter = SourceDeleter { deleteCalls += 1 },
        )

        val outcome = executeMove(source, targetDirectory, engine)

        assertTrue(outcome is EngineOutcome.Completed)
        assertFalse(Files.exists(source))
        assertArrayEquals(bytes, Files.readAllBytes(targetDirectory.resolve("source.txt")))
        assertEquals(0, deleteCalls)
    }

    @Test fun fallbackPublishesAndVerifiesTargetBeforeDeletingSource() = runTest {
        val root = temporaryFolder.newFolder("fallback-order").toPath()
        val bytes = "fallback".toByteArray()
        val source = Files.write(root.resolve("source.txt"), bytes)
        val targetDirectory = Files.createDirectory(root.resolve("target"))
        val target = targetDirectory.resolve("source.txt")
        var sawPublishedTarget = false
        val engine = engine(
            fastMover = FastMover { _, _ -> false },
            sourceDeleter = SourceDeleter { path ->
                sawPublishedTarget = Files.isRegularFile(target) && Files.size(target) == Files.size(path)
                Files.delete(path)
            },
        )

        val outcome = executeMove(source, targetDirectory, engine)

        assertTrue(outcome is EngineOutcome.Completed)
        assertTrue(sawPublishedTarget)
        assertFalse(Files.exists(source))
        assertArrayEquals(bytes, Files.readAllBytes(target))
    }

    @Test fun sourceDeleteFailureKeepsPublishedTargetAndReturnsPartial() = runTest {
        val root = temporaryFolder.newFolder("delete-failure").toPath()
        val bytes = "keep-both".toByteArray()
        val source = Files.write(root.resolve("source.txt"), bytes)
        val targetDirectory = Files.createDirectory(root.resolve("target"))
        val engine = engine(
            fastMover = FastMover { _, _ -> false },
            sourceDeleter = SourceDeleter { throw IOException("forced source delete failure") },
        )

        val outcome = executeMove(source, targetDirectory, engine)

        val failure = (outcome as EngineOutcome.Partial).result.failures.single()
        assertEquals("目标已保留，但源项目删除失败", failure.userMessage)
        assertTrue(Files.exists(source))
        assertArrayEquals(bytes, Files.readAllBytes(targetDirectory.resolve("source.txt")))
    }

    @Test fun cancellationDuringFallbackCopyLeavesSourceAndNoPublishedTarget() = runTest {
        val root = temporaryFolder.newFolder("fallback-cancel").toPath()
        val bytes = ByteArray(1024) { 7 }
        val source = Files.write(root.resolve("source.bin"), bytes)
        val targetDirectory = Files.createDirectory(root.resolve("target"))
        val target = targetDirectory.resolve("source.bin")
        val cancellation = OperationCancellation()
        val copier = FileByteCopier { from, staged, token, onBytes ->
            Files.newInputStream(from).use { input ->
                Files.newOutputStream(staged).use { output ->
                    val buffer = ByteArray(64)
                    val count = input.read(buffer)
                    output.write(buffer, 0, count)
                    onBytes(count.toLong())
                    cancellation.request()
                    token.throwIfRequested()
                }
            }
        }
        val engine = engine(byteCopier = copier, fastMover = FastMover { _, _ -> false })

        val outcome = executeMove(source, targetDirectory, engine, cancellation)

        assertTrue(outcome is EngineOutcome.Cancelled)
        assertArrayEquals(bytes, Files.readAllBytes(source))
        assertFalse(Files.exists(target))
        assertFalse(Files.exists(temporaryFile(target, "move-task")))
    }

    @Test fun replacementPublishFailureRestoresOldTargetAndKeepsSource() = runTest {
        val root = temporaryFolder.newFolder("replacement-restore").toPath()
        val newBytes = "new".toByteArray()
        val oldBytes = "old".toByteArray()
        val source = Files.write(root.resolve("source.txt"), newBytes)
        val targetDirectory = Files.createDirectory(root.resolve("target"))
        val target = Files.write(targetDirectory.resolve("source.txt"), oldBytes)
        val staged = temporaryFile(target, "move-task")
        val operations = object : TestFileSystemOperations() {
            override fun moveNoReplace(from: Path, to: Path) {
                if (from == staged && to == target) throw IOException("forced publish failure")
                super.moveNoReplace(from, to)
            }
        }
        val engine = engine(
            fileSystem = operations,
            fastMover = FastMover { _, _ -> false },
        )

        val outcome = executeMove(source, targetDirectory, engine)

        assertTrue(outcome is EngineOutcome.Failed)
        assertArrayEquals(oldBytes, Files.readAllBytes(target))
        assertArrayEquals(newBytes, Files.readAllBytes(source))
        assertFalse(Files.exists(backupPath(target, "move-task")))
        assertFalse(Files.exists(staged))
    }

    private fun engine(
        byteCopier: FileByteCopier = FileByteCopier { from, to, _, onBytes ->
            Files.copy(from, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            onBytes(Files.size(from))
        },
        fileSystem: FileSystemOperations = TestFileSystemOperations(),
        fastMover: FastMover,
        sourceDeleter: SourceDeleter = SourceDeleter { deleteRecursively(it) },
    ) = FileOperationEngine(byteCopier, fileSystem, fastMover, sourceDeleter)

    private suspend fun executeMove(
        source: Path,
        targetDirectory: Path,
        engine: FileOperationEngine,
        cancellation: OperationCancellation = OperationCancellation(),
    ): EngineOutcome = engine.execute(
        FileOperationRequest("move-task", FileOperationType.MOVE, listOf(source), targetDirectory),
        ScanOutcome.Ready(1, Files.size(source)),
        cancellation,
        {},
        { ReplacementDecision.REPLACE_ALL },
    )

    private open class TestFileSystemOperations : FileSystemOperations {
        override fun createNewFile(path: Path): OutputStream = Files.newOutputStream(path, CREATE_NEW)
        override fun moveNoReplace(from: Path, to: Path) { Files.move(from, to) }
        override fun delete(path: Path) { Files.deleteIfExists(path) }
    }

    companion object {
        private fun deleteRecursively(path: Path) {
            if (!Files.isDirectory(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                Files.deleteIfExists(path)
                return
            }
            Files.walk(path).sorted(Comparator.reverseOrder()).use { paths ->
                paths.forEach(Files::deleteIfExists)
            }
        }
    }
}
