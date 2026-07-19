# WatchFiles M2 启动基线与简易文本查看/编辑实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or **superpowers:executing-plans** to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在小米 Watch 5 上建立可重复的启动、目录和 PSS 基线，并实现 UTF-8 纯文本的分段查看、小文件编辑、当前文件覆盖和当前目录内安全另存为。

**Architecture:** 先完成只读性能测量并记录是否存在可重复瓶颈；只有证据支持时才修改启动或目录加载路径。文本能力拆成纯 Kotlin 的策略/分段读取、文本页面状态、故障可注入的安全写入和事务恢复模块，Compose 页面只负责状态呈现和用户确认，不直接操作文件。

**Tech Stack:** Kotlin 2.0.21、Android Compose/Wear Compose、`java.nio.file`、Kotlin coroutines、JUnit 4、现有 `TemporaryFolder` 和 `MainDispatcherRule`；不新增 Android 或媒体依赖。

## Global Constraints

- 保持 `targetSdk 29`。
- 保持 `android:requestLegacyExternalStorage="true"`。
- 保持 `armeabi-v7a`。
- 保持小米 Watch 5 自定义表冠滚动兼容逻辑，不能恢复 Wear Compose 默认触觉旋转路径。
- 每个设备会话先动态执行 `adb devices -l` 和 `adb mdns services`，不复用历史无线 ADB 地址。
- 启动基线期间不创建、删除或修改真机文件。
- 文本真实写入只允许 `/storage/emulated/0/Download/WatchFilesTest/M1Sandbox`。
- 文本覆盖和另存为核心逻辑必须先写失败测试，再实现最小修复，并包含故障注入和崩溃恢复测试。
- 目标冲突必须明确确认；不得使用未经确认的旧目标删除、`ATOMIC_MOVE` 或 `REPLACE_EXISTING`。
- 开发期间只构建和安装 Debug；不提前构建 Release。
- 不开发内置音频播放器、视频播放器、ZIP 查看/解压、收藏、最近目录、搜索、用户可选排序或独立文件属性页。
- 不修改或暂存 `docs/superpowers/workflow/2026-07-17-workflow-simplification-memo.md`。

## 文件结构与职责

- Create `app/src/main/java/com/example/watchfiles/text/TextFilePolicy.kt`: 纯文本扩展名、32 KiB 分段、16 MiB 查看上限、512 KiB 编辑上限和可编辑性判断。
- Create `app/src/main/java/com/example/watchfiles/text/TextFileReader.kt`: `Dispatchers.IO` 文件读取、严格 UTF-8 解码和安全边界文本段。
- Create `app/src/main/java/com/example/watchfiles/text/TextDocumentModels.kt`: 文本段、文档快照、编辑状态、保存请求/结果、事务阶段和故障注入点。
- Create `app/src/main/java/com/example/watchfiles/text/TextTransactionJournal.kt`: 事务记录接口及应用私有 `SharedPreferences` 适配器。
- Create `app/src/main/java/com/example/watchfiles/text/SafeTextWriteRepository.kt`: 当前目录校验、临时文件、backup、发布、摘要校验、失败恢复和恢复扫描。
- Create `app/src/main/java/com/example/watchfiles/text/TextDocumentViewModel.kt`: 文本加载、分页、编辑草稿、保存确认和 writer 协调。
- Create `app/src/main/java/com/example/watchfiles/text/TextDocumentScreen.kt`: 圆屏文本查看/编辑 UI；复用同包中的 `RoundList` 和 `AppChip`。
- Modify `app/src/main/java/com/example/watchfiles/MainActivity.kt`: 注册文本 ViewModel、增加文本页面路由、从文件详情进入文本页、处理返回和保存确认；不实现文件写入。
- Create `app/src/test/java/com/example/watchfiles/text/TextFileReaderTest.kt`、`SafeTextWriteRepositoryTest.kt`、`TextDocumentViewModelTest.kt` 和必要的 fake journal/fault injector。
- Create `docs/superpowers/checkpoints/2026-07-19-m2-startup-baseline.md` 和 `docs/superpowers/checkpoints/2026-07-19-m2-closeout.md`。
- Modify `docs/superpowers/checkpoints/TESTING.md` 和 `docs/superpowers/roadmap/PROJECT_PLAN.md`，只同步 M2 证据和状态。

