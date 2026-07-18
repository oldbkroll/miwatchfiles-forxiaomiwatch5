# WatchFiles Superpowers 工作流索引

这里是项目的阶段化工作流文档入口。根目录只保留 `README.md`；长期上下文、路线图、真机清单和阶段收尾记录均以本目录为 canonical 来源。

## 新会话最小读取集

每个开发阶段单独开启一个会话，按以下顺序读取：

1. `../../README.md`
2. `context/PROJECT_CONTEXT.md`
3. `roadmap/PROJECT_PLAN.md`
4. `checkpoints/TESTING.md`
5. 当前阶段的 `specs/`、`plans/` 和 `checkpoints/` 文件
6. 当前任务对应的 `workflow/` 文档；按 A/B/C 风险等级读取，不重复回放已完成阶段的全部历史

不要重新初始化 Android 项目，不要撤销 `targetSdk 29`、`requestLegacyExternalStorage`、`armeabi-v7a` 或自定义表冠滚动兼容逻辑。

## 当前阶段

- M0：已完成
- M1A：已完成
- M1B：已完成 COPY/MOVE 真机验收，收尾记录见 `checkpoints/2026-07-16-m1b-closeout.md`
- M1C：已完成删除确认真机验收，收尾记录见 `checkpoints/2026-07-17-m1c-closeout.md`
- 下一阶段：M2 启动优化与简易文本查看/编辑；内置音频播放器不在当前范围，内置视频和 ZIP 属于 M4 可选评估

## 文档分工

- `context/`：长期不随单个任务反复改写的项目背景、设备基线和兼容约束。
- `roadmap/`：M0–M4 的阶段目标与完成状态。
- `specs/`：功能设计和安全决策。
- `plans/`：可执行实现计划。
- `checkpoints/`：真机验收、阶段总审和阶段交付结论。
- `workflow/personal-project-simplified-workflow.md`：正式的 A/B/C 风险分级协作规则。
- `workflow/2026-07-17-workflow-simplification-memo.md`：工作流简化建议备忘录，不替代正式规则，也不改写已完成阶段历史。

临时任务 brief、逐轮 review diff 和暂停进度可以留在本地 `.superpowers/sdd/`，不作为正式交付文档。
