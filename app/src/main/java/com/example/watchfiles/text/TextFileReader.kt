package com.example.watchfiles.text

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

class TextFileReader {
    suspend fun open(path: Path): TextOpenResult = withContext(Dispatchers.IO) {
        try {
            if (!isSimpleTextPath(path)) {
                return@withContext TextOpenResult.Unsupported("不是受支持的简单纯文本")
            }
            val sizeBytes = Files.size(path)
            if (sizeBytes > MAX_VIEWABLE_TEXT_BYTES) {
                return@withContext TextOpenResult.Unsupported("文件超过 16 MiB，仅支持用其他应用打开")
            }
            if (sizeBytes <= MAX_EDITABLE_TEXT_BYTES) {
                decodeAll(path)
            }
            val firstSegment = readSegmentInternal(path, sizeBytes, 0L)
            TextOpenResult.Ready(
                sizeBytes = sizeBytes,
                firstSegment = firstSegment,
                editable = sizeBytes <= MAX_EDITABLE_TEXT_BYTES,
                editDisabledReason = editDisabledReason(sizeBytes),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: UnsupportedTextException) {
            TextOpenResult.Unsupported(error.message ?: "编码不支持或文件内容无效")
        } catch (error: IOException) {
            TextOpenResult.Failed("无法读取文本文件", error.message)
        } catch (error: SecurityException) {
            TextOpenResult.Failed("没有权限读取此文件", error.message)
        }
    }

    suspend fun readSegment(path: Path, startByte: Long): TextSegment = withContext(Dispatchers.IO) {
        val sizeBytes = Files.size(path)
        if (!isSimpleTextPath(path)) {
            throw UnsupportedTextException("不是受支持的简单纯文本")
        }
        if (sizeBytes > MAX_VIEWABLE_TEXT_BYTES) {
            throw UnsupportedTextException("文件超过 16 MiB，仅支持用其他应用打开")
        }
        readSegmentInternal(path, sizeBytes, startByte.coerceIn(0L, sizeBytes))
    }

    suspend fun readEditable(path: Path): String = withContext(Dispatchers.IO) {
        if (!isSimpleTextPath(path)) {
            throw UnsupportedTextException("不是受支持的简单纯文本")
        }
        val sizeBytes = Files.size(path)
        require(sizeBytes <= MAX_EDITABLE_TEXT_BYTES) {
            "文件超过 512 KiB，仅支持分段只读"
        }
        decodeAll(path)
    }

    private fun decodeAll(path: Path): String {
        val bytes = Files.readAllBytes(path)
        return decodeStrict(bytes)
    }

    private fun readSegmentInternal(path: Path, sizeBytes: Long, requestedStart: Long): TextSegment {
        if (sizeBytes == 0L) {
            return TextSegment(0L, 0L, "", hasPrevious = false, hasNext = false)
        }

        val start = requestedStart.coerceIn(0L, sizeBytes)
        var candidateEnd = minOf(sizeBytes, start + TEXT_PAGE_BYTES)
        while (true) {
            val bytes = readRange(path, start, candidateEnd)
            val decoded = decodePrefixAtSafeBoundary(bytes)
            if (decoded != null) {
                val end = start + decoded.second
                return TextSegment(
                    startByte = start,
                    endByte = end,
                    text = decoded.first,
                    hasPrevious = start > 0L,
                    hasNext = end < sizeBytes,
                )
            }
            if (candidateEnd >= sizeBytes) {
                throw UnsupportedTextException("编码不支持或文件内容无效")
            }
            candidateEnd = minOf(sizeBytes, candidateEnd + 4L)
        }
    }

    private fun readRange(path: Path, start: Long, end: Long): ByteArray {
        val length = (end - start).toInt()
        val result = ByteArray(length)
        Files.newByteChannel(path, StandardOpenOption.READ).use { channel ->
            channel.position(start)
            var offset = 0
            while (offset < result.size) {
                val read = channel.read(ByteBuffer.wrap(result, offset, result.size - offset))
                if (read <= 0) break
                offset += read
            }
            if (offset != result.size) {
                return result.copyOf(offset)
            }
        }
        return result
    }

    private fun decodePrefixAtSafeBoundary(bytes: ByteArray): Pair<String, Int>? {
        val trimLimit = minOf(3, bytes.size)
        for (trim in 0..trimLimit) {
            val length = bytes.size - trim
            try {
                return decodeStrict(bytes.copyOf(length)) to length
            } catch (_: UnsupportedTextException) {
                // A short suffix may contain a UTF-8 character cut by the page boundary.
            }
        }
        return null
    }

    private fun decodeStrict(bytes: ByteArray): String = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (error: CharacterCodingException) {
        throw UnsupportedTextException("编码不支持或文件内容无效", error)
    }
}

private class UnsupportedTextException(message: String, cause: Throwable? = null) : IOException(message, cause)
