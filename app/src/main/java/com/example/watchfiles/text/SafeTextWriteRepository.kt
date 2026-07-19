package com.example.watchfiles.text

import com.example.watchfiles.fileops.FileNameRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AccessDeniedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID

class SafeTextWriteRepository(
    private val journal: TextTransactionJournal,
    private val faultInjector: TextWriteFaultInjector = TextWriteFaultInjector { },
) {
    suspend fun save(request: TextWriteRequest): TextWriteResult = withContext(Dispatchers.IO) {
        var record: TextTransactionRecord? = null
        try {
            validateRequest(request)?.let { return@withContext it }

            val target = request.currentDirectory.resolve(request.targetName)
            val targetExists = Files.exists(target, NOFOLLOW_LINKS)
            if (targetExists && !request.overwriteConfirmed) {
                return@withContext TextWriteResult.Failure("已存在同名目标，需要确认覆盖")
            }
            if (targetExists && !Files.isRegularFile(target, NOFOLLOW_LINKS)) {
                return@withContext TextWriteResult.Failure("同名目标不是普通文件，不能覆盖")
            }
            if (targetExists && Files.isSymbolicLink(target)) {
                return@withContext TextWriteResult.Failure("不能覆盖符号链接")
            }

            val id = UUID.randomUUID().toString()
            val temp = request.currentDirectory.resolve(".watchfiles-text-$id.part")
            val backup = if (targetExists) {
                request.currentDirectory.resolve(".watchfiles-text-$id.backup")
            } else {
                null
            }
            val expectedTargetDigest = sha256(request.content.toByteArray(Charsets.UTF_8))
            record = TextTransactionRecord(
                id = id,
                target = target,
                temp = temp,
                backup = backup,
                expectedTargetDigest = expectedTargetDigest,
                phase = TextTransactionPhase.STAGED,
            )
            journal.upsert(record!!)

            faultInjector.throwIfRequested(TextWriteFaultPoint.CREATE_TEMP)
            FileChannel.open(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
                faultInjector.throwIfRequested(TextWriteFaultPoint.WRITE_TEMP)
                writeUtf8(channel, request.content)
                faultInjector.throwIfRequested(TextWriteFaultPoint.FORCE_TEMP)
                channel.force(true)
            }

            if (targetExists) {
                faultInjector.throwIfRequested(TextWriteFaultPoint.MOVE_TARGET_TO_BACKUP)
                Files.move(target, backup!!)
                record = record!!.copy(phase = TextTransactionPhase.BACKED_UP)
                journal.upsert(record!!)
            }

            faultInjector.throwIfRequested(TextWriteFaultPoint.MOVE_TEMP_TO_TARGET)
            Files.move(temp, target)
            record = record!!.copy(phase = TextTransactionPhase.PUBLISHED)
            journal.upsert(record!!)

            faultInjector.throwIfRequested(TextWriteFaultPoint.VERIFY_TARGET)
            if (!Files.exists(target, NOFOLLOW_LINKS) || sha256(target) != expectedTargetDigest) {
                throw IOException("写入后的目标摘要不匹配")
            }

            cleanupPublished(record!!)
            TextWriteResult.Success(target)
        } catch (error: CancellationException) {
            record?.let { rollback(it) }
            TextWriteResult.Cancelled
        } catch (error: FileAlreadyExistsException) {
            record?.let { rollback(it) }
            TextWriteResult.Failure("目标在保存过程中已存在", error.message)
        } catch (error: NoSuchFileException) {
            record?.let { rollback(it) }
            TextWriteResult.Failure("源文件或目标目录已不存在", error.message)
        } catch (error: AccessDeniedException) {
            record?.let { rollback(it) }
            TextWriteResult.Failure("没有权限保存文本", error.message)
        } catch (error: SecurityException) {
            record?.let { rollback(it) }
            TextWriteResult.Failure("没有权限保存文本", error.message)
        } catch (error: Exception) {
            record?.let { rollback(it) }
            TextWriteResult.Failure("文本保存失败", error.message ?: error.javaClass.simpleName)
        }
    }

    suspend fun recover(): List<TextRecoveryResult> = withContext(Dispatchers.IO) {
        journal.list().map { record -> recoverRecord(record) }
    }

    private fun validateRequest(request: TextWriteRequest): TextWriteResult.Failure? {
        val validation = FileNameRules.validate(request.targetName)
        if (validation is com.example.watchfiles.fileops.FileNameValidation.Invalid) {
            return TextWriteResult.Failure(validation.message)
        }
        val currentDirectory = request.currentDirectory.toAbsolutePath().normalize()
        val source = request.source.toAbsolutePath().normalize()
        if (source.parent != currentDirectory) {
            return TextWriteResult.Failure("只能写入当前文件所在目录")
        }
        if (!Files.isDirectory(currentDirectory, NOFOLLOW_LINKS)) {
            return TextWriteResult.Failure("当前目录不可用")
        }
        if (Files.isSymbolicLink(source) || !Files.isRegularFile(source, NOFOLLOW_LINKS)) {
            return TextWriteResult.Failure("源文件不是可安全编辑的普通文件")
        }
        if (sha256(source) != request.expectedSourceDigest) {
            return TextWriteResult.Failure("源文件已发生变化，请重新打开")
        }
        return null
    }

    private fun writeUtf8(channel: FileChannel, content: String) {
        val bytes = content.toByteArray(Charsets.UTF_8)
        var offset = 0
        while (offset < bytes.size) {
            offset += channel.write(ByteBuffer.wrap(bytes, offset, bytes.size - offset))
        }
    }

    private fun rollback(record: TextTransactionRecord) {
        runCatching { Files.deleteIfExists(record.temp) }
        val backup = record.backup
        if (backup == null) {
            if (record.phase == TextTransactionPhase.PUBLISHED) {
                runCatching { Files.deleteIfExists(record.target) }
            }
        } else {
            if (record.phase == TextTransactionPhase.PUBLISHED) {
                runCatching { Files.deleteIfExists(record.target) }
            }
            if (Files.exists(backup, NOFOLLOW_LINKS) && !Files.exists(record.target, NOFOLLOW_LINKS)) {
                runCatching { Files.move(backup, record.target) }
            }
        }
        if (!Files.exists(backup ?: record.temp, NOFOLLOW_LINKS)) {
            journal.remove(record.id)
        }
    }

    private fun cleanupPublished(record: TextTransactionRecord) {
        record.backup?.let { Files.deleteIfExists(it) }
        Files.deleteIfExists(record.temp)
        journal.remove(record.id)
    }

    private fun recoverRecord(record: TextTransactionRecord): TextRecoveryResult {
        return try {
            when (record.phase) {
                TextTransactionPhase.STAGED -> {
                    Files.deleteIfExists(record.temp)
                    journal.remove(record.id)
                    TextRecoveryResult.Recovered(record.id)
                }
                TextTransactionPhase.BACKED_UP -> {
                    when {
                        !Files.exists(record.target, NOFOLLOW_LINKS) && record.backup != null -> {
                            Files.move(record.backup, record.target)
                            Files.deleteIfExists(record.temp)
                            journal.remove(record.id)
                            TextRecoveryResult.Recovered(record.id)
                        }
                        Files.exists(record.target, NOFOLLOW_LINKS) &&
                            sha256(record.target) == record.expectedTargetDigest -> {
                            record.backup?.let { Files.deleteIfExists(it) }
                            Files.deleteIfExists(record.temp)
                            journal.remove(record.id)
                            TextRecoveryResult.Recovered(record.id)
                        }
                        else -> TextRecoveryResult.Failed(record.id, "未能安全判断文本事务状态")
                    }
                }
                TextTransactionPhase.PUBLISHED -> {
                    if (Files.exists(record.target, NOFOLLOW_LINKS) &&
                        sha256(record.target) == record.expectedTargetDigest
                    ) {
                        record.backup?.let { Files.deleteIfExists(it) }
                        Files.deleteIfExists(record.temp)
                        journal.remove(record.id)
                        TextRecoveryResult.Recovered(record.id)
                    } else if (record.backup != null && Files.exists(record.backup, NOFOLLOW_LINKS)) {
                        Files.deleteIfExists(record.target)
                        Files.move(record.backup, record.target)
                        Files.deleteIfExists(record.temp)
                        journal.remove(record.id)
                        TextRecoveryResult.Recovered(record.id)
                    } else {
                        TextRecoveryResult.Failed(record.id, "未能安全恢复文本事务")
                    }
                }
                TextTransactionPhase.CLEANED -> {
                    Files.deleteIfExists(record.temp)
                    record.backup?.let { Files.deleteIfExists(it) }
                    journal.remove(record.id)
                    TextRecoveryResult.Recovered(record.id)
                }
            }
        } catch (error: Exception) {
            TextRecoveryResult.Failed(record.id, error.message ?: "文本事务恢复失败")
        }
    }

    private fun sha256(path: Path): String = sha256(Files.readAllBytes(path))

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
