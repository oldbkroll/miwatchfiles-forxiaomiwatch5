# Task 6 report: M3 device acceptance

Date: 2026-07-21
Package: `com.example.watchfiles.debug`
APK: `app/build/outputs/apk/debug/app-debug.apk`

## Device and installation

- `adb devices -l` found two authorized transports for the same device. The
  serial was selected dynamically from the first `device` row:
  `192.168.31.60:41719`.
- Model: `M2505W1`
- Device: `grasslte`
- API: `34`
- Installed package metadata: `versionCode=6`, `versionName=0.3.1-dev-debug`,
  `minSdk=29`, `targetSdk=29`
- APK SHA-256:
  `379DB662A806FABC190DD45A0C933863F84A09F98BA21BF9B93AABB946F3A22E`
- `adb install -r` completed with `Success`.
- The actual launch component is
  `com.example.watchfiles.debug/com.example.watchfiles.MainActivity`.
  An initial attempt using the example `.MainActivity` suffix failed because
  the Activity class is in the non-debug package; the corrected component
  launched normally.

## Controlled fixture

All device file operations were confined to:

`/storage/emulated/0/Download/WatchFilesTest/M1Sandbox/M3ServiceAcceptance`

Initial fixture:

- `src/alpha.txt`
- `src/beta.txt`
- empty `dst/`

Both source files were 1 byte and had SHA-256
`01ba4719c80b6fe911b091a7c05124b64eeece964e09c058ef8f9805daca546b`.

Final fixture after the accepted operations:

- `src/alpha.txt`
- `dst/src/alpha.txt`
- `dst/src/beta.txt`
- `dst/beta.txt` was removed by the DELETE scenario

The copied `alpha.txt` hash matched its source hash. Within the final
inspection scope, no writes outside the fixture were found.

## Executed scenarios

| Scenario | UI/result evidence | Filesystem result | Status |
| --- | --- | --- | --- |
| COPY `src/` to `dst/` | `复制完成`; `完成 1 项 · 失败 0 项` | `dst/src/alpha.txt` and `dst/src/beta.txt` present | PASS |
| MOVE `src/beta.txt` to `dst/` | `移动完成`; `完成 1 项 · 失败 0 项` | `dst/beta.txt` present and source `src/beta.txt` absent | PASS |
| DELETE `dst/beta.txt` | Confirmation page showed `确认永久删除`, `1 项 · 共 1 项`, and `永久删除`; final page showed `删除完成` and `完成 1 项 · 失败 0 项` | `dst/beta.txt` absent | PASS |
| App launch and return to Browser | Activity became focused and returned to the fixture directory after each terminal result | Directory listing refreshed for the next scenario | PASS |

The UIAutomator captures were taken immediately after each action. The
observed terminal strings were:

- COPY at approximately 19:13: `复制完成` and `完成 1 项 · 失败 0 项`.
- MOVE at approximately 19:16: `移动完成` and `完成 1 项 · 失败 0 项`.
- DELETE at approximately 19:17: confirmation page `确认永久删除`, then
  `删除完成` and `完成 1 项 · 失败 0 项`.

The final ADB verification command was equivalent to:

```powershell
adb -s $serial shell find /storage/emulated/0/Download/WatchFilesTest/M1Sandbox/M3ServiceAcceptance -maxdepth 3 -type f -print
adb -s $serial shell sha256sum `
  /storage/emulated/0/Download/WatchFilesTest/M1Sandbox/M3ServiceAcceptance/src/alpha.txt `
  /storage/emulated/0/Download/WatchFilesTest/M1Sandbox/M3ServiceAcceptance/dst/src/alpha.txt
```

It returned exactly the three final files listed above and matching hashes for
the two `alpha.txt` paths. Explicit existence checks returned `ABSENT` for
`src/beta.txt` and `dst/beta.txt`, and `PRESENT` for the two copied paths.

## Foreground-service evidence

The device log contained the following evidence during the operations:

- `ActivityManager: Background started FGS: Allowed` for
  `com.example.watchfiles.fileops.action.FOREGROUND_ONLY` and
  `com.example.watchfiles.fileops.FileOperationService`.
- A notification record for channel `file_operations` with silent defaults
  (`vibrate=null`, `sound=null`, `defaults=0x0`, `groupKey=silent`).
- `ForegroundServiceTypeLoggerModule: FGS stop call` after terminal completion.
- Post-terminal notification inspection showed the channel definition but no
  active file-operation notification. The service record remained bound to the
  Activity, which is expected for the Local Binder connection after the task
  finished.
- A focused-app check showed
  `com.example.watchfiles.debug/com.example.watchfiles.MainActivity`.
- Logcat inspection found no `FATAL EXCEPTION` or matching unhandled exception.

## Not executed in this run

The following remain `PENDING_DEVICE_UI` rather than being claimed as passed:

- Screen-off and Activity re-entry while a sufficiently long operation is in
  progress.
- Same-name conflict waiting page, Replace All, and conflict cancellation.
- Cancellation during a running COPY/MOVE/DELETE task.
- Controlled process termination/recovery. This version intentionally uses
  `START_NOT_STICKY`; recovery is not implemented and must not be represented
  as a pass.

These gaps do not invalidate the completed COPY/MOVE/DELETE foreground-service
smoke acceptance, but they must remain visible in the M3 closeout and roadmap.
