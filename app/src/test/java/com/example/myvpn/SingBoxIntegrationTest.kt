package com.example.myvpn

import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class SingBoxIntegrationTest {
    private val loopback = InetAddress.getByName("127.0.0.1")
    private val binary = System.getenv("SING_BOX_TEST_BINARY")?.let(::File)
    private val uuid = "00000000-0000-0000-0000-000000000001"

    @Test fun vlessXudpRelaysBidirectionalUdpThroughTheConfiguredNode() {
        val executable = binary
        assumeTrue("Set SING_BOX_TEST_BINARY to sing-box 1.14.4", executable?.isFile == true)
        checkNotNull(executable)
        val vlessPort = ServerSocket(0, 1, loopback).use { it.localPort }
        val relayPort = DatagramSocket(0, loopback).use { it.localPort }
        val node = SubscriptionParser.parse("vless://$uuid@127.0.0.1:$vlessPort?security=none&type=tcp").single()
        DatagramSocket(0, loopback).use { echo ->
            echo.soTimeout = 5_000
            val echoWorker = thread(isDaemon = true) {
                runCatching {
                    val received = DatagramPacket(ByteArray(128), 128)
                    echo.receive(received)
                    echo.send(received)
                }
            }
            val server = run(executable, "run", """
                {"log":{"disabled":true},"inbounds":[{"type":"vless","listen":"127.0.0.1","listen_port":$vlessPort,"users":[{"uuid":"$uuid"}]}],"outbounds":[{"type":"direct","tag":"local-echo"}],"route":{"final":"local-echo"}}
            """.trimIndent())
            try {
                assertTrue((1..50).any {
                    Thread.sleep(30)
                    runCatching { Socket().use { it.connect(InetSocketAddress(loopback, vlessPort), 100) } }.isSuccess
                })
                val client = run(executable, "run", SingBoxConfig.relay(node, relayPort, "127.0.0.1", echo.localPort))
                try {
                    Thread.sleep(300)
                    assertTrue("relay started", client.isAlive)
                    DatagramSocket(0, loopback).use { socket ->
                        socket.soTimeout = 3_000
                        val payload = "probe-through-xudp".toByteArray()
                        socket.send(DatagramPacket(payload, payload.size, loopback, relayPort))
                        val reply = DatagramPacket(ByteArray(128), 128)
                        socket.receive(reply)
                        assertArrayEquals(payload, reply.data.copyOf(reply.length))
                    }
                } finally {
                    client.destroyForcibly()
                }
            } finally {
                server.destroyForcibly()
                echo.close()
                echoWorker.join(1_000)
            }
        }
    }

    @Test fun singBoxAcceptsHy2BenchmarkConfig() {
        val executable = binary
        assumeTrue("Set SING_BOX_TEST_BINARY to sing-box 1.14.4", executable?.isFile == true)
        checkNotNull(executable)
        val node = SubscriptionParser.parse("hy2://test@127.0.0.1:443?sni=example.test").single()
        val process = run(executable, "check", SingBoxConfig.probe(node, 24567))
        try {
            assertTrue(process.waitFor(5, TimeUnit.SECONDS))
            assertEquals(process.inputStream.bufferedReader().readText(), 0, process.exitValue())
        } finally {
            process.destroyForcibly()
        }
    }

    private fun run(executable: File, action: String, config: String): Process =
        ProcessBuilder(executable.absolutePath, action, "-c", "stdin", "--disable-color").redirectErrorStream(true)
            .start().also { process -> process.outputStream.bufferedWriter().use { it.write(config) } }
}