---

### Task 1: 建立只读启动与目录性能基线

**Files:**
- Create: `docs/superpowers/checkpoints/2026-07-19-m2-startup-baseline.md`
- Verify only: `app/src/main/java/com/example/watchfiles/MainActivity.kt`
- Verify only: `app/src/main/java/com/example/watchfiles/browser/FileBrowserViewModel.kt`
- Verify only: `app/src/main/java/com/example/watchfiles/data/DirectPathRepository.kt`

**Interfaces:**
- Consumes: 当前 Debug APK、当次动态发现的在线 ADB serial、现有主页/目录/图片页面。
- Produces: 可复核的 5 次以上原始时间和 PSS 数据，以及“需要优化/无需优化”的明确决定；不产生真机文件写入。

- [ ] **Step 1: 动态发现本次设备 serial**

运行：

```powershell
adb devices -l
adb mdns services
```

记录当次在线的 `M2505W1/grasslte` serial。若同一设备同时显示旧的 offline 地址和新的 mDNS serial，只使用在线条目；不把历史 IP 写入 checkpoint。

- [ ] **Step 2: 确认仅安装 Debug 包**

运行：

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME='C:\Users\13073\Documents\AndroidSDK'
.\gradlew.bat :app:assembleDebug --no-daemon --console=plain
adb -s <当前在线serial> install -r 'app\build\outputs\apk\debug\app-debug.apk'
```

预期：`assembleDebug` 成功，设备安装的是 `com.example.watchfiles.debug`；不运行 Release 构建。

- [ ] **Step 3: 测量 5 次冷启动**

每次运行：

```powershell
adb -s <当前在线serial> shell am force-stop com.example.watchfiles.debug
adb -s <当前在线serial> shell am start -W -n com.example.watchfiles.debug/.MainActivity
```

记录 `ThisTime`、`TotalTime`、`WaitTime`，并用同一观察方法记录权限页或主页首次可交互时间。每次启动之间只等待页面稳定，不创建、删除或修改设备文件。

- [ ] **Step 4: 测量 5 次热启动和主页进入目录**

热启动从后台重新打开应用，记录到首屏可交互时间；随后从主页进入一个既有普通目录，记录到列表可见时间。两个指标各记录 5 次，保存目录路径和是否首次加载。

- [ ] **Step 5: 测量既有大目录和 PSS**

使用设备上已经存在的受控大目录，不为基线创建 fixture。普通目录、大目录和主页/目录/图片页 PSS 各记录 5 次：

```powershell
adb -s <当前在线serial> shell dumpsys meminfo com.example.watchfiles.debug
```

连续进入同一张既有图片并返回 5 次后再次记录 PSS，确认是否持续上涨。若设备不存在满足条件的大目录，记录“无既有大目录，未人为创建”，不要伪造数据。

- [ ] **Step 6: 写入基线 checkpoint 并计算统计值**

在 `2026-07-19-m2-startup-baseline.md` 中保存日期、APK SHA-256、设备 serial、每次原始结果、中位数、最慢值和 PSS 变化。对每项标注“至少 3/5 次重复出现的可定位瓶颈”或“未发现可重复瓶颈”。

- [ ] **Step 7: 提交只读基线记录**

运行：

```powershell
git diff --check
git status --short
```

预期：checkpoint 无尾随空白；工作区仍保留且未暂存用户 memo。

### Task 2: 按基线证据决定是否做启动优化

**Files:**
- Modify only when Task 1 identifies a reproducible bottleneck: `app/src/main/java/com/example/watchfiles/MainActivity.kt`, `app/src/main/java/com/example/watchfiles/browser/FileBrowserViewModel.kt`, or `app/src/main/java/com/example/watchfiles/data/DirectPathRepository.kt`
- Modify: `docs/superpowers/checkpoints/2026-07-19-m2-startup-baseline.md`

**Interfaces:**
- Consumes: Task 1 的原始数据和瓶颈判断。
- Produces: 与基线数据直接对应的最小优化，或明确记录“无可证实瓶颈，因此不改源代码”。

- [ ] **Step 1: 对照代码确认瓶颈归属**

只检查首屏初始化、`FileBrowserViewModel.open()` 的加载状态、`DirectPathRepository.list()` 的属性读取/排序和图片页生命周期。不要为未测得的问题添加缓存、播放器、媒体解析或新依赖。

- [ ] **Step 2: 无瓶颈时记录不修改决定**

如果没有同一阶段在至少 3/5 次中重复出现的可定位瓶颈，在 checkpoint 中写明测量结论和“本阶段不做启动优化”，不修改上述三个源文件。

- [ ] **Step 3: 有瓶颈时只实施一项最小修改**

若首屏被非必要工作阻塞，只把该工作移到首屏之后并保持 `isLoading`/错误状态可见；若目录读取是瓶颈，只调整已被数据指向的属性读取或排序路径，继续使用 `Dispatchers.IO` 和固定“文件夹优先、名称排序”。不能改变目录排序规则、权限兼容或表冠滚动。

- [ ] **Step 4: 重跑受影响指标并更新对照**

重复受影响的指标至少 5 次，把优化前后原始值、中位数和最慢值写入 checkpoint。若优化没有改善或引入回归，撤销该针对性改动并记录结论，不扩大优化范围。

### Task 3: 以失败测试驱动 UTF-8 策略和分段读取

**Files:**
- Create: `app/src/main/java/com/example/watchfiles/text/TextFilePolicy.kt`
- Create: `app/src/main/java/com/example/watchfiles/text/TextFileReader.kt`
- Create: `app/src/main/java/com/example/watchfiles/text/TextDocumentModels.kt`
- Create: `app/src/test/java/com/example/watchfiles/text/TextFileReaderTest.kt`

**Interfaces:**
- Consumes: `java.nio.file.Path` 和现有文本分类边界。
- Produces: 后续 ViewModel 使用的以下稳定接口：

```kotlin
const val TEXT_PAGE_BYTES: Int = 32 * 1024
const val MAX_VIEWABLE_TEXT_BYTES: Long = 16L * 1024 * 1024
const val MAX_EDITABLE_TEXT_BYTES: Long = 512L * 1024

