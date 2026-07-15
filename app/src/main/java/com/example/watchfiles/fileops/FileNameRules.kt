package com.example.watchfiles.fileops

sealed interface FileNameValidation {
    data object Valid : FileNameValidation
    data class Invalid(val message: String) : FileNameValidation
}

object FileNameRules {
    fun validate(name: String): FileNameValidation = when {
        name.isEmpty() -> FileNameValidation.Invalid("名称不能为空")
        name == "." || name == ".." -> FileNameValidation.Invalid("不能使用 . 或 ..")
        name.any { it == '/' || it == '\\' || it == '\u0000' } ->
            FileNameValidation.Invalid("名称不能包含路径分隔符")
        name.first().isWhitespace() || name.last().isWhitespace() ->
            FileNameValidation.Invalid("名称首尾不能是空格")
        else -> FileNameValidation.Valid
    }
}
