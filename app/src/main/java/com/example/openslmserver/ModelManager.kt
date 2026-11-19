package com.example.openslmserver

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

internal enum class ModelFormat { LITERT, GGUF }

internal data class LocalModel(val file: File, val format: ModelFormat) {
    val id: String get() = if (file.name == ModelManager.DEFAULT_FILE) file.nameWithoutExtension else file.name
    val label: String get() = if (file.name == ModelManager.DEFAULT_FILE) "Gemma 4 E2B IT" else file.name
}

internal class ModelManager(private val context: Context) {
    companion object {
        const val DEFAULT_FILE = "gemma-4-E2B-it.litertlm"
        const val DEFAULT_DOWNLOAD = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/blob/main/$DEFAULT_FILE"

        private fun format(name: String): ModelFormat? = when (name.substringAfterLast('.').lowercase()) {
            "litertlm" -> ModelFormat.LITERT
            "gguf" -> ModelFormat.GGUF
            else -> null
        }
    }

    private val directory = File(context.filesDir, "models")

    fun listModels(): List<LocalModel> =
        listOf(directory, context.filesDir)
            .flatMap { it.listFiles().orEmpty().asList() }
            .mapNotNull { file -> format(file.name)?.takeIf { file.isFile }?.let { LocalModel(file, it) } }
            .distinctBy { it.file.name }
            .sortedWith(compareBy<LocalModel> { it.file.name != DEFAULT_FILE }.thenBy { it.file.name.lowercase() })

    suspend fun importModel(uri: Uri, onProgress: (Int?) -> Unit): LocalModel {
        val resolver = context.contentResolver
        val (displayName, size) = resolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null
        )?.use { cursor ->
            check(cursor.moveToFirst()) { "파일 정보를 읽을 수 없습니다." }
            cursor.getString(0) to if (cursor.isNull(1)) null else cursor.getLong(1)
        } ?: error("파일 정보를 읽을 수 없습니다.")

        val name = displayName?.substringAfterLast('/')?.substringAfterLast('\\')?.trim().orEmpty()
        val modelFormat = requireNotNull(format(name)) { ".litertlm 또는 .gguf 파일을 선택하세요." }
        check(name.length in 1..180) { "모델 파일 이름이 너무 깁니다." }
        check(directory.isDirectory || directory.mkdirs()) { "모델 저장 폴더를 만들 수 없습니다." }
        val target = generateSequence(1) { it + 1 }
            .map { File(directory, if (it == 1) name else "$it-$name") }
            .first { !it.exists() }
        check(size == null || size > 0 && directory.usableSpace >= size) {
            "파일이 비어 있거나 앱 저장 공간이 부족합니다."
        }

        val temporary = File.createTempFile("model-", ".part", directory)
        try {
            var copied = 0L
            var progress: Int? = null
            val input = requireNotNull(resolver.openInputStream(uri)) { "파일을 열 수 없습니다." }
            input.use { source ->
                temporary.outputStream().buffered().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = source.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        val percent = size?.takeIf { it > 0 }?.let { (copied * 100 / it).toInt().coerceAtMost(100) }
                        if (percent != progress) {
                            progress = percent
                            onProgress(percent)
                        }
                    }
                }
            }
            check(copied > 0 && (size == null || size == copied)) { "모델 파일을 끝까지 읽지 못했습니다." }
            check(temporary.renameTo(target)) { "모델 파일을 저장할 수 없습니다." }
            return LocalModel(target, modelFormat)
        } finally {
            temporary.delete()
        }
    }
}
