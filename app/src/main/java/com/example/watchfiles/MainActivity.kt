package com.example.watchfiles

import android.app.ActivityManager
import android.Manifest
import android.content.ClipData
import android.content.ActivityNotFoundException
import android.content.ComponentCallbacks2
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Text
import androidx.core.content.FileProvider
import com.example.watchfiles.browser.BrowserUiState
import com.example.watchfiles.browser.BrowserMutationState
import com.example.watchfiles.browser.FileBrowserViewModel
import com.example.watchfiles.data.FileEntry
import com.example.watchfiles.data.FileTypeInfo
import com.example.watchfiles.data.identifyFileType
import com.example.watchfiles.device.formatBytes
import com.example.watchfiles.device.readDeviceSnapshot
import com.example.watchfiles.fileops.FileNameRules
import com.example.watchfiles.fileops.FileNameValidation
import com.example.watchfiles.fileops.FileOperationCoordinator
import com.example.watchfiles.fileops.FileOperationState
import com.example.watchfiles.fileops.FileOperationType
import com.example.watchfiles.fileops.TargetDirectoryViewModel
import com.example.watchfiles.image.DecodedImage
import com.example.watchfiles.image.decodeLowMemoryImage
import com.example.watchfiles.text.TextDocumentMode
import com.example.watchfiles.text.TextDocumentScreen
import com.example.watchfiles.text.TextDocumentViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.nio.file.Path
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private enum class AppScreen {
    HOME,
    BROWSER,
    FILE_DETAILS,
    TEXT_DOCUMENT,
    IMAGE_VIEWER,
    DEVICE_INFO,
    NAME_EDITOR,
    TARGET_DIRECTORY,
    DELETE_CONFIRMATION,
    FILE_OPERATION,
}

private sealed interface NameEditorRequest {
    data object CreateDirectory : NameEditorRequest
    data class Rename(val entry: FileEntry) : NameEditorRequest
}

private const val MAX_IMAGE_ZOOM = 4f

private val TopArcButtonShape = GenericShape { size, _ ->
    val ellipseControl = 0.5522848f
    moveTo(0f, size.height)
    cubicTo(
        0f,
        size.height * (1f - ellipseControl),
        size.width * 0.5f * (1f - ellipseControl),
        0f,
        size.width * 0.5f,
        0f,
    )
    cubicTo(
        size.width * 0.5f * (1f + ellipseControl),
        0f,
        size.width,
        size.height * (1f - ellipseControl),
        size.width,
        size.height,
    )
    lineTo(0f, size.height)
    close()
}

class MainActivity : ComponentActivity() {
    private val browserViewModel by viewModels<FileBrowserViewModel>()
    private val targetDirectoryViewModel by viewModels<TargetDirectoryViewModel>()
    private val fileOperationCoordinator by viewModels<FileOperationCoordinator>()
    private val textDocumentViewModel by viewModels<TextDocumentViewModel> {
        TextDocumentViewModel.Factory(applicationContext)
    }
    private var hasStorageAccess by mutableStateOf(false)
    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        updatePermissionState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updatePermissionState()
        textDocumentViewModel.recoverTransactions()
        setContent {
            WatchFilesTheme {
                WatchFilesApp(
                    hasStorageAccess = hasStorageAccess,
                    onRequestPermission = ::requestLegacyStoragePermission,
                    onOpenAppSettings = ::openAppSettings,
                    onOpenFile = ::openFile,
                    browserViewModel = browserViewModel,
                    targetDirectoryViewModel = targetDirectoryViewModel,
                    fileOperationCoordinator = fileOperationCoordinator,
                    textDocumentViewModel = textDocumentViewModel,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionState()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            // Thumbnail and media caches will be trimmed here in later milestones.
        }
    }

    private fun updatePermissionState() {
        val allFiles = if (android.os.Build.VERSION.SDK_INT >= 30) {
            Environment.isExternalStorageManager()
        } else {
            false
        }
        val legacyRead = checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
        hasStorageAccess = allFiles || legacyRead
    }

    private fun requestLegacyStoragePermission() {
        storagePermissionLauncher.launch(
            arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ),
        )
    }

    private fun openAppSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:$packageName"),
        )
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // This vendor firmware may omit individual settings pages.
        }
    }

    private fun openFile(path: Path, mimeType: String): String? {
        return try {
            val uri = FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                path.toFile(),
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                clipData = ClipData.newRawUri(path.fileName?.toString() ?: "file", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
            null
        } catch (_: ActivityNotFoundException) {
            "未找到能打开此类文件的应用"
        } catch (_: IllegalArgumentException) {
            "此文件不在可共享的存储位置"
        } catch (_: SecurityException) {
            "系统拒绝读取此文件"
        }
    }
}

