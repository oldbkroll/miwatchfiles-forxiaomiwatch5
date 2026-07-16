package com.example.watchfiles.fileops

import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

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
