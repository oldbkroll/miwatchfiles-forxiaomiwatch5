# M2 启动与基础性能基线

日期：2026-07-19

状态：基线完成；当前数据未支持针对性源代码优化

## 设备与包

- 设备：小米 Watch 5 / `M2505W1` / `grasslte`
- ADB 发现：本次会话动态执行 `adb devices -l` 和 `adb mdns services`
- 有效 serial：`adb-d87a2e34-S40wiQ._adb-tls-connect._tcp`
- mDNS 地址：`192.168.31.60:41615`
- 未使用：旧地址 `192.168.31.60:34913`，当次状态为 offline
- APK：`app/build/outputs/apk/debug/app-debug.apk`
- 安装包：`com.example.watchfiles.debug`
- `versionCode=6`、`targetSdk=29`
- 冷/热启动组件：`com.example.watchfiles.debug/com.example.watchfiles.MainActivity`

## 测量边界

启动、目录和 PSS 基线期间没有在真机创建、删除、重命名、复制、移动、修改权限或写入文件。设备已有的大目录不足以提供受控大目录样本，因此没有人为创建大目录；只读检查发现 `/storage/emulated/0/Download/WatchFilesTest/M1Sandbox` 顶层为既有 M1 fixture。

`am start -W` 的 `TotalTime` 是启动时间代理。主页进入目录使用截图首帧发生变化作为“目录页首帧可见”代理；当前工程没有页面可见日志，因此该指标不等同于完整列表内容最终加载时间。

## 冷启动

命令使用 `force-stop` 后启动实际解析出的 Activity：

```powershell
adb -s <serial> shell am force-stop com.example.watchfiles.debug
adb -s <serial> shell am start -W -n com.example.watchfiles.debug/com.example.watchfiles.MainActivity
```

| 次数 | TotalTime | WaitTime |
|---:|---:|---:|
| 1 | 3668 ms | 3692 ms |
| 2 | 3751 ms | 3755 ms |
| 3 | 3604 ms | 3611 ms |
| 4 | 3589 ms | 3606 ms |
| 5 | 3617 ms | 3631 ms |

- `TotalTime` 中位数：3617 ms
- `TotalTime` 最慢：3751 ms
- 5/5 次成功启动；错误组件名的 5 次尝试未计入

## 热启动

每次先按 Home，再使用 `am start -W` 将当前 task 带回前台。

| 次数 | LaunchState | TotalTime | WaitTime |
|---:|:---:|---:|---:|
| 1 | HOT | 87 ms | 102 ms |
| 2 | HOT | 82 ms | 91 ms |
| 3 | HOT | 86 ms | 103 ms |
| 4 | HOT | 77 ms | 90 ms |
| 5 | HOT | 85 ms | 94 ms |

- `TotalTime` 中位数：85 ms
- `TotalTime` 最慢：87 ms

## 主页进入目录

从主页点击“内部存储”，以截图哈希第一次区别于主页截图的时间作为目录页首帧代理：

| 次数 | 首帧代理时间 | 页面变化 |
|---:|---:|:---:|
| 1 | 950 ms | 是 |
| 2 | 1008 ms | 是 |
| 3 | 885 ms | 是 |
| 4 | 867 ms | 是 |
| 5 | 943 ms | 是 |

- 首帧代理时间中位数：943 ms
- 首帧代理时间最慢：1008 ms
- 当前代码中 `FileBrowserViewModel.open()` 和 `DirectPathRepository.list()` 已通过协程/`Dispatchers.IO` 执行；本次数据没有区分 Compose 首帧和目录元数据读取的内部耗时

## PSS

`dumpsys meminfo com.example.watchfiles.debug` 的 `TOTAL PSS`，单位 KiB。

### 主页

`61393, 58441, 58441, 58441, 58441`

- 中位数：58441 KiB
- 最慢/最高：61393 KiB

### 内部存储目录

`59337, 58589, 58629, 58569, 58565`

- 中位数：58589 KiB
- 最慢/最高：59337 KiB

### 4000×3000 图片查看页

使用既有 `/storage/emulated/0/Download/WatchFilesTest/large-sample.jpg`，页面实际显示预览 `960×720`。

`72284, 67272, 67240, 67240, 67240`

- 中位数：67240 KiB
- 最慢/最高：72284 KiB

### 连续进入/退出图片页

每轮记录返回文件详情后的 PSS，以及再次进入图片页后的 PSS，单位 KiB：

| 轮次 | 返回详情后 | 再次进入图片页后 |
|---:|---:|---:|
| 1 | 65712 | 67416 |
| 2 | 65772 | 67468 |
| 3 | 65896 | 68956 |
| 4 | 67372 | 69024 |
| 5 | 67860 | 69080 |

返回详情后的值没有呈现持续快速上涨；第 1 到第 5 轮增加约 2.1 MiB，需在后续 M2/M3 性能收尾继续观察，但当前不足以单独触发图片缓存或解码重构。

## 优化决定

本次没有发现可定位且需要立即修改的启动瓶颈：

- 主页/权限页没有预先扫描目录；
- 目录读取已经在后台执行；
- 热启动中位数为 85 ms；
- 目录首帧代理中位数为 943 ms，但缺少内部阶段日志，不能据此盲目改排序或属性读取；
- PSS 没有出现启动后持续失控上涨。

因此 M2 启动增量只保留这份可重复基线，不修改 `MainActivity.kt`、`FileBrowserViewModel.kt` 或 `DirectPathRepository.kt`。后续若新增文本功能导致基线回归，再用同一命令和同一指标做前后对照。
