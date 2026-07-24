package com.example.watchfiles.fileops

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.nio.file.Path
import java.nio.file.Paths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

internal object FileOperationServiceIntentContract {
    const val ACTION_START = "com.example.watchfiles.fileops.action.START"
    const val ACTION_PREPARE_DELETE = "com.example.watchfiles.fileops.action.PREPARE_DELETE"
    const val ACTION_FOREGROUND_ONLY = "com.example.watchfiles.fileops.action.FOREGROUND_ONLY"
    const val EXTRA_TYPE = "extra_type"
    const val EXTRA_SOURCES = "extra_sources"
    const val EXTRA_TARGET_DIRECTORY = "extra_target_directory"
}

internal sealed interface FileOperationServiceIntentCommand {
    data class ForegroundOnly(val type: FileOperationType) : FileOperationServiceIntentCommand

    data class Start(
        val type: FileOperationType,
        val sources: List<Path>,
        val targetDirectory: Path,
    ) : FileOperationServiceIntentCommand

    data class PrepareDelete(val sources: List<Path>) : FileOperationServiceIntentCommand
}

internal fun dispatchFileOperationServiceIntentCommand(
    command: FileOperationServiceIntentCommand,
    onForegroundOnly: (FileOperationType) -> Unit,
    onStart: (FileOperationType, List<Path>, Path) -> Unit,
    onPrepareDelete: (List<Path>) -> Unit,
) {
    when (command) {
        is FileOperationServiceIntentCommand.ForegroundOnly -> onForegroundOnly(command.type)
        is FileOperationServiceIntentCommand.Start ->
            onStart(command.type, command.sources, command.targetDirectory)
        is FileOperationServiceIntentCommand.PrepareDelete -> onPrepareDelete(command.sources)
    }
}

internal fun parseFileOperationServiceIntent(
    action: String?,
    type: String?,
    sources: List<String>?,
    targetDirectory: String?,
): FileOperationServiceIntentCommand? {
    return when (action) {
        FileOperationServiceIntentContract.ACTION_FOREGROUND_ONLY -> {
            val operationType = type
                ?.let { value -> runCatching { FileOperationType.valueOf(value) }.getOrNull() }
                ?: return null
            FileOperationServiceIntentCommand.ForegroundOnly(operationType)
        }
        FileOperationServiceIntentContract.ACTION_START -> {
            val operationType = type
                ?.let { value -> runCatching { FileOperationType.valueOf(value) }.getOrNull() }
                ?: return null
            val parsedSources = sources
                ?.takeIf { it.isNotEmpty() }
                ?.let { values -> runCatching { values.map(Paths::get) }.getOrNull() }
                ?: return null
            val parsedTarget = targetDirectory
                ?.let { value -> runCatching { Paths.get(value) }.getOrNull() }
                ?: return null
            FileOperationServiceIntentCommand.Start(operationType, parsedSources, parsedTarget)
        }
        FileOperationServiceIntentContract.ACTION_PREPARE_DELETE -> {
            val parsedSources = sources
                ?.takeIf { it.isNotEmpty() }
                ?.let { values -> runCatching { values.map(Paths::get) }.getOrNull() }
                ?: return null
            FileOperationServiceIntentCommand.PrepareDelete(parsedSources)
        }
        else -> null
    }
}

class FileOperationStateCollectionController(
    private val scope: CoroutineScope,
    private val state: StateFlow<FileOperationState>,
    private val onState: (FileOperationState) -> Unit,
) {
    private var collectionJob: Job? = null

    fun ensureActive() {
        if (collectionJob?.isActive == true) return
        collectionJob = scope.launch { state.collect(onState) }
    }

    fun stop() {
        collectionJob?.cancel()
        collectionJob = null
    }

    fun close() = stop()
}

internal fun shouldStopServiceOnIdleCleanup(
    state: FileOperationState,
    foregroundStarted: Boolean,
): Boolean = when (state) {
    FileOperationState.Idle -> foregroundStarted
    is FileOperationState.Succeeded,
    is FileOperationState.PartiallySucceeded,
    is FileOperationState.Failed,
    is FileOperationState.Cancelled -> true
    else -> false
}

interface FileOperationServicePort {
    val state: StateFlow<FileOperationState>

    fun start(type: FileOperationType, sources: List<Path>, targetDirectory: Path): Boolean
    fun prepareDelete(sources: List<Path>): Boolean
    fun confirmLargeOperation(): Boolean
    fun confirmDelete(): Boolean
    fun replaceAll()
    fun cancel()
    fun consumeResult()
}

