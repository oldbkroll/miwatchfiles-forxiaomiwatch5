# WatchFiles 项目交接上下文

更新日期：2026-07-16
当前阶段：M0、图片查看增量、M1A 和 M1B 已完成；下一阶段为 M1C 删除确认

## 项目目标

为小米 Watch 5 制作一个针对 480×480 圆屏优化的轻量 Android 文件管理器，长期目标类似精简版 MT 文件管理器。当前为个人使用项目，达到较高完成度后才考虑开源。

应用需要逐步具备：

- 浏览内部共享存储和常用媒体目录
- 新建、复制、移动、重命名和删除文件
- 打开图片、视频和音频
- 圆屏安全区域布局与表冠滚动
- 适应厂商魔改 Android，而不是依赖完整 Wear OS 服务
- 在低内存设备上保持较低资源占用

## 协作方式

用户有编程思维、小部分 Android 刷机与分区知识，也能执行 ADB 命令、安装 APK、复现问题、提供日志和截图。语言编码水平目前约为 Hello World，Kotlin 只有浅显了解。

因此后续协作默认：

- Codex 负责绝大部分架构、编码、构建、静态检查和文档维护。
- 用户负责真机安装、操作验证、截图与反馈。
- 给用户的测试步骤应明确、短小、可直接复制执行，不假设其熟悉 Gradle 或 Kotlin。
- 每个阶段先保证真机稳定，再扩大功能范围。

## 真机基线

- 设备：小米 Watch 5，厂商魔改 Android 手表系统
- Android：14 / API 34
- 屏幕：480×480 px 圆屏
- 密度：320 dpi，即逻辑尺寸约 240×240 dp
- `ro.build.characteristics=nosdcard,watch`
- ABI：`armeabi-v7a,armeabi`，实际为 32 位 ARM；不要按 arm64 构建
- `ro.config.low_ram=true`
- 物理内存：`1807796 kB`，约 1.72 GiB
- Dalvik heap start：8 MiB
- heap growth limit：192 MiB
- heap size：384 MiB
- 内部共享存储：约 19 GiB，无物理 SD 卡
- `/storage/emulated/0` 由 FUSE 挂载
- 只有无线 ADB，没有有线 ADB；无线调试端口可能变化

## 当前工程配置

- 项目目录：`C:\Users\13073\Downloads\watche`
- 应用工作名：WatchFiles
- Debug 包名：`com.example.watchfiles.debug`
- 基础包名：`com.example.watchfiles`，目前仍是占位名称
- 当前版本：`0.3.1-dev-debug`，versionCode 6
- compileSdk：34
- minSdk：29
- targetSdk：29（厂商权限兼容需要，见下文）
- Gradle Wrapper：8.7
- Android Gradle Plugin：8.5.2
- Kotlin：2.0.21
- Compose BOM：2024.09.03
- Wear Compose：1.4.1
- Activity Compose：1.9.3
- Lifecycle：2.8.6
- Java：17
- APK 仅打包 `armeabi-v7a`

本机工具位置：

- Android Studio：`C:\Program Files\Android\Android Studio`
- JBR：`C:\Program Files\Android\Android Studio\jbr`
- Android SDK：`C:\Users\13073\Documents\AndroidSDK`

## M0 已实现

- 针对圆屏的 Wear Compose `ScalingLazyColumn`
- 不依赖 Google Wear OS 完整服务的自定义表冠滚动
- 内部存储、下载、图片、音乐、视频快捷入口
- 只读目录浏览
- 文件夹优先的目录列表
- 隐藏点文件显示/隐藏
- 根目录“返回上级”正确返回主页
- 设备诊断页
- 文件权限引导
- Android Lint 和 Debug APK 构建通过

尚未实现复制、移动、删除、长任务队列、`.part` 临时文件、失败恢复和缩略图缓存。

## M1A 基础文件操作增量

当前 `m1-file-operations` 分支已实现：

- 长按文件或文件夹进入多选，支持继续点选、全选、取消选择；系统返回键会先退出选择状态
- 在当前目录新建文件夹
- 选择单个项目后安全重命名文件或文件夹
- 名称编辑页使用系统输入法，提供空名称、`.`、`..`、路径分隔符、NUL 和首尾空白校验
- 新建和重命名均在 `Dispatchers.IO` 执行，不覆盖已经存在的同名项目，也不跟随符号链接
- 操作成功后刷新当前目录；失败时保留编辑内容并显示原因
- 文件操作通过独立 gateway/repository 与浏览 ViewModel 协调，为后续复制、移动和队列留出边界

自动化验证包含 13 个 JVM 单元测试，覆盖名称规则、创建与重命名、同名拒绝、多选状态和 ViewModel 成功/失败流程。Debug 单元测试、APK 构建和 Lint 均通过。

