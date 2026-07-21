package com.example.watchfiles.fileops

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import java.nio.file.Path
import java.nio.file.Paths
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FileOperationCoordinatorTest {
    private val source = Paths.get("/source.txt")
    private val target = Paths.get("/target")

    @Test fun exposesGatewayStateAndConnectsWhenCreated() {
        val running = FileOperationState.Running(
            FileOperationType.COPY,
            OperationProgress("source.txt", 1, 2, 4, 8),
        )
        val gateway = RecordingGateway(running)

        val coordinator = FileOperationCoordinator(gateway)

        assertSame(gateway.state, coordinator.state)
        assertEquals(running, coordinator.state.value)
        assertEquals(1, gateway.connectCalls)
    }

    @Test fun forwardsStartAndPrepareDeleteWithCompleteArguments() {
        val gateway = RecordingGateway(startResult = true, prepareDeleteResult = false)
        val coordinator = FileOperationCoordinator(gateway)

        assertTrue(coordinator.start(FileOperationType.MOVE, listOf(source), target))
        assertFalse(coordinator.prepareDelete(listOf(source)))

        assertEquals(StartCall(FileOperationType.MOVE, listOf(source), target), gateway.startCall)
        assertEquals(listOf(source), gateway.prepareDeleteSources)
    }

    @Test fun forwardsEveryControlCommand() {
        val gateway = RecordingGateway(confirmDeleteResult = true)
        val coordinator = FileOperationCoordinator(gateway)

        assertTrue(coordinator.confirmDelete())
        coordinator.replaceAll()
        coordinator.cancel()
        coordinator.consumeResult()

        assertEquals(1, gateway.confirmDeleteCalls)
        assertEquals(1, gateway.replaceAllCalls)
        assertEquals(1, gateway.cancelCalls)
        assertEquals(1, gateway.consumeResultCalls)
    }

    @Test fun disconnectsWhenViewModelStoreIsCleared() {
        val gateway = RecordingGateway()
        val store = ViewModelStore()
        ViewModelProvider(store, coordinatorFactory(gateway))[FileOperationCoordinator::class.java]

        store.clear()
        store.clear()

        assertEquals(1, gateway.connectCalls)
        assertEquals(1, gateway.disconnectCalls)
    }

    private fun coordinatorFactory(gateway: FileOperationServiceGateway) =
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                FileOperationCoordinator(gateway) as T
        }

    private data class StartCall(
        val type: FileOperationType,
        val sources: List<Path>,
        val targetDirectory: Path,
    )

    private class RecordingGateway(
        initialState: FileOperationState = FileOperationState.Idle,
        private val startResult: Boolean = false,
        private val prepareDeleteResult: Boolean = false,
        private val confirmDeleteResult: Boolean = false,
    ) : FileOperationServiceGateway {
        override val state: StateFlow<FileOperationState> = MutableStateFlow(initialState)
        var connectCalls = 0
        var disconnectCalls = 0
        var startCall: StartCall? = null
        var prepareDeleteSources: List<Path>? = null
        var confirmDeleteCalls = 0
        var replaceAllCalls = 0
        var cancelCalls = 0
        var consumeResultCalls = 0

        override fun connect() {
            connectCalls += 1
        }

        override fun disconnect() {
            disconnectCalls += 1
        }

        override fun start(
            type: FileOperationType,
            sources: List<Path>,
            targetDirectory: Path,
        ): Boolean {
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
