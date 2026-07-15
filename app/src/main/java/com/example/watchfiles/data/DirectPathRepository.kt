package com.example.watchfiles.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale

class DirectPathRepository {
    suspend fun list(path: Path): List<FileEntry> = withContext(Dispatchers.IO) {
        require(Files.isDirectory(path)) { "不是文件夹：$path" }

        Files.newDirectoryStream(path).use { directory ->
            directory.map { child -> child.toEntry() }
        }.sortedWith(
            compareBy<FileEntry>({ !it.isDirectory }, { it.name.lowercase(Locale.ROOT) })
        )
    }

    private fun Path.toEntry(): FileEntry {
        val attributes = runCatching {
            Files.readAttributes(
                this,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
        }.getOrNull()

        val directory = attributes?.isDirectory ?: Files.isDirectory(this)
        val file = toFile()

        return FileEntry(
            path = this,
            name = fileName?.toString().orEmpty().ifBlank { toString() },
            isDirectory = directory,
            sizeBytes = if (directory) null else attributes?.size(),
            modifiedAtMillis = attributes?.lastModifiedTime()?.toMillis(),
            isHidden = fileName?.toString()?.startsWith('.') == true,
            isReadable = file.canRead(),
            isWritable = file.canWrite(),
        )
    }
}
