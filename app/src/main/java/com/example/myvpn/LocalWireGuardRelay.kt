package com.example.myvpn

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import kotlin.concurrent.thread

/** Keeps GoBackend's loopback endpoint stable. No upstream means drop, never direct fallback. */
internal class LocalWireGuardRelay : AutoCloseable {
    private val local = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = local.localPort
    private val lock = Any()
    private var upstream: DatagramSocket? = null
    private var client: InetSocketAddress? = null

    init {
        thread(name = "wg-relay-local", isDaemon = true) {
            val packet = DatagramPacket(ByteArray(65_535), 65_535)
            while (!local.isClosed) {
                try {
                    packet.length = packet.data.size
                    local.receive(packet)
                    synchronized(lock) {
                        val source = packet.socketAddress as InetSocketAddress
                        if (client == null) client = source
                        if (client == source) upstream?.send(DatagramPacket(packet.data, packet.length))
                    }
                } catch (_: java.io.IOException) { if (local.isClosed) break }
            }
        }
    }

    fun route(target: InetSocketAddress?) {
        val next = target?.let { address -> DatagramSocket().also { socket ->
            try { socket.connect(address) } catch (error: Exception) { socket.close(); throw error }
        } }
        synchronized(lock) {
            if (local.isClosed) { next?.close(); throw SocketException("relay closed") }
            upstream?.close()
            upstream = next
        }
        if (next == null) return
        thread(name = "wg-relay-upstream", isDaemon = true) {
            val packet = DatagramPacket(ByteArray(65_535), 65_535)
            while (!next.isClosed && !local.isClosed) {
                try {
                    packet.length = packet.data.size
                    next.receive(packet)
                    synchronized(lock) {
                        val destination = client
                        if (upstream === next && destination != null) local.send(DatagramPacket(packet.data, packet.length, destination))
                    }
                } catch (_: java.io.IOException) { if (next.isClosed || local.isClosed) break }
            }
        }
    }

    override fun close() = synchronized(lock) {
        local.close()
        upstream?.close()
        upstream = null
    }
}
