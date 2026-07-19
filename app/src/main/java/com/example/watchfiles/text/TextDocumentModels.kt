package com.example.watchfiles.text

import java.nio.file.Path

data class TextSegment(
    val startByte: Long,
    val endByte: Long,
    val text: String,
    val hasPrevious: Boolean,
    val hasNext: Boolean,
)

sealed interface TextOpenResult {
    data class Ready(
        val sizeBytes: Long,
        val firstSegment: TextSegment,
        val editable: Boolean,
        val editDisabledReason: String?,
    ) : TextOpenResult

    data class Unsupported(val message: String) : TextOpenResult
    data class Failed(val message: String, val technicalMessage: String? = null) : TextOpenResult
}

data class TextDocumentSnapshot(
    val path: Path,
    val sizeBytes: Long,
    val sha256: String,
)