小米 Watch 5 真机已在 `/storage/emulated/0/Download/WatchFilesTest/M1Sandbox` 验证长按多选、返回退出选择、新建文件夹和重命名；搜狗手表输入法可正常弹出并通过 Done 提交。测试目录中的 `original.txt` 已成功重命名为 `renamed.txt`。把它再次重命名为已存在的 `NewFolderl` 时，编辑页显示“已存在同名项目”，文件和文件夹均保持不变，文件内容未损坏且应用未崩溃。

M1B 已实现单任务队列、安全复制与移动、同名冲突决策、取消清理和错误结果处理；删除仍不可用，下一步为 M1C 删除确认，并要求二次确认。

M1B 的代码、自动化验证和小米 Watch 5 真机验收结论见 [`2026-07-16-m1b-closeout.md`](../checkpoints/2026-07-16-m1b-closeout.md)。

## 阶段二首个增量

0.2.0/0.2.1 已实现：

- 点击普通文件进入圆屏文件详情页
- 显示文件名、类型、MIME、大小、修改时间、读写状态和路径
- 按扩展名与系统映射识别常见图片、音频、视频、文本、PDF、压缩包和 APK
- 使用 `FileProvider` 生成临时只读 `content://` URI
- 使用系统 `ACTION_VIEW` 调用手表中已安装的应用打开文件
- 没有对应应用、URI 不可共享或系统拒绝读取时显示错误提示
- 0.2.1 保留浏览列表滚动状态，进入详情再返回时不会跳回顶部

本增量已在小米 Watch 5 真机验证。设备没有图片或视频播放器，系统仅注册了 `com.xiaomi.miwear.frameworkpackagestubs/.Stubs$MediaStub` 占位 Activity：`image/*` 和 `video/*` 会成功接收临时只读 URI，但随即返回且不显示内容。0.3.0 因此加入了内置图片预览；音频和视频仍未内置播放器。

## 阶段二图片查看增量

0.3.0/当前 0.3.1-dev-debug 已实现：

- 图片详情页提供“查看图片”和“用其他应用打开”两个入口
- 使用 Android `ImageDecoder` 在 `Dispatchers.IO` 后台解码，不阻塞主线程
- 最长边超过 960 px 时按比例生成预览，并启用软件位图与低内存策略
- 查看页显示原始尺寸和实际预览尺寸
- 离开查看页立即回收位图；加载取消或失败时同样清理已产生的位图
- 圆屏全屏预览
- 当前 Debug 将返回按钮改为标准半椭圆帽形：顶部为完整连续弧线，底边为水平直线并位于图片区域上方
- 图片区域从返回按钮的水平底边下方开始，避免顶部控件遮住竖图内容
- 支持 1× 至 4× 双指缩放和单指平移，并限制平移边界，避免图片完全拖出可视区
- 双击可在 1×/2× 间切换；放大后再次双击会复位缩放和位置
- 缩放只对已采样的预览位图做图形变换，不会重新解码原图或额外制造大位图

真机结果：4000×3000 JPEG 成功按比例解码为 960×720；冷启动主页 PSS 约 49.5 MiB，查看大图约 72.1 MiB，退出后约 65.7 MiB。连续进入/退出 5 次后打开状态约 69–71 MiB，没有持续上涨或崩溃。双击 2×、拖动、双击复位已在真机验证；2× 查看时 PSS 约 60 MiB。EXIF Orientation 6 的 JPEG 能按 600×1200 正向显示，透明 PNG 显示正常，损坏 JPEG 会进入失败页而不崩溃。

## 已解决的关键兼容问题

### 1. 表冠滚动崩溃

小米固件缺少类：

`com.google.wear.input.WearHapticFeedbackConstants`

Wear Compose 默认旋转输入路径会因此触发 `NoClassDefFoundError`。当前实现把 `ScalingLazyColumn` 的 `rotaryScrollableBehavior` 设为 `null`，再通过 `Modifier.onRotaryScrollEvent` 和 `state.scrollBy(...)` 自行处理表冠滚动。不要直接恢复 Wear Compose 默认触觉旋转实现，除非先在真机验证依赖存在。

### 2. “所有文件访问”设置页闪退

最初声明 `MANAGE_EXTERNAL_STORAGE` 并打开标准特殊权限设置页，但这台手表缺少或破坏了该设置页面：点击授权入口会闪退，而且应用不会出现在设置列表。

0.1.1 的私人兼容方案：

- 声明 `READ_EXTERNAL_STORAGE` 和 `WRITE_EXTERNAL_STORAGE`
- 保留 `MANAGE_EXTERNAL_STORAGE`，为将来的双后端方案预留
- `android:requestLegacyExternalStorage="true"`
- 暂时将 `targetSdk` 设为 29
- 通过 Activity Result API 直接申请旧式“照片、媒体内容和文件”权限
- 备用按钮只打开应用详情页，不再打开会崩溃的所有文件特殊权限页

