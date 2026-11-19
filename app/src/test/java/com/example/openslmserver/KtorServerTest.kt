package com.example.openslmserver

import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class KtorServerTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun returnsTextFromTheSelectedInferenceEngine() = withServer { endpoint ->
        post(endpoint, """{
            "messages":[{"role":"developer","content":"Be concise."},
                {"role":"user","content":[{"type":"text","text":"안녕"},{"type":"text","text":"하세요"}]}],
            "max_tokens":4096
        }""") { connection ->
            assertEquals(200, connection.responseCode)
            val response = connection.body().json()
            assertEquals(File(ModelManager.DEFAULT_FILE).nameWithoutExtension, response.text("model"))
            val choice = response.choice()
            assertEquals("stop", choice.text("finish_reason"))
            assertEquals("안녕하세요 (2 messages, 256 tokens)", choice.obj("message").text("content"))
        }
    }

    @Test
    fun streamsTextBeforeInferenceCompletes() {
        val continueInference = CompletableDeferred<Unit>()
        withServer(generate = { _, _, onText ->
            onText("안녕")
            withTimeout(5000) { continueInference.await() }
            onText("하세요")
            emptyList()
        }) { endpoint ->
            post(endpoint, """{"messages":[{"role":"user","content":"Hi"}],"stream":true}""") { connection ->
                assertEquals(200, connection.responseCode)
                assertTrue(connection.contentType.startsWith("text/event-stream"))
                connection.inputStream.bufferedReader().use { reader ->
                    fun nextEvent(): String {
                        val event = reader.readLine().removePrefix("data: ")
                        assertEquals("", reader.readLine())
                        return event
                    }
                    val first = nextEvent().json()
                    assertEquals("chat.completion.chunk", first.text("object"))
                    assertEquals("assistant", first.choice().obj("delta").text("role"))
                    val firstText = nextEvent().json()
                    assertEquals(first.text("id"), firstText.text("id"))
                    assertEquals("안녕", firstText.choice().obj("delta").text("content"))
                    continueInference.complete(Unit)
                    assertEquals("하세요", nextEvent().json().choice().obj("delta").text("content"))
                    assertEquals("stop", nextEvent().json().choice().text("finish_reason"))
                    assertEquals("[DONE]", nextEvent())
                }
            }
        }
    }

    @Test
    fun returnsToolCallsAndContinuesWithMultipleResults() = withServer(generate = { messages, maxTokens, onText ->
        if (messages.last().role == "tool") {
            assertEquals(listOf("sunny", "cloudy"), messages.takeLast(2).map { it.content })
            assertEquals(1, maxTokens)
            onText("Weather received.")
            emptyList()
        } else {
            listOf(ToolFunction("weather", """{"city":"Seoul"}"""), ToolFunction("weather", """{"city":"Busan"}"""))
        }
    }) { endpoint ->
        val choice = post(endpoint, """{"messages":[{"role":"user","content":"Weather?"}],"tools":$weatherTool}""") {
            assertEquals(200, it.responseCode)
            it.body().json().choice()
        }
        assertEquals("tool_calls", choice.text("finish_reason"))
        val message = choice.obj("message")
        assertEquals(JsonNull, message["content"])
        val calls = message.getValue("tool_calls").jsonArray
        val first = calls[0].jsonObject
        val second = calls[1].jsonObject
        assertEquals("weather", first.obj("function").text("name"))
        assertEquals("Seoul", first.obj("function").text("arguments").json().text("city"))
        val firstResult = """{"role":"tool","tool_call_id":"${first.text("id")}","content":"sunny"}"""
        val secondResult = """{"role":"tool","tool_call_id":"${second.text("id")}","name":"weather","content":"cloudy"}"""
        val history = """{"role":"user","content":"Weather?"},$message,$firstResult"""
        post(endpoint, """{"messages":[$history,$secondResult],"max_tokens":0,"tool_choice":"none"}""") {
            assertEquals(200, it.responseCode)
            assertEquals("Weather received.", it.body().json().choice().obj("message").text("content"))
        }
        val unmatched = secondResult.replace(second.text("id"), "unknown")
        post(endpoint, """{"messages":[$history,$unmatched]}""") {
            assertEquals(400, it.responseCode)
        }
    }

    @Test
    fun streamsGemmaToolCallsAsIndexedDeltas() = withServer(generate = { _, _, onText ->
        onText("<|tool_call>call:weather")
        onText("{city:<|\"|>서울<|\"|>}<tool_call|>")
        emptyList()
    }) { endpoint ->
        post(endpoint, """{
            "messages":[{"role":"user","content":"Weather?"}],"stream":true,"tools":$weatherTool,
            "tool_choice":{"type":"function","function":{"name":"weather"}}
        }""") {
            assertEquals(200, it.responseCode)
            val events = it.body().lineSequence().filter { line -> line.startsWith("data: ") }
                .map { line -> line.removePrefix("data: ") }.toList()
            assertEquals(4, events.size)
            val delta = events[1].json().choice().obj("delta")
            val toolCall = delta.getValue("tool_calls").jsonArray.single().jsonObject
            assertEquals("0", toolCall.text("index"))
            assertTrue(toolCall.text("id").startsWith("call_"))
            assertEquals("weather", toolCall.obj("function").text("name"))
            assertEquals("서울", toolCall.obj("function").text("arguments").json().text("city"))
            assertEquals("tool_calls", events[2].json().choice().text("finish_reason"))
            assertEquals("[DONE]", events[3])
        }
    }

    @Test
    fun recordsRequestOutcomesWithoutSavingChatOrQueryData() {
        val log = ServerLog(tempFolder.newFolder())
        val body = """{"messages":[{"role":"user","content":"private prompt"}]}"""
        withServer(serverLog = log) { endpoint ->
            post(URI("$endpoint?token=private-query"), body) {
                assertEquals(200, it.responseCode)
                it.body()
            }
            post(endpoint, """{"messages":[]}""") { assertEquals(400, it.responseCode) }
        }
        val saved = log.snapshot().decodeToString()
        assertTrue(saved.contains("POST /v1/chat/completions status=200 duration_ms="))
        assertTrue(saved.contains("POST /v1/chat/completions status=400 duration_ms="))
        assertFalse(saved.contains("private prompt"))
        assertFalse(saved.contains("private-query"))
    }

    @Test
    fun leavesSavedLogsUntouchedWhenLoggingIsOff() {
        val log = ServerLog(tempFolder.newFolder())
        log.record("earlier session")
        val saved = log.snapshot().decodeToString()
        withServer(serverLog = log, logEnabled = false) { endpoint ->
            post(endpoint, """{"messages":[{"role":"user","content":"Hello"}]}""") {
                assertEquals(200, it.responseCode)
            }
        }
        assertEquals(saved, log.snapshot().decodeToString())
    }

    private fun withServer(
        generate: suspend (List<Message>, Int, suspend (String) -> Unit) -> List<ToolFunction> =
            { messages, maxTokens, onText ->
                onText("${messages.last().content} (${messages.size} messages, $maxTokens tokens)")
                emptyList()
            },
        serverLog: ServerLog? = null,
        logEnabled: Boolean = serverLog != null,
        test: (URI) -> Unit
    ) {
        val port = ServerSocket(0).use { it.localPort }
        var closed = false
        val engine = object : InferenceEngine {
            override val name = "Test engine"
            override val accelerator = Accelerator.CPU

            override suspend fun generate(
                messages: List<Message>,
                tools: ChatTools,
                maxTokens: Int,
                onText: suspend (String) -> Unit
            ) = generate(messages, maxTokens, onText)

            override fun close() {
                closed = true
            }
        }
        val model = LocalModel(File(ModelManager.DEFAULT_FILE), ModelFormat.LITERT)
        val settings = ServerSettings(port, 1024, 256, Accelerator.CPU, allowLan = false, logEnabled = logEnabled)
        val server = KtorServer(model, engine, settings, serverLog)
        server.start()
        try {
            test(URI("http://127.0.0.1:$port/v1/chat/completions"))
        } finally {
            runBlocking { server.stop() }
        }
        assertTrue(closed)
    }

    private fun <T> post(endpoint: URI, body: String, read: (HttpURLConnection) -> T): T {
        val connection = endpoint.toURL().openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.bufferedWriter().use { it.write(body) }
            read(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun HttpURLConnection.body() = inputStream.bufferedReader().use { it.readText() }
    private fun String.json() = Json.parseToJsonElement(this).jsonObject
    private fun JsonObject.choice() = getValue("choices").jsonArray.single().jsonObject
    private fun JsonObject.obj(key: String) = getValue(key).jsonObject
    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content

    private val weatherTool = """[{"type":"function","function":{"name":"weather",
        "parameters":{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}}}]"""
}