fun isSimpleTextPath(path: Path): Boolean

data class TextSegment(
    val startByte: Long,
    val endByte: Long,
    val text: String,
    val hasPrevious: Boolean,
    val hasNext: Boolean,
)

sealed interface TextOpenResult {
    data class Ready(
        val sizeBytes: Long,
        val firstSegment: TextSegment,
        val editable: Boolean,
        val editDisabledReason: String?,
    ) : TextOpenResult

    data class Unsupported(val message: String) : TextOpenResult
    data class Failed(val message: String, val technicalMessage: String? = null) : TextOpenResult
}

class TextFileReader {
    suspend fun open(path: Path): TextOpenResult
    suspend fun readSegment(path: Path, startByte: Long): TextSegment
    suspend fun readEditable(path: Path): String
}
```

- [ ] **Step 1: 写扩展名和大小策略失败测试**

在 `TextFileReaderTest.kt` 先加入以下测试，使用 `TemporaryFolder` 写入 fixture：

```kotlin
@Test fun txtAndKnownPlainTextExtensionsAreSupported()
@Test fun unknownExtensionIsNotGuessedAsEditableText()
@Test fun fileOverViewLimitReturnsUnsupported()
@Test fun fileOverEditLimitIsReadyButReadOnly()
```

- [ ] **Step 2: 写 UTF-8 和段边界失败测试**

加入空文件、单行、多行、`LF`、`CRLF`、Unicode 和 32 KiB 边界多字节字符测试：

```kotlin
@Test fun emptyFileProducesEmptyFirstSegment()
@Test fun segmentPreservesLineEndingsAndEmptyLines()
@Test fun segmentNeverSplitsUtf8Character()
@Test fun invalidUtf8ReturnsUnsupportedInsteadOfReplacementCharacters()
```

- [ ] **Step 3: 运行失败测试确认红灯**

运行：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests 'com.example.watchfiles.text.TextFileReaderTest' --no-daemon --console=plain
```

预期：测试因文本包和方法不存在而失败；不得跳过失败测试直接写实现。

- [ ] **Step 4: 实现最小策略和严格 decoder**

在 `TextFilePolicy.kt` 固定常量和扩展名集合；在 `TextFileReader.kt` 使用 `Files.size`、`FileChannel` 和 `Dispatchers.IO`。UTF-8 解码器必须配置为报告错误：

