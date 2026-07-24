package com.example.watchfiles.fileops

import com.example.watchfiles.browser.MainDispatcherRule
import java.nio.file.Paths
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FileOperationRunnerTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()
    private val source = Paths.get("/source.txt")
    private val target = Paths.get("/target")
    private val ready = ScanOutcome.Ready(1, 4)

    @Test fun refusesEmptyInputAndKeepsIdle() = runTest {
        val runner = runner()

        assertFalse(runner.start(FileOperationType.COPY, emptyList(), target))
        assertFalse(runner.prepareDelete(emptyList()))

        assertEquals(FileOperationState.Idle, runner.state.value)
    }

    @Test fun refusesSecondTaskWhileFirstIsRunning() = runTest {
        val gate = CompletableDeferred<EngineOutcome>()
        val runner = runner(engine = engine { gate.await() })
        assertTrue(runner.start(FileOperationType.COPY, listOf(source), target))
        runCurrent()
        assertFalse(runner.start(FileOperationType.COPY, listOf(source), target))
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
        val runner = runner(scanner = scanner)
        runner.start(FileOperationType.COPY, listOf(source), target)
        runCurrent()
        runner.cancel()
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(runner.state.value is FileOperationState.Cancelled)
    }

    @Test fun exposesWaitingConflictUntilReplaceAllIsChosen() = runTest {
        var decision: ReplacementDecision? = null
        val runner = runner(engine = OperationEngineGateway { _, _, _, progress, conflict ->
            val p = OperationProgress("source.txt", 0, 1, 0, 4)
            progress(p)
            decision = conflict(FileConflict(source, target.resolve("source.txt")))
            EngineOutcome.Completed(FileOperationResult(1, 0))
        })
        runner.start(FileOperationType.COPY, listOf(source), target)
        runCurrent()
        assertTrue(runner.state.value is FileOperationState.WaitingForReplacement)
        runner.replaceAll()
        advanceUntilIdle()
        assertEquals(ReplacementDecision.REPLACE_ALL, decision)
        assertTrue(runner.state.value is FileOperationState.Succeeded)
    }

    @Test fun cancelWhileWaitingCompletesDeferredWithCancel() = runTest {
        var decision: ReplacementDecision? = null
        val runner = runner(engine = OperationEngineGateway { _, _, _, progress, conflict ->
            progress(OperationProgress("source.txt", 0, 1, 0, 4))
            decision = conflict(FileConflict(source, target.resolve("source.txt")))
            EngineOutcome.Cancelled(FileOperationResult(0, 0))
        })
        runner.start(FileOperationType.COPY, listOf(source), target)
        runCurrent()
        runner.cancel()
        advanceUntilIdle()
        assertEquals(ReplacementDecision.CANCEL, decision)
        assertTrue(runner.state.value is FileOperationState.Cancelled)
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
            val runner = runner(engine = engine { outcome })
            runner.start(FileOperationType.COPY, listOf(source), target)
            advanceUntilIdle()
            assertEquals(stateClass, runner.state.value::class)
        }
    }

    @Test fun movePartialOutcomePreservesMoveTypeAndFailure() = runTest {
        val result = FileOperationResult(
            completedItems = 1,
            failedItems = 1,
            failures = listOf(FileOperationFailure(source, "目标已保留，但源项目删除失败")),
        )
        val runner = runner(engine = engine { EngineOutcome.Partial(result) })

        runner.start(FileOperationType.MOVE, listOf(source), target)
        advanceUntilIdle()

        val state = runner.state.value as FileOperationState.PartiallySucceeded
        assertEquals(FileOperationType.MOVE, state.type)
        assertEquals(result, state.result)
    }

    @Test fun consumeResultReturnsToIdle() = runTest {
        val runner = runner()
        runner.start(FileOperationType.COPY, listOf(source), target)
        advanceUntilIdle()
        runner.consumeResult()
        assertEquals(FileOperationState.Idle, runner.state.value)
    }

    @Test fun deleteDoesNotCallEngineBeforeConfirmation() = runTest {
        var engineCalls = 0
        val engine = OperationEngineGateway { _, _, _, _, _ ->
            engineCalls += 1
            EngineOutcome.Completed(FileOperationResult(1, 0))
        }
        val runner = runner(
            scanner = OperationScannerGateway { _, _ -> ScanOutcome.Ready(3, 12) },
            engine = engine,
        )

        assertTrue(runner.prepareDelete(listOf(source)))
        advanceUntilIdle()

        assertTrue(runner.state.value is FileOperationState.WaitingForDeleteConfirmation)
        assertEquals(0, engineCalls)
    }

    @Test fun largeCopyAndMoveWaitForWarningBeforeCallingEngine() = runTest {
        listOf(FileOperationType.COPY, FileOperationType.MOVE).forEach { type ->
            var engineCalls = 0
            val runner = runner(
                scanner = OperationScannerGateway { _, _ ->
                    ScanOutcome.Ready(LARGE_OPERATION_ITEM_THRESHOLD, 4)
                },
                engine = OperationEngineGateway { _, _, _, _, _ ->
                    engineCalls += 1
                    EngineOutcome.Completed(FileOperationResult(1, 0))
                },
            )

            assertTrue(runner.start(type, listOf(source), target))
            advanceUntilIdle()

            assertEquals(
                FileOperationState.WaitingForLargeOperationConfirmation(
                    type = type,
                    itemCount = LARGE_OPERATION_ITEM_THRESHOLD,
                    totalBytes = 4,
                ),
                runner.state.value,
            )
            assertEquals(0, engineCalls)

            assertTrue(runner.confirmLargeOperation())
            advanceUntilIdle()

            assertEquals(1, engineCalls)
            assertTrue(runner.state.value is FileOperationState.Succeeded)
        }
    }

    @Test fun largeDeleteReachesDeleteConfirmationOnlyAfterWarningConfirmation() = runTest {
        var engineCalls = 0
        val runner = runner(
            scanner = OperationScannerGateway { _, _ ->
                ScanOutcome.Ready(LARGE_OPERATION_ITEM_THRESHOLD, 12)
            },
            engine = OperationEngineGateway { _, _, _, _, _ ->
                engineCalls += 1
                EngineOutcome.Completed(FileOperationResult(1, 0))
            },
        )

        assertTrue(runner.prepareDelete(listOf(source)))
        advanceUntilIdle()

        assertEquals(
            FileOperationState.WaitingForLargeOperationConfirmation(
                type = FileOperationType.DELETE,
                itemCount = LARGE_OPERATION_ITEM_THRESHOLD,
                totalBytes = 12,
            ),
            runner.state.value,
        )
        assertEquals(0, engineCalls)

        assertTrue(runner.confirmLargeOperation())
        advanceUntilIdle()

        assertTrue(runner.state.value is FileOperationState.WaitingForDeleteConfirmation)
        assertEquals(0, engineCalls)
    }

    @Test fun warningCancelReturnsIdleWithoutTerminalResultOrEngineCall() = runTest {
        var engineCalls = 0
        val runner = runner(
            scanner = OperationScannerGateway { _, _ ->
                ScanOutcome.Ready(LARGE_OPERATION_ITEM_THRESHOLD, 8)
            },
            engine = OperationEngineGateway { _, _, _, _, _ ->
                engineCalls += 1
                EngineOutcome.Completed(FileOperationResult(1, 0))
            },
        )

        assertTrue(runner.start(FileOperationType.COPY, listOf(source), target))
        advanceUntilIdle()

        assertTrue(runner.state.value is FileOperationState.WaitingForLargeOperationConfirmation)

        runner.cancel()
        advanceUntilIdle()

        assertEquals(FileOperationState.Idle, runner.state.value)
        assertEquals(0, engineCalls)
    }

    @Test fun smallTransferKeepsDirectExecutionPath() = runTest {
        var engineCalls = 0
        val runner = runner(
            scanner = OperationScannerGateway { _, _ -> ScanOutcome.Ready(1, 4) },
            engine = OperationEngineGateway { _, _, _, _, _ ->
                engineCalls += 1
                EngineOutcome.Completed(FileOperationResult(1, 0))
            },
        )

        assertTrue(runner.start(FileOperationType.COPY, listOf(source), target))
        advanceUntilIdle()

        assertEquals(1, engineCalls)
        assertTrue(runner.state.value is FileOperationState.Succeeded)
    }

    @Test fun deleteCancelBeforeConfirmationReturnsIdleWithoutDeleting() = runTest {
        var engineCalls = 0
        val runner = runner(
            scanner = OperationScannerGateway { _, _ -> ScanOutcome.Ready(1, 1) },
            engine = OperationEngineGateway { _, _, _, _, _ ->
                engineCalls += 1
                EngineOutcome.Completed(FileOperationResult(1, 0))
            },
        )

        runner.prepareDelete(listOf(source))
        advanceUntilIdle()
        runner.cancel()
        advanceUntilIdle()

        assertEquals(FileOperationState.Idle, runner.state.value)
        assertEquals(0, engineCalls)
    }

    @Test fun deleteConfirmationStartsEngineAndMapsResult() = runTest {
        val runner = runner(
            scanner = OperationScannerGateway { _, _ -> ScanOutcome.Ready(1, 1) },
            engine = OperationEngineGateway { _, _, _, _, _ ->
                EngineOutcome.Partial(FileOperationResult(0, 1))
            },
        )

        runner.prepareDelete(listOf(source))
        advanceUntilIdle()
        assertTrue(runner.confirmDelete())
        advanceUntilIdle()

        assertTrue(runner.state.value is FileOperationState.PartiallySucceeded)
        assertEquals(
            FileOperationType.DELETE,
            (runner.state.value as FileOperationState.PartiallySucceeded).type,
        )
    }

    @Test fun secondTaskIsRejectedDuringDeleteConfirmation() = runTest {
        val runner = runner(
            scanner = OperationScannerGateway { _, _ -> ScanOutcome.Ready(1, 1) },
        )

        assertTrue(runner.prepareDelete(listOf(source)))
        advanceUntilIdle()

        assertFalse(runner.start(FileOperationType.COPY, listOf(source), target))
    }

    @Test fun deleteCancelKeepsTaskActiveUntilConfirmationCleanupCompletes() = runTest {
        val copyScanGate = CompletableDeferred<Unit>()
        val runner = runner(scanner = OperationScannerGateway { request, token ->
            if (request.type == FileOperationType.DELETE) {
                ready
            } else {
                copyScanGate.await()
                token.throwIfRequested()
                ready
            }
        })

        assertTrue(runner.prepareDelete(listOf(source)))
        advanceUntilIdle()

        runner.cancel()

        assertTrue(runner.state.value is FileOperationState.WaitingForDeleteConfirmation)
        assertFalse(runner.start(FileOperationType.COPY, listOf(source), target))

        advanceUntilIdle()

        assertEquals(FileOperationState.Idle, runner.state.value)
        assertTrue(runner.start(FileOperationType.COPY, listOf(source), target))
        runCurrent()
        runner.cancel()
        copyScanGate.complete(Unit)
        advanceUntilIdle()
        assertTrue(runner.state.value is FileOperationState.Cancelled)
    }

    @Test fun deletePreScanCancellationReturnsIdleWithoutCallingEngine() = runTest {
        val scanGate = CompletableDeferred<Unit>()
        var engineCalls = 0
        val runner = runner(
            scanner = OperationScannerGateway { _, token ->
                scanGate.await()
                token.throwIfRequested()
                ready
            },
            engine = OperationEngineGateway { _, _, _, _, _ ->
                engineCalls += 1
                EngineOutcome.Completed(FileOperationResult(1, 0))
            },
        )

        assertTrue(runner.prepareDelete(listOf(source)))
        runCurrent()
        runner.cancel()
        scanGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(FileOperationState.Idle, runner.state.value)
        assertEquals(0, engineCalls)
    }

    @Test fun deleteCancelDuringExecutionMapsToCancelled() = runTest {
        val engine = OperationEngineGateway { _, _, token, _, _ ->
            while (!token.isRequested()) delay(1)
            EngineOutcome.Cancelled(FileOperationResult(0, 1))
        }
        val runner = runner(
            scanner = OperationScannerGateway { _, _ -> ScanOutcome.Ready(2, 2) },
            engine = engine,
        )

        runner.prepareDelete(listOf(source))
        advanceUntilIdle()
        runner.confirmDelete()
        runCurrent()
        runner.cancel()
        assertTrue(runner.state.value is FileOperationState.Cancelling)
        advanceUntilIdle()

        val state = runner.state.value as FileOperationState.Cancelled
        assertEquals(FileOperationType.DELETE, state.type)
    }

    @Test fun deleteScannerExceptionMapsToFailed() = runTest {
        val runner = runner(
            scanner = OperationScannerGateway { _, _ -> error("scanner failed") },
        )

        assertTrue(runner.prepareDelete(listOf(source)))
        advanceUntilIdle()

        val state = runner.state.value as FileOperationState.Failed
        assertEquals(FileOperationType.DELETE, state.type)
        assertEquals(1, state.result.failedItems)
        assertEquals("scanner failed", state.result.failures.single().technicalMessage)
    }

    @Test fun loggerFailureDoesNotPreventFailureState() = runTest {
        val runner = FileOperationRunner(
            scanner = OperationScannerGateway { _, _ -> error("scanner failed") },
            taskIdFactory = { "task" },
            errorLogger = { _, _ -> error("logger failed") },
            dispatcher = mainDispatcherRule.dispatcher,
        )

        assertTrue(runner.start(FileOperationType.COPY, listOf(source), target))
        advanceUntilIdle()

        assertTrue(runner.state.value is FileOperationState.Failed)
    }

    private fun runner(
        scanner: OperationScannerGateway = OperationScannerGateway { _, _ -> ready },
        engine: OperationEngineGateway = engine { EngineOutcome.Completed(FileOperationResult(1, 0)) },
    ) = FileOperationRunner(scanner, engine, { "task" }, dispatcher = mainDispatcherRule.dispatcher)

    private fun engine(block: suspend () -> EngineOutcome) = OperationEngineGateway { _, _, _, progress, _ ->
        progress(OperationProgress("source.txt", 0, 1, 0, 4))
        block()
    }
}
