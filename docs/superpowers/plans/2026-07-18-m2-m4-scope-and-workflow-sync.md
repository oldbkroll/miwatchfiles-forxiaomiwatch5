# M2-M4 范围与简化工作流同步实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将已确认的 M2-M4 产品范围、文本编辑安全边界和 A/B/C 风险工作流同步到正式路线图、工作流入口和测试清单。

**Architecture:** 这是一次文档-only 同步，不修改 Android 实现，不构建 APK。`PROJECT_PLAN.md` 负责阶段目标，`personal-project-simplified-workflow.md` 负责执行规则，`docs/superpowers/README.md` 负责新会话入口，`TESTING.md` 负责可执行验收清单；已确认 spec 作为本次同步的唯一设计基线。

**Tech Stack:** Markdown、PowerShell 读取/检查、Git；不新增 Android 依赖。

## Global Constraints

- 保持 `targetSdk 29`。
- 保持 `requestLegacyExternalStorage`。
- 保持 `armeabi-v7a`。
- 保持小米 Watch 5 自定义表冠滚动兼容逻辑。
- 真机写操作仅限 `/storage/emulated/0/Download/WatchFilesTest/M1Sandbox`。
- 每次无线 ADB 设备会话动态发现在线 serial，不复用历史地址。
- DELETE、COPY、MOVE、文本保存、ZIP 解压和真实文件写入继续使用 TDD。
- 开发期间只构建 Debug；阶段完成后才进行 Release 交接。
- 不修改已完成 M0、M1A、M1B、M1C 的历史验收结论。
- 不修改未跟踪的 `docs/superpowers/workflow/2026-07-17-workflow-simplification-memo.md`，它继续作为建议备忘录保留。

---

### Task 1: 同步项目路线图

**Files:**
- Modify: `docs/superpowers/roadmap/PROJECT_PLAN.md`
- Reference: `docs/superpowers/specs/2026-07-18-m2-m4-scope-and-workflow-design.md`

**Interfaces:**
- Consumes: 已确认的 M2-M4 范围和“不做”清单。
- Produces: 一份不再把音频、收藏、搜索、独立属性页等列为目标的正式路线图。

- [ ] **Step 1: 将 M2 改为启动优化与文本查看/编辑阶段**

保留已完成的图片预览、缩放平移、MIME 识别和“用其他应用打开”；新增：启动速度与基础性能基线及优化、分段纯文本查看、简易文本编辑（覆盖当前文件、当前目录内另存为）。明确不开发内置音频播放器，视频播放器移至 M4。

- [ ] **Step 2: 收敛 M3 功能清单**

保留前台文件操作服务、息屏继续操作、操作恢复日志、启动/内存/目录性能收尾、5000 项压力测试和表冠/触觉兼容性；删除收藏、最近目录、搜索、用户排序和独立文件属性页。补充固定“文件夹优先、名称排序”继续保留；不把 MIME 当作独立属性页。

- [ ] **Step 3: 将 M4 改为可选评估阶段**

仅列内置视频播放、ZIP 查看和 ZIP 解压，并明确三者不是核心版本必须交付；将 APK 信息及安装、局域网/WebDAV、Shizuku/root 和内置音频列入明确不做项。

- [ ] **Step 4: 更新阶段验收说明**

将下一阶段描述改成：先完成启动性能基线和文本查看/编辑，再根据 M3 稳定性结果评估 M4 的视频和 ZIP；保留 M1B/M1C checkpoint 链接。

- [ ] **Step 5: 验证路线图没有过期目标**

运行：

```powershell
Get-Content -Raw docs/superpowers/roadmap/PROJECT_PLAN.md
rg -n "收藏|最近|搜索|用户排序|文件属性页|内置音频|WebDAV|Shizuku|root|APK 信息及安装" docs/superpowers/roadmap/PROJECT_PLAN.md
```

预期：旧目标只在“不做项”说明中出现，不再出现在待办功能清单中。

