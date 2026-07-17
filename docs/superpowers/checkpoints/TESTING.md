# 真机测试清单

每次测试请记录“操作步骤、预期结果、实际结果、截图或日志”。无需自己分析代码。

## M1A 长按多选、新建文件夹与重命名

仅在 `/storage/emulated/0/Download/WatchFilesTest/M1Sandbox` 中测试，不要使用重要文件。

1. 长按任一项目，应显示“已选 1 项”、取消选择和全选；继续点击可增减选择，系统返回键应先退出选择状态。
2. 点击“全选”，选中数应等于当前列表项目数；点击“取消选择”应返回普通浏览。
3. 点击“新建文件夹”，应出现系统输入法、名称输入框、确认和取消按钮。
4. 空名称、`.`、`..`、包含 `/` 或 `\` 的名称以及首尾带空格的名称应显示校验错误，不能提交。
5. 输入一个不存在的名称并通过输入法 Done 或“确认”提交，新文件夹应出现于当前目录。
6. 长按一个文件，只选中该项目后点击“重命名”；输入新名称并提交，原名称应消失，新名称应出现，文件内容不变。
7. 尝试重命名为当前目录已经存在的项目名称，应停留在编辑页并显示“已存在同名项目”；两个原项目都不能被覆盖或删除。
8. 操作过程中不应卡住界面或导致应用崩溃；失败后应能修改名称重试或取消。

M1A 的验收只覆盖选择、新建和重命名；复制、移动和删除分别在 M1B/M1C 检查点验收。所有写操作测试仍必须使用上述固定沙箱。

## M1B 安全复制与移动

仅在 `/storage/emulated/0/Download/WatchFilesTest/M1Sandbox` 中测试。每次操作前记录源和目标 SHA-256；每次操作后检查源删除结果、任务临时文件和崩溃日志。

1. 普通文件 MOVE：完成 1/失败 0，源消失，目标哈希与源一致。
2. 递归目录 MOVE：包含三层嵌套目录和空文件；源目录消失，目标嵌套哈希一致，空文件保持 0 字节。
3. 冲突取消：冲突页选择取消任务，完成 0/失败 0，源和旧目标均保持不变。
4. 冲突替换全部：确认后完成 1/失败 0，源消失，目标哈希变为新源哈希，旧目标独占内容不应合并残留。
5. 非法子目录目标：把目录移动到自身子目录，显示“目标目录不能位于源文件夹内部”，不创建递归副本。
6. 最终审计：没有任务拥有的 `.part`、`.part-dir`、`.backup`；用户自己的点文件不应被清理；`AndroidRuntime` 和 `FATAL EXCEPTION` 为空。

M1B 完整实测记录见 [`2026-07-16-m1b-closeout.md`](2026-07-16-m1b-closeout.md)。

## M1C 删除确认

只在 `/storage/emulated/0/Download/WatchFilesTest/M1Sandbox` 中操作。每个设备会话先运行 `adb devices -l` 和 `adb mdns services`，只使用当次在线的 `M2505W1/grasslte` serial；不得复用旧 IP。无线调试反复不可用时停止设备操作。仅运行 `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug`，不构建 Release。

本阶段默认 fixture 为 `DeleteFile/source.txt`、`DeleteFile/notes.part`、`DeleteTree/empty/`、`DeleteTree/nested/level1/level2/leaf.txt`、`DeleteCancel/first.txt` 和 `DeleteCancel/second.txt`。如需取消中验收，可在已获授权的 `DeleteCancel` 内创建有限数量的受控小文件/子目录；每个路径都必须先验证 exact root prefix 与白名单。不得创建、删除或改权限于 system/media/个人目录或其他 M1Sandbox fixture。

1. 安装前记录 Debug APK SHA-256；安装后核对 `versionCode=6`、`versionName=0.3.1-dev-debug`、`targetSdk=29`。
2. 长按 fixture 并点“删除”，记录确认页顶层数、递归数和已知大小。点“取消”并单独以硬件 Back 退出；源项目必须保留且无终态删除结果。
3. 确认删除普通文件、空目录和递归目录。ADB 精确核对选中路径不存在、列表刷新，`notes.part` byte-for-byte 不变。
4. 对受控多条目 `DeleteCancel`，确认后等 FILE_OPERATION 显示 Running，再立即点“取消”。记录是否显示 Cancelling、最终 Cancelled/失败结果、已删除和未触及条目、不可恢复提示以及 `.part-dir`/`.backup` 残留。
5. 通过本地注入测试覆盖根目录拒绝及子项删除失败；不要修改真机目录权限。清空 logcat 后做 crash audit，分别记录 `AndroidRuntime` 和 `FATAL EXCEPTION`。

本次 M1C 证据见 [`2026-07-17-m1c-closeout.md`](2026-07-17-m1c-closeout.md)。

## 0.3.1 内置低内存图片查看器

1. 安装 `app/build/outputs/apk/debug/app-debug.apk` 并启动。
2. 进入测试目录，点击 JPEG 或 PNG，再点击“查看图片”。
3. 图片应按比例适应圆屏，不变形、不崩溃；顶部返回按钮应为完整半椭圆弧，底边是位于图片区域上方的水平直线。
4. 大于 960 px 的图片应在底部显示“原始尺寸 · 预览尺寸”，预览最长边不超过 960 px。
5. 双指缩放应限制在 1×–4×；放大后单指拖动可查看不同区域，且图片不能被完全拖出屏幕。
6. 双击图片应切换到 2×；再次双击应恢复 1×并复位图片位置。
7. 连续进入和退出同一张大图 5 次，应用内存不应持续上涨；缩放时不应重新加载或闪烁。
8. 按系统返回键或点击返回按钮，应回到原文件详情；再次返回应回到原目录位置。
9. 带 EXIF 旋转信息的 JPEG 应方向正确；透明 PNG 应正常合成在黑色背景上。
10. 损坏或不受支持的图片应显示“图片打开失败”，应用不应崩溃。

## 0.2.1 文件详情与系统打开

建议在 `Download/WatchFilesTest` 中准备 JPEG、PNG、MP3、MP4 和一个未知扩展名文件，不要使用重要文件。

1. 安装当前 APK 并启动。
2. 进入测试目录，点击一个普通文件；应显示文件详情页，而不是无响应。
3. 核对名称、类型、MIME、大小、修改时间、读写状态和路径。
4. 点击“打开”；如果手表有对应应用，应进入该应用或出现系统应用选择界面。
5. 返回 WatchFiles，确认仍能回到原文件详情/目录。
6. 分别测试 JPEG/PNG、MP3、MP4 和未知扩展名文件。
7. 未知类型没有系统处理器时，应显示“未找到能打开此类文件的应用”，WatchFiles 不应崩溃。
8. 在详情页按系统返回键，应回到原目录；目录位置和隐藏文件开关不应改变。

小米 Watch 5 当前没有图片或视频播放器。系统会把 `image/*` 和 `video/*` 交给厂商 `MediaStub` 占位页后立即返回，因此本机上不会真正显示图片或播放视频。这不是 URI 授权失败。

本版本已支持新建文件夹、重命名、复制、移动和带独立确认的递归永久删除；音频和视频播放器不在 M1C 范围。

## M0 回归测试

1. 安装 APK 并启动 WatchFiles。
2. 点击“授予文件权限”，在系统弹窗中允许“照片、媒体内容和文件”。
3. 如果没有弹窗，点击“打开应用信息”，检查权限页里是否出现文件权限。
4. 返回应用，确认出现内部存储、下载、图片、音乐和视频入口。
5. 转动表冠，确认列表能上下滚动。
6. 进入内部存储，确认文件夹可以打开。
7. 点击“显示点文件”，确认以 `.` 开头的项目出现或隐藏。
8. 在内部存储根目录点击“返回上级”，应返回主页，而不是进入 `/storage/emulated`。
9. 打开设备诊断页，核对圆屏、ABI、内存和存储数据。

## 反馈格式

```text
版本：0.3.1-dev-debug
页面：例如内部存储
操作：例如快速旋转表冠三圈
预期：列表连续向下滚动
实际：
是否复现：每次 / 偶尔 / 一次
截图或日志：
```

## 获取日志

清空旧日志：

```powershell
adb logcat -c
```

复现问题后保存：

```powershell
adb logcat -d > watchfiles-log.txt
```

只查看崩溃：

```powershell
adb logcat -d AndroidRuntime:E *:S
```

查看内存：

```powershell
adb shell dumpsys meminfo com.example.watchfiles.debug
```

## 安全限制

当前版本已提供新建文件夹、重命名、复制、移动和带独立确认的递归永久删除。所有写操作只在 `/storage/emulated/0/Download/WatchFilesTest/M1Sandbox` 中测试；不要直接使用重要照片、录音或系统目录。开发期保持 Debug，Release 交接不在本检查点范围。
