# M3 表冠交互与触觉兼容实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (\`- [ ]\`) syntax for tracking.

**Goal:** 在不触发 Watch 5 缺失 Wear 触觉类的前提下，稳定快速表冠滚动，并仅为列表顶部/底部到达和长按进入文件选择模式提供可降级的系统触觉反馈。

**Architecture:** 保留 RoundList 的自定义 onRotaryScrollEvent 路径，将旋转像素放入有界 Channel，单个 LaunchedEffect 顺序调用 ScalingLazyListState.scrollBy。纯 Kotlin CrownHapticPolicy 只在未消费事件指向顶部/底部且同一边界尚未反馈时允许一次反馈；Android View.performHapticFeedback 适配器负责映射 VIRTUAL_KEY 和 LONG_PRESS，失败时返回 false 但不影响交互。

**Tech Stack:** Kotlin 2.0.21、Jetpack Compose/Wear Compose 1.4.1、Kotlin Coroutines 1.9.0、Android View.performHapticFeedback、JUnit 4、Gradle Debug build、Android Lint、动态 ADB Watch 5 真机验收。

## Global Constraints

- 保留 rotaryScrollableBehavior = null，不得恢复依赖 com.google.wear.input.WearHapticFeedbackConstants 的 Wear Compose 默认旋转路径。
- 不新增震动权限、第三方依赖、Wear OS 服务或厂商私有 SDK。
- 表冠事件使用有界队列和单消费者；不为每个旋转事件新建协程。
- 正常表冠滚动不发送触觉；未消费的顶部/底部边界事件每次到达只发送一次 `VIRTUAL_KEY`。
- 只有非选择模式长按文件卡片进入选择模式时才发送一次 LONG_PRESS。
- View.performHapticFeedback 返回 false 或抛出异常时静默降级，不阻止滚动或选择。
- 不改变目录排序、选择、多选、全选、系统返回键和文件操作安全语义。
- 保持 targetSdk 29、requestLegacyExternalStorage、armeabi-v7a 和小米 Watch 5 自定义表冠滚动逻辑。
- 不引入进程恢复、任务持久化、自动重试或压力测试。
- 真机写操作仍只允许 /storage/emulated/0/Download/WatchFilesTest/M1Sandbox；本任务表冠/长按验证不应写入设备文件。
- 每次设备会话先运行 adb devices -l，只使用当次在线目标 Watch 5 serial，不复用历史无线地址。

---

### Task 1: 建立纯逻辑触觉策略和 Android 适配器

**Files:**
- Create: app/src/main/java/com/example/watchfiles/interaction/CrownHapticPolicy.kt
- Create: app/src/main/java/com/example/watchfiles/interaction/AndroidHapticFeedback.kt
- Test: app/src/test/java/com/example/watchfiles/interaction/CrownHapticPolicyTest.kt

**Interfaces:**
- Produces CrownHapticPolicy with boundaryReached(deltaPixels: Float, consumedPixels: Float): CrownBoundary?.
- Produces shouldEmitLongPressHaptic(selectionMode: Boolean): Boolean.
- Produces enum class HapticCue { ScrollBoundary, LongPress } and fun View.performWatchHaptic(cue: HapticCue): Boolean.

- [ ] **Step 1: Write the failing policy tests**

Create CrownHapticPolicyTest.kt:

~~~kotlin
package com.example.watchfiles.interaction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrownHapticPolicyTest {
    @Test
    fun crownScrollDoesNotEmitContinuousHaptic() {
        val policy = CrownHapticPolicy()

        assertNull(policy.boundaryReached(deltaPixels = -12f, consumedPixels = -12f))
    }

    @Test
    fun reachingTopEmitsOneBoundaryCue() {
        val policy = CrownHapticPolicy()

        assertEquals(
            CrownBoundary.Top,
            policy.boundaryReached(deltaPixels = 12f, consumedPixels = 0f),
        )
        assertNull(policy.boundaryReached(deltaPixels = 12f, consumedPixels = 0f))
    }

    @Test
    fun reachingBottomEmitsOneBoundaryCue() {
        val policy = CrownHapticPolicy()

        assertEquals(
            CrownBoundary.Bottom,
            policy.boundaryReached(deltaPixels = -12f, consumedPixels = 0f),
        )
        assertNull(policy.boundaryReached(deltaPixels = -12f, consumedPixels = 0f))
    }

    @Test
    fun boundaryCueCanEmitAgainAfterLeavingAndReturning() {
        val policy = CrownHapticPolicy()

        assertEquals(
            CrownBoundary.Top,
            policy.boundaryReached(deltaPixels = 12f, consumedPixels = 0f),
        )
        assertNull(policy.boundaryReached(deltaPixels = -12f, consumedPixels = -12f))

        assertEquals(
            CrownBoundary.Top,
            policy.boundaryReached(deltaPixels = 12f, consumedPixels = 0f),
        )
    }

    @Test
    fun longPressFeedbackOnlyAppliesWhenEnteringSelectionMode() {
        assertTrue(shouldEmitLongPressHaptic(selectionMode = false))
        assertFalse(shouldEmitLongPressHaptic(selectionMode = true))
    }
}
~~~

