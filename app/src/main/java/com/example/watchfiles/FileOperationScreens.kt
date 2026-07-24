package com.example.watchfiles

import androidx.compose.runtime.Composable
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.Text
import com.example.watchfiles.device.formatBytes
import com.example.watchfiles.fileops.FileOperationState
import com.example.watchfiles.fileops.FileOperationType
import com.example.watchfiles.fileops.LARGE_OPERATION_WARNING_MESSAGE
import com.example.watchfiles.fileops.LARGE_OPERATION_WARNING_TITLE
import com.example.watchfiles.fileops.TargetDirectoryUiState
import com.example.watchfiles.fileops.formatLargeOperationScale
import java.nio.file.Path

@Composable
internal fun TargetDirectoryScreen(
    state: TargetDirectoryUiState,
    sourceCount: Int,
    onOpenDirectory: (Path) -> Unit,
    onNavigateUp: () -> Unit,
    onUseCurrent: () -> Unit,
    onCancel: () -> Unit,
) {
    RoundList {
        item { ListHeader { Text("选择目标 · $sourceCount 项") } }
        item { AppChip("放到此处", state.currentPath.toString(), onClick = onUseCurrent) }
        item { AppChip("返回上级", "浏览父目录", onClick = onNavigateUp) }
        item { AppChip("取消", "返回原目录", onClick = onCancel) }
        if (state.isLoading) item { Text("正在读取…") }
        state.errorMessage?.let { message -> item { AppChip("读取失败", message, onClick = {}) } }
        items(state.directories, key = { it.path.toString() }) { entry ->
            AppChip(entry.name, entry.path.toString(), onClick = { onOpenDirectory(entry.path) })
        }
    }
}

@Composable
internal fun FileOperationScreen(
    state: FileOperationState,
    onReplaceAll: () -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
) {
    RoundList {
        when (state) {
            FileOperationState.Idle -> item { Text("准备文件操作…") }
            is FileOperationState.Scanning -> {
                item { ListHeader { Text("正在扫描…") } }
                item { AppChip("取消", "停止扫描", onClick = onCancel) }
            }
            is FileOperationState.WaitingForLargeOperationConfirmation -> {
                item { ListHeader { Text("等待继续确认") } }
                item { AppChip("取消", "返回原目录", onClick = onCancel) }
            }
            is FileOperationState.Running -> {
                val progress = state.progress
                item { ListHeader { Text(operationTitle(state.type)) } }
                item { AppChip(progress.currentName ?: "准备中", "${progress.processedItems} / ${progress.totalItems}", onClick = {}) }
                item {
                    val bytes = progress.totalBytes?.let { "${formatBytes(progress.processedBytes)} / ${formatBytes(it)}" }
                        ?: "已处理 ${formatBytes(progress.processedBytes)}"
                    AppChip("数据进度", bytes, onClick = {})
                }
                item { AppChip("取消", cancellationText(state.type), onClick = onCancel) }
            }
            is FileOperationState.WaitingForReplacement -> {
                if (state.type == FileOperationType.DELETE) {
                    item { Text("正在删除…") }
                } else {
                    item { ListHeader { Text("发现同名项目") } }
                    item { AppChip(state.conflict.target.fileName.toString(), "现有同名项目会整体替换，不会合并", onClick = {}) }
                    item { AppChip("替换全部", "本任务后续同名项目不再询问", onClick = onReplaceAll) }
                    item { AppChip("取消任务", "保留现有目标", onClick = onCancel) }
                }
            }
            is FileOperationState.WaitingForDeleteConfirmation -> item { Text("等待删除确认…") }
            is FileOperationState.Cancelling -> item {
                ListHeader { Text(cancellingTitle(state.type)) }
            }
            is FileOperationState.Succeeded -> terminal(
                completedTitle(state.type),
                state.result,
                onDone,
            )
            is FileOperationState.PartiallySucceeded -> terminal(partialCompletionTitle(state.type), state.result, onDone)
            is FileOperationState.Failed -> terminal(
                failedTitle(state.type),
                state.result,
                onDone,
            )
            is FileOperationState.Cancelled -> terminal(cancelledTitle(state.type), state.result, onDone)
        }
    }
}

