package com.example.watchfiles.fileops

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import java.nio.file.Path
import kotlinx.coroutines.flow.StateFlow

class FileOperationCoordinator(
    private val gateway: FileOperationServiceGateway,
) : ViewModel() {
    val state: StateFlow<FileOperationState> = gateway.state

    init {
        gateway.connect()
    }

    fun start(
        type: FileOperationType,
        sources: List<Path>,
        targetDirectory: Path,
    ): Boolean = gateway.start(type, sources, targetDirectory)

    fun prepareDelete(sources: List<Path>): Boolean = gateway.prepareDelete(sources)

    fun confirmDelete(): Boolean = gateway.confirmDelete()

    fun replaceAll() = gateway.replaceAll()

    fun cancel() = gateway.cancel()

    fun consumeResult() = gateway.consumeResult()

    override fun onCleared() {
        gateway.disconnect()
    }

    class Factory(context: Context) : ViewModelProvider.Factory {
        private val applicationContext = context.applicationContext

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(FileOperationCoordinator::class.java)) {
                "Unsupported ViewModel class: ${modelClass.name}"
            }
            return FileOperationCoordinator(
                FileOperationServiceClient(applicationContext),
            ) as T
        }
    }
}
