# M3 大量文件操作提醒实施计划

> For agentic workers: implement this plan task-by-task with `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans`. Steps use checkbox syntax for tracking.

**Goal:** 在 COPY、MOVE、DELETE 真正修改文件前，基于 Scanner 的递归统计为大任务增加一次可取消的亮屏风险确认，同时保持现有文件安全语义、Service 生命周期和 DELETE 二次确认不变。

**Architecture:** 在独立的纯 Kotlin 策略文件中集中定义阈值、摘要和提示文案；Runner 在 Scanner 返回 `Ready` 后统一判断是否需要等待确认。Service、Client、Coordinator 只转发新确认命令，Activity 根据新的 `FileOperationState` 路由到独立圆屏提醒页；普通小任务和现有执行页不改变。

**Tech Stack:** Kotlin 2.0.21、Coroutines、StateFlow、Android Service/Local Binder、Jetpack Compose/Wear Compose、JUnit 4、`kotlinx-coroutines-test`、Gradle Debug build、Android Lint、动态 ADB 真机验收。

## Global Constraints

- 阈值固定为 `itemCount >= 100` 或 `totalBytes >= 52,428,800` bytes（50 MiB）。
- `itemCount` 沿用 Scanner 的递归 `itemCount`，包含顶层项目、目录、普通文件和符号链接本身。
- `totalBytes == null` 时只依据项目数判断，不把未知大小当作 0。
- 大任务提醒必须发生在真正修改文件之前；COPY/MOVE 确认前不得调用 Engine。
- DELETE 的大任务提醒确认后仍必须经过现有 `WaitingForDeleteConfirmation` 和“永久删除”二次确认。
- 大任务提醒取消或系统返回回到 `Idle`，不产生成功、失败或取消终态，不刷新目录，不调用 Engine。
- Service、Client、Coordinator 不新增第二套扫描、队列或持久化机制；Runner 继续是任务状态的唯一业务来源。
- 不改变现有 Scanner、Engine、冲突处理、执行中取消、路径安全和临时文件语义。
- 不改变当前普通操作页、冲突页、删除确认页的卡片高度、统一尺寸或布局。
- 保持 `targetSdk 29`、`requestLegacyExternalStorage`、`armeabi-v7a` 和小米 Watch 5 自定义表冠滚动逻辑。
- 不引入 WorkManager、AIDL、数据库、独立进程或新的第三方依赖。
- 真机写操作只允许 `/storage/emulated/0/Download/WatchFilesTest/M1Sandbox`。
- 每次设备会话先动态执行 `adb devices -l`，只使用当次在线的 Watch 5 serial，不复用历史无线地址。
- 不执行 5000 项压力测试，不承诺熄屏后台继续、Activity 重入恢复或进程终止恢复。
- 不擅自推送远程仓库；每个本地提交完成后先停在当前 `main`。

## 文件结构与职责

**Create**

- `app/src/main/java/com/example/watchfiles/fileops/LargeOperationWarning.kt` — 阈值、摘要文案和纯逻辑格式化函数。
- `app/src/test/java/com/example/watchfiles/fileops/LargeOperationWarningTest.kt` — 阈值边界、未知大小和提示文案测试。

**Modify**

- `app/src/main/java/com/example/watchfiles/fileops/FileOperationModels.kt` — 增加 `WaitingForLargeOperationConfirmation` 状态。
- `app/src/main/java/com/example/watchfiles/fileops/FileOperationRunner.kt` — 扫描完成后的统一等待闸门和 `confirmLargeOperation()`。
- `app/src/main/java/com/example/watchfiles/fileops/FileOperationService.kt` — Service Port 增加确认命令，保持通知和任务生命周期一致。
- `app/src/main/java/com/example/watchfiles/fileops/FileOperationServiceClient.kt` — Client 转发确认命令。
- `app/src/main/java/com/example/watchfiles/fileops/FileOperationCoordinator.kt` — Coordinator 转发确认命令。
- `app/src/main/java/com/example/watchfiles/MainActivity.kt` — 新增状态路由、Back 行为和提醒页回调。
- `app/src/main/java/com/example/watchfiles/FileOperationScreens.kt` — 新增独立圆屏大任务提醒页。
- `app/src/test/java/com/example/watchfiles/fileops/FileOperationRunnerTest.kt` — Runner 闸门、DELETE 顺序和取消语义测试。
- `app/src/test/java/com/example/watchfiles/fileops/FileOperationServiceTest.kt` — Service Adapter/通知状态回归测试。
- `app/src/test/java/com/example/watchfiles/fileops/FileOperationServiceClientTest.kt` — Client 确认命令转发测试。
- `app/src/test/java/com/example/watchfiles/fileops/FileOperationCoordinatorTest.kt` — Coordinator 确认命令转发测试。
- `app/src/test/java/com/example/watchfiles/MainActivityOperationRoutingTest.kt` — 新状态页面路由测试。

