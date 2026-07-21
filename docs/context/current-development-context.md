# 当前开发上下文

更新日期：2026-07-21

当前阶段为 M3。M3 已交付前台文件操作增量：Runner 从 Coordinator 解耦，Service 提供
Started + bound 的同进程 Local Binder，Client 负责重连和状态桥接，Activity 根据操作状态恢复操作页；
低重要性通知在运行期间持续，终态移除。文本覆盖和另存为仍由 `SafeTextWriteRepository` 负责。

本地基线：Debug 单元测试、`assembleDebug`、`lintDebug` 和 `git diff --check` 均成功；Lint 保留两条
`TextTransactionJournal.kt` 的既有 warning。APK 为 `0.3.1-dev-debug`、versionCode 6、targetSdk 29，
SHA-256 为 `379DB662A806FABC190DD45A0C933863F84A09F98BA21BF9B93AABB946F3A22E`。

真机证据：M2505W1，API 34，动态 serial `192.168.31.60:41719`。在 M1Sandbox 内完成 COPY、MOVE、
DELETE 冒烟；UI 完成字符串、文件清单/哈希、前台服务允许日志、静默通知记录和终态停止日志均有记录。

剩余缺口：熄屏和 Activity 重入、冲突替换、运行中取消、进程终止/恢复、性能和 5000 项压力测试未完成，
均保持 pending。`START_NOT_STICKY` 是明确设计边界，不代表实现了进程恢复。

下一步是补齐上述设备验收和性能证据，再决定是否扩大 M3 范围。历史决策与兼容约束见
`docs/superpowers/context/PROJECT_CONTEXT.md`。
