package com.example.openslmserver

import com.google.gson.JsonParser
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class ChatTools(request: ChatRequest) {
    private val definitions = request.tools.orEmpty().map { tool ->
        require(tool["type"].stringValue() == "function") { "Only function tools are supported" }
        val function = requireNotNull(tool["function"] as? JsonObject) { "Missing function definition" }
        require(function["name"].stringValue()?.matches(functionName) == true) { "Invalid function name" }
        require("parameters" !in function || function["parameters"] is JsonObject) { "Invalid function parameters" }
        function
    }
    private val byName = definitions.associateBy { it["name"].stringValue()!! }
    private val choice = request.tool_choice
    private val namedFunction = (choice as? JsonObject)?.takeIf { it["type"].stringValue() == "function" }
        ?.let { (it["function"] as? JsonObject)?.get("name").stringValue() }
    private val mode = choice.stringValue() ?: if (choice == null) "auto" else null

    val functions = when {
        namedFunction != null -> listOf(requireNotNull(byName[namedFunction]) { "Unknown function in tool_choice" })
        mode == "none" -> emptyList()
        mode == "auto" || mode == "required" -> definitions
        else -> throw IllegalArgumentException("Invalid tool_choice")
    }
    val required = mode == "required" || namedFunction != null
    private val allowedNames = functions.map { it["name"].stringValue() }.toSet()

    init {
        require(byName.size == definitions.size) { "Duplicate function name" }
        require(!required || functions.isNotEmpty()) { "tool_choice requires at least one function" }
    }

    fun instruction(withSchema: Boolean): String? {
        if (functions.isEmpty()) return null
        val behavior = if (required) "Call a listed function before answering." else
            "Call a listed function when needed, or respond with ordinary text."
        if (!withSchema) return behavior.takeIf { required }
        return """
            Available functions: ${JsonArray(functions)}
            $behavior To call a function, output only a JSON object such as
            {"name":"function_name","arguments":{}}. For multiple calls, output an array of those objects.
            Use only listed names and supply arguments according to their schemas.
            Use results labeled "Tool results (JSON)" as data for the next response.
        """.trimIndent()
    }

    fun response(text: String, nativeCalls: List<ToolFunction>): Message {
        val calls = nativeCalls.ifEmpty { if (functions.isEmpty()) emptyList() else parseTextCalls(text) }
        if (calls.isNotEmpty() && calls.all { it.name in allowedNames }) {
            return Message(
                role = "assistant",
                tool_calls = calls.map { ToolCall(id = "call_${UUID.randomUUID()}", function = it) }
            )
        }
        check(!required) { "The model did not return a call to a required function" }
        return Message("assistant", text)
    }

    private fun parseTextCalls(text: String): List<ToolFunction> {
        val body = text.trim().removePrefix("```json").removeSuffix("```").trim()
        val tagged = gemmaCall.findAll(body).toList()
        if (tagged.isNotEmpty()) {
            return tagged.map { call ->
                val args = gemmaString.replace(call.groupValues[2]) { JsonPrimitive(it.groupValues[1]).toString() }
                val parsed = runCatching { JsonParser.parseString(args).asJsonObject }.getOrNull()
                    ?: return emptyList()
                ToolFunction(call.groupValues[1], parsed.toString())
            }
        }
        val payload = runCatching { Json.parseToJsonElement(body) }.getOrNull()
        val calls = when (payload) {
            is JsonArray -> payload
            is JsonObject -> payload["tool_calls"] as? JsonArray ?: listOf(payload)
            else -> return emptyList()
        }
        return calls.map { element ->
            val call = element as? JsonObject ?: return emptyList()
            val function = call["function"] as? JsonObject ?: call
            val name = function["name"].stringValue() ?: return emptyList()
            val arguments = function["arguments"].asArguments() ?: return emptyList()
            ToolFunction(name, arguments.toString())
        }
    }

    companion object {
        private val functionName = Regex("[A-Za-z0-9_-]{1,64}")
        private val gemmaCall = Regex("""<\|tool_call>\s*call:([A-Za-z0-9_-]+)(\{.*?\})\s*<tool_call\|>""", RegexOption.DOT_MATCHES_ALL)
        private val gemmaString = Regex("""<\|"\|>(.*?)<\|"\|>""", RegexOption.DOT_MATCHES_ALL)
    }
}

internal fun parseArguments(arguments: String): JsonObject? =
    runCatching { Json.parseToJsonElement(arguments) as? JsonObject }.getOrNull()

private fun JsonElement?.asArguments(): JsonObject? =
    this as? JsonObject ?: stringValue()?.let(::parseArguments)

internal data class ToolResult(val name: String, val id: String, val content: String) {
    fun toJson() = buildJsonObject {
        put("name", name)
        put("tool_call_id", id)
        put("content", content)
    }
}

internal fun toolResult(message: Message, names: Map<String, String>): ToolResult {
    val id = requireNotNull(message.tool_call_id)
    return ToolResult(names.getValue(id), id, message.content.orEmpty())
}

internal fun toolNames(messages: List<Message>): Map<String, String> =
    messages.flatMap { it.tool_calls.orEmpty() }.associate { it.id to it.function.name }