**Update after implementation and device evidence**

- `docs/TESTING.md`
- `docs/roadmap.md`
- `docs/superpowers/roadmap/PROJECT_PLAN.md`
- `docs/context/current-development-context.md`
- `docs/context/m3-foreground-file-operation-service-closeout.md`

---

### Task 1: Add the pure threshold and warning-content policy

**Files**

- Create: `app/src/main/java/com/example/watchfiles/fileops/LargeOperationWarning.kt`
- Create: `app/src/test/java/com/example/watchfiles/fileops/LargeOperationWarningTest.kt`

**Implementation contract**

- `LARGE_OPERATION_ITEM_THRESHOLD = 100`.
- `LARGE_OPERATION_SIZE_THRESHOLD_BYTES = 50L * 1024L * 1024L`.
- `isLargeOperation(itemCount: Int, totalBytes: Long?): Boolean`.
- `formatLargeOperationScale(itemCount: Int, totalBytes: Long?, formatBytes: (Long) -> String): String`.
- Stable title `文件较多` and risk message `建议尽量保持手表亮屏。熄屏或系统调度可能导致操作中断或失败。`.

- [ ] **Step 1: Write failing policy tests**

Cover 99 items below the threshold, 100 items at the inclusive boundary, 50 MiB at the inclusive boundary, unknown size, and exact warning text. Assert that unknown size renders `共 N 项 · 大小未知` and does not become zero.

- [ ] **Step 2: Run the focused test and verify RED**

    .\gradlew.bat :app:testDebugUnitTest --tests "com.example.watchfiles.fileops.LargeOperationWarningTest" --no-daemon --console=plain

Expected: compilation fails because the policy file and symbols do not exist.

- [ ] **Step 3: Implement the minimal pure policy**

Keep the file Android- and Compose-free. Implement the inclusive OR rule and size formatting only; do not add a second scanner or UI behavior.

- [ ] **Step 4: Run the policy test and make the local task commit**

    .\gradlew.bat :app:testDebugUnitTest --tests "com.example.watchfiles.fileops.LargeOperationWarningTest" --no-daemon --console=plain
    git add app/src/main/java/com/example/watchfiles/fileops/LargeOperationWarning.kt app/src/test/java/com/example/watchfiles/fileops/LargeOperationWarningTest.kt
    git commit -m "test: define M3 large operation thresholds"

Expected: all policy tests pass.

---

### Task 2: Gate Runner execution after scanning

**Files**

- Modify: `app/src/main/java/com/example/watchfiles/fileops/FileOperationModels.kt`
- Modify: `app/src/main/java/com/example/watchfiles/fileops/FileOperationRunner.kt`
- Modify: `app/src/test/java/com/example/watchfiles/fileops/FileOperationRunnerTest.kt`

**Implementation contract**

- Add `FileOperationState.WaitingForLargeOperationConfirmation(type, itemCount, totalBytes)`.
- Add `FileOperationRunnerPort.confirmLargeOperation(): Boolean`.
- Preserve all existing command signatures and behavior.
- After every successful `ScanOutcome.Ready`, call `isLargeOperation(scan.itemCount, scan.totalBytes)`.
- For large COPY/MOVE, publish the warning and await confirmation before calling Engine.
- For large DELETE, await the warning confirmation before publishing existing `WaitingForDeleteConfirmation`.
- Small tasks bypass the deferred confirmation.
- Confirmation is accepted only in the warning state and only completes the current task’s deferred.
- Warning cancel or Back requests the active token, completes the deferred with `false`, cleans up to `Idle`, and never publishes `Cancelled`, a terminal result, or a directory refresh.

