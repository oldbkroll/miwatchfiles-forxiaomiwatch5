# 真机测试规范

## M3 前台文件操作服务与大任务提醒

只使用动态发现的当次在线 ADB serial。先执行 `adb devices -l`，只选择当前状态为
`device` 的目标 Watch 5；不要复用历史无线地址。所有真实写入仍只允许：

`/storage/emulated/0/Download/WatchFilesTest/M1Sandbox`

测试前后记录受控 fixture 的完整文件清单和 SHA-256。M3 验收继续确认 COPY、MOVE、
DELETE 经 `FileOperationCoordinator`、Local Binder 和前台 `FileOperationService`
执行；操作期间检查低重要性持续通知，终态检查通知移除、目录刷新和 logcat。

2026-07-24 的本地 gate 证据：

- `:app:testDebugUnitTest`、`:app:assembleDebug`、`:app:lintDebug`、`git diff --check` 全部成功。
- XML 汇总：`178 tests / 0 failures / 0 errors / 4 skipped`。
- Lint：`0 errors / 2 warnings`，且仅为 `TextTransactionJournal.kt` 第 22、33 行的既有 `ApplySharedPref` warning。
- 当前 Debug APK 构建产物时间：`2026-07-24 12:09:36 +08:00`。
- 当前 Debug APK SHA-256：`00BE0ED679BA9886EAD42DC0DB26F590E80CCDD830F3ED3A08B400CA64B8D8EB`。

2026-07-24 表冠与触觉兼容增量的本地证据：

- `CrownHapticPolicyTest` 覆盖首次非零滚动、零消费距离、40 ms 节流窗口、边界恢复和选择模式长按策略。
- `RoundList` 继续保持 `rotaryScrollableBehavior = null`；自定义表冠事件改为有界队列和单消费者，实际消费距离为零时不请求 `CLOCK_TICK`。
- 文件卡片非选择模式长按请求一次 `LONG_PRESS`；选择模式不重复请求，触觉失败不阻止选择状态切换。
- 当前设备会话执行 `adb devices -l` 和 `adb mdns services` 均未发现在线设备，因此本增量的 Watch 5 表冠、边界和触觉行为标为 `PENDING_DEVICE_UI`，没有安装 APK、没有复用历史 serial，也没有修改设备文件。

大任务提醒语义必须按当前实现和测试记录：

- 阈值固定为递归 `itemCount >= 100`，或已知 `totalBytes >= 52,428,800` bytes（50 MiB）。
- `itemCount` 沿用 Scanner 的递归统计，包含顶层项目、目录、普通文件和符号链接本身。
- `totalBytes` 只在全部大小可得时参与阈值判断；任一项目大小不可得时记录为 `null`，UI 显示“大小未知”，不得按 0 bytes 伪造。
- COPY/MOVE 在提醒确认前不得调用 Engine；DELETE 通过提醒后仍必须经过现有“永久删除”二次确认。
- 提醒阶段取消或返回直接回到 `Idle`，不产生终态结果、不刷新目录、不调用 Engine。
- 本任务不构造以凑阈值为目的的 `100 项`、`50 MiB` 或 `5000 项` 真机压力夹具；这些边界不能写成已做真机验证。

结果必须明确标为：

- `PASS`：有可复核的 UI、文件系统或 logcat 证据。
- `FAIL`：实际结果不符合预期，附日志或复现步骤。
- `PENDING_DEVICE_UI`：未执行或证据不足，不得写成“已验证”。

已有用户确认的真实 Watch 5 M3 证据已覆盖普通 COPY/MOVE/DELETE、冲突取消、替换全部和运行中 COPY 取消。

2026-07-24 大任务提醒增量的 Debug APK 受限真机回归（历史 PASS 证据）：

- `adb devices -l` 仅发现 `192.168.31.60:38935`，属性为 `model=M2505W1`、`device=grasslte`；用户确认该设备就是目标 Watch 5。
- 当前 APK 已安装成功并运行：`com.example.watchfiles.debug`，APK SHA-256 为上文记录值。
- 真实写入和审计均限制在 `/storage/emulated/0/Download/WatchFilesTest/M1Sandbox`。
- 使用既有 `M3RunningCancel20260721/src/large.bin`（256.0 MiB）触发已知大小阈值；递归扫描 UI 显示 `共 2 项 · 总计 256.0 MiB`，未为凑阈值新建压力夹具。
- COPY：真实显示“文件较多”和亮屏风险文案；点击“取消”及系统返回均回到选择页，无终态结果、无文件变更。
- MOVE：真实显示同一提醒；点击“取消”回到选择页，无文件变更。
- DELETE：真实显示同一提醒；点击“继续操作”后进入既有“确认永久删除”页，随后取消，无文件变更。
- 普通小任务：29 B 的 `known.txt` 在小 COPY、小 MOVE 中直接进入既有同名冲突页，在小 DELETE 中直接进入既有永久删除确认页，均未出现新的“文件较多”提醒；所有后续操作均取消。
- 结束审计：M3 源/目标 SHA-256 均保持 `a6d72ac7…06484`，小任务源/目标 SHA-256 均保持 `6d6ce506…f7eff0`；未发现新 `.part-dir`、`.backup`、`.watchfiles*`、应用通知记录或 `AndroidRuntime` 错误，Activity 仍在前台。

本次真机证据不扩展为以下能力：熄屏后继续、进程回收后的恢复、任务持久化、自动重试，以及为凑阈值而构造的 `100 项`、`50 MiB` 或 `5000 项` 压力测试。这些仍应标为 `PENDING_DEVICE_UI` 或明确的非目标。

表冠与触觉兼容的设备验收也必须使用当次动态发现的 Watch 5 serial；未发现设备时只能记录 `PENDING_DEVICE_UI`，不得把本地编译或策略测试写成厂商触觉已通过。

## 常用命令

```powershell
adb devices -l
adb -s <serial> shell find /storage/emulated/0/Download/WatchFilesTest/M1Sandbox -maxdepth 3 -type f -print
adb -s <serial> shell sha256sum <fixture-file>
adb -s <serial> shell dumpsys notification
adb -s <serial> logcat -d AndroidRuntime:E *:S
```

文本覆盖与另存为也必须遵守同一 M1Sandbox 边界；不得修改 `SafeTextWriteRepository` 的安全写入路径以配合测试。