```kotlin
StandardCharsets.UTF_8.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT)
    .onUnmappableCharacter(CodingErrorAction.REPORT)
```

段读取从安全的上一个 `endByte` 开始，读取到约 32 KiB 后继续补足被截断的 UTF-8 字符；返回实际安全的 `endByte`。不得把 16 MiB 文件整体转换为字符串。

- [ ] **Step 5: 实现小文件全文读取**

`readEditable(path)` 只接受不超过 `MAX_EDITABLE_TEXT_BYTES` 的简单纯文本，严格读取和解码完整 UTF-8 字节；超限、不可读或非法编码分别返回明确异常/结果，保留换行，不替换无效字节。

- [ ] **Step 6: 运行读取聚焦测试并提交**

运行同一测试命令，预期全部 PASS；再运行：

```powershell
git diff --check
git add app/src/main/java/com/example/watchfiles/text app/src/test/java/com/example/watchfiles/text/TextFileReaderTest.kt
git commit -m "feat: add bounded UTF-8 text reader"
```

### Task 4: 以失败测试驱动安全文本写入和事务模型

**Files:**
- Create: `app/src/main/java/com/example/watchfiles/text/TextTransactionJournal.kt`
- Create: `app/src/main/java/com/example/watchfiles/text/SafeTextWriteRepository.kt`
- Modify: `app/src/main/java/com/example/watchfiles/text/TextDocumentModels.kt`
- Create: `app/src/test/java/com/example/watchfiles/text/SafeTextWriteRepositoryTest.kt`

**Interfaces:**
- Consumes: Task 3 的编辑上限和 UTF-8 内容。
- Produces: 以下 writer 接口供 ViewModel 和 UI 使用；所有文件操作在实现内部的 `Dispatchers.IO` 执行：

```kotlin
data class TextWriteRequest(
    val source: Path,
    val currentDirectory: Path,
    val targetName: String,
    val content: String,
    val expectedSourceDigest: String,
    val overwriteConfirmed: Boolean,
)

enum class TextWriteFaultPoint {
    CREATE_TEMP,
    WRITE_TEMP,
    FORCE_TEMP,
    MOVE_TARGET_TO_BACKUP,
    MOVE_TEMP_TO_TARGET,
    VERIFY_TARGET,
}

fun interface TextWriteFaultInjector {
    fun throwIfRequested(point: TextWriteFaultPoint)
}

sealed interface TextWriteResult {
    data class Success(val target: Path) : TextWriteResult
    data class Failure(val userMessage: String, val technicalMessage: String? = null) : TextWriteResult
    data object Cancelled : TextWriteResult
}

class SafeTextWriteRepository(
    private val journal: TextTransactionJournal,
    private val faultInjector: TextWriteFaultInjector = TextWriteFaultInjector { },
) {
    suspend fun save(request: TextWriteRequest): TextWriteResult
    suspend fun recover(): List<TextRecoveryResult>
}
```

- [ ] **Step 1: 写覆盖和另存为失败测试**

先在 `SafeTextWriteRepositoryTest.kt` 写以下测试，全部使用 `TemporaryFolder`，断言原始字节而不是只断言字符串：

```kotlin
@Test fun overwriteRequiresExplicitConfirmation()
@Test fun overwriteKeepsTargetContentOnSuccessUntilPublish()
@Test fun saveAsCreatesTargetInCurrentDirectoryAndKeepsSource()
@Test fun saveAsRejectsPathSeparatorAndParentEscape()
@Test fun existingSaveAsTargetRequiresConfirmation()
@Test fun sourceDigestChangeRejectsStaleOverwrite()
```

- [ ] **Step 2: 写故障注入和取消失败测试**

对每个 `TextWriteFaultPoint` 注入异常，加入：

```kotlin
@Test fun tempWriteFailureLeavesOriginalBytesAndNoPartFile()
@Test fun forceFailureLeavesOriginalBytesAndNoPartFile()
@Test fun backupFailureLeavesOriginalBytes()
@Test fun publishFailureRestoresOriginalBytes()
@Test fun cancellationBeforePublishLeavesOriginalBytes()
```

每个测试检查源/目标 SHA-256、`.part`、`.backup` 和 fake journal；禁止测试通过删除旧目标来“修复”结果。

- [ ] **Step 3: 运行保存红灯测试**

