# 真机测试规范

## M3 前台文件操作服务

只使用动态发现的当次在线 ADB serial。先执行 `adb devices -l`，从状态为
`device` 的设备中选择目标 Watch 5；不要复用历史无线地址。所有真实写入仅限：

`/storage/emulated/0/Download/WatchFilesTest/M1Sandbox`

测试前后记录受控 fixture 的完整文件清单和 SHA-256。M3 验收应确认 COPY、MOVE、
DELETE 经 `FileOperationCoordinator`、Local Binder 和前台 `FileOperationService`
执行；操作期间检查低重要性持续通知，终态检查通知移除、目录刷新和 logcat。

结果必须明确标为：

- `PASS`：有可复核的 UI、文件系统或 logcat 证据。
- `FAIL`：实际结果不符合预期，附日志或复现步骤。
- `PENDING_DEVICE_UI`：未执行或证据不足，不得写成“已验证”。

Task 6 的已完成证据包括动态 serial `192.168.31.60:41719`、APK 安装、
COPY/MOVE/DELETE 终态文件和前台服务日志。熄屏继续、重开任务、冲突替换、运行中取消、
进程终止/恢复仍须单独记录；当前实现使用 `START_NOT_STICKY`，不宣称自动恢复。

## 常用命令

```powershell
adb devices -l
adb -s <serial> shell find /storage/emulated/0/Download/WatchFilesTest/M1Sandbox -maxdepth 3 -type f -print
adb -s <serial> shell sha256sum <fixture-file>
adb -s <serial> shell dumpsys notification
adb -s <serial> logcat -d AndroidRuntime:E *:S
```

文本覆盖与另存为也必须遵守同一 M1Sandbox 边界；不得修改 `SafeTextWriteRepository` 的安全写入路径以配合测试。
