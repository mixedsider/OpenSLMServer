package com.example.openslmserver

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message as LiteMessage
import java.io.File

internal enum class Accelerator(val label: String) {
    AUTO("자동"), CPU("CPU"), GPU("GPU"), NPU("NPU")
}

internal data class ServerSettings(
    val port: Int,
    val contextTokens: Int,
    val outputTokens: Int,
    val accelerator: Accelerator,
    val allowLan: Boolean
)

internal interface InferenceEngine : AutoCloseable {
    val name: String
    val accelerator: Accelerator

    fun generate(messages: List<Message>, maxTokens: Int): String
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

    override fun generate(messages: List<Message>, maxTokens: Int): String {
        val instructions = messages.takeWhile { it.role == "system" }
        val history = messages.drop(instructions.size).dropLast(1).map {
            if (it.role == "assistant") LiteMessage.model(it.content) else LiteMessage.user(it.content)
        }
        val systemInstruction = instructions.joinToString("\n") { it.content }
            .takeIf { it.isNotBlank() }?.let { Contents.of(it) }
        val config = ConversationConfig(systemInstruction = systemInstruction, initialMessages = history)
        return engine.createConversation(config).use { conversation ->
            conversation.sendMessage(messages.last().content, maxOutputToken = maxTokens).toString()
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

    override fun generate(messages: List<Message>, maxTokens: Int): String =
        native.completion(
            pointer,
            messages.map { it.role }.toTypedArray(),
            messages.map { it.content.encodeToByteArray() }.toTypedArray(),
            maxTokens
        ).decodeToString()

    override fun close() = native.releaseContext(pointer)
}