运行：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests 'com.example.watchfiles.text.SafeTextWriteRepositoryTest' --no-daemon --console=plain
```

预期：因 writer 和事务接口尚未实现而失败。

- [ ] **Step 4: 实现目标校验和摘要核对**

在 writer 内先用 `FileNameRules.validate(request.targetName)` 校验名称，要求 `request.source.parent == request.currentDirectory`，目标父目录是实际目录；使用 `NOFOLLOW_LINKS` 检查源、目标和 backup，拒绝符号链接。保存前重新计算源 SHA-256，与 `expectedSourceDigest` 不同则返回失败且不创建发布目标。

- [ ] **Step 5: 实现同目录临时文件和安全发布**

临时文件使用同目录唯一名称和 `CREATE_NEW`，通过 `FileChannel` 分块写 UTF-8 内容并调用 `force(true)`。目标不存在时只移动临时文件；目标存在时仅在 `overwriteConfirmed == true` 后将旧目标移动到唯一 backup，再移动临时文件到目标。实现中不得出现：

```kotlin
StandardCopyOption.ATOMIC_MOVE
StandardCopyOption.REPLACE_EXISTING
```

失败时按阶段清理或恢复，绝不使用原地 `TRUNCATE_EXISTING` 修改原文件。

- [ ] **Step 6: 实现结果映射和正常清理**

将 `FileAlreadyExistsException`、`NoSuchFileException`、`AccessDeniedException`、`SecurityException`、`IOException` 和 `CancellationException` 映射为明确的 `TextWriteResult`。取消只在临时文件发布前生效；成功发布后返回 `Success`。成功后校验目标摘要，再删除本事务 backup 和 journal，不清理用户自己的点文件。

- [ ] **Step 7: 运行故障注入测试并提交核心 writer**

运行：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests 'com.example.watchfiles.text.SafeTextWriteRepositoryTest' --no-daemon --console=plain
```

预期：全部 PASS；再运行 `git diff --check` 并提交：

```powershell
git add app/src/main/java/com/example/watchfiles/text app/src/test/java/com/example/watchfiles/text/SafeTextWriteRepositoryTest.kt
git commit -m "feat: add transactional text writes"
```

### Task 5: 实现事务 journal 和崩溃恢复

**Files:**
- Modify: `app/src/main/java/com/example/watchfiles/text/TextTransactionJournal.kt`
- Modify: `app/src/main/java/com/example/watchfiles/text/SafeTextWriteRepository.kt`
- Modify: `app/src/test/java/com/example/watchfiles/text/SafeTextWriteRepositoryTest.kt`

**Interfaces:**
- Consumes: Task 4 的 `TextWriteRequest`、阶段枚举和 fake journal。
- Produces: 可在应用启动后后台执行、并在下一次文本操作前再次执行的 `recover()`；正常事务不留下任务拥有的临时文件。

- [ ] **Step 1: 定义事务记录和 journal 接口**

在 `TextTransactionJournal.kt` 加入：

```kotlin
data class TextTransactionRecord(
    val id: String,
    val target: Path,
    val temp: Path,
    val backup: Path?,
    val expectedTargetDigest: String,
    val phase: TextTransactionPhase,
)

enum class TextTransactionPhase { STAGED, BACKED_UP, PUBLISHED, CLEANED }

interface TextTransactionJournal {
    fun upsert(record: TextTransactionRecord)
    fun remove(id: String)
    fun list(): List<TextTransactionRecord>
}
```

- [ ] **Step 2: 先写恢复红灯测试**

使用 fake journal 和临时目录加入：

```kotlin
@Test fun stagedTransactionDeletesOnlyItsTempFile()
@Test fun backedUpTransactionRestoresMissingTarget()
@Test fun backedUpTransactionWithExpectedTargetCleansBackup()
@Test fun ambiguousRecoveryKeepsBackupAndJournal()
@Test fun recoveryNeverDeletesUnownedPartOrBackupFile()
```

- [ ] **Step 3: 实现阶段更新和恢复规则**

在每个外部文件移动前更新 journal。`STAGED` 只清理事务 temp；`BACKED_UP` 且 target 缺失时恢复 backup；target 摘要等于预期新摘要时视为已发布并清理 backup；目标状态不一致时保留 backup 和记录并返回明确恢复失败。

