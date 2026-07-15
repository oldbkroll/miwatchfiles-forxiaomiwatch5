# WatchFiles

WatchFiles 是为 480×480 圆屏 Android 手表设计的轻量文件管理器。当前版本已完成只读浏览原型，并开始加入普通文件详情与系统打开能力。

从新 Codex 项目任务继续开发时，请先阅读 [PROJECT_CONTEXT.md](PROJECT_CONTEXT.md)。

## 已完成

- Android 14 / API 34 项目骨架
- `armeabi-v7a` 单 ABI 打包
- 小米手表“照片、媒体内容和文件”兼容授权入口
- 内部存储及常用目录入口
- 只读目录浏览
- 隐藏点文件开关
- 圆屏缩放列表与自定义表冠滚动
- 设备诊断信息
- 小米魔改系统缺少 Google Wear 触觉类时的兼容处理
- 普通文件详情、MIME 类型识别
- 通过安全的 `content://` URI 调用系统应用打开文件
- 内置低内存图片预览，最长边采样到最多 960 px
- 图片支持 1×–4× 双指缩放、边界内平移和双击 1×/2× 切换
- 贴合圆屏上沿的弧形图片返回按钮，避免遮挡图片

当前版本不会复制、移动、重命名或删除文件。图片可使用内置采样查看器预览；音频和视频仍尝试交给系统应用处理，但小米 Watch 5 当前没有实际播放器。

## 用 Android Studio 打开

1. 启动 Android Studio。
2. 选择 **Open**。
3. 选择整个 `WatchFiles` 文件夹，而不是只选择 `app`。
4. 等待 Gradle 同步完成。
5. Build Variant 选择 `debug`。

项目使用本机 Android SDK API 34、Gradle 8.7、AGP 8.5.2 和 JDK 17/21 兼容配置。

## 命令行构建

在项目根目录执行：

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME='C:\Users\13073\Documents\AndroidSDK'
.\gradlew.bat :app:assembleDebug
```

生成位置：

```text
app/build/outputs/apk/debug/app-debug.apk
```

当前开发调试包：

```text
app/build/outputs/apk/debug/app-debug.apk
```

最近一次阶段检查包（可能落后于当前 Debug）：

```text
releases/WatchFiles-0.3.1-image-viewer-arc-debug.apk
```

## 无线 ADB 安装

```powershell
adb connect 手表IP:端口
adb install -r app-debug.apk
```

如需重新验证权限，可先卸载旧测试版再安装：

```powershell
adb uninstall com.example.watchfiles.debug
adb install app-debug.apk
```

0.3.1 暂时以 Android 10（API 29）为目标版本，以适配这台手表保留的旧式“照片、媒体内容和文件”权限页面。Android 14 可能显示“此应用是为旧版 Android 构建”的安装或启动提醒，这是当前私人测试版的预期行为。

Debug 包名为：

```text
com.example.watchfiles.debug
```

`com.example.watchfiles` 目前是占位包名，正式命名前可以修改。

## 已知设备基线

- Android 14 / API 34
- 480×480 px、320 dpi、240×240 dp 圆屏
- `armeabi-v7a` 32 位进程
- `ro.config.low_ram=true`
- 约 1.72 GiB 物理内存
- 普通应用堆增长上限约 192 MiB
- 内部共享存储约 19 GiB
- 无物理 SD 卡
- 仅无线 ADB

详细测试步骤见 [TESTING.md](TESTING.md)，迭代计划见 [PROJECT_PLAN.md](PROJECT_PLAN.md)。
