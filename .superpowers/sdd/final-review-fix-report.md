# Final whole-branch review fix report — M3 large-operation warning

Date: 2026-07-24

Starting HEAD: `118d38b`

Scope: fix the final whole-branch review findings for the M3 large-operation warning feature directly on `main`, without branches/worktrees or push.

## Findings addressed

### 1) `FileOperationService.kt` idle cleanup now stops an actually started foreground service instance

Root cause:

- `publishState()` handled `FileOperationState.Idle` by cancelling the notification and calling `stopForegroundIfStarted()`, but it never called `stopSelf()`.
- That was correct for the initial bound/no-task `Idle` emission on service creation, but incorrect after a real started foreground task returned to `Idle` through warning cancellation.

Test-first change:

- Extended `FileOperationServiceTest` with `idleCleanupStopsOnlyStartedForegroundServiceInstance`.
- RED was captured by introducing a narrow lifecycle helper expectation (`shouldStopServiceOnIdleCleanup(...)`) before the helper existed.

Implementation:

- Added `shouldStopServiceOnIdleCleanup(state, foregroundStarted)` as a focused lifecycle decision helper.
- Updated `publishState()` so `Idle` and terminal cleanup compute the stop decision before foreground teardown, then call `stopSelf()` only when appropriate.
- Reused the same idle cleanup path for rejected `start(...)` / `prepareDelete(...)` calls that leave the service idle after a foreground start.

Safety property preserved:

- Initial `Idle` on a merely bound/no-task service does not call `stopSelf()`.
- `Idle` after a started foreground instance does call `stopSelf()`.
- Terminal states still stop the service.

### 2) `LargeOperationWarning.kt` known-size copy now uses the approved design text

Root cause:

- `formatLargeOperationScale(...)` still rendered known sizes as `共 N 项 · 大小 SIZE`.

Test-first change:

- Updated `LargeOperationWarningTest.formatsKnownSizeUsingProvidedFormatter` to expect `共 N 项 · 总计 SIZE`.

Implementation:

- Changed the known-size formatter branch to return `共 $itemCount 项 · 总计 ${formatBytes(totalBytes)}`.
- Unknown-size formatting remains `共 N 项 · 大小未知`.

### 3) `docs/context/current-development-context.md` no longer claims `297672b` is the current HEAD

Root cause:

- The document said `当前 HEAD 297672b`, which becomes stale as soon as later commits exist.

Implementation:

- Reworded the phrase to identify `297672b` as the implementation commit instead of the current HEAD.
- Preserved the existing evidence details and the `PENDING_DEVICE_UI` status.

## Files changed

- `app/src/main/java/com/example/watchfiles/fileops/FileOperationService.kt`
- `app/src/main/java/com/example/watchfiles/fileops/LargeOperationWarning.kt`
- `app/src/test/java/com/example/watchfiles/fileops/FileOperationServiceTest.kt`
- `app/src/test/java/com/example/watchfiles/fileops/LargeOperationWarningTest.kt`
- `docs/context/current-development-context.md`
- `.superpowers/sdd/final-review-fix-report.md`

## Command log and results

1. RED capture for focused tests:

   - Command:
     `.\gradlew.bat :app:testDebugUnitTest --tests "com.example.watchfiles.fileops.LargeOperationWarningTest" --tests "com.example.watchfiles.fileops.FileOperationServiceTest" --no-daemon --console=plain`
   - Result: `FAILED`
   - Expected RED evidence: `compileDebugUnitTestKotlin` failed with unresolved reference `shouldStopServiceOnIdleCleanup`.

2. Focused GREEN after implementation:

   - Command:
     `.\gradlew.bat :app:testDebugUnitTest --tests "com.example.watchfiles.fileops.LargeOperationWarningTest" --tests "com.example.watchfiles.fileops.FileOperationServiceTest" --no-daemon --console=plain`
   - Result: `BUILD SUCCESSFUL`

3. Focused regression set:

   - Command:
     `.\gradlew.bat :app:testDebugUnitTest --tests "com.example.watchfiles.fileops.LargeOperationWarningTest" --tests "com.example.watchfiles.fileops.FileOperationServiceTest" --tests "com.example.watchfiles.fileops.FileOperationRunnerTest" --tests "com.example.watchfiles.fileops.FileOperationCoordinatorTest" --tests "com.example.watchfiles.fileops.FileOperationServiceClientTest" --no-daemon --console=plain`
   - Result: `BUILD SUCCESSFUL`

4. Full debug unit test suite:

   - Command:
     `.\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain`
   - Result: `BUILD SUCCESSFUL`

5. Debug assemble:

   - Command:
     `.\gradlew.bat :app:assembleDebug --no-daemon --console=plain`
   - Result: `BUILD SUCCESSFUL`

6. Debug lint:

   - Command:
     `.\gradlew.bat :app:lintDebug --no-daemon --console=plain`
   - Result: `BUILD SUCCESSFUL`

7. Diff cleanliness:

   - Command:
     `git diff --check`
   - Result: exit code `0`
   - Note: Git emitted CRLF normalization warnings for modified files, but no whitespace or patch-format errors.

## Verification summary

- Parsed `app/build/test-results/testDebugUnitTest/TEST-*.xml`:
  - `173 tests`
  - `0 failures`
  - `0 errors`
  - `4 skipped`

- Parsed `app/build/reports/lint-results-debug.xml`:
  - `0 errors`
  - `2 warnings`
  - Warnings remained the pre-existing `ApplySharedPref` items at lines `22` and `33`

## Out of scope / remaining concern

- No device validation was performed in this fix pass.
- `PENDING_DEVICE_UI` remains the correct status for large-operation warning UI confirmation on a verified Watch 5 device.
