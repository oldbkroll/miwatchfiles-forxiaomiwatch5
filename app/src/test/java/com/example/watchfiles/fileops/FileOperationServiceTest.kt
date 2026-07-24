package com.example.watchfiles.fileops

import java.nio.file.Paths
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
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

    @Test fun waitingForLargeOperationConfirmationNotificationIsLowNoise() {
        val content = notificationContentFor(
            FileOperationState.WaitingForLargeOperationConfirmation(
                type = FileOperationType.MOVE,
                itemCount = 10,
                totalBytes = 1024,
            ),
        )!!

        assertEquals("WatchFiles 正在操作", content.title)
        assertTrue(content.text.contains("移动"))
        assertTrue(content.text.contains("等待确认"))
        assertEquals(null, content.processedItems)
        assertEquals(null, content.totalItems)
        assertFalse(content.hasSound)
        assertFalse(content.hasVibration)
    }

    @Test fun servicePortAdapterForwardsEveryRunnerCommand() {
        val runner = RecordingRunnerPort()
        val adapter = FileOperationServicePortAdapter(runner)

        assertSame(runner.state, adapter.state)
        assertTrue(adapter.start(FileOperationType.COPY, listOf(source), target))
        assertTrue(adapter.prepareDelete(listOf(source)))
        assertTrue(adapter.confirmLargeOperation())
        assertTrue(adapter.confirmDelete())
        adapter.replaceAll()
        adapter.cancel()
        adapter.consumeResult()

        assertEquals(FileOperationType.COPY, runner.startedType)
        assertEquals(listOf(source), runner.startedSources)
        assertEquals(target, runner.startedTarget)
        assertEquals(listOf(source), runner.deleteSources)
        assertEquals(1, runner.confirmLargeOperationCalls)
        assertEquals(1, runner.confirmDeleteCalls)
        assertEquals(1, runner.replaceAllCalls)
        assertEquals(1, runner.cancelCalls)
        assertEquals(1, runner.consumeResultCalls)
    }

    @Test fun servicePortAdapterEntersForegroundBeforeRunnerAcceptsTask() {
        val events = mutableListOf<String>()
        val runner = RecordingRunnerPort(events)
        val adapter = FileOperationServicePortAdapter(runner) { events += "foreground" }

        assertTrue(adapter.start(FileOperationType.COPY, listOf(source), target))

        assertEquals(listOf("foreground", "runner.start"), events)
    }

    @Test fun stateCollectionRestartsAfterTerminalStopForNextTask() = runTest {
        val state = MutableStateFlow<FileOperationState>(FileOperationState.Idle)
        val observed = mutableListOf<FileOperationState>()
        val firstTask = FileOperationState.Running(
            FileOperationType.COPY,
            OperationProgress("first.txt", 1, 2, 1, 2),
        )
        val secondTask = FileOperationState.Running(
            FileOperationType.MOVE,
            OperationProgress("second.txt", 1, 2, 1, 2),
        )
        val controller = FileOperationStateCollectionController(this, state, observed::add)

        controller.ensureActive()
        runCurrent()
        state.value = firstTask
        runCurrent()
        controller.stop()

        state.value = FileOperationState.Idle
        controller.ensureActive()
        runCurrent()
        state.value = secondTask
        runCurrent()

        assertEquals(listOf(FileOperationState.Idle, firstTask, FileOperationState.Idle, secondTask), observed)
        controller.close()
    }

    @Test fun stateCollectionDoesNotDuplicateWhileAlreadyActive() = runTest {
        val state = MutableStateFlow<FileOperationState>(FileOperationState.Idle)
        val observed = mutableListOf<FileOperationState>()
        val running = FileOperationState.Running(
            FileOperationType.COPY,
            OperationProgress("source.txt", 1, 1, 1, 1),
        )
        val controller = FileOperationStateCollectionController(this, state, observed::add)

        controller.ensureActive()
        controller.ensureActive()
        runCurrent()
        state.value = running
        runCurrent()

        assertEquals(listOf(FileOperationState.Idle, running), observed)
        controller.close()
    }

    @Test fun concurrentStartsAcceptOnlyOneTask() {
        val ready = CountDownLatch(2)
        val begin = CountDownLatch(1)
        val foregroundEntered = CountDownLatch(1)
        val releaseForeground = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val runner = RecordingRunnerPort(updateStateOnStart = true)
        val adapter = FileOperationServicePortAdapter(runner) {
            foregroundEntered.countDown()
            releaseForeground.await(5, TimeUnit.SECONDS)
        }
        val tasks = (1..2).map {
            executor.submit<Boolean> {
                ready.countDown()
                begin.await(5, TimeUnit.SECONDS)
                adapter.start(FileOperationType.COPY, listOf(source), target)
            }
        }

        try {
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            begin.countDown()
            assertTrue(foregroundEntered.await(5, TimeUnit.SECONDS))
            releaseForeground.countDown()

            val results = tasks.map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it })
            assertEquals(1, results.count { !it })
        } finally {
            releaseForeground.countDown()
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    @Test fun prepareDeleteLaunchUsesServiceParsingContract() {
        val request = FileOperationServiceLaunchRequest.PrepareDelete(
            arrayListOf(source.toString()),
        )

        assertEquals(
            FileOperationServiceIntentContract.ACTION_PREPARE_DELETE,
            request.action,
        )
        assertEquals(
            FileOperationServiceIntentCommand.PrepareDelete(listOf(source)),
            parseFileOperationServiceIntent(
                action = request.action,
                type = request.type,
                sources = request.sources,
                targetDirectory = request.targetDirectory,
            ),
        )
    }

    @Test fun foregroundOnlyLaunchUsesServiceParsingContract() {
        val request = FileOperationServiceLaunchRequest.ForegroundOnly(FileOperationType.DELETE.name)

        assertEquals(
            FileOperationServiceIntentContract.ACTION_FOREGROUND_ONLY,
            request.action,
        )
        assertEquals(
            FileOperationServiceIntentCommand.ForegroundOnly(FileOperationType.DELETE),
            parseFileOperationServiceIntent(
                action = request.action,
                type = request.type,
                sources = request.sources,
                targetDirectory = request.targetDirectory,
            ),
        )
    }

    @Test fun foregroundOnlyCommandEnsuresForegroundWithoutStartingRunner() {
        val events = mutableListOf<String>()
        val command = FileOperationServiceIntentCommand.ForegroundOnly(FileOperationType.DELETE)

        dispatchFileOperationServiceIntentCommand(
            command = command,
            onForegroundOnly = { events += "foreground" },
            onStart = { _, _, _ -> events += "runner.start" },
            onPrepareDelete = { events += "runner.prepareDelete" },
        )

        assertEquals(listOf("foreground"), events)
    }

    private class RecordingRunnerPort(
        private val events: MutableList<String> = mutableListOf(),
        private val updateStateOnStart: Boolean = false,
    ) : FileOperationRunnerPort {
        private val mutableState = MutableStateFlow<FileOperationState>(FileOperationState.Idle)
        override val state: StateFlow<FileOperationState> = mutableState
        var startedType: FileOperationType? = null
        var startedSources: List<java.nio.file.Path>? = null
        var startedTarget: java.nio.file.Path? = null
        var deleteSources: List<java.nio.file.Path>? = null
        var confirmLargeOperationCalls = 0
        var confirmDeleteCalls = 0
        var replaceAllCalls = 0
        var cancelCalls = 0
        var consumeResultCalls = 0

        override fun start(
            type: FileOperationType,
            sources: List<java.nio.file.Path>,
            targetDirectory: java.nio.file.Path,
        ): Boolean {
            events += "runner.start"
            startedType = type
            startedSources = sources
            startedTarget = targetDirectory
            if (updateStateOnStart) mutableState.value = FileOperationState.Scanning(type)
            return true
        }

        override fun prepareDelete(sources: List<java.nio.file.Path>): Boolean {
            deleteSources = sources
            return true
        }

        override fun confirmLargeOperation(): Boolean {
            confirmLargeOperationCalls += 1
            return true
        }

        override fun confirmDelete(): Boolean {
            confirmDeleteCalls += 1
            return true
        }

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
