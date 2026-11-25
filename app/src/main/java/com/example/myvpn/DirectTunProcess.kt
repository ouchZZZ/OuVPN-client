package com.example.myvpn

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** The private Unix socket transfers the TUN FD and health commands, never app payload. */
internal class DirectTunProcess private constructor(
    private val process: Process,
    private val control: LocalSocket,
    private val directory: File,
) : AutoCloseable {
    val alive: Boolean get() = process.isAlive

    fun verifyEgress() {
        if (!alive) throw IOException("Node engine exited")
        control.soTimeout = 5_000
        control.outputStream.write('Q'.code)
        if (control.inputStream.read() != 'Y'.code || !alive) throw IOException("Node HTTPS check failed")
    }

    override fun close() {
        try { control.close() } finally {
            try { stop(process) } finally { removeSocketDirectory(directory) }
        }
    }

    companion object {
        fun start(context: Context, tun: ParcelFileDescriptor, node: ProxyNode): DirectTunProcess {
            val config = SingBoxConfig.nodeTunnel(node, serverAddress = InetAddress.getByName(node.host).hostAddress)
            val directory = Files.createTempDirectory(context.cacheDir.toPath(), "tun").toFile()
            val path = File(directory, "s").absolutePath
            var process: Process? = null
            var socket: LocalSocket? = null
            try {
                val builder = ProcessBuilder(File(context.applicationInfo.nativeLibraryDir, "libsingbox.so").absolutePath,
                    "run", "-c", "stdin", "--disable-color")
                    .directory(context.cacheDir).redirectOutput(File("/dev/null")).redirectError(File("/dev/null"))
                builder.environment()["OUVPN_TUN_SOCKET"] = path
                val child = builder.start().also { process = it }
                child.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(config) }
                val channel = connect(child, path).also { socket = it }
                if (channel.peerCredentials.uid != android.os.Process.myUid()) throw IOException("Unexpected TUN peer UID")
                channel.soTimeout = 4_000
                channel.setFileDescriptorsForSend(arrayOf(tun.fileDescriptor))
                try { channel.outputStream.write('T'.code) }
                finally { channel.setFileDescriptorsForSend(null) }
                if (channel.inputStream.read() != 'R'.code || !child.isAlive) throw IOException("TUN engine did not start")
                return DirectTunProcess(child, channel, directory)
            } catch (error: Exception) {
                runCatching { socket?.close() }
                try { process?.let(::stop) } finally { removeSocketDirectory(directory) }
                throw error
            }
        }

        private fun connect(process: Process, path: String): LocalSocket {
            val limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
            val address = LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM)
            while (process.isAlive && System.nanoTime() < limit) {
                val socket = LocalSocket()
                try {
                    socket.connect(address)
                    return socket
                } catch (_: IOException) {
                    socket.close()
                    Thread.sleep(25)
                }
            }
            throw IOException("Private TUN socket did not become available")
        }

        private fun stop(process: Process) {
            if (!process.isAlive) return
            process.destroyForcibly()
            if (!process.waitFor(2, TimeUnit.SECONDS)) throw IOException("TUN engine did not exit")
        }

        private fun removeSocketDirectory(directory: File) {
            File(directory, "s").delete()
            directory.delete()
        }
    }
}
