# WatchFiles

WatchFiles 是一个面向 480×480 圆屏 Android 手表的轻量文件管理器，当前主要针对小米 Watch 5 的低内存、32 位和旧式存储权限环境开发。

正式版本 `0.3.1` 已完成签名构建并上传为 GitHub Draft Release，Release 仍保持草稿，待后续确认后再公开发布。

## 已实现功能

- 内部存储、下载、图片、音乐和视频等常用目录入口
- 文件夹优先、名称排序的目录浏览
- 隐藏点文件开关
- 小米自定义表冠滚动兼容
- 设备诊断信息和文件详情
- MIME 类型识别，以及通过系统应用打开文件
- 低内存图片预览、缩放、平移和双击缩放
- 新建文件夹、重命名、复制、移动和带确认的递归删除
- 复制、移动和删除的冲突确认、取消处理和临时文件清理
- UTF-8 纯文本分段查看
- `.txt`、`.text`、`.log`、`.csv` 和 `.md` 文本编辑
- 覆盖当前文件，以及仅在当前目录内另存为
- 同名确认、摘要校验、失败回滚和事务恢复

## 文本编辑范围

文本功能优先支持严格 UTF-8。普通文本最多支持 16 MiB 分段查看，超过 512 KiB 的文本保持只读；非法 UTF-8、不支持的内容或超出安全范围的文件不会强行进入编辑状态。

覆盖保存和另存为共用安全写入流程，保存失败、取消或应用中断时原文件应保持不变。

## 兼容性

- 目标设备：小米 Watch 5
- 屏幕：480×480 圆屏
- 目标 Android API：29
- `requestLegacyExternalStorage`：启用
- ABI：`armeabi-v7a`
- Debug 包名：`com.example.watchfiles.debug`
- 正式版本：`0.3.1`，`versionCode=6`
- Debug 版本：`0.3.1-debug`，`versionCode=6`

## 构建

使用 Android Studio 打开项目根目录，选择 `debug` 构建变体。开发调试可以在项目根目录执行：

```powershell
.\gradlew.bat :app:assembleDebug
```

APK 输出位置：

```text
app/build/outputs/apk/debug/app-debug.apk
```

正式 Release 使用本机未提交的 keystore 文件签名。不要把 keystore 或密码写入仓库；构建时通过 Gradle 属性注入：

```powershell
$releaseStore = (Resolve-Path 'askey1').Path
$releasePassword = '<本机 keystore 密码>'
$releaseArgs = @(
    ':app:assembleRelease', '--no-daemon', '--console=plain',
    "-Pandroid.injected.signing.store.file=$releaseStore",
    "-Pandroid.injected.signing.store.password=$releasePassword",
    '-Pandroid.injected.signing.key.alias=key0',
    "-Pandroid.injected.signing.key.password=$releasePassword"
)
.\gradlew.bat @releaseArgs
```

Release APK 输出位置：

```text
app/build/outputs/apk/release/app-release.apk
```

## 测试

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:lintDebug
git diff --check
```

真机调试时先动态检查设备：

```powershell
adb devices -l
adb mdns services
```

直接安装：

```powershell
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
```

如果直接安装不稳定，可以先传输再安装：

```powershell
adb -s <serial> push app/build/outputs/apk/debug/app-debug.apk /data/local/tmp/watchfiles-debug.apk
adb -s <serial> shell pm install -r /data/local/tmp/watchfiles-debug.apk
```

涉及真实文件写入的测试只能使用：

```text
/storage/emulated/0/Download/WatchFilesTest/M1Sandbox
```

不要在 system、media、个人目录或该沙箱之外进行写入测试。

## 项目文件说明

`docs/superpowers/` 和 `.superpowers/` 仅保留在本地作为开发工作流记录，不参与 GitHub 同步。

## 当前限制与后续方向

- 不内置音频播放器，音频和视频继续交给系统或其他应用打开
- ZIP 查看、ZIP 解压和视频播放属于后续可选评估
- 暂不提供收藏、最近目录、文件系统搜索、用户自定义排序或独立属性页
- 暂不提供 APK 安装、局域网/WebDAV、Shizuku 或 root 后端
- 不承诺进程终止恢复、任务持久化、自动重试、熄屏继续或为凑阈值构造的压力测试
- 内置视频播放、ZIP 查看和 ZIP 解压属于后续可选评估

## License

本项目原创源代码使用 [Apache License 2.0](LICENSE) 授权。第三方依赖、平台组件和其他外部内容仍以其各自的许可证为准。