@Composable
private fun WatchFilesTheme(content: @Composable () -> Unit) {
    MaterialTheme(content = content)
}

@Composable
private fun WatchFilesApp(
    hasStorageAccess: Boolean,
    onRequestPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenFile: (Path, String) -> String?,
    browserViewModel: FileBrowserViewModel,
    targetDirectoryViewModel: TargetDirectoryViewModel,
    fileOperationCoordinator: FileOperationCoordinator,
    textDocumentViewModel: TextDocumentViewModel,
) {
    if (!hasStorageAccess) {
        PermissionScreen(onRequestPermission, onOpenAppSettings)
        return
    }

    var screen by remember { mutableStateOf(AppScreen.HOME) }
    var selectedFile by remember { mutableStateOf<FileEntry?>(null) }
    var nameEditorRequest by remember { mutableStateOf<NameEditorRequest?>(null) }
    val browserState by browserViewModel.state.collectAsState()
    val targetState by targetDirectoryViewModel.state.collectAsState()
    val operationState by fileOperationCoordinator.state.collectAsState()
    val textState by textDocumentViewModel.state.collectAsState()
    var pendingOperationSources by remember { mutableStateOf<List<Path>>(emptyList()) }
    var pendingOperationType by remember { mutableStateOf(FileOperationType.COPY) }
    val browserListState = rememberScalingLazyListState()
    val scope = rememberCoroutineScope()
    val storageRoot = remember { Environment.getExternalStorageDirectory().toPath() }

    val finishPendingOperation = {
        browserViewModel.refreshAfterOperation()
        fileOperationCoordinator.consumeResult()
        pendingOperationSources = emptyList()
        pendingOperationType = FileOperationType.COPY
        screen = AppScreen.BROWSER
    }

    val resetBrowserPosition = {
        scope.launch { browserListState.scrollToItem(0) }
    }
    val openBrowserDirectory: (Path) -> Unit = { path ->
        resetBrowserPosition()
        browserViewModel.open(path)
        screen = AppScreen.BROWSER
    }
    val navigateBrowserUp = {
        if (browserState.currentPath == storageRoot) {
            screen = AppScreen.HOME
        } else {
            resetBrowserPosition()
            if (!browserViewModel.navigateUp()) screen = AppScreen.HOME
        }
    }

    LaunchedEffect(browserState.mutation) {
        if (browserState.mutation is BrowserMutationState.Succeeded) {
            nameEditorRequest = null
            screen = AppScreen.BROWSER
            browserViewModel.consumeMutationResult()
        }
    }

    BackHandler(enabled = screen != AppScreen.HOME) {
        when (screen) {
            AppScreen.BROWSER -> {
                if (browserState.selection.isActive) {
                    browserViewModel.clearSelection()
                } else {
                    navigateBrowserUp()
                }
            }
            AppScreen.FILE_DETAILS -> screen = AppScreen.BROWSER
            AppScreen.TEXT_DOCUMENT -> Unit
            AppScreen.IMAGE_VIEWER -> screen = AppScreen.FILE_DETAILS
            AppScreen.DEVICE_INFO -> screen = AppScreen.HOME
            AppScreen.NAME_EDITOR -> {
                if (browserState.mutation != BrowserMutationState.Working) {
                    browserViewModel.consumeMutationResult()
                    nameEditorRequest = null
                    screen = AppScreen.BROWSER
                }
            }
            AppScreen.TARGET_DIRECTORY -> screen = AppScreen.BROWSER
            AppScreen.DELETE_CONFIRMATION -> when (operationState) {
                is FileOperationState.Scanning,
                is FileOperationState.WaitingForDeleteConfirmation -> {
                    fileOperationCoordinator.cancel()
                    screen = AppScreen.BROWSER
                }
                is FileOperationState.Failed,
                is FileOperationState.Succeeded,
                is FileOperationState.PartiallySucceeded,
                is FileOperationState.Cancelled -> finishPendingOperation()
                is FileOperationState.Running,
                is FileOperationState.WaitingForReplacement,
                is FileOperationState.Cancelling -> screen = AppScreen.FILE_OPERATION
                FileOperationState.Idle -> screen = AppScreen.BROWSER
            }
            AppScreen.FILE_OPERATION -> Unit
            AppScreen.HOME -> Unit
        }
    }

    when (screen) {
        AppScreen.HOME -> HomeScreen(
            onOpenDirectory = openBrowserDirectory,
            onOpenDeviceInfo = { screen = AppScreen.DEVICE_INFO },
        )
        AppScreen.BROWSER -> BrowserScreen(
            state = browserState,
            listState = browserListState,
            onOpenDirectory = { path ->
                resetBrowserPosition()
                browserViewModel.open(path)
            },
            onOpenFile = { entry ->
                selectedFile = entry
                screen = AppScreen.FILE_DETAILS
            },
            onNavigateUp = navigateBrowserUp,
            onToggleHidden = browserViewModel::toggleHidden,
            onRefresh = browserViewModel::refresh,
            onBeginSelection = { path ->
                browserViewModel.beginSelection(path)
                scope.launch { browserListState.scrollToItem(0) }
            },
            onToggleSelection = browserViewModel::toggleSelection,
            onSelectAll = browserViewModel::selectAll,
            onClearSelection = browserViewModel::clearSelection,
            onCreateDirectory = {
                browserViewModel.consumeMutationResult()
                nameEditorRequest = NameEditorRequest.CreateDirectory
                screen = AppScreen.NAME_EDITOR
            },
            onRenameSelected = {
                val selectedPath = browserState.selection.selectedPaths.singleOrNull()
                val selectedEntry = browserState.entries.firstOrNull { it.path == selectedPath }
                if (selectedEntry != null) {
                    browserViewModel.consumeMutationResult()
                    nameEditorRequest = NameEditorRequest.Rename(selectedEntry)
                    screen = AppScreen.NAME_EDITOR
                }
            },
            onCopySelected = {
                pendingOperationType = FileOperationType.COPY
                pendingOperationSources = browserState.selection.selectedPaths.toList()
                targetDirectoryViewModel.open(browserState.currentPath)
                screen = AppScreen.TARGET_DIRECTORY
            },
            onMoveSelected = {
                pendingOperationType = FileOperationType.MOVE
                pendingOperationSources = browserState.selection.selectedPaths.toList()
                targetDirectoryViewModel.open(browserState.currentPath)
                screen = AppScreen.TARGET_DIRECTORY
            },
            onDeleteSelected = {
                pendingOperationType = FileOperationType.DELETE
                pendingOperationSources = browserState.selection.selectedPaths.toList()
                if (fileOperationCoordinator.prepareDelete(pendingOperationSources)) {
                    screen = AppScreen.DELETE_CONFIRMATION
                }
            },
        )
        AppScreen.FILE_DETAILS -> selectedFile?.let { entry ->
            FileDetailsScreen(
                entry = entry,
                onOpenFile = onOpenFile,
                onOpenText = {
                    textDocumentViewModel.open(it.path)
                    screen = AppScreen.TEXT_DOCUMENT
                },
                onPreviewImage = {
                    selectedFile = it
                    screen = AppScreen.IMAGE_VIEWER
                },
                onNavigateBack = { screen = AppScreen.BROWSER },
            )
        } ?: HomeScreen(
            onOpenDirectory = openBrowserDirectory,
            onOpenDeviceInfo = { screen = AppScreen.DEVICE_INFO },
        )
        AppScreen.TEXT_DOCUMENT -> TextDocumentScreen(
            state = textState,
            onNextSegment = textDocumentViewModel::nextSegment,
            onPreviousSegment = textDocumentViewModel::previousSegment,
            onBeginEditing = textDocumentViewModel::beginEditing,
            onUpdateDraft = textDocumentViewModel::updateDraft,
            onRequestOverwriteConfirmation = textDocumentViewModel::requestOverwriteConfirmation,
            onRequestSaveAs = textDocumentViewModel::requestSaveAs,
            onConfirmSave = textDocumentViewModel::confirmSave,
            onCancelSave = textDocumentViewModel::cancelSave,
            onDiscardChanges = textDocumentViewModel::discardChanges,
            onNavigateBack = { screen = AppScreen.FILE_DETAILS },
        )
        AppScreen.IMAGE_VIEWER -> selectedFile?.let { entry ->
            ImageViewerScreen(
                entry = entry,
                onNavigateBack = { screen = AppScreen.FILE_DETAILS },
            )
        } ?: HomeScreen(
            onOpenDirectory = openBrowserDirectory,
            onOpenDeviceInfo = { screen = AppScreen.DEVICE_INFO },
        )
        AppScreen.DEVICE_INFO -> DeviceInfoScreen()
        AppScreen.NAME_EDITOR -> {
            val request = nameEditorRequest
            if (request == null) {
                LaunchedEffect(Unit) { screen = AppScreen.BROWSER }
            } else {
                FileNameEditorScreen(
                    request = request,
                    mutation = browserState.mutation,
                    onSubmit = { name ->
                        when (request) {
                            NameEditorRequest.CreateDirectory -> {
                                browserViewModel.createDirectory(name)
                            }

                            is NameEditorRequest.Rename -> {
                                browserViewModel.rename(request.entry.path, name)
                            }
                        }
                    },
                    onCancel = {
                        browserViewModel.consumeMutationResult()
                        nameEditorRequest = null
                        screen = AppScreen.BROWSER
                    },
                )
            }
        }
        AppScreen.TARGET_DIRECTORY -> TargetDirectoryScreen(
            state = targetState,
            sourceCount = pendingOperationSources.size,
            onOpenDirectory = targetDirectoryViewModel::open,
            onNavigateUp = targetDirectoryViewModel::navigateUp,
            onUseCurrent = {
                if (fileOperationCoordinator.start(
                        pendingOperationType,
                        pendingOperationSources,
                        targetState.currentPath,
                    )
                ) screen = AppScreen.FILE_OPERATION
            },
            onCancel = { screen = AppScreen.BROWSER },
        )
        AppScreen.DELETE_CONFIRMATION -> DeleteConfirmationScreen(
            state = operationState,
            onConfirm = {
                if (fileOperationCoordinator.confirmDelete()) {
                    screen = AppScreen.FILE_OPERATION
                }
            },
            onCancel = {
                fileOperationCoordinator.cancel()
                screen = AppScreen.BROWSER
            },
            onDone = finishPendingOperation,
        )
        AppScreen.FILE_OPERATION -> FileOperationScreen(
            state = operationState,
            onReplaceAll = fileOperationCoordinator::replaceAll,
            onCancel = fileOperationCoordinator::cancel,
            onDone = finishPendingOperation,
        )
    }
}