- [ ] **Step 4: 实现应用私有 journal 适配器**

使用 `SharedPreferences` 保存每个事务的独立 key 前缀 `watchfiles.text.tx.<id>.`，字段固定为 `target`、`temp`、`backup`、`expectedTargetDigest` 和 `phase`，使用 `commit()` 完成阶段更新。生产构造函数从 `applicationContext` 提供的 `SharedPreferences` 创建 journal；单元测试继续使用 fake journal，不依赖真实 Android Context。

- [ ] **Step 5: 运行恢复测试并提交**

运行：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests 'com.example.watchfiles.text.SafeTextWriteRepositoryTest' --no-daemon --console=plain
```

预期：保存和恢复测试全部 PASS；提交：

```powershell
git add app/src/main/java/com/example/watchfiles/text app/src/test/java/com/example/watchfiles/text/SafeTextWriteRepositoryTest.kt
git commit -m "feat: recover interrupted text transactions"
```

### Task 6: 实现文本 ViewModel 和状态转换

**Files:**
- Create: `app/src/main/java/com/example/watchfiles/text/TextDocumentViewModel.kt`
- Modify: `app/src/main/java/com/example/watchfiles/text/TextDocumentModels.kt`
- Create: `app/src/test/java/com/example/watchfiles/text/TextDocumentViewModelTest.kt`

**Interfaces:**
- Consumes: `TextFileReader`、`SafeTextWriteRepository`、`TextWriteRequest` 和文本段/保存结果。
- Produces: Compose 页面使用的 `StateFlow<TextDocumentUiState>` 和以下操作：

```kotlin
fun open(path: Path)
fun nextSegment()
fun previousSegment()
fun beginEditing()
fun updateDraft(content: String)
fun requestOverwriteConfirmation()
fun requestSaveAs(name: String)
fun confirmSave(overwriteConfirmed: Boolean)
fun cancelSave()
fun discardChanges()
```

- [ ] **Step 1: 定义 UI 状态和保存意图**

`TextDocumentUiState` 必须包含 path、当前 `TextSegment`、总字节数、编辑可用性及原因、draft、原始摘要、dirty、保存阶段、冲突确认和用户可见错误；初始状态为 `Idle`，加载时为 `Loading`。

- [ ] **Step 2: 写状态转换失败测试**

加入：

```kotlin
@Test fun openLoadsFirstSegmentOnIoDispatcher()
@Test fun nextAndPreviousSegmentUpdateOffsets()
@Test fun oversizedTextStaysReadOnly()
@Test fun beginEditingCopiesSmallFileIntoDraft()
@Test fun dirtyBackRequiresDiscardAndDoesNotSave()
@Test fun saveFailureKeepsDraftAndOriginalFile()
@Test fun saveSuccessClearsDirtyState()
@Test fun saveAsUsesCurrentDirectoryOnly()
```

- [ ] **Step 3: 运行 ViewModel 红灯测试**

运行：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests 'com.example.watchfiles.text.TextDocumentViewModelTest' --no-daemon --console=plain
```

预期：因 ViewModel 和状态尚未实现而失败。

- [ ] **Step 4: 实现加载、分页和编辑草稿**

使用 `viewModelScope` 调用 reader；加载期间不阻塞主线程。只有 `TextOpenResult.Ready.editable == true` 才允许 `beginEditing()`；编辑超限、非法 UTF-8、未知类型和读取失败只读或显示明确原因。

- [ ] **Step 5: 实现保存确认和 writer 协调**

覆盖当前文件生成 `targetName = source.fileName.toString()`；另存为先验证当前目录内名称，再检查目标是否存在，存在时只把页面状态置为冲突确认，不直接调用 writer。确认后把 `overwriteConfirmed` 传给 writer；成功清除 dirty，失败保留 draft 和错误，取消不改变原文件。

- [ ] **Step 6: 运行 ViewModel 测试并提交**

运行同一聚焦命令，预期全部 PASS；执行 `git diff --check` 后提交：

```powershell
git add app/src/main/java/com/example/watchfiles/text app/src/test/java/com/example/watchfiles/text/TextDocumentViewModelTest.kt
git commit -m "feat: add text document state flow"
```

### Task 7: 集成圆屏文本页面和导航

