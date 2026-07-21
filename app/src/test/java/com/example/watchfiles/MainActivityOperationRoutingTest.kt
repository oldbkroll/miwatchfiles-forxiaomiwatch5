package com.example.watchfiles

import com.example.watchfiles.fileops.DeletePreview
import com.example.watchfiles.fileops.FileConflict
import com.example.watchfiles.fileops.FileOperationResult
import com.example.watchfiles.fileops.FileOperationState
import com.example.watchfiles.fileops.FileOperationType
import com.example.watchfiles.fileops.OperationProgress
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MainActivityOperationRoutingTest {
    private val progress = OperationProgress(
        currentName = "current.txt",
        processedItems = 1,
        totalItems = 2,
        processedBytes = 4,
        totalBytes = 8,
    )
    private val result = FileOperationResult(completedItems = 1, failedItems = 0)

    @Test
    fun routesDeleteConfirmationStateToDeleteConfirmationScreen() {
        val state = FileOperationState.WaitingForDeleteConfirmation(
            DeletePreview(topLevelCount = 1, itemCount = 2, totalBytes = 8),
        )

        assertEquals("DELETE_CONFIRMATION", operationScreenForState(state)?.name)
    }

    @Test
    fun routesActiveOperationStatesToFileOperationScreen() {
        val conflict = FileConflict(Paths.get("/source.txt"), Paths.get("/target.txt"))
        val states = listOf(
            FileOperationState.Scanning(FileOperationType.COPY),
            FileOperationState.Running(FileOperationType.MOVE, progress),
            FileOperationState.WaitingForReplacement(FileOperationType.COPY, conflict, progress),
            FileOperationState.Cancelling(FileOperationType.DELETE, progress),
        )

        states.forEach { state ->
            assertEquals("FILE_OPERATION", operationScreenForState(state)?.name)
        }
    }

    @Test
    fun leavesIdleAndTerminalStatesUnrouted() {
        val states = listOf(
            FileOperationState.Idle,
            FileOperationState.Succeeded(FileOperationType.COPY, result),
            FileOperationState.PartiallySucceeded(FileOperationType.MOVE, result),
            FileOperationState.Failed(FileOperationType.DELETE, result),
            FileOperationState.Cancelled(FileOperationType.COPY, result),
        )

        states.forEach { state ->
            assertNull(operationScreenForState(state))
        }
    }
}
