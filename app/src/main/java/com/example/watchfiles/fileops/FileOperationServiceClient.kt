package com.example.watchfiles.fileops

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

interface FileOperationServiceGateway {
    val state: StateFlow<FileOperationState>
    fun connect()
    fun disconnect()
    fun start(type: FileOperationType, sources: List<Path>, targetDirectory: Path): Boolean
    fun prepareDelete(sources: List<Path>): Boolean
    fun confirmDelete(): Boolean
    fun replaceAll()
    fun cancel()
    fun consumeResult()
}

internal interface FileOperationServiceBindingConnection {
    fun onConnected(port: FileOperationServicePort)
    fun onDisconnected()
    fun onBindingDied()
    fun onNullBinding()
}

internal interface FileOperationServiceBindingAdapter {
    fun bind(connection: FileOperationServiceBindingConnection): Boolean
    fun unbind()
    fun startForegroundService(request: FileOperationServiceLaunchRequest)
}

internal sealed interface FileOperationServiceLaunchRequest {
    val action: String
    val type: String?
    val sources: ArrayList<String>?
    val targetDirectory: String?

    data class ForegroundOnly(override val type: String) : FileOperationServiceLaunchRequest {
        override val action = FileOperationServiceIntentContract.ACTION_FOREGROUND_ONLY
        override val sources: ArrayList<String>? = null
        override val targetDirectory: String? = null
    }

    data class Start(
        override val type: String,
        override val sources: ArrayList<String>,
        override val targetDirectory: String,
    ) : FileOperationServiceLaunchRequest {
        override val action = FileOperationServiceIntentContract.ACTION_START
    }

    data class PrepareDelete(
        override val sources: ArrayList<String>,
    ) : FileOperationServiceLaunchRequest {
        override val action = FileOperationServiceIntentContract.ACTION_PREPARE_DELETE
        override val type: String? = null
        override val targetDirectory: String? = null
    }
}

