# M1B COPY/MOVE 阶段收尾

日期：2026-07-16
分支：`m1-file-operations`
阶段提交：`628feaa` 至 `ac7a843`

## 交付范围

- 单前台文件操作任务和取消状态。
- 文件与递归目录安全复制，任务私有 `.part`/`.part-dir` staging、校验和失败清理。
- 同名冲突取消、替换全部和整体替换语义。
- 同 FileStore 快速移动；快速能力探测受设备限制时回退到复制→校验→删除。
- 移动失败时保留源；源删除失败时报告部分成功并保留已发布目标。
- 目标目录必须是目录且不能位于任一源项目内部。

## 代码与验证

- `e587d84`：修复多源/目录快速移动进度、同存储覆盖备份事务、失败恢复和备份清理。
- `509e962`：修复源扫描期间晚到目标未重新请求替换确认的竞态。
- `ac7a843`：针对小米手表 `Files.getFileStore()` 抛出 `SecurityException` 的情况，让快速路径回退到安全复制流程。
- Debug 全量验证：72 tests，0 failures/errors，1 symlink-assumption skip；`assembleDebug`、`lintDebug` 和 `git diff --check` 通过。
- 生产代码没有 `ATOMIC_MOVE`、`REPLACE_EXISTING` 或诊断 `printStackTrace`。

## 小米 Watch 5 真机验收

设备：动态发现的 `M2505W1/grasslte`，当前验收序列号为 `192.168.31.60:37595`。所有写操作限定于 `/storage/emulated/0/Download/WatchFilesTest/M1Sandbox`。

- Debug APK 推送前后 SHA-256：`883a33c99c9c577722ae6a9ad5b8da95da51d27ee2765e443264b2bb9f212d0a`。
- 安装包：`versionCode=6`、`versionName=0.3.1-dev-debug`、`targetSdk=29`；临时 APK 已清理。
- 普通文件 MOVE：完成 1/失败 0，源消失，目标哈希 `c84b6b48d680b9e1031de7818ecd8edee98b7d45fdfbce0ef634e1b3709da714`。
- 递归目录 MOVE：完成 1/失败 0，源目录消失，三层嵌套目标哈希 `a37ddc3e0ef7c29d321ceb1b54c7565acf403f829990dca604dab30059c77be9`，空文件保持 0 字节。
- 冲突取消：完成 0/失败 0，源和旧目标哈希均保持不变。
- 替换全部：完成 1/失败 0，源消失，目标哈希 `f2d85479c81432f3c6dbe878539b8a5edef18dc8f984138077620a971a740087` 与新源一致。
- 非法子目录目标：完成 0/失败 1，显示“目标目录不能位于源文件夹内部”，源内容保留且未创建递归副本。
- 最终审计：无任务 `.part`、`.part-dir`、`.backup`；用户 `CopyTarget/notes.part` 哈希保持；`AndroidRuntime` 和 `FATAL EXCEPTION` 均为空。

## 阶段边界

M1B 已关闭。删除确认归入下一阶段 M1C；音频/视频播放器不在 M1B 范围。项目文档结构已在本次收尾统一迁移到 `docs/superpowers/`，根目录只保留 `README.md` 入口。
