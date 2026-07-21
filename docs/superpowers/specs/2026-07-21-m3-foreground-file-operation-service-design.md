# WatchFiles M3 前台文件操作服务设计

日期：2026-07-21
状态：设计已确认，待文档审阅

## 1. 目标

M3 的第一个增量把现有 COPY、MOVE、DELETE 文件操作从 Activity/ViewModel 的前台协程迁移到 Android 前台服务，使任务在 Activity 进入后台或手表息屏后继续执行，同时保持 M1 已验收的文件安全语义。

本增量优先解决任务生命周期问题，不改变文件操作引擎的安全规则，也不提前实现操作恢复日志。

## 2. 范围与非目标

### 本增量范围

- 新增 `FileOperationService`，采用 started + bound 前台服务模式。
- 从现有协调器中抽取纯 Kotlin 的 `FileOperationRunner`。
- Service 负责任务生命周期、前台通知和 Runner 持有。
- Activity 通过本地 Binder 连接 Service，继续使用现有操作页面和状态模型。
- COPY、MOVE、DELETE 均由 Service 执行，并保持单任务限制。
- Activity 离开或手表息屏时任务继续执行。
- Activity 重新打开时自动连接正在运行的 Service，并回到操作页面。

### 非目标

- 不迁移文本覆盖和当前目录内另存为；它们继续使用 `SafeTextWriteRepository`。
- 不实现操作恢复日志、进程被杀后的自动恢复或自动重试。
- 不使用 WorkManager、AIDL、独立进程或新的第三方依赖。
- 不改变现有目录排序、表冠滚动、权限兼容或文件事务规则。
- 不在本增量构建 Release 或更新阶段发布包。

## 3. 不变的安全与设备约束

- 保持 `targetSdk 29`、`requestLegacyExternalStorage` 和 `armeabi-v7a`。
- 保持小米 Watch 5 的 `rotaryScrollableBehavior = null` 与自定义表冠滚动逻辑。
- 所有扫描和文件操作继续在 IO 调度器执行，不阻塞主线程。
- COPY/MOVE/DELETE 继续复用现有 Scanner、Engine、冲突确认、删除确认、取消和结果分类。
- 不使用未经确认的覆盖或静默永久删除；不跟随符号链接；只清理任务明确拥有的临时文件。
- 真机写操作严格限定在 `/storage/emulated/0/Download/WatchFilesTest/M1Sandbox`。
- 每次真机设备会话动态发现在线无线 ADB serial。

## 4. 组件边界与数据流

### `FileOperationRunner`

`FileOperationRunner` 是不依赖 Android UI 的纯 Kotlin 任务执行组件，负责：

- 持有一个活动任务和任务 `StateFlow`；
- 调用现有 Scanner 和 Engine；
- 管理协作式取消、冲突等待和 DELETE 独立确认；
- 保持现有 `FileOperationState`、`FileOperationResult` 和失败消息语义；
- 拒绝第二个并发任务。

Runner 的依赖通过构造函数注入，便于 JVM 测试，不直接持有 Activity、Compose 或 ViewModel。

### `FileOperationService`

Service 负责：

- 创建 Service 生命周期范围和 Runner；
- 接收任务启动命令并调用 `startForeground`；
- 通过本地 Binder 暴露当前状态和任务控制方法；
- 在 Activity 解绑后继续持有 Runner；
- 任务终态产生后停止前台状态并清理自身。

Service 与应用处于同一进程，不引入跨进程序列化。Intent 只传递操作类型、源路径字符串和目标路径字符串，Service 内部再转换为 `Path`。

### `FileOperationCoordinator`

保留现有对 UI 的操作接口，包括 `start`、`prepareDelete`、`confirmDelete`、`replaceAll`、`cancel` 和 `consumeResult`。内部改为连接 Service、镜像 Service 状态并转发用户操作，避免大幅修改现有 `MainActivity` 和 `FileOperationScreens`。

数据流为：

`Activity/ViewModel → Service → FileOperationRunner → FileOperationState → ViewModel/UI 与前台通知`

