# WatchFiles M1A Selection, Create, and Rename Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add safe long-press multi-selection, folder creation, and single-item rename to the existing round-screen file browser.

**Architecture:** Keep directory reads in `DirectPathRepository`, add a focused `FileMutationRepository` for create/rename, and keep pure selection state separate from Compose. `FileBrowserViewModel` coordinates selection and short mutations; `MainActivity` only owns screen navigation and name-editor presentation.

**Tech Stack:** Kotlin 2.0.21, Android API 29–34, Java NIO `Path`/`Files`, coroutines 1.9.0, StateFlow, Jetpack Compose BOM 2024.09.03, Wear Compose 1.4.1, JUnit 4.13.2.

## Global Constraints

- Preserve `targetSdk = 29`, `requestLegacyExternalStorage="true"`, legacy Files & Media permission flow, and `armeabi-v7a`-only packaging.
- Preserve the custom crown handler with `rotaryScrollableBehavior = null`; do not restore Wear Compose's Google-dependent haptic path.
- Do not initialize a new project or add WorkManager, a foreground service, Media3, or a new storage backend.
- All file-system mutations run on `Dispatchers.IO` and must never silently replace an existing path.
- Reject empty names, `.`, `..`, `/`, `\`, NUL, and names with leading or trailing whitespace; never silently trim a submitted name.
- M1A only exposes implemented actions: selection cancel, select all, and single-item rename. Copy, move, and delete controls arrive in M1B/M1C.
- Build and install Debug only. Do not update `releases` or create a Release APK until the complete M1 stage is accepted.
- Destructive testing is limited to `/storage/emulated/0/Download/WatchFilesTest/M1Sandbox`.
- This workspace is not a Git repository. Replace commit steps with explicit verification checkpoints and changed-file records.

---

## File Map

**Create**

- `app/src/main/java/com/example/watchfiles/fileops/FileNameRules.kt` — pure file-name validation.
- `app/src/main/java/com/example/watchfiles/fileops/FileMutationRepository.kt` — create/rename interface, result model, and direct-path implementation.
- `app/src/main/java/com/example/watchfiles/browser/BrowserSelection.kt` — pure multi-selection state transitions.
- `app/src/test/java/com/example/watchfiles/fileops/FileNameRulesTest.kt` — name-rule unit tests.
- `app/src/test/java/com/example/watchfiles/fileops/FileMutationRepositoryTest.kt` — temporary-directory create/rename tests.
- `app/src/test/java/com/example/watchfiles/browser/BrowserSelectionTest.kt` — selection transition tests.
- `app/src/test/java/com/example/watchfiles/browser/MainDispatcherRule.kt` — coroutine Main dispatcher rule.
- `app/src/test/java/com/example/watchfiles/browser/FileBrowserViewModelTest.kt` — mutation state and refresh tests with fakes.

**Modify**

- `app/build.gradle.kts` — add JVM test dependencies only.
- `app/src/main/java/com/example/watchfiles/data/DirectPathRepository.kt` — implement a small `DirectoryReader` interface for testable ViewModel injection.
- `app/src/main/java/com/example/watchfiles/browser/FileBrowserViewModel.kt` — selection state, mutation state, create/rename orchestration.
- `app/src/main/java/com/example/watchfiles/MainActivity.kt` — selection-mode UI, long-press item surface, name editor, and navigation.
- `docs/superpowers/roadmap/PROJECT_PLAN.md`, `docs/superpowers/context/PROJECT_CONTEXT.md`, `README.md`, `docs/superpowers/checkpoints/TESTING.md` — update only after Debug and real-device acceptance.

---

### Task 1: Establish JVM Tests and File-Name Rules

**Files:**

- Modify: `app/build.gradle.kts`
- Create: `app/src/test/java/com/example/watchfiles/fileops/FileNameRulesTest.kt`
- Create: `app/src/main/java/com/example/watchfiles/fileops/FileNameRules.kt`

**Interfaces:**

- Produces: `FileNameRules.validate(name: String): FileNameValidation`
- Produces: `FileNameValidation.Valid` and `FileNameValidation.Invalid(message: String)`

- [ ] **Step 1: Add the local test dependencies**

Append inside `dependencies` in `app/build.gradle.kts`:

```kotlin
testImplementation("junit:junit:4.13.2")
testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
```

- [ ] **Step 2: Write the failing name-validation tests**

Create `FileNameRulesTest.kt`:

```kotlin
package com.example.watchfiles.fileops

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileNameRulesTest {
    @Test
    fun acceptsOrdinaryChineseAndExtensionNames() {
        assertEquals(FileNameValidation.Valid, FileNameRules.validate("新文件夹"))
        assertEquals(FileNameValidation.Valid, FileNameRules.validate("photo 01.jpg"))
    }

    @Test
    fun rejectsEmptyReservedAndSeparatorNames() {
        val invalid = listOf("", ".", "..", "a/b", "a\\b", "a\u0000b")
        invalid.forEach { name ->
            assertTrue("Expected invalid: $name", FileNameRules.validate(name) is FileNameValidation.Invalid)
        }
    }

    @Test
    fun rejectsLeadingOrTrailingWhitespaceWithoutTrimming() {
        assertTrue(FileNameRules.validate(" folder") is FileNameValidation.Invalid)
        assertTrue(FileNameRules.validate("folder ") is FileNameValidation.Invalid)
        assertTrue(FileNameRules.validate("\tfolder") is FileNameValidation.Invalid)
    }
}
```

- [ ] **Step 3: Run the focused test and confirm the expected failure**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME='C:\Users\13073\Documents\AndroidSDK'
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.watchfiles.fileops.FileNameRulesTest" --no-daemon --console=plain
```

