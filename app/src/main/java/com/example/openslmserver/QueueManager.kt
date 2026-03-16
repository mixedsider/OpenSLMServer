package com.example.openslmserver

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

data class PendingRequest(
    val request: ChatRequest,
    val responseDeferred: CompletableDeferred<ChatResponse?>
)

class QueueManager(
    private val maxQueueSize: Int = 5,
    private val processTimeoutMs: Long = 30000,
    private val onProcess: suspend (ChatRequest) -> ChatResponse
) {
    private val queue = Channel<PendingRequest>(Channel.UNLIMITED)
    private val mutex = Mutex()
    private var currentQueueCount = 0

    suspend fun enqueue(request: ChatRequest): ChatResponse? {
        mutex.withLock {
            if (currentQueueCount >= maxQueueSize) {
                return null // Queue full
            }
            currentQueueCount++
        }

        val deferred = CompletableDeferred<ChatResponse?>()
        queue.send(PendingRequest(request, deferred))
        
        return deferred.await()
    }

    suspend fun startProcessing() {
        for (pending in queue) {
            val response = withTimeoutOrNull(processTimeoutMs) {
                onProcess(pending.request)
            }
            
            pending.responseDeferred.complete(response)
            
            mutex.withLock {
                currentQueueCount--
            }
        }
    }
}