- [ ] **Step 2: Run the focused test and verify RED**

Run:

~~~powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.watchfiles.interaction.CrownHapticPolicyTest" --no-daemon --console=plain
~~~

Expected: compilation fails because the interaction package and policy symbols do not exist.

- [ ] **Step 3: Implement the minimal pure policy and adapter**

Create CrownHapticPolicy.kt:

~~~kotlin
package com.example.watchfiles.interaction

enum class CrownBoundary {
    Top,
    Bottom,
}

class CrownHapticPolicy {
    private var lastBoundary: CrownBoundary? = null

    fun boundaryReached(deltaPixels: Float, consumedPixels: Float): CrownBoundary? {
        if (deltaPixels == 0f) return null
        if (consumedPixels != 0f) {
            lastBoundary = null
            return null
        }

        val boundary = if (deltaPixels > 0f) CrownBoundary.Top else CrownBoundary.Bottom
        if (lastBoundary == boundary) return null

        lastBoundary = boundary
        return boundary
    }
}

fun shouldEmitLongPressHaptic(selectionMode: Boolean): Boolean = !selectionMode
~~~

Create AndroidHapticFeedback.kt:

~~~kotlin
package com.example.watchfiles.interaction

import android.view.HapticFeedbackConstants
import android.view.View

enum class HapticCue {
    ScrollBoundary,
    LongPress,
}

fun View.performWatchHaptic(cue: HapticCue): Boolean = runCatching {
    performHapticFeedback(
        when (cue) {
            HapticCue.ScrollBoundary -> HapticFeedbackConstants.VIRTUAL_KEY
            HapticCue.LongPress -> HapticFeedbackConstants.LONG_PRESS
        },
    )
}.getOrDefault(false)
~~~

- [ ] **Step 4: Run the focused test and verify GREEN**

Run the same focused Gradle command. Expected: all five policy tests pass with zero failures and zero errors.

- [ ] **Step 5: Commit the policy seam**

~~~powershell
git add app/src/main/java/com/example/watchfiles/interaction app/src/test/java/com/example/watchfiles/interaction/CrownHapticPolicyTest.kt
git diff --cached --check
git commit -m "feat: add crown haptic policy"
~~~

Expected: one commit containing only the policy, adapter, and JVM tests.

---

### Task 2: Serialize custom rotary scrolling and add boundary feedback

**Files:**
- Modify: app/src/main/java/com/example/watchfiles/MainActivity.kt in imports and rotaryScroll.

**Interfaces:**
- Consumes CrownHapticPolicy and View.performWatchHaptic from Task 1.
- Produces the existing RoundList behavior with a bounded event buffer, single scroll consumer, and no Wear Compose default rotary behavior.

- [ ] **Step 1: Add the implementation imports and bounded queue**

Add these imports to MainActivity.kt:

~~~kotlin
import androidx.compose.ui.platform.LocalView
import com.example.watchfiles.interaction.CrownHapticPolicy
import com.example.watchfiles.interaction.HapticCue
import com.example.watchfiles.interaction.performWatchHaptic
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
~~~

Keep rotaryScrollableBehavior = null unchanged.

- [ ] **Step 2: Replace per-event coroutine launches with one bounded consumer**

Replace only the body of rotaryScroll with:

