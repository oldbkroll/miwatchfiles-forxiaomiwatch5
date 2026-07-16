package com.example.watchfiles.fileops

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException

class OperationCancellation {
    private val requested = AtomicBoolean(false)

    fun request() {
        requested.set(true)
    }

    fun isRequested(): Boolean = requested.get()

    fun throwIfRequested() {
        if (requested.get()) throw OperationCancelledException()
    }
}

class OperationCancelledException : CancellationException("file operation cancelled")