Expected: compilation fails because `FileNameRules` and `FileNameValidation` do not exist.

- [ ] **Step 4: Implement the minimal pure validator**

Create `FileNameRules.kt`:

```kotlin
package com.example.watchfiles.fileops

sealed interface FileNameValidation {
    data object Valid : FileNameValidation
    data class Invalid(val message: String) : FileNameValidation
}

object FileNameRules {
    fun validate(name: String): FileNameValidation = when {
        name.isEmpty() -> FileNameValidation.Invalid("名称不能为空")
        name == "." || name == ".." -> FileNameValidation.Invalid("不能使用 . 或 ..")
        name.any { it == '/' || it == '\\' || it == '\u0000' } ->
            FileNameValidation.Invalid("名称不能包含路径分隔符")
        name.first().isWhitespace() || name.last().isWhitespace() ->
            FileNameValidation.Invalid("名称首尾不能是空格")
        else -> FileNameValidation.Valid
    }
}
```

- [ ] **Step 5: Re-run the test and record the checkpoint**

Run the command from Step 3.

Expected: `3 tests completed, 0 failed` and `BUILD SUCCESSFUL`.

Checkpoint files: `app/build.gradle.kts`, `FileNameRules.kt`, `FileNameRulesTest.kt`.

---

### Task 2: Add Safe Create and Rename Repository Operations

**Files:**

- Create: `app/src/test/java/com/example/watchfiles/fileops/FileMutationRepositoryTest.kt`
- Create: `app/src/main/java/com/example/watchfiles/fileops/FileMutationRepository.kt`

**Interfaces:**

- Consumes: `FileNameRules.validate(String)` from Task 1.
- Produces: `FileMutationGateway.createDirectory(parent: Path, name: String): FileMutationResult`
- Produces: `FileMutationGateway.rename(source: Path, newName: String): FileMutationResult`
- Produces: `FileMutationResult.Success(path: Path)` and `Failure(userMessage: String, technicalMessage: String?)`

- [ ] **Step 1: Write repository tests against temporary directories**

Create `FileMutationRepositoryTest.kt`:

```kotlin
package com.example.watchfiles.fileops

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class FileMutationRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val repository = FileMutationRepository()

    @Test
    fun createDirectoryCreatesExactlyOneRequestedDirectory() = runTest {
        val parent = temporaryFolder.newFolder("parent").toPath()

        val result = repository.createDirectory(parent, "新文件夹")

        val success = result as FileMutationResult.Success
        assertEquals(parent.resolve("新文件夹"), success.path)
        assertTrue(Files.isDirectory(success.path))
    }

    @Test
    fun createDirectoryNeverReusesExistingName() = runTest {
        val parent = temporaryFolder.newFolder("parent-existing").toPath()
        Files.createDirectory(parent.resolve("same"))

        val result = repository.createDirectory(parent, "same")

        assertTrue(result is FileMutationResult.Failure)
        assertTrue(Files.isDirectory(parent.resolve("same")))
    }

    @Test
    fun renameChangesNameWithoutChangingContents() = runTest {
        val parent = temporaryFolder.newFolder("rename").toPath()
        val source = Files.writeString(parent.resolve("before.txt"), "watchfiles")

        val result = repository.rename(source, "after.txt")

        val success = result as FileMutationResult.Success
        assertEquals("watchfiles", Files.readString(success.path))
        assertFalse(Files.exists(source))
    }

    @Test
    fun renameNeverOverwritesExistingTarget() = runTest {
        val parent = temporaryFolder.newFolder("rename-conflict").toPath()
        val source = Files.writeString(parent.resolve("source.txt"), "source")
        val target = Files.writeString(parent.resolve("target.txt"), "target")

        val result = repository.rename(source, "target.txt")

        assertTrue(result is FileMutationResult.Failure)
        assertEquals("source", Files.readString(source))
        assertEquals("target", Files.readString(target))
    }
}
```