**Files:**
- Create: `app/src/main/java/com/example/watchfiles/text/TextDocumentScreen.kt`
- Modify: `app/src/main/java/com/example/watchfiles/MainActivity.kt:115-500,841-910`

**Interfaces:**
- Consumes: `TextDocumentViewModel.state`、文本操作方法、现有 `RoundList`、`AppChip` 和 `FileDetailsScreen`。
- Produces: 从文本文件详情进入、查看/分页/编辑/保存/另存为/取消/返回均可操作的圆屏 UI。

- [ ] **Step 1: 增加文本页面路由和 ViewModel**

在 `AppScreen` 增加 `TEXT_DOCUMENT`；在 `MainActivity` 注册 `TextDocumentViewModel`，使用 `applicationContext` 初始化生产 journal。`WatchFilesApp` 增加当前文本路径和进入文本页回调；不要把 writer 实例化在 Composable 内。

- [ ] **Step 2: 从文件详情增加文本入口**

扩展 `FileDetailsScreen` 的参数 `onOpenText: (FileEntry) -> Unit`。当 `identifyFileType(entry.path).category == FileCategory.TEXT` 时显示“查看文本”；读取失败或不支持时仍保留原有 MIME 和“用其他应用打开”。不改变既有图片预览、音视频外部打开和详情字段。

- [ ] **Step 3: 实现只读分段页面**

在 `TextDocumentScreen.kt` 显示文件名、当前字节范围、文本内容、上一段/下一段、只读原因和返回文件详情。页面内容使用现有圆屏列表和 `Text`，不引入 WebView、第三方编辑器或全文搜索。

- [ ] **Step 4: 实现小文件编辑页面**

编辑状态使用 `BasicTextField`，提供“保存覆盖当前文件”“另存为”“取消编辑”。保存按钮在调用 ViewModel 前必须进入确认状态；另存为输入框只接受一个当前目录文件名并复用 `FileNameRules` 的错误文案。

- [ ] **Step 5: 实现冲突、失败和返回提示**

同名目标显示目标名和“确认覆盖/取消”；保存中禁用重复提交；失败显示用户可理解的错误且保留草稿；有 dirty 草稿时系统 Back 先显示“放弃编辑/继续编辑”，不自动保存。发布后才允许显示成功并返回详情。

- [ ] **Step 6: 接入后台恢复**

在 `MainActivity.onCreate` 设置内容后启动一次恢复协程执行 `SafeTextWriteRepository.recover()`，恢复不得阻塞权限页或主页首屏；文本操作开始前再执行一次恢复检查，恢复异常通过文本页提示。

- [ ] **Step 7: 运行聚焦测试并提交 UI 集成**