- [ ] **Step 1: Add failing Runner tests**

Add tests proving that large COPY/MOVE calls Engine zero times before confirmation and exactly once after confirmation; large DELETE reaches existing delete confirmation without calling Engine; warning cancellation returns `Idle` without a terminal result or Engine call; and a small task keeps the direct execution path.

- [ ] **Step 2: Run the focused Runner test and verify RED**

    .\gradlew.bat :app:testDebugUnitTest --tests "com.example.watchfiles.fileops.FileOperationRunnerTest" --no-daemon --console=plain

Expected: compilation fails because the new state, port method and Runner gate do not exist.

- [ ] **Step 3: Implement the smallest Runner gate**

Use one task-scoped `CompletableDeferred<Boolean>` and the existing cancellation token/job ownership. Ensure `finally` clears the deferred and publishes `Idle` for pre-execution warning cancellation. Do not alter Scanner or Engine.

- [ ] **Step 4: Run focused and file-operation regression tests**

    .\gradlew.bat :app:testDebugUnitTest --tests "com.example.watchfiles.fileops.FileOperationRunnerTest" --no-daemon --console=plain
    .\gradlew.bat :app:testDebugUnitTest --tests "com.example.watchfiles.fileops.*" --no-daemon --console=plain

Expected: new Runner tests and existing Scanner, Engine, conflict, replacement, DELETE, and cancellation tests pass.

- [ ] **Step 5: Make the local Runner commit**

    git add app/src/main/java/com/example/watchfiles/fileops/FileOperationModels.kt app/src/main/java/com/example/watchfiles/fileops/FileOperationRunner.kt app/src/test/java/com/example/watchfiles/fileops/FileOperationRunnerTest.kt
    git commit -m "feat: gate large file operations in runner"

---

### Task 3: Forward confirmation through Service, Client, and Coordinator

**Files**

- Modify: `app/src/main/java/com/example/watchfiles/fileops/FileOperationService.kt`
- Modify: `app/src/main/java/com/example/watchfiles/fileops/FileOperationServiceClient.kt`
- Modify: `app/src/main/java/com/example/watchfiles/fileops/FileOperationCoordinator.kt`
- Modify: `app/src/test/java/com/example/watchfiles/fileops/FileOperationServiceTest.kt`
- Modify: `app/src/test/java/com/example/watchfiles/fileops/FileOperationServiceClientTest.kt`
- Modify: `app/src/test/java/com/example/watchfiles/fileops/FileOperationCoordinatorTest.kt`

**Implementation contract**

- Add `confirmLargeOperation(): Boolean` to `FileOperationServicePort`, `FileOperationServiceGateway`, `FileOperationServiceClient`, and `FileOperationCoordinator`.
- Keep the existing foreground-only launch Intent contract; add no new action or persistence.
- Keep notification low-importance, silent, non-vibrating and ongoing while scanning or waiting.
- Add waiting-state notification content that says the task is waiting for confirmation.
- When Runner returns to `Idle`, cancel the notification and stop the started foreground instance, including the warning-cancel path.

- [ ] **Step 1: Add failing forwarding and notification tests**

Extend existing fakes with confirmation-call counters. Assert Coordinator, Client and Service Adapter each perform exactly one hop. Assert the warning-state notification is non-null and low-noise.

- [ ] **Step 2: Run focused tests and verify RED**

    .\gradlew.bat :app:testDebugUnitTest --tests "com.example.watchfiles.fileops.FileOperationServiceTest" --tests "com.example.watchfiles.fileops.FileOperationServiceClientTest" --tests "com.example.watchfiles.fileops.FileOperationCoordinatorTest" --no-daemon --console=plain

Expected: compilation fails because the new forwarding method is absent.

- [ ] **Step 3: Implement one-hop forwarding**

