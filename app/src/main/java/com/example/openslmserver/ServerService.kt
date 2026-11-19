package com.example.openslmserver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class ServerStatus(
    val busy: Boolean = false,
    val endpoint: String? = null,
    val message: String = ""
)

internal class ServerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var startup: Job? = null
    private var server: KtorServer? = null
    private var serverLog: ServerLog? = null
    private var stopping = false
    private var destroyed = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> if (startup == null && !stopping) startServer(intent)
            ACTION_STOP -> shutdown()
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun startServer(intent: Intent) {
        try {
            createChannel()
            showNotification(getString(R.string.server_loading))
        } catch (error: Exception) {
            shutdown(errorMessage(this, error))
            return
        }

        startup = scope.launch {
            try {
                val settings = ServerSettings(
                    port = intent.getIntExtra("port", 8080),
                    contextTokens = intent.getIntExtra("contextTokens", 1024),
                    outputTokens = intent.getIntExtra("outputTokens", 256),
                    accelerator = Accelerator.valueOf(requireNotNull(intent.getStringExtra("accelerator"))),
                    allowLan = intent.getBooleanExtra("allowLan", false),
                    logEnabled = intent.getBooleanExtra("logEnabled", false)
                )
                val log = if (settings.logEnabled) ServerLog(noBackupFilesDir) else null
                serverLog = log
                val (activeServer, backend, endpoint) = withContext(Dispatchers.IO) {
                    log?.record("server.starting port=${settings.port} lan=${settings.allowLan}")
                    val model = checkNotNull(ModelManager(this@ServerService).listModels().firstOrNull {
                        it.file.name == intent.getStringExtra("model")
                    }) { getString(R.string.model_not_imported) }
                    val inference = InferenceEngines.load(this@ServerService, model, settings)
                    var candidate: KtorServer? = null
                    try {
                        candidate = KtorServer(model, inference, settings, log)
                        candidate.start()
                        val host = if (settings.allowLan) localAddress() ?: "127.0.0.1" else "127.0.0.1"
                        val backend = "${model.label} · ${inference.name} · ${inference.accelerator.label}"
                        log?.record("server.started format=${model.format} accelerator=${inference.accelerator}")
                        Triple(candidate, backend, "http://$host:${settings.port}/v1")
                    } catch (error: Throwable) {
                        if (candidate == null) runCatching { inference.close() } else runCatching { candidate.stop() }
                        throw error
                    }
                }
                server = activeServer
                if (!stopping) {
                    mutableStatus.value = ServerStatus(endpoint = endpoint, message = backend)
                    showNotification(getString(R.string.notification_server_running), endpoint)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                shutdownAfterStartError(error)
            } catch (error: LinkageError) {
                shutdownAfterStartError(error)
            }
        }
    }

    private suspend fun shutdownAfterStartError(error: Throwable) {
        withContext(Dispatchers.IO) { serverLog?.record("server.start_failed error=${error.javaClass.simpleName}") }
        shutdown(errorMessage(this, error))
    }

    private fun shutdown(message: String = getString(R.string.server_stopped)) {
        if (stopping) return
        stopping = true
        mutableStatus.value = status.value.copy(busy = true, message = getString(R.string.server_stopping))
        scope.launch {
            var result = message
            try {
                startup?.join()
                withContext(Dispatchers.IO) {
                    try {
                        server?.stop()
                        serverLog?.record("server.stopped")
                    } catch (error: Exception) {
                        serverLog?.record("server.stop_failed error=${error.javaClass.simpleName}")
                        throw error
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                result = errorMessage(this@ServerService, error)
            } finally {
                server = null
                if (!destroyed) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                mutableStatus.value = ServerStatus(message = result)
                scope.cancel()
            }
        }
    }

    override fun onDestroy() {
        destroyed = true
        shutdown()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun showNotification(title: String, endpoint: String? = null) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(title, endpoint),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        )
    }

    private fun notification(title: String, endpoint: String? = null): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopServer = PendingIntent.getService(this, 0, command(this, ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_server_status)
            .setContentTitle(title)
            .setContentText(endpoint)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(0, getString(R.string.stop_server), stopServer)
        if (endpoint != null) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(endpoint))
            val iconSize = (48 * resources.displayMetrics.density).toInt()
            ContextCompat.getDrawable(this, R.mipmap.ic_launcher)?.let {
                builder.setLargeIcon(it.toBitmap(iconSize, iconSize))
            }
        }
        return builder.build()
    }

    companion object {
        private const val ACTION_START = "com.example.openslmserver.START_SERVER"
        private const val ACTION_STOP = "com.example.openslmserver.STOP_SERVER"
        private const val CHANNEL_ID = "server_status"
        private const val NOTIFICATION_ID = 1
        private val mutableStatus = MutableStateFlow(ServerStatus())
        val status = mutableStatus.asStateFlow()

        fun start(context: Context, model: LocalModel, settings: ServerSettings) {
            if (status.value.busy || status.value.endpoint != null) return
            mutableStatus.value = ServerStatus(busy = true, message = context.getString(R.string.server_loading))
            try {
                ContextCompat.startForegroundService(
                    context,
                    command(context, ACTION_START)
                        .putExtra("model", model.file.name)
                        .putExtra("port", settings.port)
                        .putExtra("contextTokens", settings.contextTokens)
                        .putExtra("outputTokens", settings.outputTokens)
                        .putExtra("accelerator", settings.accelerator.name)
                        .putExtra("allowLan", settings.allowLan)
                        .putExtra("logEnabled", settings.logEnabled)
                )
            } catch (error: Exception) {
                mutableStatus.value = ServerStatus(message = errorMessage(context, error))
            }
        }

        fun stop(context: Context) {
            context.startService(command(context, ACTION_STOP))
        }

        fun clearMessage() {
            if (!status.value.busy && status.value.endpoint == null) mutableStatus.value = ServerStatus()
        }

        private fun command(context: Context, action: String) =
            Intent(context, ServerService::class.java).setAction(action)

        private fun errorMessage(context: Context, error: Throwable): String {
            Log.w("OpenSLM", "Server operation failed", error)
            return error.localizedMessage ?: context.getString(R.string.server_failure)
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
}