class FileOperationServiceClient internal constructor(
    private val bindingAdapter: FileOperationServiceBindingAdapter,
    private val scope: CoroutineScope,
) : FileOperationServiceGateway {
    constructor(context: Context) : this(
        bindingAdapter = AndroidFileOperationServiceBindingAdapter(context.applicationContext),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    )

    private val mutableState = MutableStateFlow<FileOperationState>(FileOperationState.Idle)
    override val state: StateFlow<FileOperationState> = mutableState
    private val lock = Any()
    private var port: FileOperationServicePort? = null
    private var stateCollectionJob: Job? = null
    private var pendingCommand: StartCommand? = null
    private var foregroundLaunchInProgress = false
    private var bindingActive = false
    private var rebindScheduled = false
    private val connection = object : FileOperationServiceBindingConnection {
        override fun onConnected(port: FileOperationServicePort) {
            synchronized(lock) {
                if (!bindingActive) return
                this@FileOperationServiceClient.port = port
                stateCollectionJob?.cancel()
                mutableState.value = port.state.value
                stateCollectionJob = scope.launch {
                    port.state.collect { state -> mutableState.value = state }
                }
                if (!foregroundLaunchInProgress) pendingCommand = null
            }
        }

        override fun onDisconnected() {
            synchronized(lock) {
                port = null
                stateCollectionJob?.cancel()
                stateCollectionJob = null
            }
        }

        override fun onBindingDied() {
            loseBindingAndScheduleRebind()
        }

        override fun onNullBinding() {
            loseBindingAndScheduleRebind()
        }
    }

    override fun connect() {
        val shouldBind = synchronized(lock) {
            if (bindingActive) {
                false
            } else {
                bindingActive = true
                true
            }
        }
        if (!shouldBind) return

        val bound = runCatching { bindingAdapter.bind(connection) }
            .onFailure { error -> logError("Unable to bind file operation service", error) }
            .getOrDefault(false)
        if (!bound) {
            synchronized(lock) {
                bindingActive = false
            }
            logError("Unable to bind file operation service")
        }
    }

    override fun disconnect() {
        val shouldUnbind = synchronized(lock) {
            if (!bindingActive) {
                rebindScheduled = false
                false
            } else {
                bindingActive = false
                rebindScheduled = false
                port = null
                pendingCommand = null
                foregroundLaunchInProgress = false
                stateCollectionJob?.cancel()
                stateCollectionJob = null
                true
            }
        }
        if (shouldUnbind) unbindSafely()
    }

    override fun start(
        type: FileOperationType,
        sources: List<Path>,
        targetDirectory: Path,
    ): Boolean = submit(
        command = StartCommand.Transfer(type, sources, targetDirectory),
        fullLaunch = FileOperationServiceLaunchRequest.Start(
            type = type.name,
            sources = ArrayList(sources.map(Path::toString)),
            targetDirectory = targetDirectory.toString(),
        ),
    )

    override fun prepareDelete(sources: List<Path>): Boolean = submit(
        command = StartCommand.Delete(sources),
        fullLaunch = FileOperationServiceLaunchRequest.PrepareDelete(
            sources = ArrayList(sources.map(Path::toString)),
        ),
    )

    override fun confirmDelete(): Boolean = currentPort()?.confirmDelete() ?: false

    override fun replaceAll() {
        currentPort()?.replaceAll()
    }

    override fun cancel() {
        currentPort()?.cancel()
    }

    override fun consumeResult() {
        currentPort()?.consumeResult()
    }

    private fun submit(
        command: StartCommand,
        fullLaunch: FileOperationServiceLaunchRequest,
    ): Boolean {
        val connectedPort = synchronized(lock) {
            port ?: run {
                if (pendingCommand != null || mutableState.value != FileOperationState.Idle) {
                    return false
                }
                pendingCommand = command
                foregroundLaunchInProgress = true
                return@run null
            }
        }

        if (connectedPort == null) {
            val launched = startForegroundService(fullLaunch)
            synchronized(lock) {
                foregroundLaunchInProgress = false
                if (!launched || port != null) {
                    if (pendingCommand === command) pendingCommand = null
                }
            }
            return launched
        }

        val foregroundLaunch = FileOperationServiceLaunchRequest.ForegroundOnly(command.type.name)
        if (!startForegroundService(foregroundLaunch)) return false
        val readyPort = synchronized(lock) {
            port?.takeIf { it === connectedPort }
        } ?: return false
        return command.sendTo(readyPort)
    }

    private fun startForegroundService(request: FileOperationServiceLaunchRequest): Boolean =
        runCatching { bindingAdapter.startForegroundService(request) }
            .onFailure { error -> logError("Unable to start file operation service", error) }
            .isSuccess

    private fun currentPort(): FileOperationServicePort? = synchronized(lock) { port }

    private fun loseBindingAndScheduleRebind() {
        val shouldRebind = synchronized(lock) {
            if (!bindingActive) {
                false
            } else {
                bindingActive = false
                port = null
                stateCollectionJob?.cancel()
                stateCollectionJob = null
                if (rebindScheduled) {
                    false
                } else {
                    rebindScheduled = true
                    true
                }
            }
        }
        if (!shouldRebind) return

        unbindSafely()
        scope.launch {
            val shouldRun = synchronized(lock) {
                if (!rebindScheduled) {
                    false
                } else {
                    rebindScheduled = false
                    true
                }
            }
            if (shouldRun) connect()
        }
    }

    private fun unbindSafely() {
        runCatching { bindingAdapter.unbind() }
            .onFailure { error -> logError("Unable to unbind file operation service", error) }
    }

    private sealed interface StartCommand {
        val type: FileOperationType
        fun sendTo(port: FileOperationServicePort): Boolean

        data class Transfer(
            override val type: FileOperationType,
            val sources: List<Path>,
            val targetDirectory: Path,
        ) : StartCommand {
            override fun sendTo(port: FileOperationServicePort): Boolean =
                port.start(type, sources, targetDirectory)
        }

        data class Delete(
            val sources: List<Path>,
        ) : StartCommand {
            override val type: FileOperationType = FileOperationType.DELETE

            override fun sendTo(port: FileOperationServicePort): Boolean =
                port.prepareDelete(sources)
        }
    }

    private companion object {
        private const val TAG = "WatchFiles"

        private fun logError(message: String, error: Throwable? = null) {
            runCatching {
                if (error == null) Log.e(TAG, message) else Log.e(TAG, message, error)
            }
        }
    }
}

private class AndroidFileOperationServiceBindingAdapter(
    private val applicationContext: Context,
) : FileOperationServiceBindingAdapter {
    private var clientConnection: FileOperationServiceBindingConnection? = null
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as? FileOperationService.LocalBinder)?.getService()
            if (service == null) {
                clientConnection?.onNullBinding()
                return
            }
            clientConnection?.onConnected(service)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            clientConnection?.onDisconnected()
        }

        override fun onBindingDied(name: ComponentName?) {
            clientConnection?.onBindingDied()
        }

        override fun onNullBinding(name: ComponentName?) {
            clientConnection?.onNullBinding()
        }
    }

    override fun bind(connection: FileOperationServiceBindingConnection): Boolean {
        clientConnection = connection
        val serviceIntent = Intent(applicationContext, FileOperationService::class.java)
        return applicationContext.bindService(
            serviceIntent,
            serviceConnection,
            Context.BIND_AUTO_CREATE,
        )
    }

    override fun unbind() {
        applicationContext.unbindService(serviceConnection)
        clientConnection = null
    }

    override fun startForegroundService(request: FileOperationServiceLaunchRequest) {
        val intent = Intent(applicationContext, FileOperationService::class.java).apply {
            action = request.action
            request.type?.let { putExtra(FileOperationServiceIntentContract.EXTRA_TYPE, it) }
            request.sources?.let {
                putStringArrayListExtra(FileOperationServiceIntentContract.EXTRA_SOURCES, it)
            }
            request.targetDirectory?.let {
                putExtra(FileOperationServiceIntentContract.EXTRA_TARGET_DIRECTORY, it)
            }
        }
        ContextCompat.startForegroundService(applicationContext, intent)
    }
}
