# WatchFiles 路线图

## M3：可靠性、后台操作与性能收尾

- [x] 前台文件操作服务（本地 gates 与真机 COPY/MOVE/DELETE 冒烟已通过）
- [ ] 大任务亮屏风险提示（实现、单元测试和本地 Debug gate 已完成；递归项目数 ≥ 100 或已知总容量 ≥ 52,428,800 bytes〔50 MiB〕时，执行前提示用户尽量保持亮屏。当前构建的真机提示页确认仍为 `PENDING_DEVICE_UI`）
- [ ] 启动、内存和目录加载性能收尾
- [ ] 表冠交互和触觉反馈的厂商兼容实现

M3 真机记录见 `docs/context/m3-foreground-file-operation-service-closeout.md`。已有用户确认的真实 Watch 5 证据覆盖普通 COPY/MOVE/DELETE、冲突取消、替换全部和运行中 COPY 取消；2026-07-24 当前构建因在线 ADB transport 无法安全确认为当前 Watch 5，仅完成本地 gate，大任务提示页与本构建 ordinary rerun 仍保持 `PENDING_DEVICE_UI`。

### M3 当前边界调整

- 不实现进程终止后的操作恢复、恢复日志、自动重试或任务持久化；`START_NOT_STICKY` 保持为明确边界。
- 不把熄屏继续操作或 Activity 重入后的任务恢复作为手表产品能力验收；不强制保持应用亮屏或改变系统调度策略。
- 不执行为凑阈值而构造的 `100 项`、`50 MiB` 或 `5000 项` 真机压力测试；以大任务执行前的亮屏风险提示作为轻量保护措施。

## M4：可选能力

- [ ] 内置视频播放
- [ ] ZIP 查看
- [ ] ZIP 解压
