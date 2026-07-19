package com.example.watchfiles.text

import kotlinx.coroutines.CancellationException
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
import java.security.MessageDigest

class SafeTextWriteRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun overwriteRequiresExplicitConfirmation() = runTest {
        val source = file("source.txt", "old")
        val result = repository().save(request(source, "source.txt", "new", overwriteConfirmed = false))

        assertTrue(result is TextWriteResult.Failure)
        assertEquals("old", read(source))
    }

    @Test
    fun overwriteKeepsTargetContentUntilPublish() = runTest {
        val source = file("source.txt", "old")
        val result = repository().save(request(source, "source.txt", "new", overwriteConfirmed = true))

        assertTrue(result is TextWriteResult.Success)
        assertEquals("new", read(source))
        assertOwnedTempsCleared(source.parent!!)
    }

    @Test
    fun saveAsCreatesTargetInCurrentDirectoryAndKeepsSource() = runTest {
        val source = file("source.txt", "old")
        val result = repository().save(request(source, "copy.txt", "new", overwriteConfirmed = false))

        assertTrue(result is TextWriteResult.Success)
        assertEquals("old", read(source))
        assertEquals("new", read(source.parent!!.resolve("copy.txt")))
    }

    @Test
    fun saveAsRejectsPathSeparatorAndParentEscape() = runTest {
        val source = file("source.txt", "old")

        val slash = repository().save(request(source, "sub/name.txt", "new", overwriteConfirmed = false))
        val parent = repository().save(request(source, "../escape.txt", "new", overwriteConfirmed = false))

        assertTrue(slash is TextWriteResult.Failure)
        assertTrue(parent is TextWriteResult.Failure)
        assertEquals("old", read(source))
    }

    @Test
    fun existingSaveAsTargetRequiresConfirmation() = runTest {
        val source = file("source.txt", "source")
        val target = file("existing.txt", "target")

        val result = repository().save(request(source, target.fileName.toString(), "new", overwriteConfirmed = false))

        assertTrue(result is TextWriteResult.Failure)
        assertEquals("source", read(source))
        assertEquals("target", read(target))
    }

    @Test
    fun sourceDigestChangeRejectsStaleOverwrite() = runTest {
        val source = file("source.txt", "old")
        val staleRequest = request(source, "source.txt", "new", overwriteConfirmed = true)
        Files.write(source, bytes("changed-externally"))

        val result = repository().save(staleRequest)

        assertTrue(result is TextWriteResult.Failure)
        assertEquals("changed-externally", read(source))
    }

    @Test
    fun tempWriteFailureLeavesOriginalBytesAndNoPartFile() = runTest {
        val source = file("source.txt", "old")
        val result = repository(TextWriteFaultPoint.WRITE_TEMP).save(
            request(source, "source.txt", "new", overwriteConfirmed = true),
        )

        assertTrue(result is TextWriteResult.Failure)
        assertEquals("old", read(source))
        assertOwnedTempsCleared(source.parent!!)
    }

    @Test
    fun forceFailureLeavesOriginalBytesAndNoPartFile() = runTest {
        val source = file("source.txt", "old")
        val result = repository(TextWriteFaultPoint.FORCE_TEMP).save(
            request(source, "source.txt", "new", overwriteConfirmed = true),
        )

        assertTrue(result is TextWriteResult.Failure)
        assertEquals("old", read(source))
        assertOwnedTempsCleared(source.parent!!)
    }

    @Test
    fun backupFailureLeavesOriginalBytes() = runTest {
        val source = file("source.txt", "old")
        val result = repository(TextWriteFaultPoint.MOVE_TARGET_TO_BACKUP).save(
            request(source, "source.txt", "new", overwriteConfirmed = true),
        )

        assertTrue(result is TextWriteResult.Failure)
        assertEquals("old", read(source))
        assertOwnedTempsCleared(source.parent!!)
    }

    @Test
    fun publishFailureRestoresOriginalBytes() = runTest {
        val source = file("source.txt", "old")
        val result = repository(TextWriteFaultPoint.MOVE_TEMP_TO_TARGET).save(
            request(source, "source.txt", "new", overwriteConfirmed = true),
        )

        assertTrue(result is TextWriteResult.Failure)
        assertEquals("old", read(source))
        assertOwnedTempsCleared(source.parent!!)
    }

    @Test
    fun cancellationBeforePublishLeavesOriginalBytes() = runTest {
        val source = file("source.txt", "old")
        val journal = MemoryTextTransactionJournal()
        val result = SafeTextWriteRepository(
            journal = journal,
            faultInjector = TextWriteFaultInjector { point ->
                if (point == TextWriteFaultPoint.MOVE_TEMP_TO_TARGET) {
                    throw CancellationException("test cancellation")
                }
            },
        ).save(request(source, "source.txt", "new", overwriteConfirmed = true))

        assertTrue(result is TextWriteResult.Cancelled)
        assertEquals("old", read(source))
        assertOwnedTempsCleared(source.parent!!)
    }

    private fun repository(faultPoint: TextWriteFaultPoint? = null): SafeTextWriteRepository {
        return SafeTextWriteRepository(
            journal = MemoryTextTransactionJournal(),
            faultInjector = TextWriteFaultInjector { point ->
                if (point == faultPoint) throw IllegalStateException("injected $point")
            },
        )
    }

    private fun request(
        source: Path,
        targetName: String,
        content: String,
        overwriteConfirmed: Boolean,
    ) = TextWriteRequest(
        source = source,
        currentDirectory = source.parent!!,
        targetName = targetName,
        content = content,
        expectedSourceDigest = sha256(source),
        overwriteConfirmed = overwriteConfirmed,
    )

    private fun file(name: String, content: String): Path {
        val path = temporaryFolder.root.toPath().resolve(name)
        Files.write(path, bytes(content))
        return path
    }

    private fun read(path: Path): String = String(Files.readAllBytes(path), StandardCharsets.UTF_8)

    private fun bytes(value: String): ByteArray = value.toByteArray(StandardCharsets.UTF_8)

    private fun sha256(path: Path): String = MessageDigest.getInstance("SHA-256")
        .digest(Files.readAllBytes(path))
        .joinToString("") { "%02x".format(it) }

    private fun assertOwnedTempsCleared(parent: Path) {
        Files.list(parent).use { paths ->
            assertFalse(paths.anyMatch { path ->
                val name = path.fileName.toString()
                name.startsWith(".watchfiles-text-") &&
                    (name.endsWith(".part") || name.endsWith(".backup"))
            })
        }
    }

    private class MemoryTextTransactionJournal : TextTransactionJournal {
        private val records = linkedMapOf<String, TextTransactionRecord>()

        override fun upsert(record: TextTransactionRecord) {
            records[record.id] = record
        }

        override fun remove(id: String) {
            records.remove(id)
        }

        override fun list(): List<TextTransactionRecord> = records.values.toList()
    }
}
