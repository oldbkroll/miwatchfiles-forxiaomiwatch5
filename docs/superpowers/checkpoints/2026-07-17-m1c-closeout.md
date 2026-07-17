# M1C DELETE 阶段收尾

日期：2026-07-17  
基线：`33faee3`（Task 5）  
阶段：M1C DELETE 确认与取消验收  
状态：Debug 真机证据完成；Release 交接不在本阶段范围。

## 构建、设备与安全范围

- Debug gate：`:app:testDebugUnitTest :app:assembleDebug :app:lintDebug --no-daemon --console=plain` 成功；93 tests，0 failures，0 errors，`LINT_ISSUE_NODES=0`。本阶段未构建 Release。
- Debug APK：`app/build/outputs/apk/debug/app-debug.apk`；SHA-256 为 `E20FBFA3B880086D1EC9B1F3C7EBCD5E37154273CA4725029BEB4EDEEE2DF277`。
- 安装前后动态运行 `adb devices -l` 与 `adb mdns services`；单文件/空目录/递归删除会话动态使用 `192.168.31.60:41113`，扩展取消会话动态使用 `adb-d87a2e34-S40wiQ._adb-tls-connect._tcp`，重新连接后的追加取消复核动态使用 `192.168.31.60:38245`，设备均为 `M2505W1/grasslte`。未假定或复用历史 serial；旧 transport 在重新发现时显示为 offline。
- 安装成功后核对：`versionCode=6`、`versionName=0.3.1-dev-debug`、`targetSdk=29`。
- 设备写入严格限制在 `/storage/emulated/0/Download/WatchFilesTest/M1Sandbox`。本次新增和删除进行中取消 fixture 的所有操作只在 `DeleteCancel` 下，未触碰 `DeleteFile`、`DeleteTree`、system/media 或个人目录。

## 真机验收证据

- 单文件、空目录、递归目录删除，以及确认前取消（删除按钮与硬件 Back）沿用本阶段前序真机证据：预览包含 top-level/recursive count 与 known size，成功终态为完成 1、失败 0；`source.txt`、`empty/`、`nested/` 与 leaf 均不存在。`notes.part` SHA-256 为 `c1a66b9d085b43601135241826537b5d5e215143065c41c300a5aa9cb56980ab`，byte-for-byte 保持。
- 扩展的 `CancelBulk` 预览显示 `1 项 · 共 1057 项`、`11.0 KiB`，并显示不可逆删除警告。确认后在 `FILE_OPERATION` 看到“正在删除”，快照为 `f0357.txt`、`180 / 1057`、`2.0 KiB / 11.0 KiB`；立即点击“取消”后终态为“删除已取消”、`完成 0 项 · 失败 1 项`，并提示部分内容可能已删除。ADB 核对显示 1024 个 bulk 文件中 774 个已删除、250 个保留；32 个 bulk 子目录中 24 个已删除、8 个保留；`first.txt` 与 `second.txt` 未触及且哈希不变。
- 为构造可信取消窗口，在已授权的 `DeleteCancel` 下创建 `many`，初始为 405 个 1 字节小文件；选择该目录时预览显示 `1 项 · 共 406 项`、`405 B`，并显示不可逆删除警告。两个保留文件 SHA-256 分别为 `a7937b64b8caa58f03721bb6bacf5c78cb235febe0e70b1b84cd99541461a08e` 与 `16367aacb67a4a017c8da8ab95682ccb390863780f7114dda0a0e0c55644c7c4`。
- 确认后在 `FILE_OPERATION` 看到“正在删除”，立即点击“取消”后终态为“删除已取消”、`完成 0 项 · 失败 1 项`，并提示“删除已取消，部分内容可能已删除”。ADB 只读核对显示取消时 `many` 仍有 365 个文件，即已有 40 个条目删除、其余条目保留；`first.txt` 与 `second.txt` 未触及且哈希不变。
- 未发现 `.part-dir` 或 `.backup`。取消验收后，仅按 exact root prefix 清理 `DeleteCancel/many`；最终 `DeleteCancel` 仅保留 `first.txt`、`second.txt`，未修改其他 M1Sandbox fixture。

## 本地安全验证、回归边界与 crash audit

- 本地 DELETE scanner/engine 验证覆盖 root guard、注入 `AccessDeniedException` 子项失败、不可逆取消、无链接跟随及 `.part` 保留。
- 本阶段未重跑 M1A 新建/重命名、M1B 实际 COPY/MOVE 或图片查看器；既有独立验收记录仍保留，不把未重跑项目写成当前设备结果。
- 删除复现前清空过 logcat；UI inspector 期间的 helper `AndroidRuntime` 行与应用崩溃区分记录。全部 inspector 操作和清理结束后再次清空 logcat、冷启动 Debug 应用且不再调用 inspector；最终 `AndroidRuntime` 与 `FATAL EXCEPTION` 均为空。

M1C 的确认页、单文件/空目录/递归删除、确认前取消、删除中取消、root guard/injected failure、`.part` 保持、ADB fixture 状态和 crash audit 均已有真实证据。项目约束继续保留 `targetSdk 29`、legacy storage、`armeabi-v7a`、crown、dynamic ADB、`M1Sandbox` 以及 Debug/Release policy；Release 仍留待 M1 完成后的独立交接。