运行：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests 'com.example.watchfiles.text.*' --tests 'com.example.watchfiles.browser.*' --no-daemon --console=plain
```

预期：文本、浏览器已有测试全部 PASS；提交：

```powershell
git add app/src/main/java/com/example/watchfiles/MainActivity.kt app/src/main/java/com/example/watchfiles/text
git commit -m "feat: add text viewer and editor UI"
```

### Task 8: Debug 真机只读验收与 M1Sandbox 写入验收

**Files:**
- Verify: `app/build/outputs/apk/debug/app-debug.apk`
- Verify: `/storage/emulated/0/Download/WatchFilesTest/M1Sandbox`
- Create/update locally: `docs/superpowers/checkpoints/2026-07-19-m2-closeout.md`

**Interfaces:**
- Consumes: 已通过 JVM 测试的 Debug APK、动态发现的在线 serial、M2 测试 fixture。
- Produces: 真实设备的只读启动/文本查看证据和受限文本写入证据；不触碰 M1Sandbox 之外的路径。

- [ ] **Step 1: 新设备会话重新动态发现 ADB**

运行：

```powershell
adb devices -l
adb mdns services
```

只选择当次在线 serial；无线调试反复不可用时停止设备写入验收，不尝试历史地址。

- [ ] **Step 2: 安装并核对 Debug 包**

运行：

```powershell
adb -s <当前在线serial> install -r 'app\build\outputs\apk\debug\app-debug.apk'
adb -s <当前在线serial> shell dumpsys package com.example.watchfiles.debug
```

核对 `targetSdk=29`、Debug 包名、`versionCode=6` 和无 Release 安装。

- [ ] **Step 3: 做只读文本查看验收**

在既有 `M1Sandbox` fixture 上验证空 UTF-8 文本、多行文本、分页、换行、返回目录位置、超编辑上限只读、非法 UTF-8 提示和未知类型外部打开。此步骤只读，不改变 fixture。

- [ ] **Step 4: 记录覆盖保存证据**

在 M1Sandbox 内准备受控源文件，保存前记录 SHA-256；执行覆盖确认，保存后重新计算内容摘要，检查源文件路径、目标内容、`.part`/`.backup` 和 crash audit。取消确认和模拟失败由自动化 fault-injection 覆盖，真机只做正常成功/取消/同名确认等不破坏 fixture 的验收。

- [ ] **Step 5: 记录当前目录另存为证据**

输入同一 M1Sandbox 当前目录内的新文件名，核对原文件不变、新文件内容摘要一致；使用已有名称验证明确冲突确认，取消后两份内容都不变。不得打开任意目录选择器，不得写入 M1Sandbox 外路径。

- [ ] **Step 6: 完成 crash audit 和 closeout 草稿**

运行：

```powershell
adb -s <当前在线serial> logcat -c
adb -s <当前在线serial> logcat -d AndroidRuntime:E *:S
adb -s <当前在线serial> shell dumpsys meminfo com.example.watchfiles.debug
```

把实际操作、预期、实际结果、SHA-256、临时文件审计、PSS、截图/日志路径写入 M2 closeout；未执行的项目标记为未执行，不写成通过。

### Task 9: 阶段文档、完整 gate 和最终提交

**Files:**
- Modify: `docs/superpowers/checkpoints/TESTING.md`
- Modify: `docs/superpowers/roadmap/PROJECT_PLAN.md`
- Modify: `docs/superpowers/checkpoints/2026-07-19-m2-closeout.md`
- Verify preserved: `docs/superpowers/workflow/2026-07-17-workflow-simplification-memo.md`

**Interfaces:**
- Consumes: Tasks 1-8 的基线、单测、构建、Lint 和真机证据。
- Produces: 可追溯的 M2 checkpoint、与实际实现一致的测试清单、路线图状态和一个不包含 memo 的阶段提交。

- [ ] **Step 1: 更新 M2 真机测试清单**

只在 `TESTING.md` 的 M2 部分补充实际页面名称、512 KiB/16 MiB 边界、同名确认、事务恢复、SHA-256 和 `.part`/`.backup` 检查；保留启动基线只读约束和 M1A-M1C 历史清单。

- [ ] **Step 2: 更新路线图状态**

只有当 closeout 具备实际测试证据后，才把 M2 的启动基线、分段纯文本查看和简易编辑复选框改为 `[x]`；若某项没有证据，保留 `[ ]` 并在 closeout 记录阻塞原因。

- [ ] **Step 3: 执行完整 Debug gate**

在项目根目录运行：

```powershell
.\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain
.\gradlew.bat :app:assembleDebug --no-daemon --console=plain
.\gradlew.bat :app:lintDebug --no-daemon --console=plain
git diff --check
```

预期：四项均成功；Lint 不引入新的与本阶段相关错误；不构建 Release。

- [ ] **Step 4: 审计提交范围**

运行：

```powershell
git status --short
git diff --stat
git diff -- docs/superpowers/workflow/2026-07-17-workflow-simplification-memo.md
```

预期：memo 仍为未跟踪且没有 diff 输出；提交只包含 M2 代码、测试、checkpoint、必要的测试清单和路线图，不包含构建产物或其他阶段文档。

- [ ] **Step 5: 提交 M2 阶段增量**

显式暂存计划中列出的 M2 文件，确认 `git diff --cached --check` 通过后提交：

```powershell
git add app/src/main/java/com/example/watchfiles/text app/src/main/java/com/example/watchfiles/MainActivity.kt app/src/test/java/com/example/watchfiles/text docs/superpowers/checkpoints/2026-07-19-m2-startup-baseline.md docs/superpowers/checkpoints/2026-07-19-m2-closeout.md docs/superpowers/checkpoints/TESTING.md docs/superpowers/roadmap/PROJECT_PLAN.md
git diff --cached --check
git commit -m "feat: complete M2 text workflow and performance baseline"
```

预期：提交成功，memo 没有进入暂存区；最终报告只声称有实际证据支持的功能和真机结果。
