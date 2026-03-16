package com.example.openslmserver

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QueueManagerTest {

    @Test
    fun `test sequential processing`() = runBlocking {
        val processedItems = mutableListOf<String>()
        val queueManager = QueueManager(
            maxQueueSize = 5,
            onProcess = { request ->
                delay(100)
                val content = request.messages.last().content
                processedItems.add(content)
                ChatResponse("id", "obj", 0, "model", emptyList())
            }
        )

        launch { queueManager.startProcessing() }

        val job1 = launch { queueManager.enqueue(ChatRequest(messages = listOf(Message("user", "1")))) }
        val job2 = launch { queueManager.enqueue(ChatRequest(messages = listOf(Message("user", "2")))) }

        job1.join()
        job2.join()

        assertEquals(listOf("1", "2"), processedItems)
    }

    @Test
    fun `test queue full rejection`() = runBlocking {
        val queueManager = QueueManager(
            maxQueueSize = 1,
            onProcess = { 
                delay(500)
                ChatResponse("id", "obj", 0, "model", emptyList())
            }
        )

        launch { queueManager.startProcessing() }

        // First request occupies the slot (and is currently "processing", but technically it's dequeued immediately when processing starts)
        // Wait, in my implementation: enqueue increments count, then sends to channel. 
        // startProcessing receives, then decrements count *after* processing.
        
        val resp1 = launch { 
            val res = queueManager.enqueue(ChatRequest(messages = listOf(Message("user", "1"))))
            assertNotNull(res)
        }
        
        delay(50) // ensure it's in the queue/processing
        
        // Second request should be rejected because currentQueueCount is 1
        val res2 = queueManager.enqueue(ChatRequest(messages = listOf(Message("user", "2"))))
        assertNull(res2)
        
        resp1.join()
    }
}
