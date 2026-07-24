# M3 表冠交互与触觉兼容设计

日期：2026-07-24
状态：设计已确认

## 1. 背景与目标

Watch 5 固件缺少 `com.google.wear.input.WearHapticFeedbackConstants`。当前项目因此关闭 Wear Compose 默认的 `rotaryScrollableBehavior`，改用 `Modifier.onRotaryScrollEvent` 和 `ScalingLazyListState.scrollBy(...)` 自行处理表冠滚动。

本增量完成 M3 剩余的表冠交互与触觉反馈兼容收尾：保留不会触发缺失类的自定义滚动路径，避免快速旋转事件造成协程堆积，并通过 Android 标准 View 触觉接口为表冠滚动和长按进入文件选择模式提供可降级反馈。

## 2. 范围与非目标

### 本增量范围

- 保留所有 `RoundList` 使用的自定义表冠滚动，不恢复 Wear Compose 默认旋转行为。
- 将表冠事件送入有界队列，由单个协程按顺序调用 `scrollBy`。
- 队列满时丢弃旧事件，避免快速旋转时无限创建滚动协程。
- 仅当 `scrollBy` 实际消费了非零距离时触发表冠 `CLOCK_TICK` 反馈。
- 对表冠触觉增加最小时间间隔，避免快速旋转时触觉过密。
- 文件卡片在非选择模式下长按进入选择模式时触发一次 `LONG_PRESS` 反馈。
- 触觉接口返回失败或厂商不支持时静默降级，滚动与选择行为不受影响。
- 主页、目录页、详情页和其他复用 `RoundList` 的页面继续使用同一交互路径。

### 非目标

- 不恢复 `rotaryScrollableBehavior` 或任何依赖 Google Wear 触觉类的实现。
- 不新增震动权限、第三方依赖、Wear OS 服务或厂商私有 SDK。
- 不改变目录排序、选择、多选、全选、系统返回键或文件操作安全语义。
- 不为普通点击、列表边界、取消操作增加额外触觉。
- 不实现进程恢复、后台任务持久化、自动重试或长任务压力测试。

## 3. 组件与接口设计

### 3.1 纯逻辑触觉策略

新增 Android-free 的触觉策略模块，定义两类反馈意图：表冠滚动 tick 和长按进入选择模式。

策略只决定“是否允许发出反馈”，不直接依赖 `View`：

- 第一次实际表冠滚动允许反馈；
- 实际消费距离为 0 时拒绝反馈；
- 距上一次表冠反馈小于固定节流窗口时拒绝反馈；
- 节流窗口达到后再次允许反馈；
- 长按反馈只在 `selectionMode == false` 时允许。

节流使用单调时间源，默认窗口固定为 40 ms；该值用于限制反馈频率，不改变旋转距离或滚动方向。

### 3.2 Android 触觉适配器

Android 适配器接收一个 `View`，分别将触觉意图映射为：

- 表冠滚动：`HapticFeedbackConstants.CLOCK_TICK`；
- 长按进入选择：`HapticFeedbackConstants.LONG_PRESS`。

适配器只调用 `View.performHapticFeedback(...)`，不调用 `Vibrator`，不申请权限，也不把反馈失败转换为业务错误。

### 3.3 自定义表冠滚动处理

`rotaryScroll` 保持现有焦点请求和 `focusable` 行为，但将事件处理调整为：

1. `onRotaryScrollEvent` 使用非阻塞发送将 `verticalScrollPixels` 放入有界队列；
2. `LaunchedEffect` 中只有一个消费者顺序调用 `state.scrollBy(delta)`；
3. 根据实际消费距离调用纯逻辑触觉策略；
4. 组件销毁时关闭队列，取消消费者和焦点相关协程。

事件处理不改变 `onRotaryScrollEvent` 的消费结果，仍返回 `true`。列表到达顶部或底部时，如果实际消费距离为零，不产生 tick。

### 3.4 长按文件卡片

文件卡片继续使用 `combinedClickable`。在已有 `onLongClick` 回调中：

- 非选择模式先请求一次 `LONG_PRESS` 反馈，再调用 `onBeginSelection`；
- 选择模式保持现有逻辑，不发送反馈；
- 反馈调用失败时仍调用 `onBeginSelection`。

不增加新的确认页、菜单或文件操作命令。

## 4. 数据流与生命周期

```text
ROTARY_ENCODER
  -> onRotaryScrollEvent
  -> bounded delta queue
  -> single LaunchedEffect consumer
  -> ScalingLazyListState.scrollBy
  -> consumed distance
  -> haptic policy
  -> Android View.performHapticFeedback

long press file card
  -> selection-mode policy
  -> Android View.performHapticFeedback(LONG_PRESS)
  -> existing onBeginSelection
```

队列、消费者和策略状态均绑定当前 Compose 组件实例；页面离开后不保留事件、不继续发送触觉，也不影响下一页面的列表状态。触觉失败不传播异常。

## 5. 测试与验收

### 自动化测试

- 策略首次实际滚动允许 `CLOCK_TICK`。
- 零消费距离不允许 `CLOCK_TICK`。
- 40 ms 节流窗口内抑制重复 tick，窗口边界后恢复。
- 非选择模式长按允许 `LONG_PRESS`，选择模式拒绝重复反馈。
- 现有浏览器、文件操作、文本和操作服务测试全部回归通过。

### Watch 5 真机验收

- 动态发现当次在线 Watch 5 ADB serial，不复用历史地址。
- 主页、目录、文件详情页普通表冠滚动可用且无崩溃。
- 快速连续旋转不会造成卡顿、明显延迟或应用崩溃。
- 列表顶部和底部边界不产生无效滚动触觉。
- 长按文件进入选择模式时有一次反馈，选择模式下不重复产生长按反馈。
- 触觉不可用时滚动、长按和选择状态仍正常。
- 清空并审计 `AndroidRuntime`、`FATAL EXCEPTION`，确认没有 `NoClassDefFoundError`。

真实文件写入限制、Debug-only 构建、`targetSdk 29`、`requestLegacyExternalStorage`、`armeabi-v7a` 和当前 M3 其他边界保持不变。

## 6. 完成条件

- 自定义表冠路径保持启用，Wear Compose 缺失类不再被引入。
- 快速旋转通过有界队列和单消费者处理，没有每事件新建协程。
- 表冠和长按触觉均走标准 Android 适配器并可静默降级。
- 触觉策略有 JVM 测试，现有测试、Debug 构建和 Lint 通过。
- Watch 5 真机完成表冠、边界、长按和崩溃审计；未验证的厂商行为不写成已验证。
