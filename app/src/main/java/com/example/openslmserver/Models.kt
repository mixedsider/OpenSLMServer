package com.example.openslmserver

import kotlinx.serialization.Serializable

@Serializable
data class ChatRequest(
    val model: String? = null,
    val messages: List<Message>,
    val stream: Boolean = false,
    val max_tokens: Int? = null
)

@Serializable
data class Message(
    val role: String,
    val content: String
)

@Serializable
data class ChatResponse(
    val id: String,
    val `object`: String = "chat.completion",
    val created: Long,
    val model: String,
    val choices: List<Choice>,
    val usage: Usage? = null
)

@Serializable
data class Choice(
    val index: Int,
    val message: Message,
    val finish_reason: String? = "stop"
)

@Serializable
data class Usage(
    val prompt_tokens: Int,
    val completion_tokens: Int,
    val total_tokens: Int
)

@Serializable
internal data class ModelList(val data: List<ModelEntry>, val `object`: String = "list")

@Serializable
internal data class ModelEntry(val id: String, val `object`: String = "model", val owned_by: String = "local")

@Serializable
internal data class ApiError(val error: ErrorMessage)

@Serializable
internal data class ErrorMessage(val message: String)
