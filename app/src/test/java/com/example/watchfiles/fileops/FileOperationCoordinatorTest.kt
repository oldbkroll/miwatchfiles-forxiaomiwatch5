package com.example.watchfiles.fileops

import com.example.watchfiles.browser.MainDispatcherRule
import java.nio.file.Paths
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FileOperationCoordinatorTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()
    private val source = Paths.get("/source.txt")
    private val target = Paths.get("/target")
    private val ready = ScanOutcome.Ready(1, 4)

    @Test fun refusesSecondTaskWhileFirstIsRunning() = runTest {
        val gate = CompletableDeferred<EngineOutcome>()
        val coordinator = coordinator(engine = engine { gate.await() })
        assertTrue(coordinator.start(FileOperationType.COPY, listOf(source), target))
        runCurrent()
        assertFalse(coordinator.start(FileOperationType.COPY, listOf(source), target))
        gate.complete(EngineOutcome.Completed(FileOperationResult(1, 0)))
        advanceUntilIdle()
    }

    @Test fun cancelDuringScanningRequestsTokenAndEndsCancelled() = runTest {
        val gate = CompletableDeferred<Unit>()
        val scanner = OperationScannerGateway { _, token ->
            gate.await()
            token.throwIfRequested()
            ready
        }
        val coordinator = coordinator(scanner = scanner)
        coordinator.start(FileOperationType.COPY, listOf(source), target)
        runCurrent()
        coordinator.cancel()
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(coordinator.state.value is FileOperationState.Cancelled)
    }

    @Test fun exposesWaitingConflictUntilReplaceAllIsChosen() = runTest {
        var decision: ReplacementDecision? = null
        val coordinator = coordinator(engine = OperationEngineGateway { _, _, _, progress, conflict ->
            val p = OperationProgress("source.txt", 0, 1, 0, 4)
            progress(p)
            decision = conflict(FileConflict(source, target.resolve("source.txt")))
            EngineOutcome.Completed(FileOperationResult(1, 0))
        })
        coordinator.start(FileOperationType.COPY, listOf(source), target)
        runCurrent()
        assertTrue(coordinator.state.value is FileOperationState.WaitingForReplacement)
        coordinator.replaceAll()
        advanceUntilIdle()
        assertEquals(ReplacementDecision.REPLACE_ALL, decision)
        assertTrue(coordinator.state.value is FileOperationState.Succeeded)
    }

    @Test fun cancelWhileWaitingCompletesDeferredWithCancel() = runTest {
        var decision: ReplacementDecision? = null
        val coordinator = coordinator(engine = OperationEngineGateway { _, _, _, progress, conflict ->
            progress(OperationProgress("source.txt", 0, 1, 0, 4))
            decision = conflict(FileConflict(source, target.resolve("source.txt")))
            EngineOutcome.Cancelled(FileOperationResult(0, 0))
        })
        coordinator.start(FileOperationType.COPY, listOf(source), target)
        runCurrent()
        coordinator.cancel()
        advanceUntilIdle()
        assertEquals(ReplacementDecision.CANCEL, decision)
        assertTrue(coordinator.state.value is FileOperationState.Cancelled)
    }

    @Test fun mapsCompletedPartialFailedAndCancelledOutcomes() = runTest {
        val outcomes = listOf(
            EngineOutcome.Completed(FileOperationResult(1, 0)),
            EngineOutcome.Partial(FileOperationResult(1, 1)),
            EngineOutcome.Failed(FileOperationResult(0, 1)),
            EngineOutcome.Cancelled(FileOperationResult(0, 0)),
        )
        val expected = listOf(
            FileOperationState.Succeeded::class,
            FileOperationState.PartiallySucceeded::class,
            FileOperationState.Failed::class,
            FileOperationState.Cancelled::class,
        )
        outcomes.zip(expected).forEach { (outcome, stateClass) ->
            val coordinator = coordinator(engine = engine { outcome })
            coordinator.start(FileOperationType.COPY, listOf(source), target)
            advanceUntilIdle()
            assertEquals(stateClass, coordinator.state.value::class)
        }
    }

    @Test fun consumeResultReturnsToIdle() = runTest {
        val coordinator = coordinator()
        coordinator.start(FileOperationType.COPY, listOf(source), target)
        advanceUntilIdle()
        coordinator.consumeResult()
        assertEquals(FileOperationState.Idle, coordinator.state.value)
    }

    private fun coordinator(
        scanner: OperationScannerGateway = OperationScannerGateway { _, _ -> ready },
        engine: OperationEngineGateway = engine { EngineOutcome.Completed(FileOperationResult(1, 0)) },
    ) = FileOperationCoordinator(scanner, engine) { "task" }

    private fun engine(block: suspend () -> EngineOutcome) = OperationEngineGateway { _, _, _, progress, _ ->
        progress(OperationProgress("source.txt", 0, 1, 0, 4))
        block()
    }
}
