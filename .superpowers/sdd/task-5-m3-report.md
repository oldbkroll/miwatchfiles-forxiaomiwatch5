# M3 Task 5 Report — Local Debug Gate, Security Scan, and Closeout Template

日期：2026-07-21

状态：本地 Debug 任务完成并已记录；真机验收保持 `PENDING_DEVICE`。提交：`chore: verify M3 foreground service baseline`。

## 改动文件

- `docs/context/m3-foreground-file-operation-service-closeout.md`：新增本地 Debug 验证记录、APK 可追溯信息、设备验收字段和 Task 6 清单。
- `.superpowers/sdd/task-5-m3-report.md`：本任务的执行结果与 concern 记录。

未修改 app 代码、其他项目文档或历史 `.superpowers/sdd/task-5-report.md`。

## 命令结果

| 命令 | 结果 |
|---|---|
| `.\\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain` | exit 0，`BUILD SUCCESSFUL in 12s`，24 actionable tasks（24 up-to-date）。当前 XML 汇总：158 tests、0 failures、0 errors、4 skipped。 |
| `.\\gradlew.bat :app:assembleDebug --no-daemon --console=plain` | exit 0，`BUILD SUCCESSFUL in 12s`，38 actionable tasks（38 up-to-date）。 |
| `.\\gradlew.bat :app:lintDebug --no-daemon --console=plain` | exit 0，`BUILD SUCCESSFUL in 48s`，28 actionable tasks（9 executed、1 from cache、18 up-to-date）；0 errors、2 warnings。 |
| `git diff --check` | exit 0，无输出。 |
| `rg -n "START_STICKY\|WorkManager" app/src/main` | 无输出，exit 1（`rg` 的无匹配退出码）。 |
| `rg -n 'android:exported="true"' app/src/main/AndroidManifest.xml \| Select-String -NotMatch 'activity'` | 输出 Manifest 第 47 行的 `android:exported="true"`，管道 exit 0。上下文证实该属性属于 `MainActivity`，是跨行 XML 导致的逐行筛选误报。 |
| `rg -n "START_NOT_STICKY\|FOREGROUND_SERVICE\|FileOperationService\|FileOperationRunner" app/src/main docs` | exit 0，定位到 Manifest 的前台服务权限/注册、Service 的 `START_NOT_STICKY`、Runner，以及 M3 设计/计划约束。 |
| `rg -n -U '<(service\|provider\|receiver)\\b[^>]*\\bandroid:exported="true"' app/src/main/AndroidManifest.xml` | 补充结构扫描，无输出，exit 1（无匹配）；`FileOperationService` 与 `FileProvider` 均为 `exported="false"`。 |

三条 Gradle 命令均给出非致命 SDK XML v3/v4 兼容性警告。未运行 Release、未调用 ADB、未安装 APK、未写入设备。

## 产物

- `app/build/outputs/apk/debug/app-debug.apk`
- LastWriteTime：2026-07-21 18:30:28 +08:00
- SHA-256：`379DB662A806FABC190DD45A0C933863F84A09F98BA21BF9B93AABB946F3A22E`

## Concerns

- Lint 成功但含 2 条既有 `ApplySharedPref` warning，位于未授权修改的 `TextTransactionJournal.kt` 第 22、33 行。
- 简报所列的逐行 `exported` 管道对换行的 `<activity>` 产生误报；本报告保留该真实输出，并以多行结构扫描核实非 Activity 组件未导出。
- Task 6 的设备 serial、API、型号、通知/熄屏行为、文件哈希、进程边界及 logcat 均为 `PENDING_DEVICE`，没有把未执行项目写成通过。
