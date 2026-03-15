package com.example.openslmserver

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import java.util.*

class KtorServer(private val onLog: (String) -> Unit) {

    private var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null

    fun start(port: Int = 8080) {
        server = embeddedServer(Netty, port = port) {
            install(ContentNegotiation) {
                json(Json {
                    prettyPrint = true
                    isLenient = true
                    ignoreUnknownKeys = true
                })
            }

            routing {
                get("/") {
                    call.respondText("OpenSLM Server is running!")
                }

                post("/v1/chat/completions") {
                    try {
                        val request = call.receive<ChatRequest>()
                        onLog("Received request: ${request.messages.lastOrNull()?.content}")

                        // Phase 1 Mock Response
                        val response = ChatResponse(
                            id = "chatcmpl-${UUID.randomUUID()}",
                            created = System.currentTimeMillis() / 1000,
                            model = request.model ?: "openslm-mock",
                            choices = listOf(
                                Choice(
                                    index = 0,
                                    message = Message(
                                        role = "assistant",
                                        content = "This is a mock response from OpenSLM Server. (Phase 1)"
                                    )
                                )
                            )
                        )
                        call.respond(response)
                    } catch (e: Exception) {
                        onLog("Error: ${e.message}")
                        call.respondText("Error: ${e.message}", status = io.ktor.http.HttpStatusCode.BadRequest)
                    }
                }
            }
        }.start(wait = false)
        onLog("Server started on port $port")
    }

    fun stop() {
        server?.stop(1000, 2000)
        onLog("Server stopped")
    }
}
