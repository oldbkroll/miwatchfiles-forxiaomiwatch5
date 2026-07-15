package com.example.watchfiles.data

import android.webkit.MimeTypeMap
import java.nio.file.Path
import java.util.Locale

enum class FileCategory(val displayName: String) {
    IMAGE("图片"),
    AUDIO("音频"),
    VIDEO("视频"),
    TEXT("文本"),
    PDF("PDF 文档"),
    ARCHIVE("压缩文件"),
    APK("Android 安装包"),
    OTHER("普通文件"),
}

data class FileTypeInfo(
    val mimeType: String,
    val category: FileCategory,
)

fun identifyFileType(path: Path): FileTypeInfo {
    val extension = path.fileName
        ?.toString()
        ?.substringAfterLast('.', missingDelimiterValue = "")
        ?.lowercase(Locale.ROOT)
        .orEmpty()

    val mimeType = knownMimeTypes[extension]
        ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        ?: "application/octet-stream"

    val category = when {
        mimeType.startsWith("image/") -> FileCategory.IMAGE
        mimeType.startsWith("audio/") -> FileCategory.AUDIO
        mimeType.startsWith("video/") -> FileCategory.VIDEO
        mimeType.startsWith("text/") -> FileCategory.TEXT
        mimeType == "application/pdf" -> FileCategory.PDF
        mimeType == "application/vnd.android.package-archive" -> FileCategory.APK
        mimeType in archiveMimeTypes -> FileCategory.ARCHIVE
        else -> FileCategory.OTHER
    }
    return FileTypeInfo(mimeType = mimeType, category = category)
}

private val knownMimeTypes = mapOf(
    "apk" to "application/vnd.android.package-archive",
    "csv" to "text/csv",
    "flac" to "audio/flac",
    "m4a" to "audio/mp4",
    "md" to "text/markdown",
    "mkv" to "video/x-matroska",
    "opus" to "audio/opus",
    "rar" to "application/vnd.rar",
    "tar" to "application/x-tar",
    "webp" to "image/webp",
    "zip" to "application/zip",
    "7z" to "application/x-7z-compressed",
)

private val archiveMimeTypes = setOf(
    "application/gzip",
    "application/java-archive",
    "application/vnd.rar",
    "application/x-7z-compressed",
    "application/x-bzip2",
    "application/x-tar",
    "application/zip",
)
