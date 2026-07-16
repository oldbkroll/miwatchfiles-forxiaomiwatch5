package com.example.watchfiles.browser

import com.example.watchfiles.data.DirectoryReader
import com.example.watchfiles.data.FileEntry
import com.example.watchfiles.fileops.FileMutationGateway
import com.example.watchfiles.fileops.FileMutationResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.nio.file.Path
import java.nio.file.Paths

@OptIn(ExperimentalCoroutinesApi::class)
class FileBrowserViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val root = Paths.get("/storage/emulated/0/Download/Test")
    private val reader = DirectoryReader { emptyList() }

    @Test
    fun createDirectoryPublishesSuccessAndClearsSelection() = runTest {
        val created = root.resolve("New")
        val mutations = FakeMutationGateway(createResult = FileMutationResult.Success(created))
        val viewModel = FileBrowserViewModel(reader, mutations, root)
        viewModel.beginSelection(root.resolve("old.txt"))

        viewModel.createDirectory("New")
        advanceUntilIdle()

        assertEquals(BrowserMutationState.Succeeded(created), viewModel.state.value.mutation)
        assertTrue(viewModel.state.value.selection.selectedPaths.isEmpty())
    }

    @Test
    fun renamePublishesUserFacingFailureWithoutRefreshingAwayTheSource() = runTest {
        val failure = FileMutationResult.Failure("已存在同名项目", "target exists")
        val mutations = FakeMutationGateway(renameResult = failure)
        val viewModel = FileBrowserViewModel(reader, mutations, root)

        viewModel.rename(root.resolve("old.txt"), "same.txt")
        advanceUntilIdle()

        assertEquals(
            BrowserMutationState.Failed("已存在同名项目", "target exists"),
            viewModel.state.value.mutation,
        )
    }

    @Test
    fun refreshAfterOperationReloadsEntriesAndClearsSelection() = runTest {
        var entries = listOf(fileEntry(root.resolve("old.txt")))
        val viewModel = FileBrowserViewModel(DirectoryReader { entries }, FakeMutationGateway(), root)
        viewModel.open(root)
        advanceUntilIdle()
        viewModel.beginSelection(entries.single().path)
        entries = listOf(fileEntry(root.resolve("copied.txt")))

        viewModel.refreshAfterOperation()
        advanceUntilIdle()

        assertEquals(entries, viewModel.state.value.entries)
        assertTrue(viewModel.state.value.selection.selectedPaths.isEmpty())
    }

    private fun fileEntry(path: Path) = FileEntry(path, path.fileName.toString(), false, 1, null, false, true, true)

    private class FakeMutationGateway(
        private val createResult: FileMutationResult = FileMutationResult.Failure("unused"),
        private val renameResult: FileMutationResult = FileMutationResult.Failure("unused"),
    ) : FileMutationGateway {
        override suspend fun createDirectory(parent: Path, name: String) = createResult

        override suspend fun rename(source: Path, newName: String) = renameResult
    }
}
