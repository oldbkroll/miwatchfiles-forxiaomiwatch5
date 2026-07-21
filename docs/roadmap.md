# WatchFiles 路线图

## M3：可靠性、后台操作与性能收尾

- [x] 前台文件操作服务（本地 gates 与真机 COPY/MOVE/DELETE 冒烟已通过）
- [ ] 息屏继续文件操作（`PENDING_DEVICE_UI`，尚未执行）
- [ ] 操作恢复日志（未实现；`START_NOT_STICKY` 不提供自动恢复）
- [ ] 启动、内存和目录加载性能收尾
- [ ] 5000 项大目录压力测试
- [ ] 表冠交互和触觉反馈的厂商兼容实现

M3 真机记录见 `docs/context/m3-foreground-file-operation-service-closeout.md`。冲突等待/替换、运行中取消和进程终止恢复也仍须单独验收，不因基础冒烟通过而标记完成。

## M4：可选能力

- [ ] 内置视频播放
- [ ] ZIP 查看
- [ ] ZIP 解压