- [ ] **Step 2: Run the focused test and verify it fails**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.watchfiles.fileops.FileMutationRepositoryTest" --no-daemon --console=plain
```

Expected: compilation fails because the mutation repository types do not exist.

- [ ] **Step 3: Implement the direct-path mutation gateway**

Create `FileMutationRepository.kt`:

```kotlin
package com.example.watchfiles.fileops

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path

sealed interface FileMutationResult {
    data class Success(val path: Path) : FileMutationResult
    data class Failure(
        val userMessage: String,
        val technicalMessage: String? = null,
    ) : FileMutationResult
}

interface FileMutationGateway {
    suspend fun createDirectory(parent: Path, name: String): FileMutationResult
    suspend fun rename(source: Path, newName: String): FileMutationResult
}

class FileMutationRepository : FileMutationGateway {
    override suspend fun createDirectory(parent: Path, name: String): FileMutationResult =
        mutate(name) { Files.createDirectory(parent.resolve(name)) }

    override suspend fun rename(source: Path, newName: String): FileMutationResult =
        mutate(newName) {
            val parent = source.parent ?: throw IllegalArgumentException("源项目没有父目录")
            val target = parent.resolve(newName)
            if (source == target) return@mutate source
            if (Files.exists(target, NOFOLLOW_LINKS)) {
                throw FileAlreadyExistsException(target.toString())
            }
            Files.move(source, target)
        }

    private suspend fun mutate(name: String, block: () -> Path): FileMutationResult {
        val validation = FileNameRules.validate(name)
        if (validation is FileNameValidation.Invalid) {
            return FileMutationResult.Failure(validation.message)
        }
        return withContext(Dispatchers.IO) {
            try {
                FileMutationResult.Success(block())
            } catch (error: FileAlreadyExistsException) {
                FileMutationResult.Failure("已存在同名项目", error.message)
            } catch (error: NoSuchFileException) {
                FileMutationResult.Failure("文件或文件夹已不存在", error.message)
            } catch (error: SecurityException) {
                FileMutationResult.Failure("没有权限执行此操作", error.message)
            } catch (error: Exception) {
                FileMutationResult.Failure("操作失败", error.message ?: error.javaClass.simpleName)
            }
        }
    }
}
```

- [ ] **Step 4: Run both file-operation test classes**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.watchfiles.fileops.*" --no-daemon --console=plain
```

Expected: `7 tests completed, 0 failed` and `BUILD SUCCESSFUL`.

Checkpoint files: `FileMutationRepository.kt`, `FileMutationRepositoryTest.kt`.

---

### Task 3: Add Pure Multi-Selection State and ViewModel Commands

**Files:**

- Create: `app/src/test/java/com/example/watchfiles/browser/BrowserSelectionTest.kt`
- Create: `app/src/main/java/com/example/watchfiles/browser/BrowserSelection.kt`
- Modify: `app/src/main/java/com/example/watchfiles/data/DirectPathRepository.kt`
- Modify: `app/src/main/java/com/example/watchfiles/browser/FileBrowserViewModel.kt`

**Interfaces:**

- Produces: `BrowserSelection(selectedPaths: Set<Path>)`, `isActive`, `begin`, `toggle`, `selectAll`, and `clear`.
- Produces: `DirectoryReader.list(path: Path): List<FileEntry>`.
- Produces ViewModel commands: `beginSelection`, `toggleSelection`, `selectAll`, `clearSelection`.

- [ ] **Step 1: Write selection transition tests**

Create `BrowserSelectionTest.kt`:

```kotlin
package com.example.watchfiles.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

class BrowserSelectionTest {
    private val a = Path.of("/storage/emulated/0/a")
    private val b = Path.of("/storage/emulated/0/b")

    @Test
    fun longPressBeginsSelectionWithOnePath() {
        val state = BrowserSelection().begin(a)
        assertTrue(state.isActive)
        assertEquals(setOf(a), state.selectedPaths)
    }

    @Test
    fun toggleAddsAndRemovesPathsAndLeavesModeWhenEmpty() {
        val selected = BrowserSelection().begin(a).toggle(b)
        assertEquals(setOf(a, b), selected.selectedPaths)

        val empty = selected.toggle(a).toggle(b)
        assertFalse(empty.isActive)
    }

    @Test
    fun selectAllUsesOnlyPathsPassedByVisibleList() {
        val state = BrowserSelection().selectAll(listOf(a, b))
        assertEquals(setOf(a, b), state.selectedPaths)
        assertTrue(state.clear().selectedPaths.isEmpty())
    }
}
```

- [ ] **Step 2: Run the selection test and verify it fails**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.watchfiles.browser.BrowserSelectionTest" --no-daemon --console=plain
```

Expected: compilation fails because `BrowserSelection` does not exist.

- [ ] **Step 3: Implement immutable selection transitions**

Create `BrowserSelection.kt`:

```kotlin
package com.example.watchfiles.browser

import java.nio.file.Path

