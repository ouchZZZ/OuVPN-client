package com.example.myvpn

import android.content.Context
import java.io.File
import java.io.IOException
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal class SingBoxProcess(private val process: Process, val port: Int) : AutoCloseable {
    val alive: Boolean get() = process.isAlive

    override fun close() {
        process.destroy()
        if (process.isAlive) process.destroyForcibly()
    }

    private fun abortProbe() {
        process.destroyForcibly()
        val interrupted = Thread.interrupted()
        try {
            if (!process.waitFor(300, TimeUnit.MILLISECONDS)) throw IOException("Не удалось остановить процесс замера.")
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    companion object {
        private val loopback = InetAddress.getByName("127.0.0.1")

        fun benchmark(context: Context, node: ProxyNode, startupMs: Long = 2_000, owner: TimedSpeedProbe? = null): SingBoxProcess =
            proxy(context, startupMs, owner) { SingBoxConfig.probe(node, it, InetAddress.getByName(node.host).hostAddress!!) }

        fun homeBenchmark(context: Context, home: SingBoxConfig.HomeProbe, node: ProxyNode?, owner: TimedSpeedProbe): SingBoxProcess =
            proxy(context, 2_000, owner) { SingBoxConfig.homeProbe(home, node, it) }


        private fun proxy(context: Context, startupMs: Long, owner: TimedSpeedProbe?, config: (Int) -> String): SingBoxProcess {
            val port = ServerSocket(0, 1, loopback).use { it.localPort }
            val box = launch(context, port, config(port), owner)
            val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(startupMs)
            try {
                while (System.nanoTime() < end) {
                    if (!box.alive) throw IOException("Не удалось запустить транспорт ноды.")
                    if (runCatching { Socket().use { it.connect(InetSocketAddress(loopback, port), 100) } }.isSuccess) return box
                    Thread.sleep(50)
                }
                throw IOException("Нода не запустилась вовремя.")
            } catch (error: Exception) {
                box.close()
                throw error
            }
        }

        fun relay(context: Context, node: ProxyNode, targetHost: String, targetPort: Int): SingBoxProcess {
            val port = DatagramSocket(0, loopback).use { it.localPort }
            val box = launch(context, port, SingBoxConfig.relay(node, port, targetHost, targetPort))
            try {
                if (box.process.waitFor(300, TimeUnit.MILLISECONDS)) {
                    throw IOException("Не удалось запустить UDP-транспорт выбранной ноды.")
                }
                return box
            } catch (error: Exception) {
                box.close()
                throw error
            }
        }

        private fun launch(context: Context, port: Int, config: String, owner: TimedSpeedProbe? = null): SingBoxProcess {
            val binary = File(context.applicationInfo.nativeLibraryDir, "libsingbox.so")
            val nullFile = File("/dev/null")
            val process = ProcessBuilder(binary.absolutePath, "run", "-c", "stdin", "--disable-color")
                .directory(context.cacheDir)
                .redirectOutput(nullFile)
                .redirectError(nullFile)
                .start()
            val box = SingBoxProcess(process, port)
            try {
                owner?.own(AutoCloseable { box.abortProbe() })
                process.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(config) }
                return box
            } catch (error: Exception) {
                box.close()
                throw error
            }
        }
    }
}

internal data class NodeSpeed(val downloadMbps: Double, val uploadMbps: Double?, val latencyMs: Long)

internal object SpeedCheck {
    private const val PING_BUDGET_MS = 1_500L
    private const val NODE_BUDGET_MS = 3_000L
    private const val WIREGUARD_BUDGET_MS = 5_000L
    private const val DOWN_BYTES = 4 * 1024 * 1024
    private const val UP_BYTES = 256 * 1024

    fun ping(context: Context, node: ProxyNode): Long? {
        val attempt = TimedSpeedProbe(PING_BUDGET_MS, ping = true)
        return attempt.run(onTimeout = { null }) {
            val box = SingBoxProcess.benchmark(context, node, owner = attempt)
            val conn = request(proxy(box), "__down?bytes=64")
            val started = System.nanoTime()
            try {
                if (conn.responseCode != 200 || conn.inputStream.use { it.read() } < 0) null
                else TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            } finally { conn.disconnect() }
        }
    }

    fun measure(context: Context, node: ProxyNode, light: Boolean = false, includeUpload: Boolean = false): NodeSpeed? =
        measure(NODE_BUDGET_MS, light, includeUpload) { SingBoxProcess.benchmark(context, node, owner = it) }

    fun home(context: Context, spec: SingBoxConfig.HomeProbe, node: ProxyNode?, light: Boolean = false, includeUpload: Boolean = false): NodeSpeed? =
        measure(WIREGUARD_BUDGET_MS, light, includeUpload) { SingBoxProcess.homeBenchmark(context, spec, node, it) }

    private fun measure(budgetMs: Long, light: Boolean, includeUpload: Boolean, start: (TimedSpeedProbe) -> SingBoxProcess): NodeSpeed? {
        val attempt = TimedSpeedProbe(budgetMs)
        val sample = AtomicReference<DownloadWindow>()
        val down = attempt.run(onTimeout = { sample.get()?.sample(attempt.deadline, complete = false) }) {
            download(proxy(start(attempt)), attempt.deadline, sample, requested = if (light) 128 * 1024 else DOWN_BYTES)
        } ?: return null
        if (!includeUpload) return down
        val uploadAttempt = TimedSpeedProbe(budgetMs)
        val up = NodeSurvey.attempt {
            uploadAttempt.run(onTimeout = { null }) { upload(proxy(start(uploadAttempt))) }
        }
        return down.copy(uploadMbps = up)
    }

    private fun download(proxy: Proxy, deadline: Long, sample: AtomicReference<DownloadWindow>, requested: Int): NodeSpeed? {
        val conn = request(proxy, "__down?bytes=$requested")
        val window = DownloadWindow(System.nanoTime(), deadline).also(sample::set)
        try {
            if (conn.responseCode != 200) return null
            conn.inputStream.use { input ->
                if (input.read() < 0) return null
                window.record(1, System.nanoTime())
                var total = 1
                val buffer = ByteArray(16384)
                while (total < requested && System.nanoTime() < deadline) {
                    NodeSurvey.interrupted()
                    val amount = input.read(buffer, 0, minOf(buffer.size, requested - total))
                    if (amount < 0) return null
                    window.record(amount, System.nanoTime())
                    total += amount
                }
                return window.sample(System.nanoTime(), complete = total == requested)
            }
        } finally { conn.disconnect() }
    }

    private fun upload(proxy: Proxy): Double? {
        val up = request(proxy, "__up")
        try {
            up.requestMethod = "POST"
            up.doOutput = true
            up.setFixedLengthStreamingMode(UP_BYTES)
            up.setRequestProperty("Content-Type", "application/octet-stream")
            val start = System.nanoTime()
            up.outputStream.use { it.write(ByteArray(UP_BYTES)) }
            if (up.responseCode !in 200..299) return null
            return speed(UP_BYTES, System.nanoTime() - start)
        } finally { up.disconnect() }
    }

    private fun proxy(box: SingBoxProcess) = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", box.port))

    private fun request(proxy: Proxy, path: String, timeoutMs: Int = 7_000) =
        (URL("https://${SingBoxConfig.BENCHMARK}/$path").openConnection(proxy) as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = false
            useCaches = false
        }

    private fun speed(bytes: Int, nanos: Long): Double = bytes * 8000.0 / nanos.coerceAtLeast(1_000_000)
}
