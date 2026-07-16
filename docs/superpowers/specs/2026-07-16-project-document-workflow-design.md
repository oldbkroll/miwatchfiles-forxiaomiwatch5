# WatchFiles 项目文档工作流设计

## 目标

让每个开发阶段拥有一个独立、可收敛的会话上下文：根目录只保留项目入口，长期上下文、路线图、测试清单和阶段交付记录统一进入可提交的 `docs/superpowers/` 工作流。

## 结构决策

- `README.md` 是根目录唯一项目入口，负责说明新会话读取顺序和当前阶段。
- `docs/superpowers/context/` 保存长期项目上下文和设备兼容基线。
- `docs/superpowers/roadmap/` 保存跨阶段计划状态。
- `docs/superpowers/specs/` 与 `docs/superpowers/plans/` 保存设计和实现计划。
- `docs/superpowers/checkpoints/` 保存真机验收和阶段收尾结论。
- `docs/superpowers/workflow/` 保存个人小项目简化协作规则。
- `.superpowers/sdd/` 仅保存本地临时任务记录，不强制纳入版本控制。

## 会话边界

一个阶段对应一个会话。新会话读取 README、context、roadmap、TESTING 和当前阶段 checkpoint；不自动加载已关闭阶段的逐任务 brief、review diff 或完整历史。

## 收尾边界

阶段收尾提交代码、checkpoint 和路线图状态；开发期只用 Debug，阶段完成后才统一构建 Release/阶段包。真机写入继续限定在固定 M1Sandbox，ADB 地址每次动态发现。
