package com.example.watchfiles.fileops

import java.nio.file.Path
import java.nio.file.Paths
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
                FileOperationServiceLaunchRequest.Start(
                    FileOperationType.MOVE.name,
                    arrayListOf(source.toString()),
                    target.toString(),
                ),
                FileOperationServiceLaunchRequest.PrepareDelete(
                    arrayListOf(source.toString()),
                ),
            ),
            binding.launches,
        )
    }

    @Test fun clientQueuesAtMostOneStartUntilBound() = runTest {
        val port = RecordingServicePort(startResult = true)
        val binding = FakeBindingAdapter()
        val client = client(binding)
        client.connect()

        assertTrue(client.start(FileOperationType.COPY, listOf(source), target))
        assertFalse(client.prepareDelete(listOf(Paths.get("/second.txt"))))
        assertEquals(1, binding.launches.size)

        binding.connect(port)
        runCurrent()
        assertEquals(1, port.startCalls)

        binding.disconnectService()
        binding.connect(port)
        runCurrent()
        assertEquals(1, port.startCalls)
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

    @Test fun failedBindingKeepsClientIdle() = runTest {
        val binding = FakeBindingAdapter(bindResult = false)
        val client = client(binding)

        client.connect()
        client.disconnect()

        assertEquals(FileOperationState.Idle, client.state.value)
        assertEquals(1, binding.bindCalls)
        assertEquals(0, binding.unbindCalls)
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
        private val bindResult: Boolean = true,
    ) : FileOperationServiceBindingAdapter {
        private var connection: FileOperationServiceBindingConnection? = null
        val launches = mutableListOf<FileOperationServiceLaunchRequest>()
        var bindCalls = 0
        var unbindCalls = 0

        override fun bind(connection: FileOperationServiceBindingConnection): Boolean {
            bindCalls += 1
            this.connection = connection
            return bindResult
        }

        override fun unbind() {
            unbindCalls += 1
        }

        override fun startForegroundService(request: FileOperationServiceLaunchRequest) {
            launches += request
        }

        fun connect(port: FileOperationServicePort) {
            connection?.onConnected(port)
        }

        fun disconnectService() {
            connection?.onDisconnected()
        }
    }

    private class RecordingServicePort(
        initialState: FileOperationState = FileOperationState.Idle,
        private val startResult: Boolean = false,
        private val prepareDeleteResult: Boolean = false,
        private val confirmDeleteResult: Boolean = false,
    ) : FileOperationServicePort {
        val mutableState = MutableStateFlow(initialState)
        override val state: StateFlow<FileOperationState> = mutableState
        var startCall: StartCall? = null
        var startCalls = 0
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
            startCalls += 1
            startCall = StartCall(type, sources, targetDirectory)
            return startResult
        }

        override fun prepareDelete(sources: List<Path>): Boolean {
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