用户已真机确认：0.1.1 能正常授权，不再闪退。Android 14 可能提示应用面向旧版 Android，这是此私人兼容构建的预期行为。

未来开源时不要只保留 target 29。建议做双存储后端：该手表使用 Legacy Direct Path，标准 Android 构建使用 `MANAGE_EXTERNAL_STORAGE` 或 SAF，并提高 targetSdk。

## 真机测试结果

- 0.1.0 能安装和启动。
- 修复触觉依赖后，主页和目录页可稳定显示。
- 圆屏布局基本合适，表冠滚动可用。
- 主页 PSS 约 51 MiB，目录浏览约 59 MiB。
- 0.1.1 的“照片、媒体内容和文件”授权已由用户确认正常。
- 0.2.1 已通过 ADB 真机冒烟测试：普通文件详情、PNG/MP4 MIME 分类、未知类型错误提示、详情返回后的列表位置保持均正常。
- 使用 ADB 注入真实 `ROTARY_ENCODER` 滚动事件，详情页和主页均正常滚动且无崩溃。
- 0.2.1 冷启动后主页 PSS 约 53 MiB，与阶段一基线接近。
- 系统图片/视频 `ACTION_VIEW` 仅由小米 `MediaStub` 占位页处理，URI 授权成功但设备没有实际播放器。
- M1A 已加入新建文件夹和重命名，但不提供覆盖、复制、移动或删除；真机写操作只在固定测试沙箱中验证。

## 阶段一 APK

路径：

`releases\WatchFiles-0.1.1-legacy-permission-debug.apk`

SHA-256：

`EBC1328C2515CACB2EC3ABCBD7EA689182F3843C1A90071DB3D68EDCC3BAC447`

## 最近阶段检查 APK

`releases\WatchFiles-0.3.1-image-viewer-arc-debug.apk`

SHA-256：

`340C55C8509623C777232E898006429AA700EDA4C9BB5BF01AD59FA0F3CACDB6`

当前工作区 Debug 含有更新的直底边半椭圆按钮，输出位置：

`app\build\outputs\apk\debug\app-debug.apk`

开发期小改动只构建和安装 Debug；等当前阶段完成后再更新版本号、`releases` 阶段包和 SHA-256。

## 构建命令

在项目根目录执行：

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME='C:\Users\13073\Documents\AndroidSDK'
.\gradlew.bat :app:assembleDebug :app:lintDebug --no-daemon --console=plain
```

Debug APK 输出位置：

`app\build\outputs\apk\debug\app-debug.apk`

## 无线 ADB 测试

端口变化后先连接：

```powershell
adb connect 手表IP:端口
adb devices
```

干净安装测试版：

```powershell
adb uninstall com.example.watchfiles.debug
adb install -r "C:\Users\13073\Downloads\watche\app\build\outputs\apk\debug\app-debug.apk"
```

获取崩溃日志：

```powershell
adb logcat -c
# 在手表复现问题
adb logcat -d AndroidRuntime:E *:S
```

## 下一步建议

开始 M1C 删除确认设计与实现。所有写操作继续限定在 `Download/WatchFilesTest/M1Sandbox` 真机验收；删除必须先做失败测试，再提供二次确认、取消和失败恢复路径。

## 开发约束

- 优先适配 240×240 dp 圆屏安全区域，不照搬手机端 UI。
- 不假设存在 Google Play 服务、完整 Wear OS API 或 Google 触觉类。
- 文件读取、媒体解析和写操作不得阻塞主线程。
- 控制依赖数量、内存峰值和缩略图尺寸。
- 真机为 32 位，任何原生库必须提供 `armeabi-v7a`。
- 加入删除、覆盖、批量移动之前，必须先在专门测试目录验证并提供确认界面。
- 开发期小改动只更新 Debug；每个阶段验收时再统一更新版本号、测试清单和 `releases` 中的 APK。

## 新任务启动说明

每个开发阶段使用一个独立会话。新会话开始时按以下顺序阅读：

1. 根目录 `README.md`
2. `docs/superpowers/context/PROJECT_CONTEXT.md`
3. `docs/superpowers/roadmap/PROJECT_PLAN.md`
4. `docs/superpowers/checkpoints/TESTING.md`
5. 当前阶段的 spec、plan 和 checkpoint

然后检查工作树和构建状态，从当前阶段 checkpoint 继续，不要重新初始化 Android 项目，也不要撤销 target 29 和自定义表冠兼容逻辑。个人小项目简化流程见 [`personal-project-simplified-workflow.md`](../workflow/personal-project-simplified-workflow.md)。
