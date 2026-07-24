# M3 前台文件操作服务 closeout

日期：2026-07-24

状态：大任务亮屏风险提示的代码、Runner/路由 gate 和本地 Debug gate 已完成；真实 Watch 5 的既有 M3 证据已覆盖普通 COPY/MOVE/DELETE、冲突取消、替换全部和运行中 COPY 取消。2026-07-24 当前在线 ADB 仅发现 `192.168.31.60:38935`（`M2505W1` / `grasslte`），无法安全确认就是当前 Xiaomi Watch 5，因此本构建未安装、未做真机回归；大任务提醒页和当前构建 ordinary rerun 均保持 `PENDING_DEVICE_UI`。

## 本地 Debug 验证

| 项目 | 命令/产物 | 实际结果 |
|---|---|---|
| Debug 单元测试 | `.\\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain` | exit 0，`BUILD SUCCESSFUL`；当前 XML 报告汇总 172 tests、0 failures、0 errors、4 skipped。 |
| Debug APK 构建 | `.\\gradlew.bat :app:assembleDebug --no-daemon --console=plain` | exit 0，`BUILD SUCCESSFUL`；38 actionable tasks，38 `up-to-date`。 |
| Debug Lint | `.\\gradlew.bat :app:lintDebug --no-daemon --console=plain` | exit 0，`BUILD SUCCESSFUL`；0 errors，2 warnings（均为 `TextTransactionJournal.kt` 第 22、33 行的既有 `ApplySharedPref` warning）。 |
| 空白差异检查 | `git diff --check` | exit 0，无输出。 |

所有 Gradle 命令均显示同一条非致命环境警告：当前 SDK command-line tools 只理解至 SDK XML v3，但检测到 v4 XML。未运行 Release 构建。

## 大任务提醒语义（来自当前源码与测试）

- 阈值固定为 `itemCount >= 100` 或已知 `totalBytes >= 52,428,800` bytes（50 MiB）。
- `itemCount` 沿用 `FileOperationScanner` 的递归统计，包含顶层项目、目录、普通文件和符号链接本身。
- 任一项目大小不可得时，`ScanOutcome.Ready.totalBytes` 为 `null`；UI 必须显示“大小未知”，且只能继续按 `itemCount` 判定是否为大任务。
- COPY/MOVE 在提醒确认前不得调用 Engine；DELETE 通过提醒后仍必须进入现有 `WaitingForDeleteConfirmation` / “永久删除”二次确认。
- 提醒页取消或系统返回直接回到 `Idle`，不产生终态结果、不刷新目录、不调用 Engine。
- 本次记录不把为凑阈值而构造的 `100 项`、`50 MiB` 或 `5000 项` 真机压力夹具写成已执行。

## Debug APK 可追溯性

- 路径：`app/build/outputs/apk/debug/app-debug.apk`
- 文件大小：21,220,040 bytes
- 构建产物时间（APK `LastWriteTime`）：2026-07-24 09:47:53 +08:00
- SHA-256（当前最终 Debug APK）：`5743D82C60B8C1021B9C93208817B49E755135C82D6D365CA4A87529286E83FE`

## 架构与明确边界

- `FileOperationService` 继续返回 `START_NOT_STICKY`；M3 不宣称进程终止后的自动恢复、自动重试或任务持久化。
- 大任务提醒只是执行前 gate，不改变既有单任务互斥、前台 Service 生命周期、删除二次确认或 `SafeTextWriteRepository` 的文本安全写入边界。
- 当前 closeout 以源码、单元测试和既有真机记录为准；没有把未执行的提示页 UI、熄屏继续或压力边界写成通过。

## 已有真实 Watch 5 证据

| 场景 | 证据状态 | 说明 |
|---|---|---|
| 普通 COPY/MOVE/DELETE | PASS | 来自既有 M3 真机记录；范围仍限 `M1Sandbox`。 |
| 冲突取消 | PASS | 用户已确认在真实 Watch 5 上完成。 |
| 替换全部 | PASS | 用户已确认在真实 Watch 5 上完成。 |
| 运行中 COPY 取消 | PASS | 用户已确认在真实 Watch 5 上完成。 |
| 前台服务基础日志/通知清理 | PASS | 见 Task 6 report 的 FGS/logcat 记录。 |

## 2026-07-24 当前设备重新发现

| 字段 | 当前值 |
|---|---|
| `adb devices -l` 在线 transport | `192.168.31.60:38935` |
| Model | `M2505W1` |
| Device | `grasslte` |
| 设备结论 | `PENDING_DEVICE_UI` |
| 原因 | 该 transport 不能安全确认就是当前 Xiaomi Watch 5，因此未执行 `adb install`、未执行真机回归，也不宣称大任务提醒页或当前构建 ordinary no-warning 路径已验证。 |

## 剩余设备验收项（PENDING_DEVICE_UI）

1. 在安全确认的 Watch 5 上，为当前 APK 验收大任务提醒页本身，包括达到阈值后的提示展示、继续操作和取消返回。
2. 为当前 APK 重新确认 ordinary 小任务路径不会误触发大任务提醒。
3. 如需产品级宣称，再单独记录任何熄屏继续、Activity 重入后的继续执行或长任务中断边界；当前 closeout 不把这些写成已完成。
4. 如需额外性能/压力结论，再单独设计并执行受控夹具；当前 closeout 不包含 `100 项`、`50 MiB` 或 `5000 项` 真机压力测试。

## Concerns

- 本地 Lint 没有 error，但保留 2 条既有 `ApplySharedPref` warning；本任务按范围不修改 `TextTransactionJournal.kt`。
- 当前在线 ADB transport 的型号字段仍是 `M2505W1` / `grasslte`，但这不足以安全确认它就是当前应验收的 Xiaomi Watch 5；因此必须把本构建设备结论保持为 `PENDING_DEVICE_UI`。
- 真实 Watch 5 的既有普通操作/冲突/取消证据可以用来清理文档里的过期 pending 项，但不能替代当前 APK 的大任务提醒页 UI 证据。
- 不得把进程恢复、持久化、自动重试、熄屏继续或大规模压力测试写成 M3 已验证能力。
