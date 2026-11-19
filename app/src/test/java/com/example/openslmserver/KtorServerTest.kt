package com.example.openslmserver

import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KtorServerTest {
    @Test
    fun returnsTextFromTheSelectedInferenceEngine() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        var closed = false
        val engine = object : InferenceEngine {
            override val name = "Test engine"
            override val accelerator = Accelerator.CPU

            override fun generate(messages: List<Message>, maxTokens: Int): String =
                "${messages.last().content} (${messages.size} messages, $maxTokens tokens)"

            override fun close() {
                closed = true
            }
        }
        val model = LocalModel(File(ModelManager.DEFAULT_FILE), ModelFormat.LITERT)
        val server = KtorServer(model, engine, ServerSettings(port, 1024, 256, Accelerator.CPU, allowLan = false))
        server.start()
        try {
            val connection = URI("http://127.0.0.1:$port/v1/chat/completions").toURL().openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.bufferedWriter().use {
                    it.write(
                        """{"messages":[{"role":"system","content":"Be concise."},{"role":"user","content":"안녕하세요"}],"max_tokens":24}"""
                    )
                }
                assertEquals(200, connection.responseCode)
                val response = connection.inputStream.bufferedReader().use {
                    Json.parseToJsonElement(it.readText()).jsonObject
                }
                assertEquals(model.id, response.getValue("model").jsonPrimitive.content)
                val message = response.getValue("choices").jsonArray.single().jsonObject.getValue("message").jsonObject
                assertEquals("안녕하세요 (2 messages, 24 tokens)", message.getValue("content").jsonPrimitive.content)
            } finally {
                connection.disconnect()
            }
        } finally {
            server.stop()
        }

        assertTrue(closed)
    }
}
