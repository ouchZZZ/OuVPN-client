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

internal class SingBoxProcess(private val process: Process, val port: Int) : AutoCloseable {
    val alive: Boolean get() = process.isAlive

    override fun close() {
        process.destroy()
        if (process.isAlive) process.destroyForcibly()
    }

    companion object {
        private val loopback = InetAddress.getByName("127.0.0.1")

        fun benchmark(context: Context, node: ProxyNode, startupMs: Long = 2_000): SingBoxProcess =
            proxy(context, startupMs) { SingBoxConfig.probe(node, it) }

        fun homeBenchmark(context: Context, home: SingBoxConfig.HomeProbe, node: ProxyNode?): SingBoxProcess =
            proxy(context, 2_000) { SingBoxConfig.homeProbe(home, node, it) }

        private fun proxy(context: Context, startupMs: Long, config: (Int) -> String): SingBoxProcess {
            val port = ServerSocket(0, 1, loopback).use { it.localPort }
            val box = launch(context, port, config(port))
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

        private fun launch(context: Context, port: Int, config: String): SingBoxProcess {
            val binary = File(context.applicationInfo.nativeLibraryDir, "libsingbox.so")
            val nullFile = File("/dev/null")
            val process = ProcessBuilder(binary.absolutePath, "run", "-c", "stdin", "--disable-color")
                .directory(context.cacheDir)
                .redirectOutput(nullFile)
                .redirectError(nullFile)
                .start()
            val box = SingBoxProcess(process, port)
            try {
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
    private const val DOWN_BYTES = 768 * 1024
    private const val UP_BYTES = 256 * 1024

    fun quick(context: Context, node: ProxyNode): Long? {
        val started = System.nanoTime()
        return SingBoxProcess.benchmark(context, node, 550).use { box ->
            val remaining = RoutePolicy.QUICK_TIMEOUT_MS - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            if (remaining <= 0 || Thread.currentThread().isInterrupted) return null
            val conn = request(proxy(box), "__down?bytes=64", remaining.toInt())
            try {
                if (conn.responseCode != 200 || conn.inputStream.use { it.read() } < 0) return null
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started).takeIf { it <= RoutePolicy.QUICK_TIMEOUT_MS }
            } finally { conn.disconnect() }
        }
    }

    fun measure(context: Context, node: ProxyNode): NodeSpeed = SingBoxProcess.benchmark(context, node).use { measure(it, false) }

    fun home(context: Context, spec: SingBoxConfig.HomeProbe, node: ProxyNode?, light: Boolean = false): NodeSpeed =
        SingBoxProcess.homeBenchmark(context, spec, node).use { measure(it, light) }

    private fun measure(box: SingBoxProcess, light: Boolean): NodeSpeed {
        val proxy = proxy(box)
        val requested = if (light) 128 * 1024 else DOWN_BYTES
        val conn = request(proxy, "__down?bytes=$requested")
        val latency: Long
        val download: Double
        try {
            val started = System.nanoTime()
            if (conn.responseCode != 200) throw IOException("Сервис замера недоступен.")
            conn.inputStream.use { input ->
                val first = input.read()
                if (first < 0) throw IOException("Сервис замера вернул пустой ответ.")
                val firstByte = System.nanoTime()
                latency = TimeUnit.NANOSECONDS.toMillis(firstByte - started)
                var total = 1
                val buffer = ByteArray(16384)
                while (total < requested) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    if (System.nanoTime() - started > TimeUnit.SECONDS.toNanos(20)) throw IOException("Загрузка для замера слишком медленная.")
                    val amount = input.read(buffer, 0, minOf(buffer.size, requested - total))
                    if (amount < 0) throw IOException("Сервис замера вернул неполный ответ.")
                    total += amount
                }
                download = speed(total, System.nanoTime() - firstByte)
            }
        } finally {
            conn.disconnect()
        }
        return NodeSpeed(download, if (light) null else upload(proxy), latency)
    }

    private fun upload(proxy: Proxy): Double? = runCatching {
            val up = request(proxy, "__up")
            try {
                up.requestMethod = "POST"
                up.doOutput = true
                up.setFixedLengthStreamingMode(UP_BYTES)
                up.setRequestProperty("Content-Type", "application/octet-stream")
                val start = System.nanoTime()
                up.outputStream.use { it.write(ByteArray(UP_BYTES)) }
                if (up.responseCode !in 200..299) throw IOException("Сервис не принял пробную загрузку.")
                speed(UP_BYTES, System.nanoTime() - start)
            } finally {
                up.disconnect()
            }
        }.getOrNull()

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
