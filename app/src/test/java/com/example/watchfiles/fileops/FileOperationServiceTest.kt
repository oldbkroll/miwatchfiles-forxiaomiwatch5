package com.example.watchfiles.fileops

import java.nio.file.Paths
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FileOperationServiceTest {
    private val source = Paths.get("/source.txt")
    private val target = Paths.get("/target")

    @Test fun idleAndTerminalStatesHaveNoNotificationContent() {
        val result = FileOperationResult(1, 0)
        val states = listOf(
            FileOperationState.Idle,
            FileOperationState.Succeeded(FileOperationType.COPY, result),
            FileOperationState.PartiallySucceeded(FileOperationType.MOVE, result),
            FileOperationState.Failed(FileOperationType.DELETE, result),
            FileOperationState.Cancelled(FileOperationType.COPY, result),
        )

        states.forEach { assertNull(notificationContentFor(it)) }
    }

    @Test fun runningNotificationContainsTypeCurrentNameAndProgress() {
        val content = notificationContentFor(
            FileOperationState.Running(
                FileOperationType.COPY,
                OperationProgress("source.txt", 2, 4, 8, 16),
            ),
        )!!

        assertEquals("WatchFiles 正在操作", content.title)
        assertTrue(content.text.contains("复制"))
        assertTrue(content.text.contains("source.txt"))
        assertTrue(content.text.contains("2/4"))
        assertEquals(2, content.processedItems)
        assertEquals(4, content.totalItems)
        assertFalse(content.hasSound)
        assertFalse(content.hasVibration)
    }

    @Test fun servicePortAdapterForwardsEveryRunnerCommand() {
        val runner = RecordingRunnerPort()
        val adapter = FileOperationServicePortAdapter(runner)

        assertSame(runner.state, adapter.state)
        assertTrue(adapter.start(FileOperationType.COPY, listOf(source), target))
        assertTrue(adapter.prepareDelete(listOf(source)))
        assertTrue(adapter.confirmDelete())
        adapter.replaceAll()
        adapter.cancel()
        adapter.consumeResult()

        assertEquals(FileOperationType.COPY, runner.startedType)
        assertEquals(listOf(source), runner.startedSources)
        assertEquals(target, runner.startedTarget)
        assertEquals(listOf(source), runner.deleteSources)
        assertEquals(1, runner.replaceAllCalls)
        assertEquals(1, runner.cancelCalls)
        assertEquals(1, runner.consumeResultCalls)
    }

    private class RecordingRunnerPort : FileOperationRunnerPort {
        private val mutableState = MutableStateFlow<FileOperationState>(FileOperationState.Idle)
        override val state: StateFlow<FileOperationState> = mutableState
        var startedType: FileOperationType? = null
        var startedSources: List<java.nio.file.Path>? = null
        var startedTarget: java.nio.file.Path? = null
        var deleteSources: List<java.nio.file.Path>? = null
        var replaceAllCalls = 0
        var cancelCalls = 0
        var consumeResultCalls = 0

        override fun start(
            type: FileOperationType,
            sources: List<java.nio.file.Path>,
            targetDirectory: java.nio.file.Path,
        ): Boolean {
            startedType = type
            startedSources = sources
            startedTarget = targetDirectory
            return true
        }

        override fun prepareDelete(sources: List<java.nio.file.Path>): Boolean {
            deleteSources = sources
            return true
        }

        override fun confirmDelete(): Boolean = true

        override fun replaceAll() {
            replaceAllCalls += 1
        }

        override fun cancel() {
            cancelCalls += 1
        }

        override fun consumeResult() {
            consumeResultCalls += 1
        }
    }
}