## 5. Service 启动与通知

- Manifest 增加 `android.permission.FOREGROUND_SERVICE`。
- 注册非导出的 `FileOperationService`，不设置独立进程。
- Android O 及以上使用 `startForegroundService`；Service 在系统规定时间内调用 `startForeground`。
- 创建低重要性通知渠道，通知标题为 `WatchFiles 正在操作`。
- 通知显示 COPY/MOVE/DELETE 类型、当前项目和简短进度；不播放声音、不震动。
- 首个增量不提供通知内的取消按钮，取消继续通过操作页面完成。
- 任务结束、失败或取消后移除前台通知；不保留完成通知。

## 6. 生命周期与异常语义

1. 用户开始任务时，Coordinator 启动并绑定 Service。
2. Service 先进入前台并创建通知，再把请求交给 Runner。
3. Activity 可解绑，Service 继续执行任务；息屏不触发任务取消。
4. Activity 重新打开时重新绑定 Service，读取当前状态并自动显示操作页面。
5. 任务完成、部分完成、失败或取消后，Service 发布终态、移除通知并停止自身；若 Activity 当时可见，则消费终态并刷新目录；若不可见，下一次打开应用时按正常目录读取刷新。

Service 使用 `START_NOT_STICKY`。如果进程或 Service 被系统杀死，首个增量不自动重试、不猜测任务状态，也不伪造成功；操作恢复日志和可恢复事务处理留给后续 M3 增量。

取消继续采用现有协作式语义：

- COPY/MOVE：当前数据块结束后停止，清理任务拥有的未发布临时项目，已发布目标保留；
- DELETE：已删除内容不恢复，未处理内容保留，并显示不可恢复提示；
- 冲突等待和删除确认阶段的取消保持现有行为，不调用未确认的破坏性操作。

## 7. 测试与验收

### 自动化测试

- `FileOperationRunner` 覆盖启动、单任务互斥、扫描失败、进度、冲突等待、替换全部、取消、DELETE 确认和终态映射。
- Service/本地 Binder 覆盖前台启动、状态读取、控制方法转发、重复启动拒绝、终态停止和通知创建/移除。
- 现有 FileOperation Scanner、Engine、Coordinator、文本事务和浏览器测试全部回归。
- 不依赖真实手表权限或目录来验证失败路径；继续使用注入的文件系统和临时目录。

### 小米 Watch 5 真机验收

所有真实写入只使用 `/storage/emulated/0/Download/WatchFilesTest/M1Sandbox`，并在每次会话动态发现 ADB serial。至少覆盖：

1. COPY、MOVE、DELETE 能正常启动并显示前台通知。
2. 操作页面离开或手表息屏后任务继续执行。
3. 重新打开 WatchFiles 后自动回到操作页面，进度和取消按钮可用。
4. 正常完成、冲突等待、取消、失败和部分成功的结果与 M1 语义一致。
5. COPY/MOVE 的源与目标哈希、DELETE 的保留内容和任务临时文件均符合既有安全规则。
6. 任务结束后通知被移除，目录刷新正确，无 `AndroidRuntime` 或 `FATAL EXCEPTION`。
7. 真实设备写操作不触碰 M1Sandbox 之外的路径。

开发期只构建和安装 Debug；阶段完成后再统一执行完整 Debug gate，并另行决定 Release 交接。

## 8. 完成条件

本增量只有同时满足以下条件才可标记完成：

- 前台 Service 能承接 COPY、MOVE、DELETE，并在 Activity 解绑/息屏后继续；
- Activity 重新打开时能重新连接并显示运行中的任务；
- 通知在任务期间存在，终态后移除；
- Service 被意外终止时不自动重试，行为与非恢复阶段边界一致；
- M1 文件安全测试和现有文本/浏览器回归测试全部通过；
- 小米 Watch 5 真机在 M1Sandbox 完成受限验收且无崩溃证据；
- 更新 M3 checkpoint，记录测试数量、APK、设备和实际结果；
- 不引入 WorkManager、AIDL、新媒体依赖或 Release 交接。
