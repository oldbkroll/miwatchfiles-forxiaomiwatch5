package com.example.watchfiles.text

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.Text
import com.example.watchfiles.AppChip
import com.example.watchfiles.RoundList
import com.example.watchfiles.fileops.FileNameRules
import com.example.watchfiles.fileops.FileNameValidation

@Composable
internal fun TextDocumentScreen(
    state: TextDocumentUiState,
    onNextSegment: () -> Unit,
    onPreviousSegment: () -> Unit,
    onBeginEditing: () -> Unit,
    onUpdateDraft: (String) -> Unit,
    onRequestOverwriteConfirmation: () -> Unit,
    onRequestSaveAs: (String) -> Unit,
    onConfirmSave: (Boolean) -> Unit,
    onCancelSave: () -> Unit,
    onDiscardChanges: () -> Unit,
    onNavigateBack: () -> Unit,
) {
    var saveAsName by remember(state.path) { mutableStateOf("") }
    var showSaveAsEditor by remember(state.path) { mutableStateOf(false) }
    var showDiscardConfirmation by remember(state.path) { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current

    BackHandler {
        if (state.mode == TextDocumentMode.EDITING && state.isDirty) {
            showDiscardConfirmation = true
        } else if (state.mode != TextDocumentMode.SAVING) {
            onNavigateBack()
        }
    }

    if (showDiscardConfirmation) {
        RoundList {
            item { ListHeader { Text("放弃编辑？") } }
            item { Text("当前修改尚未保存，原文件仍未改变。", fontSize = 11.sp) }
            item {
                AppChip(
                    label = "放弃修改",
                    secondary = "返回文件详情",
                    onClick = {
                        showDiscardConfirmation = false
                        onDiscardChanges()
                        onNavigateBack()
                    },
                )
            }
            item {
                AppChip(
                    label = "继续编辑",
                    secondary = "保留当前草稿",
                    onClick = { showDiscardConfirmation = false },
                )
            }
        }
        return
    }

    if (state.saveConfirmation != TextSaveConfirmation.NONE) {
        val isOverwrite = state.saveConfirmation == TextSaveConfirmation.OVERWRITE || state.targetExists
        RoundList {
            item { ListHeader { Text(if (isOverwrite) "确认覆盖" else "确认另存为") } }
            item {
                Text(
                    text = state.pendingTargetName.orEmpty(),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            item {
                Text(
                    text = if (isOverwrite) "同名目标将被替换，旧内容会先保留到事务 backup。"
                    else "只会在当前文件夹创建新文件。",
                    fontSize = 11.sp,
                )
            }
            item {
                AppChip(
                    label = if (isOverwrite) "确认覆盖" else "确认另存为",
                    secondary = "保存 UTF-8 文本",
                    onClick = { onConfirmSave(isOverwrite) },
                )
            }
            item {
                AppChip(
                    label = "取消保存",
                    secondary = "原文件保持不变",
                    onClick = {
                        showSaveAsEditor = false
                        onCancelSave()
                    },
                )
            }
        }
        return
    }

    when (state.mode) {
        TextDocumentMode.IDLE,
        TextDocumentMode.LOADING,
        -> RoundList {
            item { ListHeader { Text("正在打开文本") } }
            item { Text(state.path?.fileName?.toString().orEmpty(), maxLines = 2) }
            item { Text("后台读取中…", fontSize = 11.sp) }
            item {
                AppChip(label = "返回", secondary = "取消查看", onClick = onNavigateBack)
            }
        }

        TextDocumentMode.FAILED -> RoundList {
            item { ListHeader { Text("文本打开失败") } }
            item { Text(state.message ?: "编码不支持或文件内容无效", fontSize = 11.sp) }
            item {
                AppChip(label = "返回文件详情", secondary = state.path?.fileName?.toString().orEmpty(), onClick = onNavigateBack)
            }
        }

        TextDocumentMode.VIEWING -> TextViewingContent(
            state = state,
            onNextSegment = onNextSegment,
            onPreviousSegment = onPreviousSegment,
            onBeginEditing = onBeginEditing,
            onNavigateBack = onNavigateBack,
        )

        TextDocumentMode.EDITING,
        TextDocumentMode.SAVING,
        -> {
            val validation = remember(saveAsName) { FileNameRules.validate(saveAsName) }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = if (state.mode == TextDocumentMode.SAVING) "正在保存" else "编辑文本",
                    fontSize = 18.sp,
                )
                BasicTextField(
                    value = state.draft,
                    onValueChange = onUpdateDraft,
                    enabled = state.mode == TextDocumentMode.EDITING,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(130.dp)
                        .padding(top = 12.dp)
                        .background(Color.White, RoundedCornerShape(12.dp))
                        .padding(10.dp),
                    textStyle = TextStyle(color = Color.Black, fontSize = 12.sp),
                    cursorBrush = SolidColor(Color.Blue),
                )
                state.message?.let { message ->
                    Text(
                        text = message,
                        color = Color(0xFFFF8A80),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (state.mode == TextDocumentMode.EDITING) {
                    AppChip(
                        label = "保存覆盖当前文件",
                        secondary = "需要明确确认",
                        enabled = state.isDirty,
                        onClick = onRequestOverwriteConfirmation,
                    )
                    AppChip(
                        label = "另存为",
                        secondary = "仅限当前文件夹",
                        enabled = state.isDirty,
                        onClick = { showSaveAsEditor = true },
                    )
                    if (showSaveAsEditor) {
                        BasicTextField(
                            value = saveAsName,
                            onValueChange = { saveAsName = it.replace("\n", "") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                                .background(Color.White, RoundedCornerShape(12.dp))
                                .padding(10.dp),
                            singleLine = true,
                            textStyle = TextStyle(color = Color.Black, fontSize = 14.sp),
                            cursorBrush = SolidColor(Color.Blue),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    if (validation == FileNameValidation.Valid) {
                                        keyboard?.hide()
                                        onRequestSaveAs(saveAsName)
                                    }
                                },
                            ),
                        )
                        AppChip(
                            label = "确认文件名",
                            secondary = if (validation is FileNameValidation.Invalid) validation.message else "继续同名检查",
                            enabled = validation == FileNameValidation.Valid,
                            onClick = {
                                keyboard?.hide()
                                onRequestSaveAs(saveAsName)
                            },
                        )
                    }
                    AppChip(
                        label = "取消编辑",
                        secondary = "不保存当前草稿",
                        onClick = {
                            showDiscardConfirmation = true
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun TextViewingContent(
    state: TextDocumentUiState,
    onNextSegment: () -> Unit,
    onPreviousSegment: () -> Unit,
    onBeginEditing: () -> Unit,
    onNavigateBack: () -> Unit,
) {
    val segment = state.segment
    RoundList {
        item { ListHeader { Text("查看文本") } }
        item {
            Text(
                text = state.path?.fileName?.toString().orEmpty(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        item {
            Text(
                text = if (segment == null) "无内容" else "字节 ${segment.startByte}–${segment.endByte} / ${state.sizeBytes}",
                fontSize = 10.sp,
            )
        }
        if (segment != null) {
            item {
                Text(
                    text = segment.text.ifEmpty { "（空文件或空白段）" },
                    modifier = Modifier
                        .fillMaxWidth(0.92f)
                        .background(Color(0xFF202124), RoundedCornerShape(12.dp))
                        .padding(10.dp),
                    fontSize = 10.sp,
                )
            }
            item {
                AppChip(
                    label = "上一段",
                    secondary = if (segment.hasPrevious) "返回前一页" else "已经是第一段",
                    enabled = segment.hasPrevious,
                    onClick = onPreviousSegment,
                )
            }
            item {
                AppChip(
                    label = "下一段",
                    secondary = if (segment.hasNext) "读取下一页" else "已经是最后一段",
                    enabled = segment.hasNext,
                    onClick = onNextSegment,
                )
            }
        }
        if (state.editable) {
            item {
                AppChip(label = "编辑", secondary = "UTF-8 · 不超过 512 KiB", onClick = onBeginEditing)
            }
        } else {
            item {
                AppChip(
                    label = "只读",
                    secondary = state.editDisabledReason ?: "此文本不能安全编辑",
                    onClick = {},
                )
            }
        }
        item {
            AppChip(label = "返回文件详情", secondary = "原目录位置保持不变", onClick = onNavigateBack)
        }
    }
}
