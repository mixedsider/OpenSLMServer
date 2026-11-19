package com.example.openslmserver

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message as LiteMessage
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.ToolCall as LiteToolCall
import com.google.ai.edge.litertlm.tool
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

internal enum class Accelerator(val label: String) {
    AUTO("자동"), CPU("CPU"), GPU("GPU"), NPU("NPU")
}

internal data class ServerSettings(
    val port: Int,
    val contextTokens: Int,
    val outputTokens: Int,
    val accelerator: Accelerator,
    val allowLan: Boolean,
    val logEnabled: Boolean = false
)

internal interface InferenceEngine : AutoCloseable {
    val name: String
    val accelerator: Accelerator

    suspend fun generate(
        messages: List<Message>,
        tools: ChatTools,
        maxTokens: Int,
        onText: suspend (String) -> Unit
    ): List<ToolFunction>
}

internal object InferenceEngines {
    private val qualcommModel = Regex("[_.](?:qualcomm[_.])?(sm[0-9]{4})\\.litertlm$", RegexOption.IGNORE_CASE)

    private val soc: String
        get() {
            val reported = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL.orEmpty() else ""
            return when (val model = reported.takeUnless { it.isBlank() || it == "unknown" }
                .orEmpty().ifBlank { Build.BOARD }.trim().uppercase()) {
                "LAHAINA", "SNAPDRAGON 888" -> "SM8350"
                else -> model
            }
        }

    private fun npuModelSoc(model: LocalModel): String? =
        qualcommModel.find(model.file.name)?.groupValues?.get(1)?.uppercase()

    fun npuUnavailableReason(context: Context, model: LocalModel?): String? {
        if (model == null || npuModelSoc(model) != soc) {
            return if (soc == "SM8350") {
                "Snapdragon 888 (SM8350)용 공식 Gemma 4 E2B NPU 모델은 확인되지 않았습니다."
            } else {
                "이 기기에 맞는 Qualcomm NPU 모델이 필요합니다."
            }
        }

        val libraryDir = File(context.applicationInfo.nativeLibraryDir)
        val libraries = libraryDir.list().orEmpty()
        val required = listOf("libLiteRtDispatch_Qualcomm.so", "libQnnHtp.so", "libQnnSystem.so")
        return if (required.any { it !in libraries } ||
            libraries.none { it.startsWith("libQnnHtp") && it.endsWith("Stub.so") } ||
            libraries.none { it.startsWith("libQnnHtp") && it.endsWith("Skel.so") }
        ) {
            "Qualcomm NPU 런타임이 이 APK에 포함되어 있어야 합니다."
        } else {
            null
        }
    }

    fun availableAccelerators(context: Context, model: LocalModel?): List<Accelerator> = when {
        model?.format == ModelFormat.GGUF -> listOf(Accelerator.CPU)
        model != null && npuModelSoc(model) != null ->
            if (npuUnavailableReason(context, model) == null) listOf(Accelerator.NPU) else emptyList()
        else -> listOf(Accelerator.CPU, Accelerator.GPU, Accelerator.AUTO)
    }

    fun load(context: Context, model: LocalModel, settings: ServerSettings): InferenceEngine {
        val available = availableAccelerators(context, model)
        require(settings.accelerator in available) {
            npuUnavailableReason(context, model) ?: "선택한 모델에서 지원하지 않는 가속기입니다."
        }
        if (model.format == ModelFormat.GGUF) return GgufEngine(model, settings.contextTokens)
        if (settings.accelerator != Accelerator.AUTO) {
            return LiteRtEngine(context, model, settings, settings.accelerator)
        }
        return try {
            LiteRtEngine(context, model, settings, Accelerator.GPU)
        } catch (error: Exception) {
            Log.w("OpenSLM", "LiteRT GPU initialization failed, trying CPU", error)
            LiteRtEngine(context, model, settings, Accelerator.CPU)
        }
    }
}

