# 当前开发上下文

更新日期：2026-07-24

当前阶段为 M3。实现提交 `c9b2c66` 已完成大任务亮屏风险提示的代码与评审；随后 `9875136`、`3422ab9`、
`928cffb` 完成表冠与触觉兼容增量，当前修订增加边界一次性短反馈：纯 Kotlin 触觉策略、表冠有界事件队列/单消费者，以及文件卡片长按
进入选择模式时的一次性系统触觉。Scanner 完成递归统计后，
Runner 会在 COPY/MOVE/DELETE 真正执行前发布 `WaitingForLargeOperationConfirmation`；DELETE 通过该 gate 后仍保留现有
“永久删除”二次确认；提醒页取消直接回到 `Idle`，不产生终态结果、不刷新目录。前台 Service、Started + bound 的同进程
Local Binder、重连状态桥接和低重要性通知生命周期保持不变；文本覆盖和另存为仍由 `SafeTextWriteRepository` 负责。

本地基线：Debug 单元测试、`assembleDebug`、`lintDebug` 和 `git diff --check` 均成功；解析结果为
`178 tests / 0 failures / 0 errors / 4 skipped`。Lint 仍只保留两条 `TextTransactionJournal.kt`
第 22、33 行的既有 `ApplySharedPref` warning，0 errors。当前最终 Debug APK 为 `0.3.1-dev-debug`、
versionCode 6、targetSdk 29，SHA-256 为
`A819128CFD68AAC8E76ED55A897C2A957D3D93E7AD990A79DFFE9D36105C69CF`，构建产物时间为
`2026-07-24 12:32:18 +08:00`。

2026-07-24 已完成 Release 交接：正式版本名为 `0.3.1`、versionCode 6、targetSdk 29，使用本机 `askey1`
（别名 `key0`）签名；keystore 不纳入 Git。最终 Release APK、签名证书指纹和构建验证见
`docs/superpowers/checkpoints/2026-07-24-release-handoff.md`。

真实 Watch 5 的既有 M3 证据已覆盖普通 COPY/MOVE/DELETE、冲突取消、替换全部和运行中 COPY 取消，均限制在
M1Sandbox。2026-07-24 用户确认在线设备 `192.168.31.60:38935`（`model=M2505W1`、`device=grasslte`）
就是目标 Watch 5；当前 Debug APK 已安装并完成受限真机回归。大任务 COPY/MOVE/DELETE 提醒页、取消语义、
DELETE 二次确认链，以及普通小 COPY/MOVE/DELETE 不出现新提醒均已通过；文件哈希、临时残留、通知和崩溃审计正常。

本次表冠与触觉增量已在动态发现的 `adb-d87a2e34-S40wiQ._adb-tls-connect._tcp`（`M2505W1` / `grasslte`）上完成受限回归：
普通 rotary encoder 滚动无连续触觉；到达底部和反向到达顶部各记录一次 `Primitive=TICK`，持续 `17–18 ms`，同一边界继续输入不重复；
文件长按进入选择和选择模式重复长按均通过。设备历史中安装前的 `CLOCK_TICK` 长触觉记录不作为本次结果；安装后没有新的
`TEXTURE_TICK` 请求。进程保持前台，`AndroidRuntime` 为空，M1Sandbox 没有新增任务临时残留。

2026-07-24 已完成 M3 启动、内存和目录加载性能收尾只读复测：当前动态 serial 为
`adb-d87a2e34-S40wiQ._adb-tls-connect._tcp`，设备为 `M2505W1` / `grasslte` / Android 14 / API 34；冷启动 5 次为
`3617/3580/3593/3595/3596 ms`，中位数 `3595 ms`。主页、目录层级、文件详情、UTF-8 文本查看、长按选择和 4000×3000 图片低内存预览均正常；图片预览 5 轮首尾 PSS 增加约 `1.6 MiB`，退出预览后回到详情为 `72699 KiB`。清空日志后的最终导航审计没有应用进程错误匹配，M1Sandbox 顶层清单未变化。完整测量见
`docs/superpowers/checkpoints/2026-07-24-m3-performance-closeout.md`。

剩余边界：任何熄屏继续或 Activity 重入继续执行的产品性宣称、进程终止/恢复、任务持久化、自动重试，
以及为凑阈值而构造的 `100 项`、`50 MiB` 或 `5000 项` 压力测试。
`START_NOT_STICKY` 是明确设计边界，不代表实现了进程恢复。

M3 已完成当前范围内的开发与验证；后续不扩大到持久化恢复、自动重试或压力测试。历史决策与兼容约束见
`docs/superpowers/context/PROJECT_CONTEXT.md`。