### Task 2: 正式工作流改为 A/B/C 风险分级

**Files:**
- Modify: `docs/superpowers/workflow/personal-project-simplified-workflow.md`
- Reference: `docs/superpowers/specs/2026-07-18-m2-m4-scope-and-workflow-design.md`

**Interfaces:**
- Consumes: 已确认的 A/B/C 风险边界。
- Produces: 后续会话可直接执行的简化工作流。

- [ ] **Step 1: 保留阶段、会话和安全底线**

继续保留每阶段一个独立会话、Debug-only、M1Sandbox、动态 ADB、targetSdk/ABI/表冠兼容和阶段收尾完整 gate；不删除安全核心要求。

- [ ] **Step 2: 写入 A 级规则**

A 级包含文档、文案、颜色、间距和不改变行为的重命名；使用几行说明和静态/手工检查，文档-only 不构建 APK，不要求独立复审和真机写操作。

- [ ] **Step 3: 写入 B 级规则**

B 级包含启动优化、文本查看、导航、列表、表冠、MIME、图片查看和不涉及真实写入的媒体能力；使用 3-5 行小计划，关键状态补单测，开发中聚焦验证，硬件交互做一次只读真机检查，阶段收尾统一完整 Debug gate。

- [ ] **Step 4: 写入 C 级规则**

C 级包含文本覆盖/另存为、DELETE、COPY、MOVE、权限、路径、取消、恢复、临时文件、前台文件服务、真实设备写入和 ZIP 解压；继续设计确认、TDD、故障注入、必要的独立复审、M1Sandbox 证据和完整 Debug gate。

- [ ] **Step 5: 删除固定强度流程中的重复劳动**

明确 B 级不强制完整 spec/plan/逐任务 brief，提交按完整功能或可解释修复组织，独立复审默认保留给 C 级和阶段收尾；同时明确不会因此减少安全测试或设备证据。

- [ ] **Step 6: 验证工作流规则一致**

运行：

```powershell
rg -n "A 级|B 级|C 级|文本保存|ZIP 解压|M1Sandbox|targetSdk 29|armeabi-v7a" docs/superpowers/workflow/personal-project-simplified-workflow.md
git diff --check
```

预期：三类规则均有明确范围和验证要求，且没有与已确认安全底线冲突的旧规则。

### Task 3: 更新 Superpowers 入口文档

**Files:**
- Modify: `docs/superpowers/README.md`
- Reference: `docs/superpowers/roadmap/PROJECT_PLAN.md`

**Interfaces:**
- Consumes: 新路线图和正式工作流入口。
- Produces: 新会话不会误以为 M1C 尚未完成，也能找到风险分级规则。

- [ ] **Step 1: 更新当前阶段**

将“下一阶段：M1C 删除确认”改为 M2 启动优化与文本查看/编辑，并保留 M1C checkpoint 链接。

- [ ] **Step 2: 更新最小读取集说明**

保留 README、PROJECT_CONTEXT、PROJECT_PLAN、TESTING 和当前阶段 spec/plan/checkpoint；补充当前阶段相关 workflow 文档按风险等级读取，不要求重新回放已完成阶段的全部历史。

- [ ] **Step 3: 更新 workflow 文档分工**

说明 `workflow/personal-project-simplified-workflow.md` 是正式 A/B/C 规则，`2026-07-17-workflow-simplification-memo.md` 是建议备忘录，不作为新会话的唯一规范来源。

- [ ] **Step 4: 验证入口文档状态**

运行：

```powershell
rg -n "下一阶段|M1C 删除确认|M2|workflow|简化" docs/superpowers/README.md
```

预期：入口状态指向 M2，且正式 workflow 与 memo 的定位清楚。

### Task 4: 更新真机测试清单

**Files:**
- Modify: `docs/superpowers/checkpoints/TESTING.md`
- Reference: `docs/superpowers/specs/2026-07-18-m2-m4-scope-and-workflow-design.md`

