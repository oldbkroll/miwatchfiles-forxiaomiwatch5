# M3 前台文件操作服务 closeout

日期：2026-07-24

状态：大任务亮屏风险提示的代码、Runner/路由 gate、本地 Debug gate 和当前 Debug APK 的受限 Watch 5 真机回归均已完成；
表冠事件队列、无连续滚动震动的边界短反馈、标准 Android 触觉适配和长按触觉代码已完成本地实现、测试及当前 Watch 5 受限回归；启动/内存/目录性能收尾仍未完成。真实设备上的普通 COPY/MOVE/DELETE、冲突取消、替换全部、运行中 COPY 取消，
以及既有构建的大任务提醒页和普通小任务 no-warning 路径均已有证据。

## 本地 Debug 验证

| 项目 | 命令/产物 | 实际结果 |
|---|---|---|
| Debug 单元测试 | `.\\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain` | exit 0，`BUILD SUCCESSFUL`；当前 XML 报告汇总 178 tests、0 failures、0 errors、4 skipped。 |
| Debug APK 构建 | `.\\gradlew.bat :app:assembleDebug --no-daemon --console=plain` | exit 0，`BUILD SUCCESSFUL`；38 actionable tasks，4 executed、34 up-to-date。 |
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
- 文件大小：21,224,150 bytes
- 构建产物时间（APK `LastWriteTime`）：2026-07-24 12:32:18 +08:00
- SHA-256（当前最终 Debug APK）：`A819128CFD68AAC8E76ED55A897C2A957D3D93E7AD990A79DFFE9D36105C69CF`

## 表冠与触觉兼容增量

| 项目 | 本地结果 |
|---|---|
| 触觉策略测试 | PASS；正常滚动无反馈、顶部/底部各一次、离开边界后可再次反馈和选择模式长按策略均有 JVM 覆盖。 |
| 表冠滚动实现 | PASS；`RoundList` 继续使用 `rotaryScrollableBehavior = null`，事件进入容量 32 的有界队列并由单消费者顺序调用 `scrollBy`。 |
| 触觉映射 | PASS；表冠边界使用 `VIRTUAL_KEY`，非选择模式长按使用 `LONG_PRESS`，均通过 `View.performHapticFeedback`，失败静默降级。连续表冠滚动不再发送 `CLOCK_TICK`。 |
| Watch 5 厂商交互 | PASS；动态 serial 为 `adb-d87a2e34-S40wiQ._adb-tls-connect._tcp`，普通 rotary 滚动无连续震动，底部/顶部各一次短反馈，长按选择和重复长按均通过。 |
| Watch 5 触觉平台 | PASS；边界记录为两次 `Primitive=TICK`、17–18 ms；安装后没有新的 `TEXTURE_TICK` 长触觉请求；物理触觉强度未作主观量化。 |
| Watch 5 崩溃/文件审计 | PASS；进程仍在前台，`AndroidRuntime` 为空，未新增任务临时残留，既有用户 `.part` 文件未修改。 |

本次回归不把触觉强度或熄屏继续能力写成产品性承诺；未执行文件写操作或压力夹具。此前旧 APK 的 `CLOCK_TICK` 长触觉历史记录不计入本次结果。

## 架构与明确边界

- `FileOperationService` 继续返回 `START_NOT_STICKY`；M3 不宣称进程终止后的自动恢复、自动重试或任务持久化。
- 大任务提醒只是执行前 gate，不改变既有单任务互斥、前台 Service 生命周期、删除二次确认或 `SafeTextWriteRepository` 的文本安全写入边界。
- 当前 closeout 以源码、单元测试和既有真机记录为准；没有把未执行的提示页 UI、熄屏继续或压力边界写成通过。

## 2026-07-24 大任务提醒增量的 Debug APK 真机回归（历史证据）

| 场景 | 证据状态 | 实际结果 |
|---|---|---|
| 设备与 APK | PASS | 用户确认 `192.168.31.60:38935`（`M2505W1` / `grasslte`）为目标 Watch 5；当前 Debug APK 安装成功，SHA-256 为 `03AF5DBCFDE3…C87FC6C1`。 |
| COPY 大任务提醒 | PASS | 既有 256.0 MiB fixture 递归扫描显示 `2 项 / 256.0 MiB`；提醒文案正确，取消和系统返回均回到选择页，无终态结果或文件变更。 |
| MOVE 大任务提醒 | PASS | 提醒文案正确；取消回到选择页，无文件变更。 |
| DELETE 大任务提醒 | PASS | 提醒确认后进入既有“确认永久删除”页；随后取消，无文件变更。 |
| 普通小 COPY/MOVE/DELETE | PASS | 29 B 小文件分别进入既有冲突页、既有冲突页和既有永久删除确认页，均未显示新提醒；后续均取消。 |
| 文件与运行时审计 | PASS | M3 与小任务源/目标哈希不变；无新临时残留、应用通知记录或 `AndroidRuntime` 错误，Activity 仍在前台。 |

本次大任务 fixture 为既有 `M3RunningCancel20260721/src/large.bin`，没有为凑阈值创建 `100 项`、`50 MiB` 或
`5000 项`压力夹具。所有真实写入仍限定在 `M1Sandbox`。

## 已有真实 Watch 5 证据

| 场景 | 证据状态 | 说明 |
|---|---|---|
| 普通 COPY/MOVE/DELETE | PASS | 来自既有 M3 真机记录；范围仍限 `M1Sandbox`。 |
| 冲突取消 | PASS | 用户已确认在真实 Watch 5 上完成。 |
| 替换全部 | PASS | 用户已确认在真实 Watch 5 上完成。 |
| 运行中 COPY 取消 | PASS | 用户已确认在真实 Watch 5 上完成。 |
| 前台服务基础日志/通知清理 | PASS | 见 Task 6 report 的 FGS/logcat 记录。 |

## 2026-07-24 大任务提醒增量的设备重新发现（历史证据）

| 字段 | 当前值 |
|---|---|
| `adb devices -l` 在线 transport | `192.168.31.60:38935` |
| Model | `M2505W1` |
| Device | `grasslte` |
| 设备结论 | `PASS` |
| 原因 | 用户确认该 transport 就是目标 Xiaomi Watch 5；当前 APK 已安装，且大任务提醒页、取消/返回、DELETE 二次确认链和 ordinary no-warning 路径均有 UI、文件系统及运行时审计证据。 |

## 明确未验收的产品边界

1. 不宣称熄屏后继续执行、Activity 重入后的继续执行或长任务不中断。
2. 不宣称进程终止/恢复、任务持久化或自动重试。
3. 不包含 `100 项`、`50 MiB` 或 `5000 项` 真机压力测试；当前 fixture 只用于验证已知大小阈值提示。

## Concerns

- 本地 Lint 没有 error，但保留 2 条既有 `ApplySharedPref` warning；本任务按范围不修改 `TextTransactionJournal.kt`。
- 当前在线 ADB transport 的型号字段为 `M2505W1` / `grasslte`，并已由用户确认就是当前应验收的 Xiaomi Watch 5；本构建设备结论因此为 `PASS`。
- 本次当前 APK 真机回归已取得大任务提醒页和 ordinary no-warning 的 UI、文件系统及运行时证据。
- 不得把进程恢复、持久化、自动重试、熄屏继续或大规模压力测试写成 M3 已验证能力。
