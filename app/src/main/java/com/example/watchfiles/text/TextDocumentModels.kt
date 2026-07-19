package com.example.watchfiles.text

import java.nio.file.Path

data class TextSegment(
    val startByte: Long,
    val endByte: Long,
    val text: String,
    val hasPrevious: Boolean,
    val hasNext: Boolean,
)

interface TextReaderGateway {
    suspend fun open(path: Path): TextOpenResult
    suspend fun readSegment(path: Path, startByte: Long): TextSegment
    suspend fun readEditable(path: Path): String
}

fun interface TextDigestProvider {
    suspend fun digest(path: Path): String
}

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

data class TextWriteRequest(
    val source: Path,
    val currentDirectory: Path,
    val targetName: String,
    val content: String,
    val expectedSourceDigest: String,
    val overwriteConfirmed: Boolean,
)

enum class TextWriteFaultPoint {
    CREATE_TEMP,
    WRITE_TEMP,
    FORCE_TEMP,
    MOVE_TARGET_TO_BACKUP,
    MOVE_TEMP_TO_TARGET,
    VERIFY_TARGET,
}

fun interface TextWriteFaultInjector {
    fun throwIfRequested(point: TextWriteFaultPoint)
}

sealed interface TextWriteResult {
    data class Success(val target: Path) : TextWriteResult
    data class Failure(val userMessage: String, val technicalMessage: String? = null) : TextWriteResult
    data object Cancelled : TextWriteResult
}

interface TextWriteGateway {
    suspend fun save(request: TextWriteRequest): TextWriteResult
    suspend fun recover(): List<TextRecoveryResult>
}

enum class TextTransactionPhase { STAGED, BACKED_UP, PUBLISHED, CLEANED }

data class TextTransactionRecord(
    val id: String,
    val target: Path,
    val temp: Path,
    val backup: Path?,
    val expectedTargetDigest: String,
    val phase: TextTransactionPhase,
)

sealed interface TextRecoveryResult {
    data class Recovered(val id: String) : TextRecoveryResult
    data class Failed(val id: String, val message: String) : TextRecoveryResult
}