**Interfaces:**
- Consumes: M2 文本/启动验收和 A/B/C 真机边界。
- Produces: 可直接执行且不扩大真实写入范围的 M2 测试步骤。

- [ ] **Step 1: 增加 M2 启动与基础性能基线**

明确每个设备会话先动态运行 `adb devices -l` 和 `adb mdns services`；冷启动、热启动、首屏、目录加载和 PSS 各重复 5 次；启动优化期间不创建、删除或修改真机文件。

- [ ] **Step 2: 增加简易文本查看测试**

覆盖 UTF-8 `.txt`、空文本、多行文本、超出编辑范围文本、无法识别/不支持编码的提示，以及从目录进入文本详情和返回路径保持。

- [ ] **Step 3: 增加文本编辑与另存为测试**

仅在 M1Sandbox 内测试：覆盖当前文件、当前目录内另存为、同名冲突确认、取消保存、写入失败、临时文件清理和原文件保护；记录源/目标内容或 SHA-256，不使用任意目录选择。

- [ ] **Step 4: 更新范围说明**

明确内置音频不在计划中，音视频仍可通过其他应用打开；视频和 ZIP 属于 M4 可选评估，不纳入本阶段真机验收；继续保留 M1A/M1B/M1C 的安全清单。

- [ ] **Step 5: 验证测试清单边界**

运行：

```powershell
rg -n "M2|启动|文本|另存为|M1Sandbox|动态|音频|视频|ZIP|Release" docs/superpowers/checkpoints/TESTING.md
git diff --check
```

预期：文本写入测试明确只在 M1Sandbox 内执行，启动基线不产生写操作，M4 可选能力没有被写成已验收功能。

### Task 5: 统一文档自检、提交并交付

**Files:**
- Verify: `docs/superpowers/roadmap/PROJECT_PLAN.md`
- Verify: `docs/superpowers/workflow/personal-project-simplified-workflow.md`
- Verify: `docs/superpowers/README.md`
- Verify: `docs/superpowers/checkpoints/TESTING.md`
- Preserve untracked: `docs/superpowers/workflow/2026-07-17-workflow-simplification-memo.md`

**Interfaces:**
- Consumes: Tasks 1-4 的文档修改。
- Produces: 与已确认 spec 一致、无 trailing whitespace、可追溯的文档提交。

- [ ] **Step 1: 对照 spec 做覆盖检查**

逐项核对 M2 文本查看/编辑、音频外部打开、M3 删除项、M4 可选视频/ZIP、启动基线、A/B/C 规则和不变安全底线；发现遗漏时只修改相关文档，不扩展产品范围。

- [ ] **Step 2: 扫描过期目标和占位文字**

运行：

```powershell
rg -n "TBD|TODO|下一阶段：M1C|内置音频播放器|收藏目录|最近目录|文件系统搜索|独立文件属性页" docs/superpowers/README.md docs/superpowers/roadmap/PROJECT_PLAN.md docs/superpowers/workflow/personal-project-simplified-workflow.md docs/superpowers/checkpoints/TESTING.md
```

预期：不出现占位文字和过期的待办目标；被删除的能力只在“不做项”中出现。

- [ ] **Step 3: 检查 Markdown 空白和工作区范围**

运行：

```powershell
git diff --check
git status --short
```

预期：diff 无 trailing whitespace；只有 4 份正式文档和预先存在的未跟踪 memo 处于预期状态。

- [ ] **Step 4: 提交文档同步**

```powershell
git add docs/superpowers/README.md docs/superpowers/roadmap/PROJECT_PLAN.md docs/superpowers/workflow/personal-project-simplified-workflow.md docs/superpowers/checkpoints/TESTING.md
git commit -m "docs: align M2-M4 roadmap and workflow"
```

预期：提交只包含正式文档同步，不包含未跟踪的建议 memo，也不包含 Android 源码或构建产物。
