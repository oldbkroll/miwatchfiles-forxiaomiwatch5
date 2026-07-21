# M3 前台文件操作服务实施计划

> 本计划用于执行已确认的设计：[M3 前台文件操作服务设计](../specs/2026-07-21-m3-foreground-file-operation-service-design.md)。

## 目标、架构与约束

- **目标**：将现有 COPY、MOVE、DELETE 文件操作从 Activity/ViewModel 的生命周期中解耦，迁移到同进程的前台 Service，使用户离开页面或熄屏后操作仍能继续，并在重新打开应用时恢复操作页与实时进度。
- **架构**：`FileOperationRunner`（Android-free 业务执行器）→ `FileOperationService`（前台 Service + Local Binder）→ `FileOperationServiceClient`（绑定与生命周期适配器）→ `FileOperationCoordinator`（ViewModel/UI façade）→ Compose 操作页面。
- **技术栈**：Kotlin、Coroutines、StateFlow、Android `Service`、`LocalBinder`、现有 `OperationScanner`/`OperationEngine`、JUnit4、`kotlinx-coroutines-test`、Android Instrumentation/ADB 验收。
- **全局约束**：
  - 仅处理 COPY、MOVE、DELETE；文本覆盖/另存为继续由 `SafeTextWriteRepository` 负责。
  - 不引入 WorkManager、AIDL、跨进程通信、数据库或新依赖。
  - Service 使用 `START_NOT_STICKY`；进程被系统杀死后不自动重试、不伪造恢复状态。
  - Notification 使用低重要性、持续显示、无声音/振动、无取消按钮；终态发布后移除并停止 Service。
  - Activity 离开或熄屏不取消任务；重新进入时自动绑定并显示当前任务。
  - Debug 版真实文件操作只能使用 `/storage/emulated/0/Download/WatchFilesTest/M1Sandbox`；每次设备验收动态发现 ADB serial。
  - 每个任务完成后运行该任务列出的测试；提交前运行完整 Debug gate。每个逻辑任务单独提交到当前 `main`，不创建新的开发分支。

## 文件变更总览

新增：

- `app/src/main/java/com/example/watchfiles/fileops/FileOperationRunner.kt`
- `app/src/main/java/com/example/watchfiles/fileops/FileOperationService.kt`
- `app/src/main/java/com/example/watchfiles/fileops/FileOperationNotification.kt`
- `app/src/main/java/com/example/watchfiles/fileops/FileOperationServiceClient.kt`
- `app/src/test/java/com/example/watchfiles/fileops/FileOperationRunnerTest.kt`
- `app/src/test/java/com/example/watchfiles/fileops/FileOperationServiceTest.kt`
- `app/src/test/java/com/example/watchfiles/fileops/FileOperationServiceClientTest.kt`
- `docs/context/m3-foreground-file-operation-service-closeout.md`

修改：

- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/example/watchfiles/MainActivity.kt`
- `app/src/main/java/com/example/watchfiles/fileops/FileOperationCoordinator.kt`
- `app/src/test/java/com/example/watchfiles/fileops/FileOperationCoordinatorTest.kt`
- `docs/TESTING.md`
- `docs/context/current-development-context.md`
- `docs/roadmap.md`

## Task 1：提取 Android-free 的 `FileOperationRunner`

### 目标

先把现有 Coordinator 中已经验证过的扫描、冲突、删除确认、取消与终态映射逻辑提取为可由 Service 持有的 Android-free 执行器。此步不改变用户可见行为，不添加 Service。

### 文件

- 新增 `app/src/main/java/com/example/watchfiles/fileops/FileOperationRunner.kt`。
- 新增 `app/src/test/java/com/example/watchfiles/fileops/FileOperationRunnerTest.kt`。
- 参考并迁移现有 `app/src/main/java/com/example/watchfiles/fileops/FileOperationCoordinator.kt` 与 `app/src/test/java/com/example/watchfiles/fileops/FileOperationCoordinatorTest.kt` 中的行为测试。

### 对外接口

Runner 应提供以下最小接口；类型名称以当前包中的实际定义为准：

```kotlin
interface FileOperationRunnerPort {
    val state: StateFlow<FileOperationState>

