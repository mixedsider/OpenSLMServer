package com.example.openslmserver

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class DashboardState(
    val models: List<LocalModel>,
    val selectedFile: String,
    val busy: Boolean = false,
    val running: Boolean = false,
    val message: String = "",
    val progress: Int? = null
)

internal class ServerViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application
    private val modelManager = ModelManager(application)
    val preferences = application.getSharedPreferences("server-settings", Application.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(
        DashboardState(
            models = modelManager.listModels(),
            selectedFile = preferences.getString("model", null) ?: ModelManager.DEFAULT_FILE
        )
    )
    val state = mutableState.asStateFlow()
    private var operation: Job? = null
    private var server: KtorServer? = null

    fun selectModel(fileName: String) {
        if (state.value.busy || state.value.running) return
        preferences.edit().putString("model", fileName).apply()
        mutableState.update { it.copy(selectedFile = fileName, message = "") }
    }

    fun importModel(uri: Uri) {
        if (state.value.busy || state.value.running) return
        mutableState.update { it.copy(busy = true, message = "모델을 가져오는 중…") }
        operation = scope.launch {
            try {
                val (model, models) = withContext(Dispatchers.IO) {
                    val imported = modelManager.importModel(uri) { percent ->
                        mutableState.update { it.copy(progress = percent) }
                    }
                    imported to modelManager.listModels()
                }
                preferences.edit().putString("model", model.file.name).apply()
                mutableState.update {
                    it.copy(models = models, selectedFile = model.file.name, message = "모델을 가져왔습니다.")
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.update { it.copy(message = error.localizedMessage ?: "모델을 가져오지 못했습니다.") }
            } finally {
                mutableState.update { it.copy(busy = false, progress = null) }
            }
        }
    }

    fun start(model: LocalModel, settings: ServerSettings) {
        if (state.value.busy || state.value.running) return
        preferences.edit()
            .putInt("port", settings.port)
            .putInt("contextTokens", settings.contextTokens)
            .putInt("outputTokens", settings.outputTokens)
            .putString("accelerator", settings.accelerator.name)
            .putBoolean("allowLan", settings.allowLan)
            .apply()
        mutableState.update { it.copy(busy = true, message = "모델을 로드하는 중…") }
        operation = scope.launch {
            try {
                val backend = withContext(Dispatchers.IO) {
                    val inference = InferenceEngines.load(context, model, settings)
                    var candidate: KtorServer? = null
                    try {
                        candidate = KtorServer(model, inference, settings)
                        candidate.start()
                        server = candidate
                        "${inference.name} · ${inference.accelerator.label}"
                    } catch (error: Throwable) {
                        if (candidate == null) runCatching { inference.close() } else runCatching { candidate.stop() }
                        throw error
                    }
                }
                mutableState.update { it.copy(running = true, message = "${model.label} · $backend") }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                showServerError(error)
            } catch (error: LinkageError) {
                showServerError(error)
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
        }
    }

    fun stop() {
        if (!state.value.running || state.value.busy) return
        mutableState.update { it.copy(busy = true, message = "서버를 중지하는 중…") }
        operation = scope.launch {
            try {
                withContext(Dispatchers.IO) { closeServer() }
                mutableState.update { it.copy(message = "서버가 중지되었습니다.") }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                showServerError(error)
            } finally {
                mutableState.update { it.copy(busy = false, running = false) }
            }
        }
    }

    private fun showServerError(error: Throwable) {
        Log.w("OpenSLM", "Server operation failed", error)
        mutableState.update { it.copy(message = error.localizedMessage ?: "서버 실행에 실패했습니다.") }
    }

    private suspend fun closeServer() {
        val current = server ?: return
        server = null
        current.stop()
    }

    override fun onCleared() {
        scope.launch {
            try {
                operation?.join()
                withContext(Dispatchers.IO) { closeServer() }
            } finally {
                scope.cancel()
            }
        }
    }
}
