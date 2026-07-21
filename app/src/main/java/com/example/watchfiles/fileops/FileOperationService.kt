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

interface FileOperationServicePort {
    val state: StateFlow<FileOperationState>

    fun start(type: FileOperationType, sources: List<Path>, targetDirectory: Path): Boolean
    fun prepareDelete(sources: List<Path>): Boolean
    fun confirmDelete(): Boolean
    fun replaceAll()
    fun cancel()
    fun consumeResult()
}

class FileOperationServicePortAdapter(
    private val runner: FileOperationRunnerPort,
    private val beforeTaskAccepted: (FileOperationType) -> Unit = {},
) : FileOperationServicePort {
    override val state: StateFlow<FileOperationState> = runner.state

    override fun start(type: FileOperationType, sources: List<Path>, targetDirectory: Path): Boolean {
        if (!canAccept(sources)) return false
        beforeTaskAccepted(type)
        return runner.start(type, sources, targetDirectory)
    }

    override fun prepareDelete(sources: List<Path>): Boolean {
        if (!canAccept(sources)) return false
        beforeTaskAccepted(FileOperationType.DELETE)
        return runner.prepareDelete(sources)
    }

    override fun confirmDelete(): Boolean = runner.confirmDelete()

    override fun replaceAll() = runner.replaceAll()

    override fun cancel() = runner.cancel()

    override fun consumeResult() = runner.consumeResult()

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
        if (intent?.action != ACTION_START) return START_NOT_STICKY
        val request = intent.toStartRequest() ?: return START_NOT_STICKY
        start(request.type, request.sources, request.targetDirectory)
        return START_NOT_STICKY
    }

    override fun start(type: FileOperationType, sources: List<Path>, targetDirectory: Path): Boolean =
        adapter.start(type, sources, targetDirectory).also { accepted ->
            if (!accepted && state.value == FileOperationState.Idle) stopForegroundIfStarted()
        }

    override fun prepareDelete(sources: List<Path>): Boolean =
        adapter.prepareDelete(sources).also { accepted ->
            if (!accepted && state.value == FileOperationState.Idle) stopForegroundIfStarted()
        }

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
        if (state == FileOperationState.Idle) {
            notificationManager.cancel(NOTIFICATION_ID)
            stopForegroundIfStarted()
        }
        if (state.isTerminal()) {
            stateCollection.stop()
            notificationManager.cancel(NOTIFICATION_ID)
            stopForegroundIfStarted()
            stopSelf()
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

    private fun Intent.toStartRequest(): StartRequest? {
        val type = getStringExtra(EXTRA_TYPE)
            ?.let { value -> runCatching { FileOperationType.valueOf(value) }.getOrNull() }
            ?: return null
        val sources = getStringArrayListExtra(EXTRA_SOURCES)
            ?.takeIf { it.isNotEmpty() }
            ?.mapCatching(Paths::get)
            ?: return null
        val targetDirectory = getStringExtra(EXTRA_TARGET_DIRECTORY)
            ?.let { value -> runCatching { Paths.get(value) }.getOrNull() }
            ?: return null
        return StartRequest(type, sources, targetDirectory)
    }

    private fun <T> Iterable<String>.mapCatching(transform: (String) -> T): List<T>? =
        runCatching { map(transform) }.getOrNull()

    private fun FileOperationState.isTerminal(): Boolean =
        this is FileOperationState.Succeeded ||
            this is FileOperationState.PartiallySucceeded ||
            this is FileOperationState.Failed ||
            this is FileOperationState.Cancelled

    inner class LocalBinder : Binder() {
        fun getService(): FileOperationService = this@FileOperationService
    }

    private data class StartRequest(
        val type: FileOperationType,
        val sources: List<Path>,
        val targetDirectory: Path,
    )

    private companion object {
        private const val ACTION_START = "com.example.watchfiles.fileops.action.START"
        private const val EXTRA_TYPE = "extra_type"
        private const val EXTRA_SOURCES = "extra_sources"
        private const val EXTRA_TARGET_DIRECTORY = "extra_target_directory"
        private const val NOTIFICATION_CHANNEL_ID = "file_operations"
        private const val NOTIFICATION_ID = 1001
    }
}
