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
}

internal interface FileOperationServiceBindingAdapter {
    fun bind(connection: FileOperationServiceBindingConnection): Boolean
    fun unbind()
    fun startForegroundService(request: FileOperationServiceLaunchRequest)
}

internal sealed interface FileOperationServiceLaunchRequest {
    data class Start(
        val type: String,
        val sources: ArrayList<String>,
        val targetDirectory: String,
    ) : FileOperationServiceLaunchRequest

    data class PrepareDelete(
        val sources: ArrayList<String>,
    ) : FileOperationServiceLaunchRequest
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
    private var bindingActive = false
    private val connection = object : FileOperationServiceBindingConnection {
        override fun onConnected(port: FileOperationServicePort) {
            val pending = synchronized(lock) {
                if (!bindingActive) return
                this@FileOperationServiceClient.port = port
                stateCollectionJob?.cancel()
                mutableState.value = port.state.value
                stateCollectionJob = scope.launch {
                    port.state.collect { state -> mutableState.value = state }
                }
                pendingCommand.also { pendingCommand = null }
            }
            pending?.sendTo(port)
        }

        override fun onDisconnected() {
            synchronized(lock) {
                port = null
                stateCollectionJob?.cancel()
                stateCollectionJob = null
            }
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
                port = null
            }
            logError("Unable to bind file operation service")
        }
    }

    override fun disconnect() {
        val shouldUnbind = synchronized(lock) {
            if (!bindingActive) {
                false
            } else {
                bindingActive = false
                port = null
                pendingCommand = null
                stateCollectionJob?.cancel()
                stateCollectionJob = null
                true
            }
        }
        if (shouldUnbind) {
            runCatching { bindingAdapter.unbind() }
                .onFailure { error -> logError("Unable to unbind file operation service", error) }
        }
    }

    override fun start(
        type: FileOperationType,
        sources: List<Path>,
        targetDirectory: Path,
    ): Boolean = submit(
        StartCommand.Transfer(type, sources, targetDirectory),
        FileOperationServiceLaunchRequest.Start(
            type = type.name,
            sources = ArrayList(sources.map(Path::toString)),
            targetDirectory = targetDirectory.toString(),
        ),
    )

    override fun prepareDelete(sources: List<Path>): Boolean = submit(
        StartCommand.Delete(sources),
        FileOperationServiceLaunchRequest.PrepareDelete(
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
        launchRequest: FileOperationServiceLaunchRequest,
    ): Boolean {
        val connectedPort = synchronized(lock) {
            port.also { currentPort ->
                if (currentPort == null) {
                    if (pendingCommand != null || mutableState.value != FileOperationState.Idle) {
                        return false
                    }
                    pendingCommand = command
                }
            }
        }

        val launched = runCatching { bindingAdapter.startForegroundService(launchRequest) }
            .onFailure { error -> logError("Unable to start file operation service", error) }
            .isSuccess
        if (!launched) {
            synchronized(lock) {
                if (pendingCommand === command) pendingCommand = null
            }
            return false
        }
        return connectedPort?.let(command::sendTo) ?: true
    }

    private fun currentPort(): FileOperationServicePort? = synchronized(lock) { port }

    private sealed interface StartCommand {
        fun sendTo(port: FileOperationServicePort): Boolean

        data class Transfer(
            val type: FileOperationType,
            val sources: List<Path>,
            val targetDirectory: Path,
        ) : StartCommand {
            override fun sendTo(port: FileOperationServicePort): Boolean =
                port.start(type, sources, targetDirectory)
        }

        data class Delete(
            val sources: List<Path>,
        ) : StartCommand {
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
                clientConnection?.onDisconnected()
                return
            }
            clientConnection?.onConnected(service)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            clientConnection?.onDisconnected()
        }

        override fun onBindingDied(name: ComponentName?) {
            clientConnection?.onDisconnected()
        }

        override fun onNullBinding(name: ComponentName?) {
            clientConnection?.onDisconnected()
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
            when (request) {
                is FileOperationServiceLaunchRequest.Start -> {
                    action = ACTION_START
                    putExtra(EXTRA_TYPE, request.type)
                    putStringArrayListExtra(EXTRA_SOURCES, request.sources)
                    putExtra(EXTRA_TARGET_DIRECTORY, request.targetDirectory)
                }
                is FileOperationServiceLaunchRequest.PrepareDelete -> {
                    action = ACTION_PREPARE_DELETE
                    putStringArrayListExtra(EXTRA_SOURCES, request.sources)
                }
            }
        }
        ContextCompat.startForegroundService(applicationContext, intent)
    }

    private companion object {
        private const val ACTION_START = "com.example.watchfiles.fileops.action.START"
        private const val ACTION_PREPARE_DELETE =
            "com.example.watchfiles.fileops.action.PREPARE_DELETE"
        private const val EXTRA_TYPE = "extra_type"
        private const val EXTRA_SOURCES = "extra_sources"
        private const val EXTRA_TARGET_DIRECTORY = "extra_target_directory"
    }
}