data class BrowserSelection(
    val selectedPaths: Set<Path> = emptySet(),
) {
    val isActive: Boolean get() = selectedPaths.isNotEmpty()

    fun begin(path: Path): BrowserSelection = copy(selectedPaths = linkedSetOf(path))

    fun toggle(path: Path): BrowserSelection {
        val updated = LinkedHashSet(selectedPaths)
        if (!updated.add(path)) updated.remove(path)
        return copy(selectedPaths = updated)
    }

    fun selectAll(paths: List<Path>): BrowserSelection =
        copy(selectedPaths = LinkedHashSet(paths))

    fun clear(): BrowserSelection = BrowserSelection()
}
```

- [ ] **Step 4: Introduce a testable directory-reader boundary**

Replace `DirectPathRepository.kt` with:

```kotlin
package com.example.watchfiles.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale

fun interface DirectoryReader {
    suspend fun list(path: Path): List<FileEntry>
}

class DirectPathRepository : DirectoryReader {
    override suspend fun list(path: Path): List<FileEntry> = withContext(Dispatchers.IO) {
        require(Files.isDirectory(path)) { "不是文件夹：$path" }

        Files.newDirectoryStream(path).use { directory ->
            directory.map { child -> child.toEntry() }
        }.sortedWith(
            compareBy<FileEntry>({ !it.isDirectory }, { it.name.lowercase(Locale.ROOT) })
        )
    }