~~~kotlin
@Composable
private fun Modifier.rotaryScroll(state: ScalingLazyListState): Modifier {
    val focusRequester = remember { FocusRequester() }
    val eventChannel = remember {
        Channel<Float>(
            capacity = 32,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    }
    val view = LocalView.current
    val hapticPolicy = remember { CrownHapticPolicy() }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    LaunchedEffect(state, eventChannel, view) {
        for (delta in eventChannel) {
            val consumed = state.scrollBy(delta)
            if (hapticPolicy.boundaryReached(deltaPixels = delta, consumedPixels = consumed) != null) {
                view.performWatchHaptic(HapticCue.ScrollBoundary)
            }
        }
    }

    DisposableEffect(eventChannel) {
        onDispose { eventChannel.close() }
    }

    return this
        .onRotaryScrollEvent { event ->
            eventChannel.trySend(event.verticalScrollPixels)
            true
        }
        .focusRequester(focusRequester)
        .focusable()
}
~~~

Remove the now-unused rememberCoroutineScope local from this function only; keep the import if other MainActivity code still uses it.

- [ ] **Step 3: Run compilation and focused regressions**

Run:

~~~powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.watchfiles.MainActivityOperationRoutingTest" --tests "com.example.watchfiles.browser.*" --no-daemon --console=plain
.\gradlew.bat :app:compileDebugKotlin --no-daemon --console=plain
~~~

Expected: both commands exit 0; existing routing and browser tests remain green and the app compiles with the Wear default rotary behavior still disabled.

- [ ] **Step 4: Commit serialized rotary handling**

~~~powershell
git add app/src/main/java/com/example/watchfiles/MainActivity.kt
git diff --cached --check
git commit -m "fix: serialize crown scroll events"
~~~

---

### Task 3: Add long-press feedback when entering file selection

**Files:**
- Modify: app/src/main/java/com/example/watchfiles/MainActivity.kt in FileChip.
- Test: app/src/test/java/com/example/watchfiles/interaction/CrownHapticPolicyTest.kt from Task 1 covers the selection-mode policy.

**Interfaces:**
- Consumes shouldEmitLongPressHaptic(selectionMode) and HapticCue.LongPress from Task 1.
- Preserves the existing onBeginSelection(entry.path) callback and does not add a new menu or operation command.

- [ ] **Step 1: Add LocalView and interaction imports if not already present**

Use LocalView.current and the existing HapticCue/performWatchHaptic adapter. Do not use Vibrator, LocalHapticFeedback, or Wear Compose haptic APIs.

- [ ] **Step 2: Implement the smallest long-press change**

Inside FileChip obtain the current Compose host view:

~~~kotlin
val view = LocalView.current
~~~

Replace only the existing long-click body with:

~~~kotlin
onLongClick = {
    if (shouldEmitLongPressHaptic(selectionMode)) {
        view.performWatchHaptic(HapticCue.LongPress)
        onBeginSelection(entry.path)
    }
},
~~~

The selection-mode branch intentionally does nothing, matching the existing behavior where a long press does not toggle a second selection.

- [ ] **Step 3: Run browser and full unit tests**

Run:

~~~powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.watchfiles.interaction.CrownHapticPolicyTest" --tests "com.example.watchfiles.browser.*" --no-daemon --console=plain
.\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain
~~~

Expected: policy, browser, file operation, text, and routing tests all pass.

- [ ] **Step 4: Commit long-press feedback**

~~~powershell
git add app/src/main/java/com/example/watchfiles/MainActivity.kt
git diff --cached --check
git commit -m "feat: add long press haptic feedback"
~~~

---

### Task 4: Complete Debug gate, constrained Watch 5 regression, and M3 evidence

**Files:**
- Verify: app/src/main/java/com/example/watchfiles/interaction/*, app/src/main/java/com/example/watchfiles/MainActivity.kt, and all existing tests.
- Modify after evidence: docs/TESTING.md, docs/roadmap.md, docs/superpowers/roadmap/PROJECT_PLAN.md, docs/context/current-development-context.md, docs/context/m3-foreground-file-operation-service-closeout.md.

- [ ] **Step 1: Run the complete local Debug gate**

Run:

~~~powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME='C:\Users\13073\Documents\AndroidSDK'
.\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain
.\gradlew.bat :app:assembleDebug --no-daemon --console=plain
.\gradlew.bat :app:lintDebug --no-daemon --console=plain
git diff --check
~~~

Expected: unit tests have zero failures/errors; Debug APK builds; Lint has zero errors and only the two documented TextTransactionJournal.kt ApplySharedPref warnings; diff check is clean.

- [ ] **Step 2: Parse fresh local evidence**

Run:

~~~powershell
$testFiles = Get-ChildItem app/build/test-results/testDebugUnitTest/TEST-*.xml
$testSuites = $testFiles | ForEach-Object { [xml]$xml = Get-Content $_.FullName; $xml.testsuite }
$tests = ($testSuites | Measure-Object -Property tests -Sum).Sum
$failures = ($testSuites | Measure-Object -Property failures -Sum).Sum
$errors = ($testSuites | Measure-Object -Property errors -Sum).Sum
$skipped = ($testSuites | Measure-Object -Property skipped -Sum).Sum
Write-Output "tests=$tests failures=$failures errors=$errors skipped=$skipped"
Get-FileHash app/build/outputs/apk/debug/app-debug.apk -Algorithm SHA256
~~~

Record only actual output in the M3 documents; do not stage generated APKs or reports.

- [ ] **Step 3: Rediscover the device and perform read-only interaction checks**

Run:

~~~powershell
adb devices -l
adb mdns services
~~~

Select only the current online target Watch 5 transport. Install the freshly built Debug APK only if the device is available. The crown/haptic regression must not create, delete, rename, copy, move, or modify files.

Check:

1. Home: rotate slowly and quickly; list moves without crash or visible runaway lag.
2. Directory: rotate through the middle and stop at both ends; edge rotation does not change state or produce an obvious repeated tick.
3. File details: rotate the details list and return to the directory; no NoClassDefFoundError or FATAL EXCEPTION.
4. Directory file card: long-press once to enter selection mode and confirm one long-press feedback; long-press again while selection mode is active and confirm no duplicate selection action.

If the target device is unavailable, record device UI as PENDING_DEVICE_UI and do not claim haptic or vendor behavior was verified.

- [ ] **Step 4: Audit runtime output**

Clear logcat before interaction checks and then run:

~~~powershell
$deviceSerial = (adb devices | Select-String "`tdevice$")[0].Line.Split("`t")[0]
if ([string]::IsNullOrWhiteSpace($deviceSerial)) { throw "No authorized Android device" }
adb -s $deviceSerial logcat -d AndroidRuntime:E '*:S'
adb -s $deviceSerial shell dumpsys meminfo com.example.watchfiles.debug
~~~

Expected: no application AndroidRuntime crash, no NoClassDefFoundError, and process remains alive. Record actual results, including any vendor-specific limitation.

- [ ] **Step 5: Update canonical M3 evidence documents**

After local and device evidence exists, update:

- docs/roadmap.md and docs/superpowers/roadmap/PROJECT_PLAN.md: mark only “表冠交互和触觉反馈的厂商兼容实现” complete if local gate and device evidence support it; keep performance unchecked.
- docs/TESTING.md: add crown queue, edge behavior, long-press feedback, standard Android fallback, and PENDING_DEVICE_UI rule.
- docs/context/current-development-context.md and docs/context/m3-foreground-file-operation-service-closeout.md: record exact commits, test/build/lint results, APK provenance, and actual Watch 5 interaction evidence. Do not claim guaranteed haptic strength, screen-off continuation, process recovery, or stress testing.

- [ ] **Step 6: Run final checks and create one evidence commit**

~~~powershell
git diff --check
git status --short --branch
git add app/src/main/java app/src/test/java docs/TESTING.md docs/roadmap.md docs/superpowers/roadmap/PROJECT_PLAN.md docs/context/current-development-context.md docs/context/m3-foreground-file-operation-service-closeout.md
git diff --cached --check
git commit -m "feat: complete M3 crown and haptic compatibility"
~~~

Expected: only intended source, tests, and evidence documents are staged; no APK, report, or device fixture is staged; remote branches are unchanged.

## Plan Self-Review

- The design’s Android-free boundary policy, zero-consumption rule, and selection-mode rule are covered by Task 1.
- The design’s bounded queue, single consumer, disposal, and retained rotaryScrollableBehavior = null are covered by Task 2.
- The design’s LONG_PRESS feedback and no-op selection-mode behavior are covered by Task 3.
- The design’s local gate, dynamic ADB discovery, no-write device boundary, crash audit, evidence labels, and M3 documentation are covered by Task 4.
- The plan does not introduce WorkManager, persistence, a vibrator permission, Wear haptic classes, a new dependency, or a second operation queue.
- All symbols used by later tasks are defined in Task 1, and all commands have explicit expected outcomes.
