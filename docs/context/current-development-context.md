# 当前开发上下文

更新日期：2026-07-24

当前阶段为 M3。当前 HEAD `297672b` 已完成大任务亮屏风险提示的代码与评审：Scanner 完成递归统计后，
Runner 会在 COPY/MOVE/DELETE 真正执行前发布 `WaitingForLargeOperationConfirmation`；DELETE 通过该 gate 后仍保留现有
“永久删除”二次确认；提醒页取消直接回到 `Idle`，不产生终态结果、不刷新目录。前台 Service、Started + bound 的同进程
Local Binder、重连状态桥接和低重要性通知生命周期保持不变；文本覆盖和另存为仍由 `SafeTextWriteRepository` 负责。

本地基线：Debug 单元测试、`assembleDebug`、`lintDebug` 和 `git diff --check` 均成功；解析结果为
`172 tests / 0 failures / 0 errors / 4 skipped`。Lint 仍只保留两条 `TextTransactionJournal.kt`
第 22、33 行的既有 `ApplySharedPref` warning，0 errors。当前最终 Debug APK 为 `0.3.1-dev-debug`、
versionCode 6、targetSdk 29，SHA-256 为
`5743D82C60B8C1021B9C93208817B49E755135C82D6D365CA4A87529286E83FE`。

真实 Watch 5 的既有 M3 证据已覆盖：普通 COPY/MOVE/DELETE、冲突取消、替换全部和运行中 COPY 取消，均限制在
M1Sandbox。2026-07-24 重新发现在线 ADB 时，仅看到 `192.168.31.60:38935`，属性为 `model=M2505W1`、
`device=grasslte`；该 transport 不能安全确认就是当前 Xiaomi Watch 5，因此本构建未安装 APK、未执行真机回归。
当前构建的大任务提醒页和 ordinary no-warning 路径都必须继续标为 `PENDING_DEVICE_UI`，不能写成已验证。

剩余缺口：安全确认后的 Watch 5 大任务提醒页验收、任何熄屏继续或 Activity 重入继续执行的产品性宣称、
进程终止/恢复、任务持久化、自动重试、性能收尾，以及为凑阈值而构造的 `100 项`、`50 MiB` 或 `5000 项` 压力测试。
`START_NOT_STICKY` 是明确设计边界，不代表实现了进程恢复。

下一步是在安全确认的 Watch 5 上，仅在 M1Sandbox 内补齐大任务提醒页和当前构建 ordinary 路径的真机证据；不扩大到
持久化恢复、自动重试或压力测试。历史决策与兼容约束见
`docs/superpowers/context/PROJECT_CONTEXT.md`。
