package com.example.openslmserver

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class DashboardState(
    val models: List<LocalModel>,
    val selectedFile: String,
    val busy: Boolean = false,
    val endpoint: String? = null,
    val message: String = "",
    val progress: Int? = null
) {
    val running: Boolean get() = endpoint != null
}

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
    val state = combine(mutableState, ServerService.status, ::withServerStatus)
        .stateIn(scope, SharingStarted.Eagerly, withServerStatus(mutableState.value, ServerService.status.value))

    private fun withServerStatus(dashboard: DashboardState, server: ServerStatus) = dashboard.copy(
        busy = dashboard.busy || server.busy,
        endpoint = server.endpoint,
        message = server.message.ifBlank { dashboard.message }
    )

    private fun canEdit() = !mutableState.value.busy &&
        !ServerService.status.value.busy && ServerService.status.value.endpoint == null

    fun selectModel(fileName: String) {
        if (!canEdit()) return
        ServerService.clearMessage()
        preferences.edit().putString("model", fileName).apply()
        mutableState.update { it.copy(selectedFile = fileName, message = "") }
    }

    fun importModel(uri: Uri) {
        if (!canEdit()) return
        ServerService.clearMessage()
        mutableState.update { it.copy(busy = true, message = "모델을 가져오는 중…") }
        scope.launch {
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
        if (!canEdit()) return
        preferences.edit()
            .putInt("port", settings.port)
            .putInt("contextTokens", settings.contextTokens)
            .putInt("outputTokens", settings.outputTokens)
            .putString("accelerator", settings.accelerator.name)
            .putBoolean("allowLan", settings.allowLan)
            .apply()
        mutableState.update { it.copy(message = "") }
        ServerService.start(context, model, settings)
    }

    fun stop() {
        if (state.value.running && !state.value.busy) ServerService.stop(context)
    }

    override fun onCleared() {
        scope.cancel()
    }
}