@Composable
private fun PermissionScreen(
    onRequestPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    RoundList {
        item { ListHeader { Text("需要文件权限") } }
        item {
            Text(
                text = "此手表使用“照片、媒体内容和文件”权限管理内部存储。",
                fontSize = 12.sp,
            )
        }
        item {
            AppChip(
                label = "授予文件权限",
                secondary = "弹出系统权限确认",
                onClick = onRequestPermission,
            )
        }
        item {
            AppChip(
                label = "打开应用信息",
                secondary = "权限未弹出时使用",
                onClick = onOpenAppSettings,
            )
        }
    }
}

@Composable
private fun HomeScreen(
    onOpenDirectory: (Path) -> Unit,
    onOpenDeviceInfo: () -> Unit,
) {
    val root = remember { Environment.getExternalStorageDirectory().toPath() }
    val shortcuts = remember {
        listOf(
            "内部存储" to root,
            "下载" to root.resolve(Environment.DIRECTORY_DOWNLOADS),
            "图片" to root.resolve(Environment.DIRECTORY_PICTURES),
            "音乐" to root.resolve(Environment.DIRECTORY_MUSIC),
            "视频" to root.resolve(Environment.DIRECTORY_MOVIES),
        )
    }

    RoundList {
        item { ListHeader { Text("WatchFiles") } }
        items(shortcuts, key = { it.second.toString() }) { (name, path) ->
            AppChip(
                label = name,
                secondary = path.toString(),
                onClick = { onOpenDirectory(path) },
            )
        }
        item {
            AppChip(
                label = "设备诊断",
                secondary = "屏幕、内存、ABI 与存储",
                onClick = onOpenDeviceInfo,
            )
        }
    }
}

