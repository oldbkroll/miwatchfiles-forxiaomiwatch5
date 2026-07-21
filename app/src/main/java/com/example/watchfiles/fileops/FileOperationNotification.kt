package com.example.watchfiles.fileops

data class FileOperationNotificationContent(
    val title: String,
    val text: String,
    val processedItems: Int?,
    val totalItems: Int?,
    val hasSound: Boolean = false,
    val hasVibration: Boolean = false,
)

fun notificationContentFor(state: FileOperationState): FileOperationNotificationContent? = when (state) {
    FileOperationState.Idle,
    is FileOperationState.Succeeded,
    is FileOperationState.PartiallySucceeded,
    is FileOperationState.Failed,
    is FileOperationState.Cancelled,
    -> null

    is FileOperationState.Scanning -> notificationContent(state.type, "正在准备", null)
    is FileOperationState.Running -> notificationContent(state.type, state.progress.currentName, state.progress)
    is FileOperationState.WaitingForReplacement ->
        notificationContent(state.type, state.progress.currentName, state.progress)
    is FileOperationState.WaitingForDeleteConfirmation ->
        notificationContent(FileOperationType.DELETE, "等待确认", null)
    is FileOperationState.Cancelling -> notificationContent(state.type, "正在取消", state.progress)
}

private fun notificationContent(
    type: FileOperationType,
    currentName: String?,
    progress: OperationProgress?,
): FileOperationNotificationContent {
    val processedItems = progress?.processedItems
    val totalItems = progress?.totalItems
    val progressText = if (processedItems != null && totalItems != null) " $processedItems/$totalItems" else ""
    return FileOperationNotificationContent(
        title = "WatchFiles 正在操作",
        text = "${type.displayName()}：${currentName ?: "准备中"}$progressText",
        processedItems = processedItems,
        totalItems = totalItems,
    )
}

private fun FileOperationType.displayName(): String = when (this) {
    FileOperationType.COPY -> "复制"
    FileOperationType.MOVE -> "移动"
    FileOperationType.DELETE -> "删除"
}
