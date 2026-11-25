package com.example.myvpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.wireguard.config.Config
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class MyVpnService : Service() {
    internal enum class Mode { AUTO, MANUAL, TEST }
    internal class Selection(val config: Config, val mode: Mode, val route: HomeRoute? = null, val excluded: Set<String> = emptySet())
    internal data class Status(val active: Boolean, val text: String, val busy: Boolean = false,
        val mode: Mode? = null, val routeKey: String? = null)

    companion object {
        private const val CHANNEL = "home_wireguard"
        private const val STOP = "com.example.myvpn.STOP"
        internal const val CHECK = "com.example.myvpn.CHECK"
        private val mutableStatus = MutableStateFlow(Status(false, "VPN отключён"))
        internal val status = mutableStatus.asStateFlow()
        private val mutableReport = MutableStateFlow(RouteReport())
        internal val report = mutableReport.asStateFlow()
        @Volatile internal var selection: Selection? = null
    }

    private val worker = Executors.newSingleThreadScheduledExecutor()
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private val session by lazy { VpnSession(this, ::publish, { mutableReport.value = it }, { stopSelf() }) }
    @Volatile private var stopping = false
    @Volatile private var operation: java.util.concurrent.Future<*>? = null
    private var networkWork: ScheduledFuture<*>? = null
    private var monitoring = false
    private val networkEvents = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) queueNetworkCheck()
        }
    }

    @Synchronized private fun queueNetworkCheck() {
        if (stopping) return
        networkWork?.cancel(false)
        networkWork = worker.schedule({ if (!stopping) session.onNetworkChanged() }, 1_200, TimeUnit.MILLISECONDS)
    }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Домашний WireGuard", NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val fullCheck = intent?.action == CHECK
        val request = if (fullCheck) null else selection.also { selection = null }
        if (!fullCheck && request == null) {
            if (!monitoring) stopSelf()
            return START_NOT_STICKY
        }
        if (operation?.isDone == false || mutableStatus.value.busy) return START_NOT_STICKY
        val pending = mutableStatus.value.copy(busy = true, text = if (fullCheck || request?.mode == Mode.TEST)
            "Готовим полную проверку…" else "Готовим WireGuard…")
        mutableStatus.value = pending
        startForeground(1, notification(pending.text))
        if (!monitoring) {
            monitoring = true
            connectivity.registerDefaultNetworkCallback(networkEvents)
            worker.scheduleWithFixedDelay({ if (!stopping) session.health() }, 2, 2, TimeUnit.SECONDS)
        }
        operation = worker.submit { if (!stopping) session.run(request, fullCheck) }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        synchronized(this) {
            stopping = true
            networkWork?.cancel(false)
        }
        if (monitoring) runCatching { connectivity.unregisterNetworkCallback(networkEvents) }
        worker.shutdownNow()
        if (mutableStatus.value.active || mutableStatus.value.busy) {
            mutableStatus.value = mutableStatus.value.copy(text = "Останавливаем…", busy = true)
        }
        Thread({
            // Wait for the interrupted worker before releasing the same tunnel/relay it was operating on.
            while (!worker.awaitTermination(5, TimeUnit.SECONDS)) { /* bounded network reads can outlive interruption */ }
            runCatching { session.close() }
            if (mutableStatus.value.active || mutableStatus.value.busy) mutableStatus.value = Status(false, "VPN отключён")
        }, "ouvpn-close").start()
        super.onDestroy()
    }

    private fun publish(status: Status) {
        if (!stopping && mutableStatus.value != status) {
            mutableStatus.value = status
            getSystemService(NotificationManager::class.java).notify(1, notification(status.text))
        }
    }

    private fun notification(text: String) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_ouvpn_status)
        .setContentTitle(getString(R.string.app_name))
        .setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setOnlyAlertOnce(true)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        .addAction(0, "Отключить", PendingIntent.getService(this, 1, Intent(this, MyVpnService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE))
        .setOngoing(true)
        .build()
}
