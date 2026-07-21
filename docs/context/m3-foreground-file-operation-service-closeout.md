# M3 前台文件操作服务 closeout

日期：2026-07-21

状态：本地 Debug 构建、单元测试任务、Lint 和差异检查成功；基础 COPY/MOVE/DELETE 真机冒烟验收通过，扩展设备场景仍为 `PENDING_DEVICE_UI`。简报中的逐行 `exported` 管道扫描对换行的 Activity 属性产生了一个误报，已用补充的多行结构扫描确认没有非 Activity 导出组件；详见“架构与安全扫描”。

## 本地 Debug 验证

| 项目 | 命令/产物 | 实际结果 |
|---|---|---|
| Debug 单元测试 | `.\\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain` | exit 0，`BUILD SUCCESSFUL`；当前 XML 报告汇总 159 tests、0 failures、0 errors、4 skipped。 |
| Debug APK 构建 | `.\\gradlew.bat :app:assembleDebug --no-daemon --console=plain` | exit 0，`BUILD SUCCESSFUL`；38 actionable tasks，3 executed、35 `UP-TO-DATE`。 |
| Debug Lint | `.\\gradlew.bat :app:lintDebug --no-daemon --console=plain` | exit 0，`BUILD SUCCESSFUL`；0 errors，2 warnings（均为 `TextTransactionJournal.kt` 第 22、33 行的既有 `ApplySharedPref`/`commit()` 警告）。 |
| 空白差异检查 | `git diff --check` | exit 0，无输出。 |

所有 Gradle 命令均显示同一条非致命环境警告：当前 SDK command-line tools 只理解至 SDK XML v3，但检测到 v4 XML。未运行 Release 构建。

## Debug APK 可追溯性

- 路径：`app/build/outputs/apk/debug/app-debug.apk`
- 文件大小：21,205,592 bytes
- 构建产物时间（APK `LastWriteTime`）：2026-07-21 19:35:02 +08:00
- SHA-256（断开重绑修复后的当前最终 Debug APK）：`E4930E8175AF76E947DCECE9686B67FBD57E80DC2F3D3DBD4C7695884D46023A`

## 架构与安全扫描

| 扫描 | 实际结果 |
|---|---|
| `rg -n "START_STICKY|WorkManager" app/src/main` | 无输出，`rg` exit 1（无匹配）；生产代码中未发现 `START_STICKY` 或 `WorkManager`。 |
| `rg -n 'android:exported="true"' app/src/main/AndroidManifest.xml \| Select-String -NotMatch 'activity'` | 输出第 47 行 `android:exported="true"`，管道 exit 0。该行的 `<activity` 起始标签在上一行，故这是逐行筛选的误报；并非非 Activity 组件。 |
| 补充结构扫描：`rg -n -U '<(service\|provider\|receiver)\\b[^>]*\\bandroid:exported="true"' app/src/main/AndroidManifest.xml` | 无输出，`rg` exit 1（无匹配）。Manifest 中 `FileOperationService` 与 `FileProvider` 均显式为 `android:exported="false"`。 |
| `rg -n "START_NOT_STICKY\|FOREGROUND_SERVICE\|FileOperationService\|FileOperationRunner" app/src/main docs` | exit 0；定位到 Manifest 的 `FOREGROUND_SERVICE` 权限与 Service 注册、`FileOperationService.kt` 的 `START_NOT_STICKY` 返回值、`FileOperationRunner.kt`，及 M3 设计/计划中的对应约束。 |

## 设备验收记录（Task 6）

| 字段 | 当前值 |
|---|---|
| 设备状态 | PASS；M2505W1 / grasslte |
| ADB serial | `192.168.31.60:41719`（动态选择） |
| 设备 API | 34 |
| 安装/启动结果 | PASS；`com.example.watchfiles.debug`，`versionCode=6`，`0.3.1-dev-debug`，`minSdk=29`，`targetSdk=29`；`adb install -r` 成功 |
| Task 6 安装 APK SHA-256 | `379DB662A806FABC190DD45A0C933863F84A09F98BA21BF9B93AABB946F3A22E` |
| 当前最终 Debug APK SHA-256 | `E4930E8175AF76E947DCECE9686B67FBD57E80DC2F3D3DBD4C7695884D46023A`；包含 1ccc780 断开重绑修复，未重新进行设备安装验收 |
| Fixture | PASS；仅 `/storage/emulated/0/Download/WatchFilesTest/M1Sandbox/M3ServiceAcceptance` |
| COPY/MOVE/DELETE | PASS；UI 终态、文件清单和 alpha 哈希证据见 Task 6 report |
| FGS/logcat | PASS；Background started FGS、静默通知、终态 FGS stop；无 `FATAL EXCEPTION` |

## 已完成的设备验收范围

1. 动态发现已授权设备，安装 Debug APK，并记录实际 serial、API、型号、包/版本信息。
2. 在 `M1Sandbox` 内准备受控 fixture，并在操作前后记录文件清单与 SHA-256。
3. 完成基础 COPY/MOVE/DELETE 冒烟，确认终态 UI、源/目标文件结果，以及前台服务启动、静默通知和终态停止日志。

## 剩余设备验收项（PENDING_DEVICE_UI）

1. 验收 COPY/MOVE 在离开操作页或熄屏后的继续执行、重新打开后的同一任务进度与取消入口。
2. 验收冲突等待、替换全部、取消终态，以及完成后的临时文件清理、通知移除和目录刷新。
3. 验收 DELETE 的预览、确认前取消、确认后熄屏继续、取消语义与目录结果。
4. 记录进程终止边界（不自动重试或伪造恢复）；扩展 Service/Activity/文件操作审计，确认没有重复任务、未授权路径写入、未处理异常或遗留通知。

## Concerns

- 本地 Lint 没有 error，但保留 2 条既有 `ApplySharedPref` warning；本任务按范围不修改 `TextTransactionJournal.kt`。
- 原定逐行 `exported` 扫描不能识别跨行 XML 元素，因此不能单独用其输出判断组件类型；本记录保留原命令的真实结果，并补充结构扫描证据。
- 本次完成的是基础 COPY/MOVE/DELETE 冒烟，不等同于完整 M3 验收。
- 熄屏/Activity 重入、冲突等待与替换、运行中取消、进程终止/恢复仍未执行；不得使用“已验证”描述这些项目。
- 写入范围结论仅限本次 fixture 的最终检查范围，不能外推为全设备写入审计。
