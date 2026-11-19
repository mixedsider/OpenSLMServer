package com.example.openslmserver

import android.util.Log
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

internal class KtorServer(
    private val model: LocalModel,
    private val inference: InferenceEngine,
    private val settings: ServerSettings
) {
    private val queue = QueueManager()

    // The same lock protects native generation and engine shutdown.
    private var closed = false

    private val server = embeddedServer(
        Netty,
        host = if (settings.allowLan) "0.0.0.0" else "127.0.0.1",
        port = settings.port
    ) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            })
        }
        routing {
            get("/") { call.respondText("OpenSLM Server is running!") }
            get("/v1/models") { call.respond(ModelList(data = listOf(ModelEntry(model.id)))) }
            post("/v1/chat/completions") {
                val request = try {
                    call.receive<ChatRequest>().also { validate(it) }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, ApiError(ErrorMessage(error.message ?: "Invalid request")))
                    return@post
                } catch (_: Exception) {
                    call.respond(HttpStatusCode.BadRequest, ApiError(ErrorMessage("Invalid JSON chat request")))
                    return@post
                }

                try {
                    val result = withContext(Dispatchers.IO) {
                        queue.enqueue {
                            if (closed) throw ServerStoppingException()
                            inference.generate(request.messages, request.max_tokens ?: settings.outputTokens)
                        }
                    }
                    if (result == null) {
                        call.respond(HttpStatusCode.TooManyRequests, ApiError(ErrorMessage("The inference queue is full")))
                        return@post
                    }
                    call.respond(
                        ChatResponse(
                            id = "chatcmpl-${UUID.randomUUID()}",
                            created = System.currentTimeMillis() / 1000,
                            model = model.id,
                            choices = listOf(Choice(0, Message("assistant", result), finish_reason = null))
                        )
                    )
                } catch (_: ServerStoppingException) {
                    call.respond(HttpStatusCode.ServiceUnavailable, ApiError(ErrorMessage("Server is stopping")))
                } catch (error: CancellationException) {
                    throw error
                } catch (error: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, ApiError(ErrorMessage(error.message ?: "Invalid prompt")))
                } catch (error: Exception) {
                    Log.w("OpenSLM", "Inference failed", error)
                    call.respond(HttpStatusCode.InternalServerError, ApiError(ErrorMessage("Inference failed")))
                }
            }
        }
    }

    private fun validate(request: ChatRequest) {
        require(!request.stream) { "This server supports JSON responses (stream=false)" }
        require(request.model == null || request.model == model.id) { "Unknown model. See /v1/models" }
        require(request.max_tokens == null || request.max_tokens in 1..settings.outputTokens) {
            "max_tokens must be between 1 and ${settings.outputTokens}"
        }
        val turns = request.messages.dropWhile { it.role == "system" }
        require(turns.isNotEmpty() && turns.size % 2 == 1 && turns.withIndex().all {
            it.value.role == if (it.index % 2 == 0) "user" else "assistant"
        }) { "Provide optional system messages followed by alternating user and assistant turns ending with user" }
        require(request.messages.none { it.content.contains('\u0000') } && turns.last().content.isNotBlank()) {
            "Invalid message text"
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

private class ServerStoppingException : IllegalStateException()
