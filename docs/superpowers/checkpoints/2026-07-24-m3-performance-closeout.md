# M3 启动、内存和目录加载性能收尾

日期：2026-07-24

状态：PASS。当前 Debug 构建已在动态发现的目标 Xiaomi Watch 5 上完成启动、目录浏览、文本查看、选择交互、图片预览、PSS 和应用进程日志的只读回归；未发现足以阻止 M3 收口的问题。

## 设备与 APK

- ADB serial：`adb-d87a2e34-S40wiQ._adb-tls-connect._tcp`（本次会话动态发现）
- Model / device：`M2505W1` / `grasslte`
- Android：14 / API 34
- ABI：`armeabi-v7a,armeabi`
- 包：`com.example.watchfiles.debug`
- APK：`app/build/outputs/apk/debug/app-debug.apk`
- APK 大小：`21,224,202 bytes`
- APK SHA-256：`A819128CFD68AAC8E76ED55A897C2A957D3D93E7AD990A79DFFE9D36105C69CF`
- versionCode / versionName / targetSdk：`6` / `0.3.1-dev-debug` / `29`

## 本地 Debug gate

| 项目 | 结果 |
|---|---|
| `:app:testDebugUnitTest --no-daemon --console=plain` | exit 0；`178 tests / 0 failures / 0 errors / 4 skipped` |
| `:app:assembleDebug --no-daemon --console=plain` | exit 0；`BUILD SUCCESSFUL` |
| `:app:lintDebug --no-daemon --console=plain` | exit 0；0 errors，2 条既有 `ApplySharedPref` warning |
| `git diff --check` | exit 0，无输出 |

Gradle 期间的 SDK XML v4 环境提示为非致命 warning；本检查点记录的是性能只读回归，正式 Release 交接见
`2026-07-24-release-handoff.md`。

## 真机只读回归

本次没有创建、删除、复制、移动、重命名或修改设备文件。操作路径只读检查既有
`/storage/emulated/0/Download/WatchFilesTest/M1Sandbox` fixture。

| 场景 | 实际结果 |
|---|---|
| 冷启动 | 5 次 `TotalTime=3617/3580/3593/3595/3596 ms`，中位数 `3595 ms`；5/5 状态 `ok`、`LaunchState=COLD` |
| 热启动 | 厂商系统返回 `LaunchState=UNKNOWN (0)`，记录到的 `WaitTime=45–125 ms`；未把它写成可靠的 `TotalTime` |
| 目录浏览 | 主页、内部存储、Download、WatchFilesTest、M1Sandbox、CopySource 和父目录均正常加载；未见读取失败提示 |
| 文本查看 | `known.txt` 详情与 UTF-8 文本页正常，文本页显示 `字节 0–29 / 29` 和原文，返回路径正常 |
| 选择交互 | 长按 `known.txt` 显示 `已选 1 项` 及复制/移动/删除入口；返回键退出选择，没有启动写入 |
| 图片预览 | `large-sample.jpg` 显示 `4000×3000 · 预览 960×720`，低内存预览可见 |

## 内存与日志

- 图片预览连续 5 轮 PSS：`73095/73179/74671/74699/74735 KiB`；首尾增加 `1640 KiB`。
- 退出图片预览回到详情：`72699 KiB`。
- 清空日志后的最终启动、目录进入/返回审计：应用 PID 过滤无 `AndroidRuntime`、`FATAL EXCEPTION`、`OutOfMemoryError`、`Exception` 或 `Error` 匹配。
- 最终回到主页后进程仍存活，`TOTAL PSS=57696 KiB`。
- M1Sandbox 顶层清单与测试前一致，未新增任务临时残留。

## 结论与边界

本次结果支持将路线图中的“启动、内存和目录加载性能收尾”标记为完成：当前 Debug 构建在目标 Watch 5 上没有明显启动失败、目录加载失败、图片预览 OOM、持续内存失控或应用进程崩溃。

本记录不宣称固定热启动耗时、熄屏继续、Activity 重入继续、进程终止恢复、任务持久化、自动重试，也不包含为凑阈值构造的 `100 项`、`50 MiB` 或 `5000 项`压力测试。既有 COPY/MOVE/DELETE 真机写入证据仍以 M3 主 closeout 为准，并严格限制在 M1Sandbox。
