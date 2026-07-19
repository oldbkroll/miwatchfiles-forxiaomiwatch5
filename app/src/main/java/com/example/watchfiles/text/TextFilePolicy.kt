package com.example.watchfiles.text

import java.nio.file.Path
import java.util.Locale

const val TEXT_PAGE_BYTES: Int = 32 * 1024
const val MAX_VIEWABLE_TEXT_BYTES: Long = 16L * 1024 * 1024
const val MAX_EDITABLE_TEXT_BYTES: Long = 512L * 1024

private val simpleTextExtensions = setOf("txt", "text", "log", "csv", "md")

fun isSimpleTextPath(path: Path): Boolean {
    val extension = path.fileName
        ?.toString()
        ?.substringAfterLast('.', missingDelimiterValue = "")
        ?.lowercase(Locale.ROOT)
        .orEmpty()
    return extension in simpleTextExtensions
}

fun editDisabledReason(sizeBytes: Long): String? = when {
    sizeBytes > MAX_EDITABLE_TEXT_BYTES -> "文件超过 512 KiB，仅支持分段只读"
    else -> null
}
