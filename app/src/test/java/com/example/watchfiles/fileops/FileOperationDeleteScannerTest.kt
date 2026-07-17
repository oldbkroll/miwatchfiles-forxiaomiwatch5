package com.example.watchfiles.fileops

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileOperationDeleteScannerTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

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

    @Test fun deleteScanCountsBrokenSymlinkWithoutFollowingItsTarget() = runTest {
        val root = temporaryFolder.newFolder("broken-symlink").toPath()
        val link = root.resolve("link")
        try {
            Files.createSymbolicLink(link, root.resolve("missing-target"))
        } catch (_: UnsupportedOperationException) {
            Assume.assumeTrue("symlink unsupported", false)
        } catch (_: IOException) {
            Assume.assumeTrue("symlink unavailable", false)
        }

        val outcome = scanner(root).scan(
            FileOperationRequest.delete("delete-broken-link", listOf(link)),
            OperationCancellation(),
        )

        assertEquals(ScanOutcome.Ready(itemCount = 1, totalBytes = 0), outcome)
    }

    private fun scanner(root: Path) = FileOperationScanner(
        storageRoot = { root },
        usableSpace = { Long.MAX_VALUE },
    )
}
