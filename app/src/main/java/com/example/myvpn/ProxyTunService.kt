package com.example.myvpn

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.VpnService
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor

import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Android TUN for plain proxy mode; the foreground controller owns the descriptor. */
class ProxyTunService : VpnService() {
    private var onRevoked: (() -> Unit)? = null
    private var revoked = false

    private inner class Control : Binder() {
        fun open(excluded: Set<String>, revokedCallback: () -> Unit): ParcelFileDescriptor = synchronized(this@ProxyTunService) {
            if (revoked) throw IOException("VPN permission revoked")
            val builder = Builder().setSession("ouVPN client").setMtu(SingBoxConfig.TUN_MTU)
                .addAddress(SingBoxConfig.TUN_IPV4, SingBoxConfig.TUN_IPV4_PREFIX).addRoute("0.0.0.0", 0)
                .addAddress(SingBoxConfig.TUN_IPV6, SingBoxConfig.TUN_IPV6_PREFIX).addRoute("::", 0)
                .addDnsServer("1.1.1.1").addDisallowedApplication(packageName)
            excluded.filter { it != packageName }.forEach {
                try { builder.addDisallowedApplication(it) }
                catch (_: android.content.pm.PackageManager.NameNotFoundException) { /* app uninstalled */ }
            }
            val tun = builder.establish() ?: throw IOException("Android did not establish the proxy VPN")
            onRevoked = revokedCallback
            tun
        }
    }

    override fun onBind(intent: Intent): IBinder? = if (intent.action == CONTROL) Control() else super.onBind(intent)

    override fun onRevoke() {
        val callback = synchronized(this) { revoked = true; onRevoked.also { onRevoked = null } }
        callback?.invoke()
        super.onRevoke()
    }

    companion object {
        private const val CONTROL = "com.example.myvpn.NODE_TUN"

        internal fun open(context: Context, excluded: Set<String>, onLost: () -> Unit): ProxyTun {
            if (Build.VERSION.SDK_INT < 29) throw IOException("Режим через ноду требует Android 10 или новее.")
            val app = context.applicationContext
            val result = CompletableFuture<Control>()
            val closing = AtomicBoolean(false)
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    val control = service as? Control
                    if (control != null) result.complete(control)
                    else result.completeExceptionally(IOException("Missing proxy VPN controller"))
                }
                override fun onNullBinding(name: ComponentName) {
                    result.completeExceptionally(IOException("Missing proxy VPN binding"))
                }
                override fun onServiceDisconnected(name: ComponentName) { if (!closing.get()) onLost() }
                override fun onBindingDied(name: ComponentName) { if (!closing.get()) onLost() }
            }
            if (!app.bindService(Intent(app, ProxyTunService::class.java).setAction(CONTROL), connection, Context.BIND_AUTO_CREATE)) {
                throw IOException("Could not bind proxy VPN")
            }
            val release: () -> Unit = {
                if (closing.compareAndSet(false, true)) runCatching { app.unbindService(connection) }
                Unit
            }
            try {
                val fd = result.get(3, TimeUnit.SECONDS).open(excluded) { if (!closing.get()) onLost() }
                return ProxyTun(fd, release)
            } catch (e: Exception) { release(); throw e }
        }
    }
}

/** Android keeps its original descriptor while the selected engine owns a duplicate. */
internal class ProxyTun(val fd: ParcelFileDescriptor, private val unbind: () -> Unit) : AutoCloseable {
    override fun close() {
        try { fd.close() } finally { unbind() }
    }
}