    fun start(type: FileOperationType, sources: List<Path>, targetDirectory: Path): Boolean
    fun prepareDelete(sources: List<Path>): Boolean
    fun confirmDelete(): Boolean
    fun replaceAll()
    fun cancel()
    fun consumeResult()
}

class FileOperationRunner(
    private val scanner: OperationScannerGateway = FileOperationScanner(),
    private val engine: OperationEngineGateway = FileOperationEngine(),
    private val taskIdFactory: () -> String = { UUID.randomUUID().toString() },
    private val errorLogger: (String, Throwable) -> Unit = { _, _ -> },
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : FileOperationRunnerPort, AutoCloseable {
    val state: StateFlow<FileOperationState>

    fun start(
        type: FileOperationType,
        sources: List<Path>,
        targetDirectory: Path,
    ): Boolean

    fun prepareDelete(sources: List<Path>): Boolean
    fun confirmDelete(): Boolean
    fun replaceAll()
    fun cancel()
    fun consumeResult()
    override fun close()
}
```

### TDD 步骤

1. 先新增测试并将现有 Coordinator 行为测试迁移到 Runner，至少覆盖：
   - 空输入直接拒绝且状态保持 `Idle`。
   - COPY/MOVE/DELETE 的扫描与执行状态转移。
   - 完成、失败、取消、冲突等待、`replaceAll` 后继续执行。
   - DELETE 先产生 `DeletePreview`，确认后才执行；取消确认不会执行删除。
   - 已有任务运行时拒绝第二个任务。
   - `consumeResult()` 清理终态回到 `Idle`。
2. 运行聚焦测试，确认新测试在实现未完成时按预期失败或无法编译。
3. 实现 Runner：
   - 用 `CoroutineScope(SupervisorJob() + dispatcher)` 持有任务，不依赖 `ViewModel.viewModelScope`。
   - 将当前 Coordinator 的 `MutableStateFlow`、任务 Job、取消标记、冲突决策、删除确认和进度映射原样迁移。
   - 保留现有 Scanner/Engine 的协作方式和 `FileOperationState` 语义，不在本任务调整引擎或模型。
   - 将当前 Android `Log.e` 调用改为注入的 `errorLogger`，默认实现为空，保证 Runner 不依赖 Android；Service 创建 Runner 时注入 Android 日志实现。
   - `close()` 取消 Scope 与当前 Job；不把 close 当作用户主动取消，也不伪造成功终态。
4. 运行 Runner 测试与原 Coordinator 测试，确认行为基线未变。

### 验证命令

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.watchfiles.fileops.FileOperationRunnerTest" --no-daemon --console=plain
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.watchfiles.fileops.FileOperationCoordinatorTest" --no-daemon --console=plain
```

### 提交

```text
refactor: extract file operation runner
```

## Task 2：新增前台 `FileOperationService` 与通知生命周期

### 目标

让 Service 成为 Runner 的宿主，提供同进程 Local Binder，接收操作启动请求并持续发布 Runner 状态；系统要求的前台通知从任务真正开始前建立，任务终态后移除。

### 文件

- 新增 `app/src/main/java/com/example/watchfiles/fileops/FileOperationService.kt`。
- 新增 `app/src/main/java/com/example/watchfiles/fileops/FileOperationNotification.kt`。
- 新增 `app/src/test/java/com/example/watchfiles/fileops/FileOperationServiceTest.kt`。
- 修改 `app/src/main/AndroidManifest.xml`。

### Port 契约

Service 内部与客户端共享一个最小端口接口，避免 UI 依赖 Service 具体实现：

```kotlin
interface FileOperationServicePort {
    val state: StateFlow<FileOperationState>
    fun start(type: FileOperationType, sources: List<Path>, targetDirectory: Path): Boolean
    fun prepareDelete(sources: List<Path>): Boolean
    fun confirmDelete(): Boolean
    fun replaceAll()
    fun cancel()
    fun consumeResult()
}
```

### 实现步骤

1. 在 Service 中定义稳定常量：

   ```kotlin
   private const val ACTION_START = "com.example.watchfiles.fileops.action.START"
   private const val EXTRA_TYPE = "extra_type"
   private const val EXTRA_SOURCES = "extra_sources"
   private const val EXTRA_TARGET_DIRECTORY = "extra_target_directory"
   private const val NOTIFICATION_CHANNEL_ID = "file_operations"
   private const val NOTIFICATION_ID = 1001
   ```

2. 实现 `FileOperationService : Service(), FileOperationServicePort`：
   - 在 `onCreate()` 创建 Runner、通知渠道和状态收集。
   - 用 `inner class LocalBinder : Binder()` 暴露 `getService(): FileOperationService`；`onBind()` 返回同一个 Binder。
   - `onStartCommand()` 解析 `FileOperationType`、`EXTRA_SOURCES` 中的字符串路径和目标目录字符串，在 Runner 接受任务前调用 `startForeground()`；非法参数或重复任务不启动第二个任务。Intent 只传输 String/StringArrayList，Service 内部再用 `Paths.get()` 转换。
   - 返回 `START_NOT_STICKY`。
   - 任务进入 `Succeeded`、`PartiallySucceeded`、`Failed`、`Cancelled` 后停止收集、移除通知、调用 `stopSelf()`；Activity 若仍绑定，仍能读取最后的终态。
   - 通知内容显示“WatchFiles 正在操作”、操作类型、当前项目和进度；使用低重要性，不配置声音/振动，不添加取消 Action。
   - `onDestroy()` 调用 Runner.close()；不在 Service 中创建第二套队列或持久化恢复逻辑。

3. Manifest 增加：

   ```xml
   <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
   ```

   并在 `<application>` 内注册 `android:exported="false"` 的 Service。不要把 Service 暴露给其他应用。

4. 将 `FileOperationServicePortAdapter` 放在 Service 同包内，用 `FileOperationRunnerPort` 做纯 Kotlin 转发 seam；Service 持有 Runner 和 Adapter，但不复制第二套任务状态。

### TDD 步骤

1. 先新增 `FileOperationServiceTest.kt`，写出并运行失败测试：
   - `idleAndTerminalStatesHaveNoNotificationContent`：`Idle` 与四种终态都不产生持续通知内容。
   - `runningNotificationContainsTypeCurrentNameAndProgress`：Running 状态的通知内容包含操作类型、`OperationProgress.currentName`、已处理/总数，并保持低重要性所需的无声音/振动策略输入。
   - `servicePortAdapterForwardsEveryRunnerCommand`：Adapter 的状态和 start、删除确认、覆盖、取消、消费结果全部来自 Runner Port。
2. 实现 `FileOperationNotification.kt` 中的纯 Kotlin `FileOperationNotificationContent` 与状态映射；实现 Service 文件中的 `FileOperationServicePortAdapter`，使上述测试通过。
3. 再实现 Android `FileOperationService`：
   - 在 `onCreate()` 创建 Runner、Adapter、通知渠道和状态收集；通知由纯 Kotlin 内容映射决定标题、正文和进度。
   - `startForeground()`/`stopForeground()` 只负责 Android 生命周期，真实调用不在 JVM 测试中伪造。
4. 运行服务 seam 测试和编译检查；任务 6 在真实 Debug 设备上验收 `startForeground`、通知移除和绑定生命周期。

### 验证命令

```powershell
.\gradlew.bat :app:assembleDebug --no-daemon --console=plain
```

### 提交

```text
feat: add foreground file operation service
```

## Task 3：实现 Service Client，并让 Coordinator 变为 UI façade

### 目标

保留 Activity 当前使用的 `start`、`prepareDelete`、`confirmDelete`、`replaceAll`、`cancel`、`consumeResult` API，同时把这些调用转交给 Service；Coordinator 不再直接持有 Scanner、Engine 或操作 Job。

### 文件

- 新增 `app/src/main/java/com/example/watchfiles/fileops/FileOperationServiceClient.kt`。
- 修改 `app/src/main/java/com/example/watchfiles/fileops/FileOperationCoordinator.kt`。
- 修改 `app/src/test/java/com/example/watchfiles/fileops/FileOperationCoordinatorTest.kt`。
- 扩展 `app/src/test/java/com/example/watchfiles/fileops/FileOperationServiceClientTest.kt`。

### Client 接口

```kotlin
interface FileOperationServiceGateway {
    val state: StateFlow<FileOperationState>
    fun connect()
    fun disconnect()
    fun start(type: FileOperationType, sources: List<Path>, targetDirectory: Path): Boolean
    fun prepareDelete(sources: List<Path>): Boolean
    fun confirmDelete(): Boolean
    fun replaceAll()
    fun cancel()
    fun consumeResult()
}
```

### 实现步骤

1. `FileOperationServiceClient` 使用 `applicationContext`，内部仅保留一份 `MutableStateFlow` 初始值 `Idle`、一个 `ServiceConnection` 和当前 Binder 引用。
2. `connect()` 构造指向 `FileOperationService` 的显式 Intent，并使用 `bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)`；绑定成功后取得 Service 的 Local Binder，将 Service 当前状态镜像到 Client，并开始收集，绑定失败则保持 `Idle` 并记录可诊断日志。
3. `start()`/`prepareDelete()` 先通过 `ContextCompat.startForegroundService()` 启动 Service，再在已连接时调用 Port；连接尚未完成时保留一次待发送命令，禁止排队第二个真实任务，并在连接成功后发送。待发送命令只存在内存中，进程死亡后不恢复。
4. `confirmDelete()`、`replaceAll()`、`cancel()`、`consumeResult()` 只转发到当前 Port；客户端不自行改变业务状态。
5. `disconnect()` 幂等地停止收集、解绑 Service；Coordinator `onCleared()` 必须调用它。
6. 将 Coordinator 改为构造注入 `FileOperationServiceGateway`：

   ```kotlin
   class FileOperationCoordinator(
       private val gateway: FileOperationServiceGateway,
   ) : ViewModel() {
       val state: StateFlow<FileOperationState> = gateway.state
       init { gateway.connect() }
       fun start(
           type: FileOperationType,
           sources: List<Path>,
           targetDirectory: Path,
       ): Boolean = gateway.start(type, sources, targetDirectory)
       fun prepareDelete(sources: List<Path>): Boolean = gateway.prepareDelete(sources)
       fun confirmDelete() = gateway.confirmDelete()
       fun replaceAll() = gateway.replaceAll()
       fun cancel() = gateway.cancel()
       fun consumeResult() = gateway.consumeResult()
       override fun onCleared() { gateway.disconnect() }
   }
   ```

   用 `ViewModelProvider.Factory` 从 `applicationContext` 构造真实 Client，避免在 ViewModel 中持有 Activity Context。

7. 重写 Coordinator 单元测试，使用 Fake Gateway 验证：
   - Coordinator 暴露 Gateway 的状态流。
   - `start` 与 `prepareDelete` 参数完整转发。
   - `confirmDelete`、`replaceAll`、`cancel`、`consumeResult` 完整转发。
   - 创建时 connect、清理时 disconnect，且 disconnect 可重复调用。
   - Coordinator 不再直接启动 Scanner/Engine。
8. 扩展 Client 测试覆盖绑定成功、绑定后读取已有 Running 状态、各控制命令转发、重连时不启动第二个任务、解绑后不继续收集。

   测试名至少固定为：`clientMirrorsBoundServiceState`、`clientForwardsAllControlCommands`、`clientQueuesAtMostOneStartUntilBound`、`clientDisconnectStopsStateCollection`。通过注入的绑定适配器和 Fake `FileOperationServicePort` 完成 JVM 测试，不增加 Robolectric 或其他依赖。

### 验证命令

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.watchfiles.fileops.FileOperationCoordinatorTest" --tests "com.example.watchfiles.fileops.FileOperationServiceClientTest" --no-daemon --console=plain
```

### 提交

```text
refactor: connect coordinator to file service
```

## Task 4：接回 MainActivity 的操作页与生命周期路由

### 目标

让 UI 在重新进入应用时根据 Service 当前状态自动回到正确页面；保留现有删除确认、冲突覆盖、取消和完成后的目录刷新语义。

### 文件

- 修改 `app/src/main/java/com/example/watchfiles/MainActivity.kt`。
- 复用任务 3 中的 `FileOperationCoordinator.Factory`，不在 Activity 内直接创建 Service 或 Runner。

### 实现步骤

1. 将

   ```kotlin
   private val fileOperationCoordinator by viewModels<FileOperationCoordinator>()
   ```

   改为使用 `applicationContext` 的 Factory：

   ```kotlin
   private val fileOperationCoordinator by viewModels<FileOperationCoordinator> {
       FileOperationCoordinator.Factory(applicationContext)
   }
   ```

2. 在 `WatchFilesApp` 收集 Service 镜像的 `operationState` 后增加唯一的状态路由副作用：
   - `WaitingForDeleteConfirmation` 路由到 `DELETE_CONFIRMATION`。
   - `Scanning`、`Running`、`WaitingForReplacement`、`Cancelling` 路由到 `FILE_OPERATION`。
   - `Idle` 和终态不主动覆盖当前导航；终态继续由已有 `finishPendingOperation()` 刷新目录、消费结果并返回 Browser。
3. 删除确认对话框与操作页使用现有状态和回调；不要在 UI 侧创建进度计时器或复制任务状态。
4. 保留 BackHandler 的安全规则：操作运行中不可通过返回键误取消；删除确认的返回键仍只关闭确认层；取消动作仍调用 Coordinator 的 `cancel()`。
5. 页面重新创建时若 Service 已处于 Running，首个状态快照必须直接显示当前进度和取消入口；若 Service 已终止，Activity 不应显示旧的假进度。

### 验证命令

```powershell
.\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain
.\gradlew.bat :app:assembleDebug --no-daemon --console=plain
```

### 提交

```text
feat: reconnect file operation UI to service
```

## Task 5：完成本地回归、Debug gate 与安全扫描

### 目标

在连接真实设备前确认单元测试、编译、静态检查和关键架构约束都通过，并建立 M3 设备验收记录模板。

### 文件

- 新增 `docs/context/m3-foreground-file-operation-service-closeout.md`，先写入待填写的验收表，不把未执行的设备结果写成通过。

### 验证命令

```powershell
.\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain
.\gradlew.bat :app:assembleDebug --no-daemon --console=plain
.\gradlew.bat :app:lintDebug --no-daemon --console=plain
git diff --check
```

执行架构约束扫描：

```powershell
   rg -n "START_STICKY|WorkManager" app/src/main
   rg -n "android:exported=\"true\"" app/src/main/AndroidManifest.xml | Select-String -NotMatch "activity"
rg -n "START_NOT_STICKY|FOREGROUND_SERVICE|FileOperationService|FileOperationRunner" app/src/main docs
```

前两条命令都应无输出；不得出现生产代码的 `START_STICKY`、WorkManager 依赖或非 Activity 的导出组件。第三条命令应能定位到本次实现的关键约束和类。

### 记录要求

在 closeout 中记录：测试任务与结果、lint 结果、Debug APK 路径与 SHA-256、构建时间、设备 serial、设备 API/型号。设备尚未连接时明确标记为 `PENDING_DEVICE`。

### 提交

```text
chore: verify M3 foreground service baseline
```

## Task 6：在设备上验收跨页面/熄屏期间的文件操作

### 目标

证明前台 Service 的用户可见行为，而不是只证明 Kotlin 单元测试通过。所有真实写入都限制在 M1Sandbox，测试前后记录文件清单和 SHA-256。

### 前置检查

1. 动态发现设备，不假设固定 serial：

   ```powershell
   adb devices -l
   adb mdns services
   $serial = (adb devices | Select-String "\tdevice$")[0].Line.Split("`t")[0]
   if ([string]::IsNullOrWhiteSpace($serial)) { throw "No authorized Android device" }
   ```

2. 安装当前 Debug APK，确认应用的 `targetSdkVersion`、`versionCode`、`versionName` 与本次构建记录一致；本项目当前预期为 target 29、versionCode 6、versionName `0.3.1-dev-debug`，若实现前发生变更，以实际构建输出为准并记录差异。
3. 在 `/storage/emulated/0/Download/WatchFilesTest/M1Sandbox` 创建受控 fixture：多个可区分的文件、至少一个同名冲突目录、可验证的源/目标清单；避免使用系统目录和用户真实文件。

### 验收场景

1. COPY/MOVE：从 Browser 启动操作，确认页面显示操作类型、当前项目、已完成/总数与取消入口；离开操作页或关闭屏幕，等待一段时间后重新打开 Activity，确认自动回到操作页并显示持续更新的同一任务。
2. 冲突：制造目标同名文件，确认覆盖等待页可恢复；选择替换全部后任务继续，选择取消后任务进入取消终态。
3. 完成：确认目标文件内容与源文件 SHA-256 符合预期，MOVE 的源文件状态符合预期，临时文件/残留不存在；通知在终态后消失，目录返回时刷新一次。
4. DELETE：启动删除后确认预览清单，确认后熄屏再打开，任务仍可观察；确认前取消不删除，确认后取消遵循已有逐项语义；确认删除后的目录内容与预览一致。
5. 进程边界记录：允许系统回收或使用受控的开发者手段结束进程，确认本版本不自动重试、不显示虚假恢复进度，并在 closeout 标为“恢复能力未实现”，不把它当作失败的 Service 生命周期。
6. 日志审计：采集 Service/Activity/文件操作相关 logcat，确认没有重复启动两个任务、未授权路径写入、未处理异常或通知未移除；清理测试 fixture 前再次记录结果清单。

### 设备命令示例

```powershell
adb -s $serial install -r app\build\outputs\apk\debug\app-debug.apk
adb -s $serial shell am start -n com.example.watchfiles/.MainActivity
adb -s $serial logcat -c
adb -s $serial logcat -d -v threadtime | Select-String "WatchFiles|FileOperation|FileOperationService"
```

命令中的包名和 APK 路径若以项目实际构建输出为准发生变化，必须在 closeout 中记录实际值；不要把示例值静默当作事实。

### 提交

```text
test: accept M3 foreground file operations on device
```

## Task 7：补齐文档、回写项目上下文并完成 M3 收口

### 文件

- 修改 `docs/TESTING.md`：加入 Service/Binder/通知/熄屏验收流程，明确真实写入边界、动态 serial、失败/未执行记录方式。
- 修改 `docs/context/current-development-context.md`：更新当前阶段、已完成的 M3 增量、最新测试基线、剩余风险和下一步；保留现有历史决策，不覆盖原有事实。
- 修改 `docs/roadmap.md`：仅在对应证据存在时勾选“前台文件操作服务”和“息屏继续文件操作”；文件操作恢复、性能、5000 文件和皇冠目录等未实现项继续保持未勾选。
- 更新 `docs/context/m3-foreground-file-operation-service-closeout.md`：填入本地和设备验证的实际结果；未执行项目使用 `PENDING_DEVICE`，不使用模糊的“已验证”。

### 实现/审计步骤

1. 运行完整 Debug gate：

   ```powershell
   .\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain
   .\gradlew.bat :app\assembleDebug --no-daemon --console=plain
   .\gradlew.bat :app\lintDebug --no-daemon --console=plain
   git diff --check
   ```

2. 做范围审计：`git diff --stat`、`git diff --name-only`，确认只包含 M3 设计范围内的代码、测试和文档；确认没有改动 SafeTextWriteRepository 的文本写入路径。
3. 在 closeout 中写明真实测试总数、构建产物、设备验收结果和已知限制。
4. 若用户要求同步远程，再单独执行推送；本计划本身默认只提交到本地 `main`，不擅自推送。

### 提交

```text
docs: record M3 foreground service acceptance
```

## 规格覆盖检查

| 已确认设计项 | 计划落点 |
| --- | --- |
| COPY/MOVE/DELETE 迁移到 Runner + Service | 任务 1、任务 2 |
| 文本写入留在 SafeTextWriteRepository | 全局约束、任务 7 范围审计 |
| Started + bound、同进程 Local Binder | 任务 2、任务 3 |
| Coordinator 保留 UI façade API | 任务 3、任务 4 |
| 低重要性持续通知、终态移除 | 任务 2、任务 6 |
| Activity 离开/熄屏继续、重开自动回操作页 | 任务 4、任务 6 |
| `START_NOT_STICKY`、不自动恢复 | 全局约束、任务 2、任务 6 |
| Runner/Service/client/UI/回归测试 | 任务 1、任务 2、任务 3、任务 5、任务 6 |
| M1Sandbox 与动态 ADB serial | 全局约束、任务 6 |
| 文档与 roadmap 诚实收口 | 任务 5、任务 7 |

## 计划自审清单

- [x] 文件路径与当前项目包名、目录结构一致。
- [x] 每项实现都有明确文件、接口、测试和命令，没有依赖未说明的产品决策。
- [x] 没有引入 WorkManager、AIDL、跨进程、数据库或新的外部依赖。
- [x] 没有把文本写入迁移到 Service。
- [x] 没有把进程死亡后的恢复能力写成 M3 已实现。
- [x] 设备验收只写入 M1Sandbox，且 serial 动态发现。
- [x] 文档只在有证据时勾选 roadmap 项目。