@Composable
internal fun LargeOperationConfirmationScreen(
    state: FileOperationState.WaitingForLargeOperationConfirmation,
    onContinue: () -> Unit,
    onCancel: () -> Unit,
) {
    RoundList {
        item { ListHeader { Text(LARGE_OPERATION_WARNING_TITLE) } }
        item {
            Text(
                text = formatLargeOperationScale(state.itemCount, state.totalBytes, ::formatBytes),
            )
        }
        item { Text(LARGE_OPERATION_WARNING_MESSAGE) }
        item { AppChip("继续操作", "确认后继续当前任务", onClick = onContinue) }
        item { AppChip("取消", "返回原目录", onClick = onCancel) }
    }
}

@Composable
internal fun DeleteConfirmationScreen(
    state: FileOperationState,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
) {
    RoundList {
        when (state) {
            is FileOperationState.Scanning -> {
                item { ListHeader { Text("正在扫描删除内容…") } }
                item { AppChip("取消", "不删除任何文件", onClick = onCancel) }
            }
            is FileOperationState.WaitingForDeleteConfirmation -> {
                item { ListHeader { Text("确认永久删除") } }
                item {
                    AppChip(
                        "项目",
                        "${state.preview.topLevelCount} 项 · 共 ${state.preview.itemCount} 项",
                        onClick = {},
                    )
                }
                item {
                    AppChip(
                        "大小",
                        state.preview.totalBytes?.let(::formatBytes) ?: "大小未知",
                        onClick = {},
                    )
                }
                item { AppChip("警告", "永久删除，无法恢复", onClick = {}) }
                item { AppChip("永久删除", "开始删除任务", onClick = onConfirm) }
                item { AppChip("取消", "返回原目录", onClick = onCancel) }
            }
            is FileOperationState.Failed -> terminal("删除前检查失败", state.result, onDone)
            else -> item { Text("删除状态不可用") }
        }
    }
}

private fun operationTitle(type: FileOperationType): String = when (type) {
    FileOperationType.COPY -> "正在复制"
    FileOperationType.MOVE -> "正在移动"
    FileOperationType.DELETE -> "正在删除"
}

private fun cancellationText(type: FileOperationType): String = when (type) {
    FileOperationType.COPY -> "停止并清理未发布项目"
    FileOperationType.MOVE -> "停止；已完成项目保留在目标"
    FileOperationType.DELETE -> "停止；已删除内容无法恢复"
}

private fun cancellingTitle(type: FileOperationType): String = when (type) {
    FileOperationType.COPY -> "正在停止并清理临时文件…"
    FileOperationType.MOVE -> "正在停止；已完成项目保留在目标…"
    FileOperationType.DELETE -> "正在停止；已删除内容无法恢复…"
}

private fun completedTitle(type: FileOperationType): String = when (type) {
    FileOperationType.COPY -> "复制完成"
    FileOperationType.MOVE -> "移动完成"
    FileOperationType.DELETE -> "删除完成"
}

private fun partialCompletionTitle(type: FileOperationType): String = when (type) {
    FileOperationType.COPY,
    FileOperationType.MOVE,
    -> "部分完成"
    FileOperationType.DELETE -> "部分删除"
}

private fun failedTitle(type: FileOperationType): String = when (type) {
    FileOperationType.COPY -> "复制失败"
    FileOperationType.MOVE -> "移动失败"
    FileOperationType.DELETE -> "删除失败"
}

private fun cancelledTitle(type: FileOperationType): String = when (type) {
    FileOperationType.COPY,
    FileOperationType.MOVE,
    -> "任务已取消"
    FileOperationType.DELETE -> "删除已取消"
}

private fun androidx.wear.compose.foundation.lazy.ScalingLazyListScope.terminal(
    title: String,
    result: com.example.watchfiles.fileops.FileOperationResult,
    onDone: () -> Unit,
) {
    item { ListHeader { Text(title) } }
    item { AppChip("结果", "完成 ${result.completedItems} 项 · 失败 ${result.failedItems} 项", onClick = {}) }
    result.failures.firstOrNull()?.let { failure ->
        item { AppChip("提示", failure.userMessage, onClick = {}) }
    }
    item { AppChip("返回目录", "刷新文件列表", onClick = onDone) }
}