class FileOperationServicePortAdapter(
    private val runner: FileOperationRunnerPort,
    private val beforeTaskAccepted: (FileOperationType) -> Unit = {},
) : FileOperationServicePort {
    private val commandLock = Any()
    override val state: StateFlow<FileOperationState> = runner.state

    override fun start(type: FileOperationType, sources: List<Path>, targetDirectory: Path): Boolean =
        synchronized(commandLock) {
            if (!canAccept(sources)) return@synchronized false
            beforeTaskAccepted(type)
            runner.start(type, sources, targetDirectory)
        }

    override fun prepareDelete(sources: List<Path>): Boolean =
        synchronized(commandLock) {
            if (!canAccept(sources)) return@synchronized false
            beforeTaskAccepted(FileOperationType.DELETE)
            runner.prepareDelete(sources)
        }

    override fun confirmLargeOperation(): Boolean =
        synchronized(commandLock) { runner.confirmLargeOperation() }

    override fun confirmDelete(): Boolean = synchronized(commandLock) { runner.confirmDelete() }

    override fun replaceAll() = synchronized(commandLock) { runner.replaceAll() }

    override fun cancel() = synchronized(commandLock) { runner.cancel() }

    override fun consumeResult() = synchronized(commandLock) { runner.consumeResult() }

    private fun canAccept(sources: List<Path>): Boolean =
        sources.isNotEmpty() && runner.state.value == FileOperationState.Idle
}

class FileOperationService : Service(), FileOperationServicePort {
    private lateinit var runner: FileOperationRunner
    private lateinit var adapter: FileOperationServicePortAdapter
    private lateinit var notificationManager: NotificationManager
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var stateCollection: FileOperationStateCollectionController
    private var foregroundStarted = false
    private val binder = LocalBinder()

    override val state: StateFlow<FileOperationState>
        get() = adapter.state

    override fun onCreate() {
        super.onCreate()
        runner = FileOperationRunner()
        adapter = FileOperationServicePortAdapter(runner, ::ensureForeground)
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()
        stateCollection = FileOperationStateCollectionController(serviceScope, state, ::publishState)
        stateCollection.ensureActive()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val command = parseFileOperationServiceIntent(
            action = intent?.action,
            type = intent?.getStringExtra(FileOperationServiceIntentContract.EXTRA_TYPE),
            sources = intent?.getStringArrayListExtra(FileOperationServiceIntentContract.EXTRA_SOURCES),
            targetDirectory = intent?.getStringExtra(
                FileOperationServiceIntentContract.EXTRA_TARGET_DIRECTORY,
            ),
        ) ?: return START_NOT_STICKY
        dispatchFileOperationServiceIntentCommand(
            command = command,
            onForegroundOnly = ::ensureForeground,
            onStart = { type, sources, targetDirectory ->
                start(type, sources, targetDirectory)
            },
            onPrepareDelete = ::prepareDelete,
        )
        return START_NOT_STICKY
    }

    override fun start(type: FileOperationType, sources: List<Path>, targetDirectory: Path): Boolean =
        adapter.start(type, sources, targetDirectory).also { accepted ->
            if (!accepted && state.value == FileOperationState.Idle) cleanupIdleState()
        }

    override fun prepareDelete(sources: List<Path>): Boolean =
        adapter.prepareDelete(sources).also { accepted ->
            if (!accepted && state.value == FileOperationState.Idle) cleanupIdleState()
        }

    override fun confirmLargeOperation(): Boolean = adapter.confirmLargeOperation()

    override fun confirmDelete(): Boolean = adapter.confirmDelete()

    override fun replaceAll() = adapter.replaceAll()

    override fun cancel() = adapter.cancel()

    override fun consumeResult() = adapter.consumeResult()

    override fun onDestroy() {
        stateCollection.close()
        serviceScope.cancel()
        runner.close()
        super.onDestroy()
    }

    private fun publishState(state: FileOperationState) {
        notificationContentFor(state)?.let { notificationManager.notify(NOTIFICATION_ID, buildNotification(it)) }
        if (state == FileOperationState.Idle || state.isTerminal()) {
            if (state.isTerminal()) {
                stateCollection.stop()
            }
            val stopService = shouldStopServiceOnIdleCleanup(state, foregroundStarted)
            notificationManager.cancel(NOTIFICATION_ID)
            stopForegroundIfStarted()
            if (stopService) {
                stopSelf()
            }
        }
    }

    private fun ensureForeground(type: FileOperationType) {
        stateCollection.ensureActive()
        if (foregroundStarted) return
        val content = notificationContentFor(FileOperationState.Scanning(type)) ?: return
        startForeground(NOTIFICATION_ID, buildNotification(content))
        foregroundStarted = true
    }

    private fun stopForegroundIfStarted() {
        if (!foregroundStarted) return
        stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundStarted = false
    }

    private fun cleanupIdleState() {
        val stopService = shouldStopServiceOnIdleCleanup(FileOperationState.Idle, foregroundStarted)
        notificationManager.cancel(NOTIFICATION_ID)
        stopForegroundIfStarted()
        if (stopService) {
            stopSelf()
        }
    }

    private fun createNotificationChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "文件操作",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
    }

    private fun buildNotification(content: FileOperationNotificationContent) =
        NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(com.example.watchfiles.R.drawable.ic_app)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(true)
            .setProgress(content.totalItems ?: 0, content.processedItems ?: 0, content.totalItems == null)
            .build()

    private fun FileOperationState.isTerminal(): Boolean =
        this is FileOperationState.Succeeded ||
            this is FileOperationState.PartiallySucceeded ||
            this is FileOperationState.Failed ||
            this is FileOperationState.Cancelled

    inner class LocalBinder : Binder() {
        fun getService(): FileOperationService = this@FileOperationService
    }

    private companion object {
        private const val NOTIFICATION_CHANNEL_ID = "file_operations"
        private const val NOTIFICATION_ID = 1001
    }
}
