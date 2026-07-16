package com.example.watchfiles

import androidx.compose.runtime.Composable
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.Text
import com.example.watchfiles.device.formatBytes
import com.example.watchfiles.fileops.FileOperationState
import com.example.watchfiles.fileops.TargetDirectoryUiState
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
            is FileOperationState.Running -> {
                val progress = state.progress
                item { ListHeader { Text(if (state.type.name == "COPY") "正在复制" else "正在移动") } }
                item { AppChip(progress.currentName ?: "准备中", "${progress.processedItems} / ${progress.totalItems}", onClick = {}) }
                item {
                    val bytes = progress.totalBytes?.let { "${formatBytes(progress.processedBytes)} / ${formatBytes(it)}" }
                        ?: "已处理 ${formatBytes(progress.processedBytes)}"
                    AppChip("数据进度", bytes, onClick = {})
                }
                item { AppChip("取消", "停止并清理未发布项目", onClick = onCancel) }
            }
            is FileOperationState.WaitingForReplacement -> {
                item { ListHeader { Text("发现同名项目") } }
                item { AppChip(state.conflict.target.fileName.toString(), "目录会整体替换，不会合并", onClick = {}) }
                item { AppChip("替换全部", "本任务后续同名项目不再询问", onClick = onReplaceAll) }
                item { AppChip("取消任务", "保留现有目标", onClick = onCancel) }
            }
            is FileOperationState.Cancelling -> item { ListHeader { Text("正在停止并清理临时文件…") } }
            is FileOperationState.Succeeded -> terminal("复制完成", state.result, onDone)
            is FileOperationState.PartiallySucceeded -> terminal("部分完成", state.result, onDone)
            is FileOperationState.Failed -> terminal("复制失败", state.result, onDone)
            is FileOperationState.Cancelled -> terminal("任务已取消", state.result, onDone)
        }
    }
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
