package com.example.watchfiles.text

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.min

@OptIn(ExperimentalCoroutinesApi::class)
class TextDocumentViewModelTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @get:Rule
    val mainDispatcherRule = com.example.watchfiles.browser.MainDispatcherRule(UnconfinedTestDispatcher())

    @Test
    fun openLoadsFirstSegmentOnIoDispatcher() = runTest(mainDispatcherRule.dispatcher) {
        val path = file("note.txt", "first\nsecond")
        val viewModel = viewModel()

        viewModel.open(path)
        advanceUntilIdle()

        assertEquals(viewModel.state.value.toString(), TextDocumentMode.VIEWING, viewModel.state.value.mode)
        assertEquals("first\nsecond", viewModel.state.value.segment?.text)
    }

    @Test
    fun nextAndPreviousSegmentUpdateOffsets() = runTest(mainDispatcherRule.dispatcher) {
        val path = file("pages.txt", "a".repeat(TEXT_PAGE_BYTES + 100))
        val viewModel = viewModel()

        viewModel.open(path)
        advanceUntilIdle()
        val firstStart = viewModel.state.value.segment?.startByte
        viewModel.nextSegment()
        advanceUntilIdle()
        val secondStart = viewModel.state.value.segment?.startByte
        viewModel.previousSegment()
        advanceUntilIdle()

        assertEquals(0L, firstStart)
        assertTrue(secondStart!! > firstStart!!)
        assertEquals(firstStart, viewModel.state.value.segment?.startByte)
    }

    @Test
    fun oversizedTextStaysReadOnly() = runTest(mainDispatcherRule.dispatcher) {
        val path = file("large.txt", "a".repeat(MAX_EDITABLE_TEXT_BYTES.toInt() + 1))
        val viewModel = viewModel()

        viewModel.open(path)
        advanceUntilIdle()

        assertFalse(viewModel.state.value.editable)
        assertTrue(viewModel.state.value.editDisabledReason.orEmpty().isNotBlank())
    }

    @Test
    fun beginEditingCopiesSmallFileIntoDraft() = runTest(mainDispatcherRule.dispatcher) {
        val path = file("editable.txt", "draft me")
        val viewModel = viewModel()

        viewModel.open(path)
        advanceUntilIdle()
        viewModel.beginEditing()
        advanceUntilIdle()

        assertEquals(TextDocumentMode.EDITING, viewModel.state.value.mode)
        assertEquals("draft me", viewModel.state.value.draft)
    }

    @Test
    fun dirtyBackRequiresDiscardAndDoesNotSave() = runTest(mainDispatcherRule.dispatcher) {
        val path = file("discard.txt", "original")
        val writer = RecordingWriter(TextWriteResult.Success(path))
        val viewModel = viewModel(writer)

        viewModel.open(path)
        advanceUntilIdle()
        viewModel.beginEditing()
        advanceUntilIdle()
        viewModel.updateDraft("changed")
        viewModel.discardChanges()

        assertFalse(viewModel.state.value.isDirty)
        assertEquals(0, writer.calls)
        assertEquals("original", read(path))
    }

    @Test
    fun saveFailureKeepsDraftAndOriginalFile() = runTest(mainDispatcherRule.dispatcher) {
        val path = file("failed-save.txt", "original")
        val writer = RecordingWriter(TextWriteResult.Failure("测试保存失败"))
        val viewModel = viewModel(writer)

        viewModel.open(path)
        advanceUntilIdle()
        viewModel.beginEditing()
        advanceUntilIdle()
        viewModel.updateDraft("changed")
        viewModel.requestOverwriteConfirmation()
        viewModel.confirmSave(overwriteConfirmed = true)
        advanceUntilIdle()

        assertTrue(viewModel.state.value.isDirty)
        assertEquals("changed", viewModel.state.value.draft)
        assertEquals("测试保存失败", viewModel.state.value.message)
        assertEquals("original", read(path))
    }

    @Test
    fun saveSuccessClearsDirtyState() = runTest(mainDispatcherRule.dispatcher) {
        val path = file("successful-save.txt", "original")
        val writer = RecordingWriter(TextWriteResult.Success(path))
        val viewModel = viewModel(writer)

        viewModel.open(path)
        advanceUntilIdle()
        viewModel.beginEditing()
        advanceUntilIdle()
        viewModel.updateDraft("changed")
        viewModel.requestOverwriteConfirmation()
        viewModel.confirmSave(overwriteConfirmed = true)
        advanceUntilIdle()

        assertFalse(viewModel.state.value.isDirty)
        assertEquals(TextDocumentMode.VIEWING, viewModel.state.value.mode)
        assertEquals(1, writer.calls)
    }

    @Test
    fun saveAsUsesCurrentDirectoryOnly() = runTest(mainDispatcherRule.dispatcher) {
        val path = file("save-as.txt", "original")
        val writer = RecordingWriter(TextWriteResult.Success(path))
        val viewModel = viewModel(writer)

        viewModel.open(path)
        advanceUntilIdle()
        viewModel.beginEditing()
        advanceUntilIdle()
        viewModel.updateDraft("changed")
        viewModel.requestSaveAs("../outside.txt")

        assertEquals(0, writer.calls)
        assertTrue(viewModel.state.value.message.orEmpty().isNotBlank())
        assertTrue(viewModel.state.value.isDirty)
    }

    private fun viewModel(writer: TextWriteGateway = RecordingWriter()): TextDocumentViewModel {
        return TextDocumentViewModel(
            reader = ImmediateTextReader(),
            writer = writer,
            digestProvider = TextDigestProvider { "test-digest" },
        )
    }

    private fun file(name: String, content: String): Path {
        val path = temporaryFolder.root.toPath().resolve(name)
        Files.write(path, content.toByteArray(StandardCharsets.UTF_8))
        return path
    }

    private fun read(path: Path): String = String(Files.readAllBytes(path), StandardCharsets.UTF_8)

    private class RecordingWriter(
        private val result: TextWriteResult = TextWriteResult.Success(Path.of("unused")),
    ) : TextWriteGateway {
        var calls: Int = 0
            private set

        override suspend fun save(request: TextWriteRequest): TextWriteResult {
            calls += 1
            return result
        }

        override suspend fun recover(): List<TextRecoveryResult> = emptyList()
    }

    private class ImmediateTextReader : TextReaderGateway {
        override suspend fun open(path: Path): TextOpenResult {
            val bytes = Files.readAllBytes(path)
            val text = String(bytes, StandardCharsets.UTF_8)
            val end = min(TEXT_PAGE_BYTES, bytes.size)
            return TextOpenResult.Ready(
                sizeBytes = bytes.size.toLong(),
                firstSegment = TextSegment(
                    startByte = 0L,
                    endByte = end.toLong(),
                    text = text.substring(0, end),
                    hasPrevious = false,
                    hasNext = end < bytes.size,
                ),
                editable = bytes.size <= MAX_EDITABLE_TEXT_BYTES,
                editDisabledReason = editDisabledReason(bytes.size.toLong()),
            )
        }

        override suspend fun readSegment(path: Path, startByte: Long): TextSegment {
            val bytes = Files.readAllBytes(path)
            val start = startByte.toInt().coerceIn(0, bytes.size)
            val end = min(start + TEXT_PAGE_BYTES, bytes.size)
            return TextSegment(
                startByte = start.toLong(),
                endByte = end.toLong(),
                text = String(bytes.copyOfRange(start, end), StandardCharsets.UTF_8),
                hasPrevious = start > 0,
                hasNext = end < bytes.size,
            )
        }

        override suspend fun readEditable(path: Path): String =
            String(Files.readAllBytes(path), StandardCharsets.UTF_8)
    }
}
