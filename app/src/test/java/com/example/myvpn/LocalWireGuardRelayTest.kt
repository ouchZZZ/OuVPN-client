package com.example.myvpn

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LocalWireGuardRelayTest {
    private val local = InetAddress.getByName("127.0.0.1")

    @Test fun switchAndPauseDoNotChangeClientEndpointOrForwardToTheOldRoute() {
        LocalWireGuardRelay().use { relay ->
            val stable = InetSocketAddress(local, relay.port)
            DatagramSocket(0, local).use { client ->
                DatagramSocket(0, local).use { first ->
                    DatagramSocket(0, local).use { second ->
                        client.soTimeout = 800; first.soTimeout = 800; second.soTimeout = 800
                        relay.route(first.localSocketAddress as InetSocketAddress)
                        exchange(client, stable, first, "first")
                        relay.route(null)
                        client.send(DatagramPacket(byteArrayOf(1), 1, stable))
                        first.soTimeout = 180
                        assertThrows(SocketTimeoutException::class.java) { first.receive(buffer()) }
                        relay.route(second.localSocketAddress as InetSocketAddress)
                        exchange(client, stable, second, "second")
                        assertThrows(SocketTimeoutException::class.java) { first.receive(buffer()) }
                    }
                }
            }
        }
    }

    private fun exchange(client: DatagramSocket, destination: InetSocketAddress, server: DatagramSocket, value: String) {
        val bytes = value.toByteArray()
        client.send(DatagramPacket(bytes, bytes.size, destination))
        val observed = buffer()
        server.receive(observed)
        assertArrayEquals(bytes, observed.data.copyOf(observed.length))
        server.send(observed)
        val echoed = buffer()
        client.receive(echoed)
        assertEquals(destination, echoed.socketAddress)
        assertArrayEquals(bytes, echoed.data.copyOf(echoed.length))
    }

    private fun buffer() = DatagramPacket(ByteArray(128), 128)
}
