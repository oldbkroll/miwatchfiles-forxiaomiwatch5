package com.example.watchfiles.browser

import com.example.watchfiles.data.DirectoryReader
import com.example.watchfiles.fileops.FileMutationGateway
import com.example.watchfiles.fileops.FileMutationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Paths

class BrowserSelectionTest {
    private val a = Paths.get("/storage/emulated/0/a")
    private val b = Paths.get("/storage/emulated/0/b")

    @Test
    fun longPressBeginsSelectionWithOnePath() {
        val state = BrowserSelection().begin(a)
        assertTrue(state.isActive)
        assertEquals(setOf(a), state.selectedPaths)
    }

    @Test
    fun toggleAddsAndRemovesPathsAndLeavesModeWhenEmpty() {
        val selected = BrowserSelection().begin(a).toggle(b)
        assertEquals(setOf(a, b), selected.selectedPaths)

        val empty = selected.toggle(a).toggle(b)
        assertFalse(empty.isActive)
    }

    @Test
    fun selectAllUsesOnlyPathsPassedByVisibleList() {
        val state = BrowserSelection().selectAll(listOf(a, b))
        assertEquals(setOf(a, b), state.selectedPaths)
        assertTrue(state.clear().selectedPaths.isEmpty())
    }

    @Test
    fun viewModelSelectionCommandsPublishThePureSelectionState() {
        val reader = DirectoryReader { emptyList() }
        val mutations = object : FileMutationGateway {
            override suspend fun createDirectory(parent: java.nio.file.Path, name: String) =
                FileMutationResult.Failure("unused")

            override suspend fun rename(source: java.nio.file.Path, newName: String) =
                FileMutationResult.Failure("unused")
        }
        val viewModel = FileBrowserViewModel(reader, mutations, a.parent)

        viewModel.beginSelection(a)
        viewModel.toggleSelection(b)
        assertEquals(setOf(a, b), viewModel.state.value.selection.selectedPaths)

        viewModel.clearSelection()
        assertFalse(viewModel.state.value.selection.isActive)
    }
}
