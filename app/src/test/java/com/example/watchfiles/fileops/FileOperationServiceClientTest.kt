package com.example.watchfiles.fileops

import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FileOperationServiceClientTest {
    private val source = Paths.get("/source.txt")
    private val target = Paths.get("/target")

    @Test fun clientMirrorsBoundServiceState() = runTest {
        val running = runningState("already-running.txt")
        val port = RecordingServicePort(running)
        val binding = FakeBindingAdapter()
        val client = client(binding)

        client.connect()
        binding.connect(port)

        assertEquals(running, client.state.value)
        val updated = runningState("updated.txt")
        port.mutableState.value = updated
        runCurrent()
        assertEquals(updated, client.state.value)
    }

    @Test fun clientForwardsAllControlCommands() = runTest {
        val port = RecordingServicePort(
            startResult = true,
            prepareDeleteResult = false,
            confirmDeleteResult = true,
        )
        val binding = FakeBindingAdapter()
        val client = client(binding)
        client.connect()
        binding.connect(port)

        assertTrue(client.start(FileOperationType.MOVE, listOf(source), target))
        assertFalse(client.prepareDelete(listOf(source)))
        assertTrue(client.confirmDelete())
        client.replaceAll()
        client.cancel()
        client.consumeResult()

        assertEquals(StartCall(FileOperationType.MOVE, listOf(source), target), port.startCall)
        assertEquals(listOf(source), port.prepareDeleteSources)
        assertEquals(1, port.confirmDeleteCalls)
        assertEquals(1, port.replaceAllCalls)
        assertEquals(1, port.cancelCalls)
        assertEquals(1, port.consumeResultCalls)
        assertEquals(
            listOf(
                FileOperationServiceLaunchRequest.ForegroundOnly(FileOperationType.MOVE.name),
                FileOperationServiceLaunchRequest.ForegroundOnly(FileOperationType.DELETE.name),
            ),
            binding.launches,
        )
    }

    @Test fun clientStartsForegroundBeforeSendingConnectedCommand() = runTest {
        val events = mutableListOf<String>()
        val port = RecordingServicePort(startResult = true, events = events)
        val binding = FakeBindingAdapter(events = events)
        val client = client(binding)
        client.connect()
        binding.connect(port)

        assertTrue(client.start(FileOperationType.COPY, listOf(source), target))

        assertEquals(listOf("foreground", "port.start"), events)
    }

    @Test fun clientQueuesAtMostOneStartUntilBound() = runTest {
        val port = RecordingServicePort(startResult = true)
        val binding = FakeBindingAdapter()
        val client = client(binding)
        client.connect()

        assertTrue(client.start(FileOperationType.COPY, listOf(source), target))
        assertFalse(client.prepareDelete(listOf(Paths.get("/second.txt"))))
        assertEquals(
            listOf(
                FileOperationServiceLaunchRequest.ForegroundOnly(FileOperationType.COPY.name),
            ),
            binding.launches,
        )

        binding.connect(port)
        runCurrent()
        assertEquals(1, port.startCalls)
        assertEquals(0, port.prepareDeleteCalls)
    }

    @Test fun clientDisconnectStopsStateCollection() = runTest {
        val port = RecordingServicePort()
        val binding = FakeBindingAdapter()
        val client = client(binding)
        client.connect()
        binding.connect(port)
        val running = runningState("active.txt")
        port.mutableState.value = running
        runCurrent()

        client.disconnect()
        client.disconnect()
        port.mutableState.value = FileOperationState.Succeeded(
            FileOperationType.COPY,
            FileOperationResult(1, 0),
        )
        runCurrent()

        assertEquals(running, client.state.value)
        assertEquals(1, binding.unbindCalls)
    }

    @Test fun clientRebindsOnceAfterBindingDiedAndNullBinding() = runTest {
        val binding = FakeBindingAdapter()
        val client = client(binding)
        client.connect()

        binding.bindingDied()
        runCurrent()
        assertEquals(1, binding.unbindCalls)
        assertEquals(2, binding.bindCalls)

        binding.nullBinding()
        runCurrent()
        assertEquals(2, binding.unbindCalls)
        assertEquals(3, binding.bindCalls)

        client.connect()
        runCurrent()
        assertEquals(3, binding.bindCalls)
    }

    @Test fun clientRebindsAfterServiceDisconnected() = runTest {
        val binding = FakeBindingAdapter()
        val client = client(binding)
        client.connect()

        binding.serviceDisconnected()
        runCurrent()

        assertEquals(2, binding.bindCalls)
    }

    @Test fun failedBindingKeepsClientIdle() = runTest {
        val binding = FakeBindingAdapter(bindResult = false)
        val client = client(binding)

        client.connect()
        client.disconnect()

        assertEquals(FileOperationState.Idle, client.state.value)
        assertEquals(1, binding.bindCalls)
        assertEquals(0, binding.unbindCalls)
        assertFalse(client.start(FileOperationType.COPY, listOf(source), target))
        assertEquals(emptyList<FileOperationServiceLaunchRequest>(), binding.launches)
    }

    @Test fun clientDoesNotLeavePendingWhenForegroundLaunchFails() = runTest {
        val port = RecordingServicePort(startResult = true)
        val binding = FakeBindingAdapter(initialFailForegroundLaunch = true)
        val client = client(binding)
        client.connect()

        assertFalse(client.start(FileOperationType.COPY, listOf(source), target))
        binding.failForegroundLaunch = false
        binding.connect(port)
        runCurrent()
        assertEquals(0, port.startCalls)

        assertTrue(client.start(FileOperationType.COPY, listOf(source), target))
        assertEquals(1, port.startCalls)
    }

    @Test fun clientDispatchesPendingAfterEarlyConnectionOnlyAfterForegroundReturns() = runTest {
        val events = mutableListOf<String>()
        val port = RecordingServicePort(startResult = true, events = events)
        val binding = FakeBindingAdapter(events = events, connectDuringLaunch = port)
        val client = client(binding)
        client.connect()

        assertTrue(client.start(FileOperationType.COPY, listOf(source), target))

        assertEquals(listOf("foreground", "port.start"), events)
        assertEquals(1, port.startCalls)
    }

    @Test fun clientRejectsSecondStartWhilePendingDispatching() = runTest {
        val dispatchStarted = CountDownLatch(1)
        val releaseDispatch = CountDownLatch(1)
        val port = RecordingServicePort(
            startResult = true,
            dispatchStarted = dispatchStarted,
            releaseDispatch = releaseDispatch,
        )
        val binding = FakeBindingAdapter()
        val client = client(binding)
        client.connect()
        assertTrue(client.start(FileOperationType.COPY, listOf(source), target))
        val executor = Executors.newSingleThreadExecutor()

        try {
            val connection = executor.submit { binding.connect(port) }
            assertTrue(dispatchStarted.await(2, TimeUnit.SECONDS))
            assertFalse(client.start(FileOperationType.COPY, listOf(source), target))
            releaseDispatch.countDown()
            connection.get(2, TimeUnit.SECONDS)
        } finally {
            releaseDispatch.countDown()
            executor.shutdownNow()
            executor.awaitTermination(2, TimeUnit.SECONDS)
        }
    }

    private fun TestScope.client(binding: FileOperationServiceBindingAdapter) =
        FileOperationServiceClient(binding, backgroundScope)

    private fun runningState(name: String) = FileOperationState.Running(
        FileOperationType.COPY,
        OperationProgress(name, 1, 2, 4, 8),
    )

    private data class StartCall(
        val type: FileOperationType,
        val sources: List<Path>,
        val targetDirectory: Path,
    )

    private class FakeBindingAdapter(
        private val events: MutableList<String> = mutableListOf(),
        private val connectDuringLaunch: FileOperationServicePort? = null,
        private val bindResult: Boolean = true,
        initialFailForegroundLaunch: Boolean = false,
    ) : FileOperationServiceBindingAdapter {
        private var connection: FileOperationServiceBindingConnection? = null
        val launches = mutableListOf<FileOperationServiceLaunchRequest>()
        var bindCalls = 0
        var unbindCalls = 0
        var failForegroundLaunch = initialFailForegroundLaunch

        override fun bind(connection: FileOperationServiceBindingConnection): Boolean {
            bindCalls += 1
            this.connection = connection
            return bindResult
        }

        override fun unbind() {
            unbindCalls += 1
        }

        override fun startForegroundService(request: FileOperationServiceLaunchRequest) {
            if (failForegroundLaunch) error("foreground launch failed")
            events += "foreground"
            launches += request
            connectDuringLaunch?.let { connection?.onConnected(it) }
        }

        fun connect(port: FileOperationServicePort) {
            connection?.onConnected(port)
        }

        fun bindingDied() {
            connection?.onBindingDied()
        }

        fun nullBinding() {
            connection?.onNullBinding()
        }

        fun serviceDisconnected() {
            connection?.onDisconnected()
        }
    }

    private class RecordingServicePort(
        initialState: FileOperationState = FileOperationState.Idle,
        private val startResult: Boolean = false,
        private val prepareDeleteResult: Boolean = false,
        private val confirmDeleteResult: Boolean = false,
        private val events: MutableList<String> = mutableListOf(),
        private val dispatchStarted: CountDownLatch? = null,
        private val releaseDispatch: CountDownLatch? = null,
    ) : FileOperationServicePort {
        val mutableState = MutableStateFlow(initialState)
        override val state: StateFlow<FileOperationState> = mutableState
        var startCall: StartCall? = null
        var startCalls = 0
        var prepareDeleteCalls = 0
        var prepareDeleteSources: List<Path>? = null
        var confirmDeleteCalls = 0
        var replaceAllCalls = 0
        var cancelCalls = 0
        var consumeResultCalls = 0

        override fun start(
            type: FileOperationType,
            sources: List<Path>,
            targetDirectory: Path,
        ): Boolean {
            events += "port.start"
            dispatchStarted?.countDown()
            releaseDispatch?.await(2, TimeUnit.SECONDS)
            startCalls += 1
            startCall = StartCall(type, sources, targetDirectory)
            return startResult
        }

        override fun prepareDelete(sources: List<Path>): Boolean {
            prepareDeleteCalls += 1
            prepareDeleteSources = sources
            return prepareDeleteResult
        }

        override fun confirmDelete(): Boolean {
            confirmDeleteCalls += 1
            return confirmDeleteResult
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
