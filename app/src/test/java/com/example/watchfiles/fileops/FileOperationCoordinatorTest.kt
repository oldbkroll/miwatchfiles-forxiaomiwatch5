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

    @Test fun movePartialOutcomePreservesMoveTypeAndFailure() = runTest {
        val result = FileOperationResult(
            completedItems = 1,
            failedItems = 1,
            failures = listOf(FileOperationFailure(source, "目标已保留，但源项目删除失败")),
        )
        val coordinator = coordinator(engine = engine { EngineOutcome.Partial(result) })

        coordinator.start(FileOperationType.MOVE, listOf(source), target)
        advanceUntilIdle()

        val state = coordinator.state.value as FileOperationState.PartiallySucceeded
        assertEquals(FileOperationType.MOVE, state.type)
        assertEquals(result, state.result)
    }

    @Test fun consumeResultReturnsToIdle() = runTest {
        val coordinator = coordinator()
        coordinator.start(FileOperationType.COPY, listOf(source), target)
        advanceUntilIdle()
        coordinator.consumeResult()
        assertEquals(FileOperationState.Idle, coordinator.state.value)
    }

    @Test fun deleteDoesNotCallEngineBeforeConfirmation() = runTest {
        var engineCalls = 0
        val engine = OperationEngineGateway { _, _, _, _, _ ->
            engineCalls += 1
            EngineOutcome.Completed(FileOperationResult(1, 0))
        }
        val coordinator = coordinator(
            scanner = OperationScannerGateway { _, _ -> ScanOutcome.Ready(3, 12) },
            engine = engine,
        )

        assertTrue(coordinator.prepareDelete(listOf(source)))
        advanceUntilIdle()

        assertTrue(coordinator.state.value is FileOperationState.WaitingForDeleteConfirmation)
        assertEquals(0, engineCalls)
    }

    @Test fun deleteCancelBeforeConfirmationReturnsIdleWithoutDeleting() = runTest {
        var engineCalls = 0
        val coordinator = coordinator(
            scanner = OperationScannerGateway { _, _ -> ScanOutcome.Ready(1, 1) },
            engine = OperationEngineGateway { _, _, _, _, _ ->
                engineCalls += 1
                EngineOutcome.Completed(FileOperationResult(1, 0))
            },
        )

        coordinator.prepareDelete(listOf(source))
        advanceUntilIdle()
        coordinator.cancel()
        advanceUntilIdle()

        assertEquals(FileOperationState.Idle, coordinator.state.value)
        assertEquals(0, engineCalls)
    }

    @Test fun deleteConfirmationStartsEngineAndMapsResult() = runTest {
        val coordinator = coordinator(
            scanner = OperationScannerGateway { _, _ -> ScanOutcome.Ready(1, 1) },
            engine = OperationEngineGateway { _, _, _, _, _ ->
                EngineOutcome.Partial(FileOperationResult(0, 1))
            },
        )

        coordinator.prepareDelete(listOf(source))
        advanceUntilIdle()
        assertTrue(coordinator.confirmDelete())
        advanceUntilIdle()

        assertTrue(coordinator.state.value is FileOperationState.PartiallySucceeded)
        assertEquals(
            FileOperationType.DELETE,
            (coordinator.state.value as FileOperationState.PartiallySucceeded).type,
        )
    }

    @Test fun secondTaskIsRejectedDuringDeleteConfirmation() = runTest {
        val coordinator = coordinator(
            scanner = OperationScannerGateway { _, _ -> ScanOutcome.Ready(1, 1) },
        )

        assertTrue(coordinator.prepareDelete(listOf(source)))
        advanceUntilIdle()

        assertFalse(coordinator.start(FileOperationType.COPY, listOf(source), target))
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
