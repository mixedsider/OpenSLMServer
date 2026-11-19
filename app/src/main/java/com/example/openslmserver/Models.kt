package com.example.openslmserver

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class ChatRequest(
    val model: String? = null,
    val messages: List<Message>,
    val stream: Boolean = false,
    val max_tokens: Int? = null,
    val tools: List<JsonObject>? = null,
    val tool_choice: JsonElement? = null
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Message(
    val role: String,
    @Serializable(with = MessageContentSerializer::class)
    val content: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val tool_calls: List<ToolCall>? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val tool_call_id: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val name: String? = null
) {
    internal val isInstruction: Boolean get() = role == "system" || role == "developer"
}

internal object MessageContentSerializer : KSerializer<String> {
    override val descriptor = PrimitiveSerialDescriptor("ChatMessageContent", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)

    override fun deserialize(decoder: Decoder): String =
        when (val value = (decoder as JsonDecoder).decodeJsonElement()) {
            is JsonPrimitive -> value.stringValue() ?: throw SerializationException("Expected message text")
            is JsonArray -> value.joinToString("") { part ->
                val obj = part as? JsonObject
                if (obj?.get("type").stringValue() != "text") {
                    throw SerializationException("Only text content parts are supported")
                }
                obj?.get("text").stringValue() ?: throw SerializationException("Missing text content")
            }
            else -> throw SerializationException("Expected message text or an array of text parts")
        }
}

internal fun JsonElement?.stringValue(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content

@Serializable
data class ToolFunction(val name: String, val arguments: String)

@Serializable
data class ToolCall(val id: String, val type: String = "function", val function: ToolFunction)

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
internal data class ChatChunk(
    val id: String,
    val created: Long,
    val model: String,
    val choices: List<ChunkChoice>,
    val `object`: String = "chat.completion.chunk"
)

@Serializable
internal data class ChunkChoice(val index: Int, val delta: JsonObject, val finish_reason: String?)

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
