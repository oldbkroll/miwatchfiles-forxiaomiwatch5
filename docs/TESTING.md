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
- XML 汇总：`173 tests / 0 failures / 0 errors / 4 skipped`。
- Lint：`0 errors / 2 warnings`，且仅为 `TextTransactionJournal.kt` 第 22、33 行的既有 `ApplySharedPref` warning。
- 当前 Debug APK SHA-256：`03AF5DBCFDE3F3B89555210D9FC6661DCEBFDADBDB7BFE2BE7718E29C87FC6C1`。

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

已有用户确认的真实 Watch 5 M3 证据已覆盖：普通 COPY/MOVE/DELETE、冲突取消、
替换全部和运行中 COPY 取消。2026-07-24 `adb devices -l` 仅发现
`192.168.31.60:38935`，属性为 `model=M2505W1`、`device=grasslte`；该 transport
不能安全确认就是当前 Xiaomi Watch 5，因此本构建未安装 APK、未执行真机回归。大任务
提醒页和当前构建的 ordinary no-warning 路径都必须保持 `PENDING_DEVICE_UI`。

## 常用命令

```powershell
adb devices -l
adb -s <serial> shell find /storage/emulated/0/Download/WatchFilesTest/M1Sandbox -maxdepth 3 -type f -print
adb -s <serial> shell sha256sum <fixture-file>
adb -s <serial> shell dumpsys notification
adb -s <serial> logcat -d AndroidRuntime:E *:S
```

文本覆盖与另存为也必须遵守同一 M1Sandbox 边界；不得修改 `SafeTextWriteRepository` 的安全写入路径以配合测试。