@Composable
private fun BrowserScreen(
    state: BrowserUiState,
    listState: ScalingLazyListState,
    onOpenDirectory: (Path) -> Unit,
    onOpenFile: (FileEntry) -> Unit,
    onNavigateUp: () -> Unit,
    onToggleHidden: () -> Unit,
    onRefresh: () -> Unit,
    onBeginSelection: (Path) -> Unit,
    onToggleSelection: (Path) -> Unit,
    onSelectAll: (List<FileEntry>) -> Unit,
    onClearSelection: () -> Unit,
    onCreateDirectory: () -> Unit,
    onRenameSelected: () -> Unit,
    onCopySelected: () -> Unit,
    onMoveSelected: () -> Unit,
    onDeleteSelected: () -> Unit,
) {
    val visibleEntries = remember(state.entries, state.showHidden) {
        if (state.showHidden) state.entries else state.entries.filterNot(FileEntry::isHidden)
    }

    RoundList(state = listState) {
        if (state.selection.isActive) {
            item {
                ListHeader {
                    Text("已选 ${state.selection.selectedPaths.size} 项")
                }
            }
            item {
                AppChip(
                    label = "复制",
                    secondary = "选择目标文件夹",
                    onClick = onCopySelected,
                )
            }
            item {
                AppChip(
                    label = "移动",
                    secondary = "复制成功后删除源项目",
                    onClick = onMoveSelected,
                )
            }
            item {
                AppChip(
                    label = "删除",
                    secondary = "永久删除，无法恢复",
                    onClick = onDeleteSelected,
                )
            }
            item {
                AppChip(
                    label = "取消选择",
                    secondary = "返回普通浏览",
                    onClick = onClearSelection,
                )
            }
            item {
                AppChip(
                    label = "全选",
                    secondary = "选择当前可见项目",
                    onClick = { onSelectAll(visibleEntries) },
                )
            }
            if (state.selection.selectedPaths.size == 1) {
                item {
                    AppChip(
                        label = "重命名",
                        secondary = "修改所选项目名称",
                        onClick = onRenameSelected,
                    )
                }
            }
        } else {
            item {
                ListHeader {
                    Text(
                        text = folderDisplayName(state.currentPath),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            item {
                AppChip(
                    label = "返回上级",
                    secondary = state.currentPath.toString(),
                    onClick = onNavigateUp,
                )
            }
            item {
                AppChip(
                    label = "新建文件夹",
                    secondary = "在当前目录创建",
                    onClick = onCreateDirectory,
                )
            }
            item {
                AppChip(
                    label = if (state.showHidden) "隐藏点文件" else "显示点文件",
                    secondary = "以 . 开头的文件",
                    onClick = onToggleHidden,
                )
            }
        }
        if (state.isLoading) {
            item { Text("正在读取…") }
        }
        state.errorMessage?.let { message ->
            item {
                AppChip(label = "读取失败", secondary = message, onClick = onRefresh)
            }
        }
        if (!state.isLoading && state.errorMessage == null && visibleEntries.isEmpty()) {
            item { Text("此文件夹为空") }
        }
        items(visibleEntries, key = { it.path.toString() }) { entry ->
            FileChip(
                entry = entry,
                selected = entry.path in state.selection.selectedPaths,
                selectionMode = state.selection.isActive,
                onOpenDirectory = onOpenDirectory,
                onOpenFile = onOpenFile,
                onBeginSelection = onBeginSelection,
                onToggleSelection = onToggleSelection,
            )
        }
    }
}

private fun folderDisplayName(path: Path): String {
    val storageRoot = Environment.getExternalStorageDirectory().toPath()
    return if (path == storageRoot) "内部存储" else path.fileName?.toString() ?: "/"
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileChip(
    entry: FileEntry,
    selected: Boolean,
    selectionMode: Boolean,
    onOpenDirectory: (Path) -> Unit,
    onOpenFile: (FileEntry) -> Unit,
    onBeginSelection: (Path) -> Unit,
    onToggleSelection: (Path) -> Unit,
) {
    val details = when {
        entry.isDirectory && !entry.isReadable -> "文件夹 · 不可读取"
        entry.isDirectory -> "文件夹"
        entry.sizeBytes != null -> formatBytes(entry.sizeBytes)
        else -> "文件"
    }
    val clickAction = {
        if (selectionMode) {
            onToggleSelection(entry.path)
        } else if (entry.isDirectory && entry.isReadable) {
            onOpenDirectory(entry.path)
        } else if (!entry.isDirectory) {
            onOpenFile(entry)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth(0.9f)
            .clip(RoundedCornerShape(28.dp))
            .background(if (selected) Color(0xFF3F51B5) else Color(0xFF2C2C2E))
            .combinedClickable(
                enabled = true,
                onClick = clickAction,
                onLongClick = {
                    if (!selectionMode) onBeginSelection(entry.path)
                },
            )
            .padding(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Text(
            text = (if (selected) "✓  " else if (entry.isDirectory) "▰  " else "▱  ") + entry.name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = details,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontSize = 10.sp,
            color = Color.LightGray,
        )
    }
}

@Composable
private fun FileNameEditorScreen(
    request: NameEditorRequest,
    mutation: BrowserMutationState,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val initialName = (request as? NameEditorRequest.Rename)?.entry?.name.orEmpty()
    var value by remember(request) { mutableStateOf(initialName) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val validation = remember(value) { FileNameRules.validate(value) }
    val working = mutation == BrowserMutationState.Working

    LaunchedEffect(request) {
        focusRequester.requestFocus()
        keyboard?.show()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 36.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = if (request is NameEditorRequest.CreateDirectory) "新建文件夹" else "重命名",
            fontSize = 18.sp,
        )
        BasicTextField(
            value = value,
            onValueChange = { if (!working) value = it.replace("\n", "") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 18.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White)
                .padding(12.dp)
                .focusRequester(focusRequester)
                .semantics { contentDescription = "名称输入" },
            singleLine = true,
            textStyle = TextStyle(color = Color.Black, fontSize = 16.sp),
            cursorBrush = SolidColor(Color.Blue),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = {
                    if (!working && validation == FileNameValidation.Valid) onSubmit(value)
                },
            ),
        )
        val message = when {
            validation is FileNameValidation.Invalid -> validation.message
            mutation is BrowserMutationState.Failed -> mutation.userMessage
            else -> " "
        }
        Text(
            text = message,
            color = if (message.isBlank()) Color.Transparent else Color(0xFFFF8A80),
            fontSize = 11.sp,
        )
        AppChip(
            label = if (working) "正在处理…" else "确认",
            secondary = "不会覆盖同名项目",
            enabled = !working && validation == FileNameValidation.Valid,
            onClick = { onSubmit(value) },
        )
        AppChip(
            label = "取消",
            secondary = "不做修改",
            enabled = !working,
            onClick = onCancel,
        )
    }
}

@Composable
private fun FileDetailsScreen(
    entry: FileEntry,
    onOpenFile: (Path, String) -> String?,
    onOpenText: (FileEntry) -> Unit,
    onPreviewImage: (FileEntry) -> Unit,
    onNavigateBack: () -> Unit,
) {
    val typeInfo = remember(entry.path) { identifyFileType(entry.path) }
    var openError by remember(entry.path) { mutableStateOf<String?>(null) }

    RoundList {
        item { ListHeader { Text("文件详情") } }
        item {
            AppChip(
                label = "返回文件夹",
                secondary = entry.name,
                onClick = onNavigateBack,
            )
        }
        if (typeInfo.category == com.example.watchfiles.data.FileCategory.IMAGE) {
            item {
                AppChip(
                    label = "查看图片",
                    secondary = "内置低内存预览",
                    enabled = entry.isReadable,
                    onClick = { onPreviewImage(entry) },
                )
            }
            item {
                AppChip(
                    label = "用其他应用打开",
                    secondary = typeInfo.mimeType,
                    enabled = entry.isReadable,
                    onClick = { openError = onOpenFile(entry.path, typeInfo.mimeType) },
                )
            }
        } else {
            if (typeInfo.category == com.example.watchfiles.data.FileCategory.TEXT) {
                item {
                    AppChip(
                        label = "查看文本",
                        secondary = "UTF-8 分段查看与编辑",
                        enabled = entry.isReadable,
                        onClick = { onOpenText(entry) },
                    )
                }
            }
            item {
                AppChip(
                    label = "打开",
                    secondary = openActionLabel(typeInfo),
                    enabled = entry.isReadable,
                    onClick = { openError = onOpenFile(entry.path, typeInfo.mimeType) },
                )
            }
        }
        openError?.let { message ->
            item {
                AppChip(
                    label = "无法打开",
                    secondary = message,
                    onClick = { openError = null },
                )
            }
        }
        item { DetailChip("名称", entry.name) }
        item { DetailChip("类型", typeInfo.category.displayName) }
        item { DetailChip("MIME", typeInfo.mimeType) }
        item { DetailChip("大小", entry.sizeBytes?.let(::formatBytes) ?: "未知") }
        item { DetailChip("修改时间", formatModifiedTime(entry.modifiedAtMillis)) }
        item {
            DetailChip(
                "权限",
                "读取：${yesNo(entry.isReadable)} · 写入：${yesNo(entry.isWritable)}",
            )
        }
        item { DetailChip("路径", entry.path.toString()) }
    }
}

private sealed interface ImageLoadState {
    data object Loading : ImageLoadState
    data class Ready(val image: DecodedImage) : ImageLoadState
    data class Failed(val message: String) : ImageLoadState
}

@Composable
private fun ImageViewerScreen(
    entry: FileEntry,
    onNavigateBack: () -> Unit,
) {
    var loadState by remember(entry.path) { mutableStateOf<ImageLoadState>(ImageLoadState.Loading) }

    LaunchedEffect(entry.path) {
        loadState = ImageLoadState.Loading
        loadState = try {
            ImageLoadState.Ready(decodeLowMemoryImage(entry.path))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            ImageLoadState.Failed(error.message ?: "无法解码此图片")
        }
    }

    when (val current = loadState) {
        ImageLoadState.Loading -> RoundList {
            item { ListHeader { Text("正在打开图片") } }
            item { Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            item { Text("正在生成低内存预览…", fontSize = 11.sp) }
            item {
                AppChip(label = "返回", secondary = "取消加载", onClick = onNavigateBack)
            }
        }
        is ImageLoadState.Failed -> RoundList {
            item { ListHeader { Text("图片打开失败") } }
            item { Text(current.message, fontSize = 11.sp) }
            item {
                AppChip(label = "返回文件详情", secondary = entry.name, onClick = onNavigateBack)
            }
        }
        is ImageLoadState.Ready -> ImagePreview(
            decoded = current.image,
            onNavigateBack = onNavigateBack,
        )
    }
}

@Composable
private fun ImagePreview(
    decoded: DecodedImage,
    onNavigateBack: () -> Unit,
) {
    var zoom by remember(decoded.bitmap) { mutableFloatStateOf(1f) }
    var imageOffset by remember(decoded.bitmap) { mutableStateOf(Offset.Zero) }
    var viewportSize by remember(decoded.bitmap) { mutableStateOf(IntSize.Zero) }
    var ignoreTransformsUntil by remember(decoded.bitmap) { mutableLongStateOf(0L) }

    DisposableEffect(decoded.bitmap) {
        onDispose {
            if (!decoded.bitmap.isRecycled) decoded.bitmap.recycle()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = 12.dp,
                    top = 38.dp,
                    end = 12.dp,
                    bottom = 12.dp,
                )
                .clipToBounds()
                .onSizeChanged { size ->
                    viewportSize = size
                    imageOffset = constrainImageOffset(
                        candidate = imageOffset,
                        zoom = zoom,
                        viewportSize = size,
                        bitmapWidth = decoded.bitmap.width,
                        bitmapHeight = decoded.bitmap.height,
                    )
                }
                .pointerInput(decoded.bitmap, viewportSize) {
                    detectTapGestures(
                        onDoubleTap = {
                            ignoreTransformsUntil = SystemClock.uptimeMillis() + 250L
                            zoom = if (zoom > 1.01f) 1f else 2f
                            imageOffset = Offset.Zero
                        },
                    )
                }
                .pointerInput(decoded.bitmap, viewportSize) {
                    detectTransformGestures(panZoomLock = true) {
                            centroid,
                            pan,
                            gestureZoom,
                            _ ->
                        if (SystemClock.uptimeMillis() < ignoreTransformsUntil) {
                            return@detectTransformGestures
                        }
                        val oldZoom = zoom
                        val newZoom = (oldZoom * gestureZoom).coerceIn(1f, MAX_IMAGE_ZOOM)
                        val zoomRatio = newZoom / oldZoom
                        val viewportCenter = Offset(
                            x = viewportSize.width / 2f,
                            y = viewportSize.height / 2f,
                        )
                        val focusFromCenter = centroid - viewportCenter
                        val proposedOffset = Offset(
                            x = (imageOffset.x - focusFromCenter.x) * zoomRatio +
                                focusFromCenter.x + pan.x,
                            y = (imageOffset.y - focusFromCenter.y) * zoomRatio +
                                focusFromCenter.y + pan.y,
                        )
                        zoom = newZoom
                        imageOffset = constrainImageOffset(
                            candidate = proposedOffset,
                            zoom = newZoom,
                            viewportSize = viewportSize,
                            bitmapWidth = decoded.bitmap.width,
                            bitmapHeight = decoded.bitmap.height,
                        )
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = decoded.bitmap.asImageBitmap(),
                contentDescription = "图片预览",
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = zoom
                        scaleY = zoom
                        translationX = imageOffset.x
                        translationY = imageOffset.y
                    },
                contentScale = ContentScale.Fit,
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .width(112.dp)
                .height(38.dp)
                .clip(TopArcButtonShape)
                .background(MaterialTheme.colors.primary)
                .clickable(role = Role.Button, onClick = onNavigateBack)
                .semantics { contentDescription = "返回文件详情" },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "←",
                color = MaterialTheme.colors.onPrimary,
                fontSize = 18.sp,
            )
        }
        val previewSize = "${decoded.bitmap.width}×${decoded.bitmap.height}"
        val sourceSize = "${decoded.sourceWidth}×${decoded.sourceHeight}"
        val sizeText = if (previewSize == sourceSize) sourceSize else "$sourceSize · 预览 $previewSize"
        val zoomText = String.format(Locale.ROOT, "%.1f×", zoom)
        val gestureHint = if (zoom > 1.01f) "拖动查看 · 双击复位" else "双击或双指缩放"
        val imageStatusText = if (zoom > 1.01f) {
            "$zoomText · $gestureHint"
        } else {
            "$zoomText · $gestureHint\n$sizeText"
        }
        Text(
            text = imageStatusText,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 14.dp)
                .background(Color.Black.copy(alpha = 0.68f), RoundedCornerShape(12.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp),
            fontSize = 9.sp,
            maxLines = if (zoom > 1.01f) 1 else 2,
            textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun constrainImageOffset(
    candidate: Offset,
    zoom: Float,
    viewportSize: IntSize,
    bitmapWidth: Int,
    bitmapHeight: Int,
): Offset {
    if (
        viewportSize.width <= 0 || viewportSize.height <= 0 ||
        bitmapWidth <= 0 || bitmapHeight <= 0 || zoom <= 1f
    ) {
        return Offset.Zero
    }

    val fitScale = minOf(
        viewportSize.width.toFloat() / bitmapWidth,
        viewportSize.height.toFloat() / bitmapHeight,
    )
    val displayedWidth = bitmapWidth * fitScale * zoom
    val displayedHeight = bitmapHeight * fitScale * zoom
    val maxOffsetX = ((displayedWidth - viewportSize.width) / 2f).coerceAtLeast(0f)
    val maxOffsetY = ((displayedHeight - viewportSize.height) / 2f).coerceAtLeast(0f)
    return Offset(
        x = candidate.x.coerceIn(-maxOffsetX, maxOffsetX),
        y = candidate.y.coerceIn(-maxOffsetY, maxOffsetY),
    )
}

@Composable
private fun DetailChip(label: String, value: String) {
    AppChip(label = label, secondary = value, onClick = {})
}

private fun openActionLabel(typeInfo: FileTypeInfo): String = when (typeInfo.category) {
    com.example.watchfiles.data.FileCategory.OTHER -> "交给其他应用（类型未知）"
    else -> "交给其他应用 · ${typeInfo.category.displayName}"
}

private fun formatModifiedTime(modifiedAtMillis: Long?): String {
    if (modifiedAtMillis == null) return "未知"
    return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        .format(Date(modifiedAtMillis))
}

private fun yesNo(value: Boolean): String = if (value) "是" else "否"

@Composable
private fun DeviceInfoScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val snapshot = remember(context) { readDeviceSnapshot(context) }

    RoundList {
        item { ListHeader { Text("设备诊断") } }
        items(snapshot.rows, key = { it.first }) { (label, value) ->
            AppChip(label = label, secondary = value, onClick = {})
        }
    }
}

@Composable
internal fun AppChip(
    label: String,
    secondary: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Chip(
        modifier = modifier.fillMaxWidth(0.9f),
        onClick = onClick,
        enabled = enabled,
        label = {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        secondaryLabel = {
            Text(secondary, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 10.sp)
        },
        colors = ChipDefaults.secondaryChipColors(),
    )
}

@Composable
internal fun RoundList(
    state: ScalingLazyListState = rememberScalingLazyListState(),
    content: androidx.wear.compose.foundation.lazy.ScalingLazyListScope.() -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        ScalingLazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .rotaryScroll(state),
            state = state,
            // Xiaomi's modified system omits Google Wear haptic classes.
            // Our modifier below handles crown scrolling without that dependency.
            rotaryScrollableBehavior = null,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content,
        )
        PositionIndicator(scalingLazyListState = state)
    }
}

@Composable
private fun Modifier.rotaryScroll(state: ScalingLazyListState): Modifier {
    val focusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    return this
        .onRotaryScrollEvent { event ->
            scope.launch { state.scrollBy(event.verticalScrollPixels) }
            true
        }
        .focusRequester(focusRequester)
        .focusable()
}