    private fun Path.toEntry(): FileEntry {
        val attributes = runCatching {
            Files.readAttributes(
                this,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
        }.getOrNull()

        val directory = attributes?.isDirectory ?: Files.isDirectory(this)
        val file = toFile()

        return FileEntry(
            path = this,
            name = fileName?.toString().orEmpty().ifBlank { toString() },
            isDirectory = directory,
            sizeBytes = if (directory) null else attributes?.size(),
            modifiedAtMillis = attributes?.lastModifiedTime()?.toMillis(),
            isHidden = fileName?.toString()?.startsWith('.') == true,
            isReadable = file.canRead(),
            isWritable = file.canWrite(),
        )
    }
}
```

- [ ] **Step 5: Add selection state and commands to the ViewModel**

Change the constructor dependency type and add selection to `BrowserUiState`:

```kotlin
data class BrowserUiState(
    val currentPath: Path = Environment.getExternalStorageDirectory().toPath(),
    val entries: List<FileEntry> = emptyList(),
    val isLoading: Boolean = false,
    val showHidden: Boolean = false,
    val errorMessage: String? = null,
    val selection: BrowserSelection = BrowserSelection(),
)

class FileBrowserViewModel(
    private val repository: DirectoryReader = DirectPathRepository(),
    private val mutationRepository: FileMutationGateway = FileMutationRepository(),
) : ViewModel() {
```

Add these commands:

```kotlin
fun beginSelection(path: Path) {
    _state.update { it.copy(selection = it.selection.begin(path)) }
}

fun toggleSelection(path: Path) {
    _state.update { it.copy(selection = it.selection.toggle(path)) }
}

fun selectAll(entries: List<FileEntry>) {
    _state.update { it.copy(selection = it.selection.selectAll(entries.map(FileEntry::path))) }
}

fun clearSelection() {
    _state.update { it.copy(selection = it.selection.clear()) }
}
```

When `open(path)` starts, include `selection = BrowserSelection()` in the copied state. After a successful refresh, intersect selected paths with the returned entry paths so deleted or renamed items cannot remain selected:

```kotlin
val availablePaths = entries.mapTo(HashSet(), FileEntry::path)
current.copy(
    entries = entries,
    isLoading = false,
    selection = current.selection.copy(
        selectedPaths = current.selection.selectedPaths.filterTo(LinkedHashSet()) {
            it in availablePaths
        },
    ),
)
```

- [ ] **Step 6: Run unit tests and compile Debug**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --no-daemon --console=plain
```

Expected: all unit tests pass and `BUILD SUCCESSFUL`.

Checkpoint files: `BrowserSelection.kt`, `BrowserSelectionTest.kt`, `DirectPathRepository.kt`, `FileBrowserViewModel.kt`.

---

### Task 4: Implement Round-Screen Long-Press Selection UI

**Files:**

- Modify: `app/src/main/java/com/example/watchfiles/MainActivity.kt`

**Interfaces:**

- Consumes: `BrowserUiState.selection` and ViewModel selection commands from Task 3.
- Produces callbacks from `BrowserScreen`: `onBeginSelection`, `onToggleSelection`, `onSelectAll`, and `onClearSelection`.

- [ ] **Step 1: Replace file-item touch handling with a combined-click surface**

Add these imports:

```kotlin
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
```

`RoundedCornerShape` is already imported. Replace the current `FileChip` body with a dedicated surface that owns both tap and long press:

```kotlin
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileChip(
    entry: FileEntry,
    selected: Boolean,
    selectionMode: Boolean,
    onOpenDirectory: (Path) -> Unit,
    onOpenFile: (FileEntry) -> Unit,
    onBeginSelection: (Path) -> Unit,
    onToggleSelection: (Path) -> Unit,
) {
    val details = when {
        entry.isDirectory && !entry.isReadable -> "文件夹 · 不可读取"
        entry.isDirectory -> "文件夹"
        entry.sizeBytes != null -> formatBytes(entry.sizeBytes)
        else -> "文件"
    }
    val clickAction = {
        if (selectionMode) {
            onToggleSelection(entry.path)
        } else if (entry.isDirectory && entry.isReadable) {
            onOpenDirectory(entry.path)
        } else if (!entry.isDirectory) {
            onOpenFile(entry)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth(0.9f)
            .clip(RoundedCornerShape(28.dp))
            .background(if (selected) Color(0xFF3F51B5) else Color(0xFF2C2C2E))
            .combinedClickable(
                enabled = true,
                onClick = clickAction,
                onLongClick = { onBeginSelection(entry.path) },
            )
            .padding(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Text(
            text = (if (selected) "✓  " else if (entry.isDirectory) "▰  " else "▱  ") + entry.name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = details,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontSize = 10.sp,
            color = Color.LightGray,
        )
    }
}
```

- [ ] **Step 2: Add selection actions to `BrowserScreen`**

Extend `BrowserScreen` parameters with the four selection callbacks. In its `RoundList`, branch before the normal navigation controls:

```kotlin
if (state.selection.isActive) {
    item { ListHeader { Text("已选 ${state.selection.selectedPaths.size} 项") } }
    item { AppChip("取消选择", "返回普通浏览", onClick = onClearSelection) }
    item { AppChip("全选", "选择当前可见项目", onClick = { onSelectAll(visibleEntries) }) }
} else {
    item {
        ListHeader {
            Text(folderDisplayName(state.currentPath), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    item { AppChip("返回上级", state.currentPath.toString(), onClick = onNavigateUp) }
    item {
        AppChip(
            if (state.showHidden) "隐藏点文件" else "显示点文件",
            "以 . 开头的文件",
            onClick = onToggleHidden,
        )
    }
}
```

Pass `selected`, `selectionMode`, and the selection callbacks to every `FileChip`.

- [ ] **Step 3: Wire callbacks from `WatchFilesApp`**

In the `AppScreen.BROWSER` call:

```kotlin
onBeginSelection = { path ->
    browserViewModel.beginSelection(path)
    scope.launch { browserListState.scrollToItem(0) }
},
onToggleSelection = browserViewModel::toggleSelection,
onSelectAll = browserViewModel::selectAll,
onClearSelection = browserViewModel::clearSelection,
```

Task 5 adds `onCreateDirectory` and `onRenameSelected` together with their working editor destination, so Task 4 ships no dead controls.

Update the existing `BackHandler` branch so Back exits selection mode before navigating out of the folder:

```kotlin
AppScreen.BROWSER -> {
    if (browserState.selection.isActive) {
        browserViewModel.clearSelection()
    } else {
        navigateBrowserUp()
    }
}
```

- [ ] **Step 4: Compile and manually inspect the semantics tree**

Run:

```powershell
.\gradlew.bat :app:assembleDebug --no-daemon --console=plain
```

Expected: `BUILD SUCCESSFUL`.

Install Debug, long-press a test item, then dump UI:

```powershell
adb install --fastdeploy -r "app\build\outputs\apk\debug\app-debug.apk"
adb shell uiautomator dump /sdcard/window.xml
adb shell cat /sdcard/window.xml
```

Expected: selected-count, cancel-selection, and select-all labels appear; selected item begins with `✓`.

Checkpoint file: `MainActivity.kt`.

---

### Task 5: Add ViewModel Mutation State and the Name Editor

**Files:**

- Create: `app/src/test/java/com/example/watchfiles/browser/MainDispatcherRule.kt`
- Create: `app/src/test/java/com/example/watchfiles/browser/FileBrowserViewModelTest.kt`
- Modify: `app/src/main/java/com/example/watchfiles/browser/FileBrowserViewModel.kt`
- Modify: `app/src/main/java/com/example/watchfiles/MainActivity.kt`

**Interfaces:**

- Consumes: `DirectoryReader`, `FileMutationGateway`, and `FileMutationResult`.
- Produces: `BrowserMutationState.Idle`, `Working`, `Succeeded(path)`, and `Failed(userMessage, technicalMessage)`.
- Produces ViewModel commands: `createDirectory(name)`, `rename(source, newName)`, `consumeMutationResult()`.

- [ ] **Step 1: Add a coroutine Main dispatcher rule**

Create `MainDispatcherRule.kt`:

```kotlin
package com.example.watchfiles.browser

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val dispatcher: TestDispatcher = StandardTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)
    override fun finished(description: Description) = Dispatchers.resetMain()
}
```

- [ ] **Step 2: Write failing ViewModel mutation tests with fakes**

Create `FileBrowserViewModelTest.kt`:

```kotlin
package com.example.watchfiles.browser

import com.example.watchfiles.data.DirectoryReader
import com.example.watchfiles.fileops.FileMutationGateway
import com.example.watchfiles.fileops.FileMutationResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.nio.file.Path

@OptIn(ExperimentalCoroutinesApi::class)
class FileBrowserViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val root = Path.of("/storage/emulated/0/Download/Test")
    private val reader = DirectoryReader { emptyList() }

    @Test
    fun createDirectoryPublishesSuccessAndClearsSelection() = runTest {
        val created = root.resolve("New")
        val mutations = FakeMutationGateway(createResult = FileMutationResult.Success(created))
        val viewModel = FileBrowserViewModel(reader, mutations, root)
        viewModel.beginSelection(root.resolve("old.txt"))

        viewModel.createDirectory("New")
        advanceUntilIdle()

        assertEquals(BrowserMutationState.Succeeded(created), viewModel.state.value.mutation)
        assertTrue(viewModel.state.value.selection.selectedPaths.isEmpty())
    }

    @Test
    fun renamePublishesUserFacingFailureWithoutRefreshingAwayTheSource() = runTest {
        val failure = FileMutationResult.Failure("已存在同名项目", "target exists")
        val mutations = FakeMutationGateway(renameResult = failure)
        val viewModel = FileBrowserViewModel(reader, mutations, root)

        viewModel.rename(root.resolve("old.txt"), "same.txt")
        advanceUntilIdle()

        assertEquals(
            BrowserMutationState.Failed("已存在同名项目", "target exists"),
            viewModel.state.value.mutation,
        )
    }

    private class FakeMutationGateway(
        private val createResult: FileMutationResult = FileMutationResult.Failure("unused"),
        private val renameResult: FileMutationResult = FileMutationResult.Failure("unused"),
    ) : FileMutationGateway {
        override suspend fun createDirectory(parent: Path, name: String) = createResult
        override suspend fun rename(source: Path, newName: String) = renameResult
    }
}
```

The test constructor requires adding an injectable initial path to the ViewModel:

```kotlin
class FileBrowserViewModel(
    private val repository: DirectoryReader = DirectPathRepository(),
    private val mutationRepository: FileMutationGateway = FileMutationRepository(),
    initialPath: Path = Environment.getExternalStorageDirectory().toPath(),
) : ViewModel() {
    private val _state = MutableStateFlow(BrowserUiState(currentPath = initialPath))
```

- [ ] **Step 3: Run the ViewModel test and verify it fails**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.watchfiles.browser.FileBrowserViewModelTest" --no-daemon --console=plain
```

Expected: compilation fails because mutation state and commands do not exist.

- [ ] **Step 4: Implement mutation state and commands**

Add to `FileBrowserViewModel.kt`:

```kotlin
sealed interface BrowserMutationState {
    data object Idle : BrowserMutationState
    data object Working : BrowserMutationState
    data class Succeeded(val path: Path) : BrowserMutationState
    data class Failed(val userMessage: String, val technicalMessage: String?) : BrowserMutationState
}
```

Add `val mutation: BrowserMutationState = BrowserMutationState.Idle` to `BrowserUiState`, then add:

```kotlin
fun createDirectory(name: String) = runMutation {
    mutationRepository.createDirectory(_state.value.currentPath, name)
}

fun rename(source: Path, newName: String) = runMutation {
    mutationRepository.rename(source, newName)
}

fun consumeMutationResult() {
    _state.update { it.copy(mutation = BrowserMutationState.Idle) }
}

private fun runMutation(operation: suspend () -> FileMutationResult) {
    if (_state.value.mutation == BrowserMutationState.Working) return
    _state.update { it.copy(mutation = BrowserMutationState.Working) }
    viewModelScope.launch {
        when (val result = operation()) {
            is FileMutationResult.Success -> {
                val currentPath = _state.value.currentPath
                val refreshed = runCatching { repository.list(currentPath) }
                _state.update {
                    it.copy(
                        entries = refreshed.getOrDefault(it.entries),
                        selection = BrowserSelection(),
                        mutation = BrowserMutationState.Succeeded(result.path),
                        errorMessage = refreshed.exceptionOrNull()?.message,
                    )
                }
            }
            is FileMutationResult.Failure -> {
                result.technicalMessage?.let { technical ->
                    android.util.Log.w("WatchFiles", result.userMessage + ": " + technical)
                }
                _state.update {
                    it.copy(
                        mutation = BrowserMutationState.Failed(
                            result.userMessage,
                            result.technicalMessage,
                        ),
                    )
                }
            }
        }
    }
}
```

- [ ] **Step 5: Add name-editor routing and UI**

In `MainActivity.kt`, extend the screen enum:

```kotlin
private enum class AppScreen {
    HOME, BROWSER, FILE_DETAILS, IMAGE_VIEWER, DEVICE_INFO, NAME_EDITOR
}
```

Add the editor request model:

```kotlin
private sealed interface NameEditorRequest {
    data object CreateDirectory : NameEditorRequest
    data class Rename(val entry: FileEntry) : NameEditorRequest
}
```

Inside `WatchFilesApp`, add:

```kotlin
var nameEditorRequest by remember { mutableStateOf<NameEditorRequest?>(null) }
```

Extend `BrowserScreen` with `onCreateDirectory: () -> Unit` and `onRenameSelected: () -> Unit`. In its normal-mode branch, add:

```kotlin
item { AppChip("新建文件夹", "在当前目录创建", onClick = onCreateDirectory) }
```

In its selection-mode branch, add:

```kotlin
if (state.selection.selectedPaths.size == 1) {
    item { AppChip("重命名", "修改所选项目名称", onClick = onRenameSelected) }
}
```

Wire those callbacks in the `AppScreen.BROWSER` call:

```kotlin
onCreateDirectory = {
    browserViewModel.consumeMutationResult()
    nameEditorRequest = NameEditorRequest.CreateDirectory
    screen = AppScreen.NAME_EDITOR
},
onRenameSelected = {
    val selectedPath = browserState.selection.selectedPaths.singleOrNull()
    val selectedEntry = browserState.entries.firstOrNull { it.path == selectedPath }
    if (selectedEntry != null) {
        browserViewModel.consumeMutationResult()
        nameEditorRequest = NameEditorRequest.Rename(selectedEntry)
        screen = AppScreen.NAME_EDITOR
    }
},
```

Observe success without consuming failure prematurely:

```kotlin
LaunchedEffect(browserState.mutation) {
    if (browserState.mutation is BrowserMutationState.Succeeded) {
        nameEditorRequest = null
        screen = AppScreen.BROWSER
        browserViewModel.consumeMutationResult()
    }
}
```

Add a focused editor composable using the existing Foundation dependency:

```kotlin
@Composable
private fun FileNameEditorScreen(
    request: NameEditorRequest,
    mutation: BrowserMutationState,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val initialName = (request as? NameEditorRequest.Rename)?.entry?.name.orEmpty()
    var value by remember(request) { mutableStateOf(initialName) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val validation = remember(value) { FileNameRules.validate(value) }
    val working = mutation == BrowserMutationState.Working

    LaunchedEffect(request) {
        focusRequester.requestFocus()
        keyboard?.show()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 36.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(if (request is NameEditorRequest.CreateDirectory) "新建文件夹" else "重命名")
        BasicTextField(
            value = value,
            onValueChange = { if (!working) value = it.replace("\n", "") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 18.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White)
                .padding(12.dp)
                .focusRequester(focusRequester),
            singleLine = true,
            textStyle = TextStyle(color = Color.Black, fontSize = 16.sp),
            cursorBrush = SolidColor(Color.Blue),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = {
                    if (!working && validation == FileNameValidation.Valid) onSubmit(value)
                },
            ),
        )
        val message = when {
            validation is FileNameValidation.Invalid -> validation.message
            mutation is BrowserMutationState.Failed -> mutation.userMessage
            else -> " "
        }
        Text(message, color = if (message.isBlank()) Color.Transparent else Color(0xFFFF8A80))
        AppChip(
            label = if (working) "正在处理…" else "确认",
            secondary = "不会覆盖同名项目",
            enabled = !working && validation == FileNameValidation.Valid,
            onClick = { onSubmit(value) },
        )
        AppChip("取消", "不做修改", enabled = !working, onClick = onCancel)
    }
}
```

Required imports include `BasicTextField`, `KeyboardActions`, `KeyboardOptions`, `ImeAction`, `LocalSoftwareKeyboardController`, `TextStyle`, and `SolidColor`.

Add this `when (screen)` branch:

```kotlin
AppScreen.NAME_EDITOR -> nameEditorRequest?.let { request ->
    FileNameEditorScreen(
        request = request,
        mutation = browserState.mutation,
        onSubmit = { name ->
            when (request) {
                NameEditorRequest.CreateDirectory -> browserViewModel.createDirectory(name)
                is NameEditorRequest.Rename -> browserViewModel.rename(request.entry.path, name)
            }
        },
        onCancel = {
            browserViewModel.consumeMutationResult()
            nameEditorRequest = null
            screen = AppScreen.BROWSER
        },
    )
} ?: LaunchedEffect(Unit) {
    screen = AppScreen.BROWSER
}
```

In the existing `BackHandler` switch, add:

```kotlin
AppScreen.NAME_EDITOR -> {
    if (browserState.mutation != BrowserMutationState.Working) {
        browserViewModel.consumeMutationResult()
        nameEditorRequest = null
        screen = AppScreen.BROWSER
    }
}
```

- [ ] **Step 6: Re-run the ViewModel tests and full unit suite**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain
```

Expected: all tests pass with `0 failed`.

- [ ] **Step 7: Build and inspect Debug UI on the watch**

Run:

```powershell
.\gradlew.bat :app:assembleDebug --no-daemon --console=plain
adb install --fastdeploy -r "app\build\outputs\apk\debug\app-debug.apk"
```

Expected: Debug installs without changing `targetSdk 29`; system keyboard opens for create/rename; submitting a valid name returns to the same folder.

Checkpoint files: `MainDispatcherRule.kt`, `FileBrowserViewModelTest.kt`, `FileBrowserViewModel.kt`, `MainActivity.kt`.

---

### Task 6: Real-Device M1A Acceptance, Lint, and Documentation

**Files:**

- Modify after acceptance: `docs/superpowers/roadmap/PROJECT_PLAN.md`
- Modify after acceptance: `docs/superpowers/context/PROJECT_CONTEXT.md`
- Modify after acceptance: `README.md`
- Modify after acceptance: `docs/superpowers/checkpoints/TESTING.md`

**Interfaces:**

- Consumes the complete M1A Debug build.
- Produces a verified Debug APK and updated project handoff state.

- [ ] **Step 1: Run the full local verification gate**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME='C:\Users\13073\Documents\AndroidSDK'
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --no-daemon --console=plain
```

Expected: all unit tests pass, `BUILD SUCCESSFUL`, and `app/build/reports/lint-results-debug.xml` contains zero error-severity issues.

- [ ] **Step 2: Verify compatibility metadata before installing**

Run:

```powershell
adb install --fastdeploy -r "app\build\outputs\apk\debug\app-debug.apk"
adb shell dumpsys package com.example.watchfiles.debug | Select-String -Pattern 'versionCode=|versionName=|targetSdk='
```

Expected: `targetSdk=29`, `versionCode=6`, `versionName=0.3.1-dev-debug`.

- [ ] **Step 3: Prepare the exact safe test directory**

Run only against the fixed sandbox path:

```powershell
adb shell mkdir -p /storage/emulated/0/Download/WatchFilesTest/M1Sandbox
adb shell "printf watchfiles > /storage/emulated/0/Download/WatchFilesTest/M1Sandbox/original.txt"
adb shell ls -la /storage/emulated/0/Download/WatchFilesTest/M1Sandbox
```

Expected: `original.txt` exists in `M1Sandbox`; no path outside that directory is modified.

- [ ] **Step 4: Perform M1A watch interactions**

On the watch:

1. Open `Download/WatchFilesTest/M1Sandbox`.
2. Tap “新建文件夹”, enter `新文件夹`, and confirm.
3. Long-press `original.txt`; verify selection mode shows `已选 1 项`.
4. Tap `新文件夹`; verify it becomes `已选 2 项`, then tap it again to return to one selected item.
5. Tap “重命名”, replace the name with `renamed.txt`, and confirm.
6. Try renaming it to `新文件夹`; verify the existing folder is not replaced and a clear conflict error appears.
7. Try empty, leading-space, trailing-space, `.`, `..`, slash, and backslash names; verify confirmation is disabled or an inline validation message appears.
8. Press Back from the editor; verify no file changes.
9. Rotate the crown in normal and selection modes; verify no crash and the list scrolls.

- [ ] **Step 5: Verify file-system results and crash log**

Run:

```powershell
adb shell find /storage/emulated/0/Download/WatchFilesTest/M1Sandbox -maxdepth 2 -print
adb logcat -d -t 500 | Select-String -Pattern 'FATAL EXCEPTION|OutOfMemoryError|NoClassDefFoundError'
```

Expected: `新文件夹` and `renamed.txt` exist, `original.txt` does not, no unrelated paths exist, and no WatchFiles crash is present.

- [ ] **Step 6: Update documentation only after the device checks pass**

Apply these exact status changes:

- `docs/superpowers/roadmap/PROJECT_PLAN.md`: mark “长按多选”, “新建文件夹”, and “重命名” complete; leave later-stage items incomplete until their own checkpoints.
- `docs/superpowers/context/PROJECT_CONTEXT.md`: add an M1A section documenting architecture, test sandbox, keyboard result, and real-device acceptance.
- `README.md`: add the three M1A capabilities and retain the warning that copy/move/delete are not yet available.
- `docs/superpowers/checkpoints/TESTING.md`: add repeatable M1A steps and keep all destructive tests scoped to `M1Sandbox`.

- [ ] **Step 7: Re-run the final verification after documentation edits**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --no-daemon --console=plain
Get-FileHash "app\build\outputs\apk\debug\app-debug.apk" -Algorithm SHA256
```

Expected: tests and lint still pass, Debug APK exists, and a SHA-256 hash is recorded in the handoff. Do not copy the APK into `releases`.

Final checkpoint files: all M1A source/tests plus the canonical roadmap, context, README entry, and testing checklist under `docs/superpowers/`.
