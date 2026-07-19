package com.example.watchfiles.text

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.charset.StandardCharsets
import java.nio.file.Files

class TextFileReaderTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val reader = TextFileReader()

    @Test
    fun txtAndKnownPlainTextExtensionsAreSupported() = runTest {
        val txt = temporaryFolder.newFile("note.txt").toPath()
        val markdown = temporaryFolder.newFile("note.md").toPath()
        Files.write(txt, "plain".toByteArray(StandardCharsets.UTF_8))
        Files.write(markdown, "# plain".toByteArray(StandardCharsets.UTF_8))

        assertTrue(isSimpleTextPath(txt))
        assertTrue(isSimpleTextPath(markdown))
        assertTrue(reader.open(txt) is TextOpenResult.Ready)
        assertTrue(reader.open(markdown) is TextOpenResult.Ready)
    }

    @Test
    fun unknownExtensionIsNotGuessedAsEditableText() = runTest {
        val binary = temporaryFolder.newFile("payload.bin").toPath()
        Files.write(binary, byteArrayOf(0x41, 0x00, 0x42))

        assertFalse(isSimpleTextPath(binary))
        assertTrue(reader.open(binary) is TextOpenResult.Unsupported)
    }

    @Test
    fun fileOverViewLimitReturnsUnsupported() = runTest {
        val path = temporaryFolder.newFile("large.txt").toPath()
        Files.write(path, ByteArray((MAX_VIEWABLE_TEXT_BYTES + 1L).toInt()) { 'a'.code.toByte() })

        val result = reader.open(path)

        assertTrue(result is TextOpenResult.Unsupported)
    }

    @Test
    fun fileOverEditLimitIsReadyButReadOnly() = runTest {
        val path = temporaryFolder.newFile("large-edit.txt").toPath()
        Files.write(path, ByteArray((MAX_EDITABLE_TEXT_BYTES + 1L).toInt()) { 'a'.code.toByte() })

        val result = reader.open(path) as TextOpenResult.Ready

        assertFalse(result.editable)
        assertTrue(result.editDisabledReason.orEmpty().isNotBlank())
    }

    @Test
    fun emptyFileProducesEmptyFirstSegment() = runTest {
        val path = temporaryFolder.newFile("empty.txt").toPath()

        val result = reader.open(path) as TextOpenResult.Ready

        assertEquals(0L, result.firstSegment.startByte)
        assertEquals(0L, result.firstSegment.endByte)
        assertEquals("", result.firstSegment.text)
        assertFalse(result.firstSegment.hasPrevious)
        assertFalse(result.firstSegment.hasNext)
    }

    @Test
    fun segmentPreservesLineEndingsAndEmptyLines() = runTest {
        val path = temporaryFolder.newFile("lines.txt").toPath()
        val content = "first\r\n\r\nthird\n"
        Files.write(path, content.toByteArray(StandardCharsets.UTF_8))

        val result = reader.open(path) as TextOpenResult.Ready

        assertEquals(content, result.firstSegment.text)
    }

    @Test
    fun segmentNeverSplitsUtf8Character() = runTest {
        val path = temporaryFolder.newFile("boundary.txt").toPath()
        val prefix = "a".repeat(TEXT_PAGE_BYTES - 2)
        Files.write(path, (prefix + "界").toByteArray(StandardCharsets.UTF_8))

        val first = reader.open(path) as TextOpenResult.Ready
        val second = reader.readSegment(path, first.firstSegment.endByte)

        assertFalse(first.firstSegment.text.contains('\uFFFD'))
        assertEquals("界", second.text)
        assertEquals(first.firstSegment.endByte, second.startByte)
    }

    @Test
    fun invalidUtf8ReturnsUnsupportedInsteadOfReplacementCharacters() = runTest {
        val path = temporaryFolder.newFile("invalid.txt").toPath()
        Files.write(path, byteArrayOf(0x61, 0xC3.toByte(), 0x28))

        val result = reader.open(path)

        assertTrue(result is TextOpenResult.Unsupported)
    }

    @Test
    fun readEditablePreservesUtf8TextAndNewlines() = runTest {
        val path = temporaryFolder.newFile("editable.txt").toPath()
        val content = "第一行\r\n第二行\n"
        Files.write(path, content.toByteArray(StandardCharsets.UTF_8))

        assertEquals(content, reader.readEditable(path))
    }
}
