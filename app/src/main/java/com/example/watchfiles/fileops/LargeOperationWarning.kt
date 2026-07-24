package com.example.watchfiles.fileops

const val LARGE_OPERATION_ITEM_THRESHOLD: Int = 100
const val LARGE_OPERATION_SIZE_THRESHOLD_BYTES: Long = 50L * 1024L * 1024L

const val LARGE_OPERATION_WARNING_TITLE: String = "文件较多"
const val LARGE_OPERATION_WARNING_MESSAGE: String =
    "建议尽量保持手表亮屏。熄屏或系统调度可能导致操作中断或失败。"

const val LARGE_OPERATION_TITLE: String = LARGE_OPERATION_WARNING_TITLE
const val LARGE_OPERATION_RISK_MESSAGE: String = LARGE_OPERATION_WARNING_MESSAGE

fun isLargeOperation(itemCount: Int, totalBytes: Long?): Boolean =
    itemCount >= LARGE_OPERATION_ITEM_THRESHOLD ||
        (totalBytes != null && totalBytes >= LARGE_OPERATION_SIZE_THRESHOLD_BYTES)

fun formatLargeOperationScale(
    itemCount: Int,
    totalBytes: Long?,
    formatBytes: (Long) -> String,
): String = when (totalBytes) {
    null -> "共 $itemCount 项 · 大小未知"
    else -> "共 $itemCount 项 · 总计 ${formatBytes(totalBytes)}"
}
