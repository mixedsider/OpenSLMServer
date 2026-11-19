package com.example.openslmserver

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class QueueManagerTest {
    @Test
    fun processesRequestsSequentially() = runBlocking {
        val queue = QueueManager()
        val releaseFirst = CompletableDeferred<Unit>()
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            queue.enqueue {
                releaseFirst.await()
                "first"
            }
        }
        val second = async(start = CoroutineStart.UNDISPATCHED) { queue.enqueue { "second" } }
        val processedEarly = second.isCompleted

        releaseFirst.complete(Unit)
        assertEquals("first", first.await())
        assertEquals("second", second.await())
        assertFalse(processedEarly)
    }

    @Test
    fun rejectsRequestsWhenCapacityIsFull() = runBlocking {
        val queue = QueueManager(maxQueueSize = 1)
        val release = CompletableDeferred<Unit>()
        val first = async(start = CoroutineStart.UNDISPATCHED) { queue.enqueue { release.await() } }

        try {
            assertNull(queue.enqueue { "overflow" })
        } finally {
            release.complete(Unit)
        }
        first.await()
        assertEquals("next", queue.enqueue { "next" })
    }
}
