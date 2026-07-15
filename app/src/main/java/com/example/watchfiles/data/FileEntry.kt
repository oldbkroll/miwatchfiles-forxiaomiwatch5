package com.example.watchfiles.data

import java.nio.file.Path

data class FileEntry(
    val path: Path,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long?,
    val modifiedAtMillis: Long?,
    val isHidden: Boolean,
    val isReadable: Boolean,
    val isWritable: Boolean,
)