private class LiteRtEngine(
    context: Context,
    model: LocalModel,
    settings: ServerSettings,
    override val accelerator: Accelerator
) : InferenceEngine {
    override val name = "LiteRT-LM"

    private val engine = Engine(
        EngineConfig(
            modelPath = model.file.absolutePath,
            backend = when (accelerator) {
                Accelerator.CPU -> Backend.CPU(threadCount = 4)
                Accelerator.GPU -> Backend.GPU()
                Accelerator.NPU -> Backend.NPU(context.applicationInfo.nativeLibraryDir)
                Accelerator.AUTO -> error("Select a concrete backend")
            },
            maxNumTokens = settings.contextTokens.takeUnless { accelerator == Accelerator.NPU },
            cacheDir = context.cacheDir.absolutePath
        )
    )

    init {
        try {
            engine.initialize()
        } catch (error: Throwable) {
            runCatching { engine.close() }
            throw error
        }
    }

    private val gson = GsonBuilder().serializeNulls().create()

    override suspend fun generate(
        messages: List<Message>,
        tools: ChatTools,
        maxTokens: Int,
        onText: suspend (String) -> Unit
    ): List<ToolFunction> {
        val instructions = messages.takeWhile { it.isInstruction }
        val names = toolNames(messages)
        val turns = groupToolResults(messages.drop(instructions.size)).map { turn ->
            val message = turn.first()
            when (message.role) {
                "assistant" -> LiteMessage.model(
                    Contents.of(message.content?.let { listOf(Content.Text(it)) }.orEmpty()),
                    toolCalls = message.tool_calls.orEmpty().map { call ->
                        val arguments = JsonParser.parseString(call.function.arguments).asJsonObject.asMap()
                        LiteToolCall(call.function.name, arguments)
                    }
                )
                "tool" -> LiteMessage.tool(Contents.of(turn.map {
                    val result = toolResult(it, names)
                    Content.ToolResponse(result.name, result.content)
                }))
                else -> LiteMessage.user(message.content.orEmpty())
            }
        }
        val systemInstruction = (instructions.mapNotNull { it.content } + listOfNotNull(tools.instruction(false)))
            .joinToString("\n")
            .takeIf { it.isNotBlank() }?.let { Contents.of(it) }
        val config = ConversationConfig(
            systemInstruction = systemInstruction,
            initialMessages = turns.dropLast(1),
            tools = tools.functions.map { definition ->
                tool(object : OpenApiTool {
                    override fun getToolDescriptionJsonString() = definition.toString()
                    override fun execute(paramsJsonString: String): String =
                        error("Client tools are executed by the API client")
                })
            },
            automaticToolCalling = false
        )
        return engine.createConversation(config).use { conversation ->
            val calls = mutableListOf<ToolFunction>()
            // LiteRT-LM uses trySend in its callback. Retain this token-limited output if the client is slow.
            conversation.sendMessageAsync(turns.last(), maxOutputToken = maxTokens)
                .buffer(Channel.UNLIMITED)
                .collect { part ->
                    for (content in part.contents.contents.filterIsInstance<Content.Text>()) {
                        onText(content.text)
                    }
                    calls += part.toolCalls.map { ToolFunction(it.name, gson.toJson(it.arguments)) }
                }
            calls
        }
    }

    override fun close() = engine.close()
}

private class GgufEngine(model: LocalModel, contextTokens: Int) : InferenceEngine {
    override val name = "llama.cpp"
    override val accelerator = Accelerator.CPU
    private val native = LlamaNative()
    private val pointer = native.initContext(model.file.absolutePath.encodeToByteArray(), contextTokens).also {
        check(it != 0L) { "GGUF를 로드하지 못했습니다. 모델 호환성과 메모리를 확인하세요." }
    }

    override suspend fun generate(
        messages: List<Message>,
        tools: ChatTools,
        maxTokens: Int,
        onText: suspend (String) -> Unit
    ): List<ToolFunction> {
        val instructions = messages.takeWhile { it.isInstruction }
        val system = (instructions.mapNotNull { it.content } + listOfNotNull(tools.instruction(true)))
            .joinToString("\n")
        val names = toolNames(messages)
        val turns = groupToolResults(messages.drop(instructions.size)).map { turn ->
            val message = turn.first()
            when {
                message.role == "tool" -> {
                    val results = JsonArray(turn.map { toolResult(it, names).toJson() })
                    Message("user", "Tool results (JSON): $results")
                }
                !message.tool_calls.isNullOrEmpty() -> Message(
                    "assistant",
                    listOfNotNull(message.content, Json.encodeToString(message.tool_calls)).joinToString("\n")
                )
                else -> message
            }
        }
        val prompt = listOfNotNull(system.takeIf { it.isNotBlank() }?.let { Message("system", it) }) + turns
        coroutineScope {
            val chunks = Channel<ByteArray>(Channel.BUFFERED)
            launch(Dispatchers.IO) {
                try {
                    native.streamCompletion(
                        pointer,
                        prompt.map { it.role }.toTypedArray(),
                        prompt.map { it.content.orEmpty().encodeToByteArray() }.toTypedArray(),
                        maxTokens
                    ) { chunks.trySendBlocking(it).isSuccess }
                } finally {
                    chunks.close()
                }
            }
            try {
                for (chunk in chunks) onText(chunk.decodeToString())
            } finally {
                chunks.cancel()
            }
        }
        return emptyList()
    }

    override fun close() = native.releaseContext(pointer)
}

private fun groupToolResults(messages: List<Message>): List<List<Message>> = buildList {
    var index = 0
    while (index < messages.size) {
        val start = index++
        if (messages[start].role == "tool") {
            while (index < messages.size && messages[index].role == "tool") index++
        }
        add(messages.subList(start, index))
    }
}