Use `currentPort()?.confirmLargeOperation() ?: false` in Client, `gateway.confirmLargeOperation()` in Coordinator, and synchronized `runner.confirmLargeOperation()` in the Service Port Adapter. Do not duplicate Runner state or scanning.

- [ ] **Step 4: Run focused and full file-operation tests**

    .\gradlew.bat :app:testDebugUnitTest --tests "com.example.watchfiles.fileops.FileOperationServiceTest" --tests "com.example.watchfiles.fileops.FileOperationServiceClientTest" --tests "com.example.watchfiles.fileops.FileOperationCoordinatorTest" --no-daemon --console=plain
    .\gradlew.bat :app:testDebugUnitTest --tests "com.example.watchfiles.fileops.*" --no-daemon --console=plain

- [ ] **Step 5: Make the local Service wiring commit**

    git add app/src/main/java/com/example/watchfiles/fileops/FileOperationService.kt app/src/main/java/com/example/watchfiles/fileops/FileOperationServiceClient.kt app/src/main/java/com/example/watchfiles/fileops/FileOperationCoordinator.kt app/src/test/java/com/example/watchfiles/fileops/FileOperationServiceTest.kt app/src/test/java/com/example/watchfiles/fileops/FileOperationCoordinatorTest.kt app/src/test/java/com/example/watchfiles/fileops/FileOperationServiceClientTest.kt
    git commit -m "feat: forward large operation confirmation"

---

### Task 4: Add the round-screen warning route and UI

**Files**

- Modify: `app/src/main/java/com/example/watchfiles/MainActivity.kt`
- Modify: `app/src/main/java/com/example/watchfiles/FileOperationScreens.kt`
- Modify: `app/src/test/java/com/example/watchfiles/MainActivityOperationRoutingTest.kt`

**Implementation contract**

- Add `AppScreen.LARGE_OPERATION_CONFIRMATION`.
- Route `WaitingForLargeOperationConfirmation` to that screen.
- Add an internal `LargeOperationConfirmationScreen` receiving state, `onContinue`, and `onCancel`.
- Render exact title `文件较多`, scale text from `formatLargeOperationScale`, the approved risk message, `继续操作`, and `取消`.
- Use existing `RoundList`, `ListHeader`, `Text`, `AppChip`, and `formatBytes` helpers.
- Do not change existing cards, dimensions, or layouts.
- Continue calls only `fileOperationCoordinator.confirmLargeOperation()`.
- Cancel and system Back call `cancel()` and navigate to `BROWSER` without `finishPendingOperation()`.
- Keep `LaunchedEffect(operationState)` as the state-driven route; confirmation then leads to existing operation or DELETE-confirmation pages.

- [ ] **Step 1: Add the failing routing test**

Route a `WaitingForLargeOperationConfirmation(FileOperationType.COPY, 100, null)` state and assert `LARGE_OPERATION_CONFIRMATION`.

- [ ] **Step 2: Run the routing test and verify RED**

    .\gradlew.bat :app:testDebugUnitTest --tests "com.example.watchfiles.MainActivityOperationRoutingTest" --no-daemon --console=plain

Expected: compilation fails because the enum value and route do not exist.

- [ ] **Step 3: Implement the route and dedicated page**

Reuse existing screen primitives and callbacks. Do not add progress, conflict, permanent-delete or terminal-result controls to this page.

- [ ] **Step 4: Run routing, policy, Runner and build regressions**

    .\gradlew.bat :app:testDebugUnitTest --tests "com.example.watchfiles.MainActivityOperationRoutingTest" --tests "com.example.watchfiles.fileops.LargeOperationWarningTest" --tests "com.example.watchfiles.fileops.FileOperationRunnerTest" --no-daemon --console=plain
    .\gradlew.bat :app:assembleDebug --no-daemon --console=plain

Expected: tests and Debug compilation pass without UI-size or compatibility changes.

- [ ] **Step 5: Make the local UI commit**

    git add app/src/main/java/com/example/watchfiles/MainActivity.kt app/src/main/java/com/example/watchfiles/FileOperationScreens.kt app/src/test/java/com/example/watchfiles/MainActivityOperationRoutingTest.kt
    git commit -m "feat: add large operation warning screen"

