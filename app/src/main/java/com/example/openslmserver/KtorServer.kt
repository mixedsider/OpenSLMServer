package com.example.openslmserver

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

internal class KtorServer(
    private val model: LocalModel,
    private val inference: InferenceEngine,
    private val settings: ServerSettings
) {
    private val queue = QueueManager()
    private val responseJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // The same lock protects native generation and engine shutdown.
    private var closed = false

    private val server = embeddedServer(
        Netty,
        host = if (settings.allowLan) "0.0.0.0" else "127.0.0.1",
        port = settings.port
    ) {
        install(ContentNegotiation) {
            json(responseJson)
        }
        routing {
            get("/") { call.respondText("OpenSLM Server is running!") }
            get("/v1/models") { call.respond(ModelList(data = listOf(ModelEntry(model.id)))) }
            post("/v1/chat/completions") {
                val (request, tools) = try {
                    val request = call.receive<ChatRequest>().also { validate(it) }
                    request to ChatTools(request)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, ApiError(ErrorMessage(error.message ?: "Invalid request")))
                    return@post
                } catch (_: Exception) {
                    call.respond(HttpStatusCode.BadRequest, ApiError(ErrorMessage("Invalid JSON chat request")))
                    return@post
                }

                val id = "chatcmpl-${UUID.randomUUID()}"
                val created = System.currentTimeMillis() / 1000
                val maxTokens = (request.max_tokens ?: settings.outputTokens).coerceIn(1, settings.outputTokens)
                try {
                    val accepted = withContext(Dispatchers.IO) {
                        queue.enqueue {
                            if (closed) throw ServerStoppingException()
                            if (request.stream) {
                                call.respondStream(request, tools, maxTokens, id, created)
                            } else {
                                val text = StringBuilder()
                                val calls = inference.generate(request.messages, tools, maxTokens) { text.append(it) }
                                val message = tools.response(text.toString(), calls)
                                call.respond(
                                    ChatResponse(id, created = created, model = model.id, choices = listOf(
                                        Choice(0, message, finish_reason = message.finishReason)
                                    ))
                                )
                            }
                        }
                    }
                    if (accepted == null) {
                        call.respond(HttpStatusCode.TooManyRequests, ApiError(ErrorMessage("The inference queue is full")))
                    }
                } catch (_: ServerStoppingException) {
                    call.respond(HttpStatusCode.ServiceUnavailable, ApiError(ErrorMessage("Server is stopping")))
                } catch (error: CancellationException) {
                    throw error
                } catch (error: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, ApiError(ErrorMessage(error.message ?: "Invalid prompt")))
                } catch (error: Exception) {
                    call.application.log.warn("Inference failed", error)
                    if (!call.response.isCommitted) {
                        call.respond(HttpStatusCode.InternalServerError, ApiError(ErrorMessage("Inference failed")))
                    }
                }
            }
        }
    }

    private fun validate(request: ChatRequest) {
        require(request.model == null || request.model == model.id) { "Unknown model. See /v1/models" }
        val turns = request.messages.dropWhile { it.isInstruction }
        val last = turns.lastOrNull()
        require(last?.role == "user" || last?.role == "tool") { "The last message must be from user or tool" }
        require(request.messages.none { it.content?.contains('\u0000') == true } &&
            request.messages.takeWhile { it.isInstruction }.all { it.content != null } &&
            (last.role != "user" || !last.content.isNullOrBlank())
        ) {
            "Invalid message text"
        }
        val pending = mutableMapOf<String, String>()
        val seenIds = mutableSetOf<String>()
        var expected = "user"
        for (message in turns) {
            require(message.role == expected) { "Expected a $expected message" }
            val calls = message.tool_calls.orEmpty()
            require(calls.isEmpty() || message.role == "assistant") { "Only assistant messages can contain tool_calls" }
            require(message.content != null || calls.isNotEmpty()) { "Missing message content" }
            expected = when (message.role) {
                "user" -> "assistant"
                "assistant" -> {
                    for (call in calls) {
                        require(call.type == "function" && call.id.isNotBlank() && seenIds.add(call.id) &&
                            call.function.name.isNotBlank() && parseArguments(call.function.arguments) != null
                        ) { "Invalid assistant tool call" }
                        pending[call.id] = call.function.name
                    }
                    if (pending.isEmpty()) "user" else "tool"
                }
                else -> {
                    val name = pending.remove(message.tool_call_id)
                    require(name != null && (message.name == null || message.name == name)) {
                        "Tool result must match a pending assistant tool call"
                    }
                    if (pending.isEmpty()) "assistant" else "tool"
                }
            }
        }
        require(pending.isEmpty()) { "Provide a result for each assistant tool call" }
    }

    private suspend fun ApplicationCall.respondStream(
        request: ChatRequest,
        tools: ChatTools,
        maxTokens: Int,
        id: String,
        created: Long
    ) {
        response.headers.append(HttpHeaders.CacheControl, "no-cache")
        respondTextWriter(ContentType.Text.EventStream.withCharset(Charsets.UTF_8)) {
            fun event(data: String) {
                write("data: $data\n\n")
                flush()
            }
            fun chunk(finishReason: String? = null, delta: JsonObject) {
                event(responseJson.encodeToString(ChatChunk(id, created, model.id, listOf(
                    ChunkChoice(0, delta, finishReason)
                ))))
            }

            chunk(delta = buildJsonObject { put("role", "assistant") })
            try {
                val buffered = StringBuilder()
                val calls = inference.generate(request.messages, tools, maxTokens) { text ->
                    if (tools.functions.isNotEmpty()) buffered.append(text)
                    else if (text.isNotEmpty()) chunk(delta = buildJsonObject { put("content", text) })
                }
                val message = tools.response(buffered.toString(), calls)
                if (!message.tool_calls.isNullOrEmpty()) {
                    chunk(delta = buildJsonObject {
                        putJsonArray("tool_calls") {
                            message.tool_calls.forEachIndexed { index, call ->
                                add(buildJsonObject {
                                    put("index", index)
                                    put("id", call.id)
                                    put("type", call.type)
                                    putJsonObject("function") {
                                        put("name", call.function.name)
                                        put("arguments", call.function.arguments)
                                    }
                                })
                            }
                        }
                    })
                } else if (!message.content.isNullOrEmpty()) {
                    chunk(delta = buildJsonObject { put("content", message.content) })
                }
                chunk(message.finishReason, buildJsonObject {})
                event("[DONE]")
            } catch (error: CancellationException) {
                throw error
            } catch (error: IOException) {
                throw error
            } catch (error: Exception) {
                application.log.warn("Streaming inference failed", error)
                event(responseJson.encodeToString(ApiError(ErrorMessage("Inference failed"))))
            }
        }
    }

    fun start() {
        server.start(wait = false)
    }

    suspend fun stop() {
        try {
            server.stop(1000, 2000)
        } finally {
            queue.withExclusiveAccess {
                if (!closed) {
                    closed = true
                    inference.close()
                }
            }
        }
    }
}

private val Message.finishReason: String
    get() = if (tool_calls.isNullOrEmpty()) "stop" else "tool_calls"

private class ServerStoppingException : IllegalStateException()
