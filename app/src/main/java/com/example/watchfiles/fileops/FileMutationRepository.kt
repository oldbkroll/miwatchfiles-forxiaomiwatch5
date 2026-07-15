package com.example.watchfiles.fileops

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path

sealed interface FileMutationResult {
    data class Success(val path: Path) : FileMutationResult
    data class Failure(
        val userMessage: String,
        val technicalMessage: String? = null,
    ) : FileMutationResult
}

interface FileMutationGateway {
    suspend fun createDirectory(parent: Path, name: String): FileMutationResult
    suspend fun rename(source: Path, newName: String): FileMutationResult
}

class FileMutationRepository : FileMutationGateway {
    override suspend fun createDirectory(parent: Path, name: String): FileMutationResult =
        mutate(name) { Files.createDirectory(parent.resolve(name)) }

    override suspend fun rename(source: Path, newName: String): FileMutationResult =
        mutate(newName) {
            val parent = source.parent ?: throw IllegalArgumentException("源项目没有父目录")
            val target = parent.resolve(newName)
            if (source == target) return@mutate source
            if (Files.exists(target, NOFOLLOW_LINKS)) {
                throw FileAlreadyExistsException(target.toString())
            }
            Files.move(source, target)
        }

    private suspend fun mutate(name: String, block: () -> Path): FileMutationResult {
        val validation = FileNameRules.validate(name)
        if (validation is FileNameValidation.Invalid) {
            return FileMutationResult.Failure(validation.message)
        }
        return withContext(Dispatchers.IO) {
            try {
                FileMutationResult.Success(block())
            } catch (error: FileAlreadyExistsException) {
                FileMutationResult.Failure("已存在同名项目", error.message)
            } catch (error: NoSuchFileException) {
                FileMutationResult.Failure("文件或文件夹已不存在", error.message)
            } catch (error: SecurityException) {
                FileMutationResult.Failure("没有权限执行此操作", error.message)
            } catch (error: Exception) {
                FileMutationResult.Failure("操作失败", error.message ?: error.javaClass.simpleName)
            }
        }
    }
}