---

### Task 5: Run the complete gate, constrained device regression, and evidence-based documentation

**Files**

- Verify: `app/src/main/java/com/example/watchfiles/fileops/*` and related tests.
- Modify after verification: `docs/TESTING.md`, `docs/roadmap.md`, `docs/superpowers/roadmap/PROJECT_PLAN.md`.
- Modify after verification: `docs/context/current-development-context.md`, `docs/context/m3-foreground-file-operation-service-closeout.md`.

- [ ] **Step 1: Run the complete local Debug gate**

    $env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
    $env:ANDROID_HOME='C:\Users\13073\Documents\AndroidSDK'
    .\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain
    .\gradlew.bat :app:assembleDebug --no-daemon --console=plain
    .\gradlew.bat :app:lintDebug --no-daemon --console=plain
    git diff --check

Expected: zero unit-test failures/errors; Debug build succeeds; Lint has zero errors and only documented pre-existing `TextTransactionJournal.kt` warnings; diff check is clean.

- [ ] **Step 2: Parse and record local evidence**

Parse `app/build/test-results/testDebugUnitTest/TEST-*.xml` for total tests, failures, errors and skipped tests; parse `app/build/reports/lint-results-debug.xml`; record the Debug APK path and SHA-256 with `Get-FileHash`. Do not stage generated APKs or reports.

- [ ] **Step 3: Rediscover the current Watch 5 serial**

    adb devices -l

Select only the current online Xiaomi Watch 5 transport. Do not reuse a historical wireless address. If discovery is unavailable, stop device work and record `PENDING_DEVICE_UI` rather than targeting another device.

- [ ] **Step 4: Perform only constrained device regression**

Install the current Debug APK and use only `/storage/emulated/0/Download/WatchFilesTest/M1Sandbox`. Verify ordinary small COPY, MOVE and DELETE paths do not show the new warning; existing conflict cancellation, replacement-all, running cancellation, directory refresh and terminal notification cleanup remain intact. Record fixture file lists and SHA-256 values before and after. Do not create a 100-item or 5000-item stress fixture.

- [ ] **Step 5: Audit device safety and crash output**

Check the allowed root for writes and temporary residue, ensure user-owned `.part` files are unchanged, and inspect `adb logcat -d AndroidRuntime:E '*:S'`. Mark unexecuted scenarios explicitly instead of calling them verified.

- [ ] **Step 6: Update canonical M3 documents from actual evidence**

Mark the warning complete in roadmap documents only if local tests, build/Lint and constrained device regression pass. Document exact thresholds, unknown-size behavior, no-stress boundary and ordinary-task regression in `docs/TESTING.md`. Record the new state/Runner gate and evidence in context/closeout docs, reconciling older pending entries with the latest user-confirmed M3 device results. Do not mark process recovery, 5000-item pressure testing or guaranteed screen-off continuation complete.

- [ ] **Step 7: Run final status checks and make one local feature commit**

    git diff --check
    git status --short --branch
    git add app/src/main/java app/src/test/java docs/TESTING.md docs/roadmap.md docs/superpowers/roadmap/PROJECT_PLAN.md docs/context/current-development-context.md docs/context/m3-foreground-file-operation-service-closeout.md
    git diff --cached --check
    git commit -m "feat: add M3 large operation warning"

Expected: only intended implementation and evidence documents are changed; no generated APK or device file is staged; nothing is pushed.

---

## Plan Self-Review

- Threshold boundaries, unknown sizes, recursive item counting, and no-stress device scope map to Tasks 1 and 5.
- COPY/MOVE gating, DELETE two-step confirmation, pre-execution cancellation, and single-task ownership map to Task 2.
- Service notification behavior and all Local Binder forwarding map to Task 3.
- Dedicated round-screen route, exact Chinese copy, Back behavior, and unchanged card layout map to Task 4.
- Existing M3 COPY/MOVE/DELETE, conflict, running-cancel, notification and text/browser tests are rerun in Tasks 2–5.
- No task introduces process recovery, WorkManager, persistent task state, Release packaging or remote push.
- No placeholder markers or unspecified thresholds remain in this plan.
