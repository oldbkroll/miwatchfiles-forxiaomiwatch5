package com.example.watchfiles.fileops

import com.example.watchfiles.browser.MainDispatcherRule
import com.example.watchfiles.data.DirectoryReader
import com.example.watchfiles.data.FileEntry
import java.nio.file.Path
import java.nio.file.Paths
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TargetDirectoryViewModelTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()
    private val root = Paths.get("/storage/emulated/0")

    @Test fun openFiltersFilesAndPreservesReaderOrder() = runTest {
        val directoryB = entry(root.resolve("B"), true)
        val file = entry(root.resolve("file.txt"), false)
        val directoryA = entry(root.resolve("A"), true)
        val viewModel = TargetDirectoryViewModel(DirectoryReader { listOf(directoryB, file, directoryA) }, root)
        viewModel.open(root)
        advanceUntilIdle()
        assertEquals(listOf(directoryB, directoryA), viewModel.state.value.directories)
    }

    @Test fun navigateUpNeverEscapesStorageRoot() = runTest {
        val child = root.resolve("Download/Test")
        val viewModel = TargetDirectoryViewModel(DirectoryReader { emptyList() }, root, child)
        viewModel.open(child)
        advanceUntilIdle()
        assertTrue(viewModel.navigateUp())
        advanceUntilIdle()
        assertEquals(root.resolve("Download").toAbsolutePath().normalize(), viewModel.state.value.currentPath)
        viewModel.open(root)
        advanceUntilIdle()
        assertFalse(viewModel.navigateUp())
        assertEquals(root.toAbsolutePath().normalize(), viewModel.state.value.currentPath)
    }

    @Test fun openRejectsPathsOutsideStorageRoot() = runTest {
        val viewModel = TargetDirectoryViewModel(DirectoryReader { emptyList() }, root)

        viewModel.open(Paths.get("/storage/emulated/elsewhere"))
        advanceUntilIdle()

        assertEquals(root.toAbsolutePath().normalize(), viewModel.state.value.currentPath)
        assertEquals("目标目录超出内部存储范围", viewModel.state.value.errorMessage)
    }

    private fun entry(path: Path, directory: Boolean) = FileEntry(path, path.fileName.toString(), directory, null, null, false, true, true)
}
