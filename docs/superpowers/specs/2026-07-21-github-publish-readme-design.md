# GitHub 发布与公开 README 设计

日期：2026-07-21

## 目标

将当前 `m1-file-operations` 分支及完整 Git 历史发布到 `oldbkroll/miwatchfiles-forxiaomiwatch5`，并把根目录 README 整理为面向 GitHub 访客的项目首页。

## 范围与约束

- 保留现有提交、分支和 `docs/superpowers/**` 历史，不重写 Git 历史。
- README 不介绍 Superpowers 工作流，也不把内部阶段文档作为首页主内容。
- 现有未跟踪备忘录 `docs/superpowers/workflow/2026-07-17-workflow-simplification-memo.md` 不修改、不暂存、不提交。
- 不创建 worktree，不重置或覆盖现有分支。
- 只提交 README 和本次发布所需的说明文件；使用显式路径暂存。

## README 结构

README 采用简洁中文项目首页：项目定位、当前完成范围、设备与 Android 兼容性、Debug 构建/安装、测试安全边界、当前限制和后续路线。它不链接或描述 Superpowers 工作流，避免把内部协作规范当成用户文档。

## 发布流程

先确认工作区、分支、远程和 GitHub CLI 登录状态；写入 README 后运行 `git diff --check`，只暂存 README 和本次发布说明，提交后将当前分支推送到 `origin`。不创建 PR。
