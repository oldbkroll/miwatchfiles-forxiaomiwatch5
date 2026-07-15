package com.example.watchfiles.fileops

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class FileMutationRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val repository = FileMutationRepository()

    @Test
    fun createDirectoryCreatesExactlyOneRequestedDirectory() = runTest {
        val parent = temporaryFolder.newFolder("parent").toPath()

        val result = repository.createDirectory(parent, "新文件夹")

        val success = result as FileMutationResult.Success
        assertEquals(parent.resolve("新文件夹"), success.path)
        assertTrue(Files.isDirectory(success.path))
    }

    @Test
    fun createDirectoryNeverReusesExistingName() = runTest {
        val parent = temporaryFolder.newFolder("parent-existing").toPath()
        Files.createDirectory(parent.resolve("same"))

        val result = repository.createDirectory(parent, "same")

        assertTrue(result is FileMutationResult.Failure)
        assertTrue(Files.isDirectory(parent.resolve("same")))
    }

    @Test
    fun renameChangesNameWithoutChangingContents() = runTest {
        val parent = temporaryFolder.newFolder("rename").toPath()
        val source = Files.write(parent.resolve("before.txt"), "watchfiles".toByteArray())

        val result = repository.rename(source, "after.txt")

        val success = result as FileMutationResult.Success
        assertEquals("watchfiles", String(Files.readAllBytes(success.path)))
        assertFalse(Files.exists(source))
    }

    @Test
    fun renameNeverOverwritesExistingTarget() = runTest {
        val parent = temporaryFolder.newFolder("rename-conflict").toPath()
        val source = Files.write(parent.resolve("source.txt"), "source".toByteArray())
        val target = Files.write(parent.resolve("target.txt"), "target".toByteArray())

        val result = repository.rename(source, "target.txt")

        assertTrue(result is FileMutationResult.Failure)
        assertEquals("source", String(Files.readAllBytes(source)))
        assertEquals("target", String(Files.readAllBytes(target)))
    }
}
