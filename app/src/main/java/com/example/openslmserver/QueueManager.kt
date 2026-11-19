package com.example.openslmserver

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock

internal class QueueManager(maxQueueSize: Int = 5) {
    private val capacity = Semaphore(maxQueueSize)
    private val mutex = Mutex()

    suspend fun <T : Any> enqueue(process: suspend () -> T): T? {
        if (!capacity.tryAcquire()) return null
        return try {
            withExclusiveAccess(process)
        } finally {
            capacity.release()
        }
    }

    suspend fun <T> withExclusiveAccess(action: suspend () -> T): T = mutex.withLock { action() }
}
