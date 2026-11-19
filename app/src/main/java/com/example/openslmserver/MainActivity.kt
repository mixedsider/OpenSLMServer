package com.example.openslmserver

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.example.openslmserver.databinding.ActivityMainBinding
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: ServerViewModel
    private var renderedModels: List<LocalModel>? = null
    private var modelOptions = emptyList<LocalModel>()
    private var renderedFile: String? = null
    private var accelerators: List<Accelerator> = emptyList()
    private var selectedAccelerator: Accelerator? = null

    private val openModel = registerForActivityResult(object : ActivityResultContracts.OpenDocument() {
        override fun createIntent(context: android.content.Context, input: Array<String>): Intent =
            super.createIntent(context, input).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                if (Build.VERSION.SDK_INT >= 26) {
                    putExtra(
                        DocumentsContract.EXTRA_INITIAL_URI,
                        DocumentsContract.buildRootUri("com.android.providers.downloads.documents", "downloads")
                    )
                }
            }
    }) { uri -> if (uri != null) viewModel.importModel(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.hide()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }

        viewModel = ViewModelProvider(this)[ServerViewModel::class.java]
        val preferences = viewModel.preferences
        binding.port.setText(preferences.getInt("port", 8080).toString())
        binding.contextTokens.setText(preferences.getInt("contextTokens", 1024).toString())
        binding.outputTokens.setText(preferences.getInt("outputTokens", 256).toString())
        binding.allowLan.isChecked = preferences.getBoolean("allowLan", true)
        selectedAccelerator = Accelerator.entries.find { it.name == preferences.getString("accelerator", "CPU") }

        binding.importModel.setOnClickListener { openModel.launch(arrayOf("*/*")) }
        binding.downloadModel.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ModelManager.DEFAULT_DOWNLOAD)))
            } catch (_: android.content.ActivityNotFoundException) {
                binding.statusDetail.setText(R.string.no_browser)
            }
        }
        binding.modelPicker.setOnItemClickListener { _, _, position, _ ->
            val file = modelOptions.getOrNull(position - 1)?.file?.name ?: ModelManager.DEFAULT_FILE
            viewModel.selectModel(file)
        }
        binding.acceleratorPicker.setOnItemClickListener { _, _, position, _ ->
            selectedAccelerator = accelerators.getOrNull(position)
            binding.contextLayout.isEnabled = selectedAccelerator != Accelerator.NPU
        }
        binding.serverAction.setOnClickListener {
            if (viewModel.state.value.running) viewModel.stop() else startServer()
        }

        lifecycleScope.launch {
            viewModel.state.collect { render(it) }
        }
    }

    private fun render(state: DashboardState) = with(binding) {
        if (renderedModels != state.models || renderedFile != state.selectedFile) {
            renderedModels = state.models
            renderedFile = state.selectedFile
            val model = state.models.find { it.file.name == state.selectedFile }
            val otherModels = state.models.filterNot { it.file.name == ModelManager.DEFAULT_FILE }
            modelOptions = otherModels
            val defaultLabel = if (state.models.any { it.file.name == ModelManager.DEFAULT_FILE }) {
                getString(R.string.default_model)
            } else {
                getString(R.string.default_model_missing)
            }
            modelPicker.setOptions(listOf(defaultLabel) + otherModels.map { it.label })
            modelPicker.setText(if (state.selectedFile == ModelManager.DEFAULT_FILE) defaultLabel else model?.label.orEmpty(), false)
            modelFormat.text = when {
                model == null -> getString(R.string.model_not_imported)
                model.format == ModelFormat.GGUF -> "llama.cpp · GGUF"
                else -> "LiteRT-LM · LiteRTLM"
            }
            accelerators = InferenceEngines.availableAccelerators(this@MainActivity, model)
            selectedAccelerator = selectedAccelerator?.takeIf { it in accelerators } ?: accelerators.firstOrNull()
            acceleratorPicker.setOptions(accelerators.map { it.label })
            acceleratorPicker.setText(selectedAccelerator?.label.orEmpty(), false)
            acceleratorNote.text = when (model?.format) {
                ModelFormat.GGUF -> getString(R.string.gguf_cpu)
                else -> InferenceEngines.npuUnavailableReason(this@MainActivity, model)
                    ?: getString(R.string.npu_ready)
            }
        }

        val canEdit = !state.running && !state.busy
        modelLayout.isEnabled = canEdit
        acceleratorLayout.isEnabled = canEdit && accelerators.size > 1
        contextLayout.isEnabled = canEdit && selectedAccelerator != Accelerator.NPU
        outputLayout.isEnabled = canEdit
        portLayout.isEnabled = canEdit
        allowLan.isEnabled = canEdit
        downloadModel.isEnabled = canEdit
        importModel.isEnabled = canEdit

        serverAction.isEnabled = !state.busy && (state.running ||
            accelerators.isNotEmpty() && state.models.any { it.file.name == state.selectedFile })
        serverAction.setText(if (state.running) R.string.stop_server else R.string.start_server)
        statusTitle.setText(
            when {
                state.busy -> R.string.status_working
                state.running -> R.string.status_running
                else -> R.string.status_stopped
            }
        )
        statusDetail.text = state.message.ifBlank { getString(R.string.status_idle) }
        operationProgress.isVisible = state.busy
        operationProgress.isIndeterminate = state.progress == null
        state.progress?.let { operationProgress.setProgressCompat(it, true) }
        endpoint.isVisible = state.running
        if (state.running) {
            val host = if (allowLan.isChecked) localAddress() ?: "127.0.0.1" else "127.0.0.1"
            endpoint.text = "http://$host:${port.text}/v1"
        }
    }

    private fun startServer() {
        val model = viewModel.state.value.let { state -> state.models.find { it.file.name == state.selectedFile } } ?: return
        val accelerator = selectedAccelerator ?: return
        val port = binding.port.text.toString().toIntOrNull()
        val context = if (accelerator == Accelerator.NPU) 1024 else binding.contextTokens.text.toString().toIntOrNull()
        val output = binding.outputTokens.text.toString().toIntOrNull()
        binding.portLayout.error = if (port == null || port !in 1024..65535) getString(R.string.port_range) else null
        binding.contextLayout.error = if (context == null || context < 512) getString(R.string.context_range) else null
        binding.outputLayout.error = if (output == null || context == null || output !in 1 until context) {
            getString(R.string.output_range)
        } else null
        if (port == null || context == null || output == null ||
            binding.portLayout.error != null || binding.contextLayout.error != null || binding.outputLayout.error != null
        ) return
        viewModel.start(model, ServerSettings(port, context, output, accelerator, binding.allowLan.isChecked))
    }

    private fun AutoCompleteTextView.setOptions(labels: List<String>) {
        setAdapter(ArrayAdapter(this@MainActivity, android.R.layout.simple_dropdown_item_1line, labels))
    }

    private fun localAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .sortedBy { !it.name.startsWith("wlan") }
            .asSequence()
            .flatMap { it.inetAddresses.toList().asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }?.hostAddress
    }.getOrNull()
}
