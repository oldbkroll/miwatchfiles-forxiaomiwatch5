package com.example.watchfiles.fileops

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileOperationEngineDirectoryTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun copiesThreeLevelMixedDirectoryAndPublishesOnlyWhenComplete() = runTest {
        val root = temporaryFolder.newFolder("recursive").toPath()
        val source = Files.createDirectories(root.resolve("source/a/b"))
            .let { root.resolve("source") }
        Files.write(source.resolve("top.txt"), "top".toByteArray())
        Files.write(source.resolve("a/b/deep.txt"), "deep".toByteArray())
        val targetDirectory = Files.createDirectory(root.resolve("target"))

        val outcome = execute(listOf(source), targetDirectory)

        assertTrue(outcome is EngineOutcome.Completed)
        assertArrayEquals("top".toByteArray(), Files.readAllBytes(targetDirectory.resolve("source/top.txt")))
        assertArrayEquals("deep".toByteArray(), Files.readAllBytes(targetDirectory.resolve("source/a/b/deep.txt")))
        assertFalse(Files.exists(temporaryDirectory(targetDirectory.resolve("source"), "directory-task")))
    }

    @Test
    fun replacementKeepsOldTargetUntilNewTemporaryDirectoryIsComplete() = runTest {
        val root = temporaryFolder.newFolder("replace-directory").toPath()
        val source = Files.createDirectory(root.resolve("source"))
        Files.write(source.resolve("new.txt"), "new".toByteArray())
        val targetDirectory = Files.createDirectory(root.resolve("target"))
        val target = Files.createDirectory(targetDirectory.resolve("source"))
        val old = Files.write(target.resolve("old.txt"), "old".toByteArray())
        var sawOldAtConflict = false

        val outcome = execute(listOf(source), targetDirectory) {
            sawOldAtConflict = Files.exists(old) && !Files.exists(target.resolve("new.txt"))
            ReplacementDecision.REPLACE_ALL
        }

        assertTrue(outcome is EngineOutcome.Completed)
        assertTrue(sawOldAtConflict)
        assertFalse(Files.exists(target.resolve("old.txt")))
        assertArrayEquals("new".toByteArray(), Files.readAllBytes(target.resolve("new.txt")))
    }

    @Test
    fun replaceAllCallbackRunsOnlyForFirstConflict() = runTest {
        val root = temporaryFolder.newFolder("replace-all").toPath()
        val one = Files.write(root.resolve("one.txt"), byteArrayOf(1))
        val two = Files.write(root.resolve("two.txt"), byteArrayOf(2))
        val target = Files.createDirectory(root.resolve("target"))
        Files.write(target.resolve("one.txt"), byteArrayOf(9))
        Files.write(target.resolve("two.txt"), byteArrayOf(9))
        val calls = AtomicInteger()

        val outcome = execute(listOf(one, two), target) {
            calls.incrementAndGet()
            ReplacementDecision.REPLACE_ALL
        }

        assertTrue(outcome is EngineOutcome.Completed)
        assertEquals(1, calls.get())
    }

    @Test
    fun cancellationKeepsPublishedItemsAndRemovesUnpublishedTaskDirectory() = runTest {
        val root = temporaryFolder.newFolder("cancel-directory").toPath()
        val first = Files.write(root.resolve("first.txt"), "first".toByteArray())
        val second = Files.createDirectory(root.resolve("second"))
        Files.write(second.resolve("large.bin"), ByteArray(256 * 1024) { 7 })
        val target = Files.createDirectory(root.resolve("target"))
        val cancellation = OperationCancellation()

        val outcome = execute(
            sources = listOf(first, second),
            targetDirectory = target,
            cancellation = cancellation,
            onProgress = { progress ->
                if (progress.currentName == "large.bin" && progress.processedBytes > 5) cancellation.request()
            },
        )

        assertTrue(outcome is EngineOutcome.Cancelled)
        assertTrue(Files.exists(target.resolve("first.txt")))
        assertFalse(Files.exists(target.resolve("second")))
        assertFalse(Files.exists(temporaryDirectory(target.resolve("second"), "directory-task")))
        assertTrue(Files.exists(first))
        assertTrue(Files.exists(second))
    }

    @Test
    fun fileDirectoryTypeConflictUsesReplacementFlow() = runTest {
        val root = temporaryFolder.newFolder("type-conflict").toPath()
        val source = Files.createDirectory(root.resolve("same"))
        Files.write(source.resolve("nested.txt"), "directory".toByteArray())
        val targetDirectory = Files.createDirectory(root.resolve("target"))
        val target = Files.write(targetDirectory.resolve("same"), "file".toByteArray())
        var conflicts = 0

        val outcome = execute(listOf(source), targetDirectory) {
            conflicts += 1
            ReplacementDecision.REPLACE_ALL
        }

        assertTrue(outcome is EngineOutcome.Completed)
        assertEquals(1, conflicts)
        assertTrue(Files.isDirectory(target))
        assertArrayEquals("directory".toByteArray(), Files.readAllBytes(target.resolve("nested.txt")))
    }

    private suspend fun execute(
        sources: List<Path>,
        targetDirectory: Path,
        cancellation: OperationCancellation = OperationCancellation(),
        onProgress: (OperationProgress) -> Unit = {},
        onConflict: suspend (FileConflict) -> ReplacementDecision = { ReplacementDecision.REPLACE_ALL },
    ): EngineOutcome = FileOperationEngine().execute(
        FileOperationRequest("directory-task", FileOperationType.COPY, sources, targetDirectory),
        ScanOutcome.Ready(20, sources.sumOf { runCatching { Files.size(it) }.getOrDefault(0) }),
        cancellation,
        onProgress,
        onConflict,
    )
}
