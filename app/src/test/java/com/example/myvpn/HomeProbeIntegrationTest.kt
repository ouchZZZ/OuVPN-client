package com.example.myvpn

import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class HomeProbeIntegrationTest {
    private val loop = InetAddress.getByName("127.0.0.1")
    private val uuid = "00000000-0000-0000-0000-000000000002"

    @Test fun realWireGuardProbeReachesHttpOriginDirectlyAndThroughXudp() {
        val file = System.getenv("SING_BOX_TEST_BINARY")?.let(::File)
        assumeTrue("Set SING_BOX_TEST_BINARY to sing-box 1.14.4", file?.isFile == true)
        val binary = checkNotNull(file)
        val server = keys(binary)
        val client = keys(binary)
        val wgPort = DatagramSocket(0, loop).use { it.localPort }
        val proxyPort = freeTcp()
        val marker = "only-through-wireguard"
        DatagramSocket(0, loop).use { resolver ->
            ServerSocket(0, 4, loop).use { origin ->
                val dnsThread = thread(isDaemon = true) { serveDns(resolver) }
                val httpThread = thread(isDaemon = true) { serveHttp(origin, marker) }
                val serverConfig = """
                    {"log":{"level":"warn"},"inbounds":[{"type":"vless","listen":"127.0.0.1","listen_port":$proxyPort,"users":[{"uuid":"$uuid"}]}],
                    "endpoints":[{"type":"wireguard","tag":"wg-server","system":false,"address":["10.55.0.1/24"],"listen_port":$wgPort,
                      "private_key":"${server.first}","peers":[{"public_key":"${client.second}","allowed_ips":["10.55.0.2/32"]}]}],
                    "outbounds":[{"type":"direct","tag":"direct"}],"route":{"final":"direct","rules":[
                      {"inbound":"wg-server","ip_cidr":"198.18.0.53/32","action":"route","outbound":"direct","override_address":"127.0.0.1","override_port":${resolver.localPort}},
                      {"inbound":"wg-server","ip_cidr":"198.18.0.80/32","action":"route","outbound":"direct","override_address":"127.0.0.1","override_port":${origin.localPort}},
                      {"inbound":"wg-server","action":"reject"}]}}
                """.trimIndent()
                withBox(binary, serverConfig) { serverProc ->
                    waitReady(serverProc, proxyPort)
                    val home = SingBoxConfig.HomeProbe("127.0.0.1", wgPort, listOf("10.55.0.2/32"), client.first, server.second, null, 1280)
                    val node = SubscriptionParser.parse("vless://$uuid@127.0.0.1:$proxyPort?type=tcp").single()
                    for (relay in listOf(null, node)) {
                        val localPort = freeTcp()
                        val production = SingBoxConfig.homeProbe(home, relay, localPort)
                        val check = start(binary, "check", production)
                        try {
                            assertTrue("production config check timed out", check.waitFor(5, TimeUnit.SECONDS))
                            assertEquals(check.inputStream.bufferedReader().readText(), 0, check.exitValue())
                        } finally { check.destroyForcibly() }
                        // Substitute a deterministic private resolver for public Cloudflare DoH in this fixture.
                        val fixture = JSONObject(production)
                        val dns = fixture.getJSONObject("dns")
                        dns.put("servers", JSONArray().put(dns.getJSONArray("servers").getJSONObject(0))
                            .put(JSONObject().put("type", "udp").put("tag", "inside").put("server", "198.18.0.53").put("detour", "home")))
                        withBox(binary, fixture.toString()) { proc ->
                            waitReady(proc, localPort)
                            val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(loop, localPort))
                            val connection = URL("http://${SingBoxConfig.BENCHMARK}/fixture").openConnection(proxy)
                            connection.connectTimeout = 4000; connection.readTimeout = 6000
                            assertEquals(marker, connection.inputStream.bufferedReader().use { it.readText() })
                        }
                    }
                }
                resolver.close(); origin.close(); dnsThread.join(500); httpThread.join(500)
            }
        }
    }

    private fun serveDns(socket: DatagramSocket) {
        while (!socket.isClosed) try {
            val packet = DatagramPacket(ByteArray(2048), 2048)
            socket.receive(packet)
            val data = packet.data
            var end = 12
            while (end < packet.length && data[end].toInt() != 0) end += (data[end].toInt() and 255) + 1
            if (end + 5 > packet.length) continue
            val isA = data[end + 2].toInt() == 1
            val header = byteArrayOf(data[0], data[1], 0x81.toByte(), 0x80.toByte(), 0, 1, 0, if (isA) 1 else 0, 0, 0, 0, 0)
            val answer = if (isA) byteArrayOf(0xc0.toByte(), 12, 0, 1, 0, 1, 0, 0, 0, 10, 0, 4, 198.toByte(), 18, 0, 80) else byteArrayOf()
            val payload = header + data.copyOfRange(12, end + 5) + answer
            socket.send(DatagramPacket(payload, payload.size, packet.socketAddress))
        } catch (_: java.io.IOException) { if (socket.isClosed) return }
    }

    private fun serveHttp(server: ServerSocket, marker: String) {
        while (!server.isClosed) try {
            server.accept().use { connection ->
                connection.soTimeout = 3000
                val source = connection.getInputStream().bufferedReader()
                while (!source.readLine().isNullOrEmpty()) { /* drain headers */ }
                connection.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: ${marker.length}\r\nConnection: close\r\n\r\n$marker".toByteArray())
            }
        } catch (_: java.io.IOException) { if (server.isClosed) return }
    }

    private fun keys(binary: File): Pair<String, String> {
        val process = ProcessBuilder(binary.absolutePath, "generate", "wg-keypair").redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readLines() }
        check(process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0)
        fun find(name: String) = output.first { it.substringBefore(':').contains(name, true) }.substringAfter(':').trim()
        return find("private") to find("public")
    }

    private fun freeTcp() = ServerSocket(0, 1, loop).use { it.localPort }
    private fun start(binary: File, command: String, config: String): Process = ProcessBuilder(binary.absolutePath, command, "-c", "stdin", "--disable-color")
        .redirectErrorStream(true).start().also { proc -> proc.outputStream.bufferedWriter().use { it.write(config) } }
    private fun withBox(binary: File, config: String, use: (Process) -> Unit) {
        val process = start(binary, "run", config)
        try { use(process) } finally { process.destroyForcibly(); process.waitFor(2, TimeUnit.SECONDS) }
    }
    private fun waitReady(process: Process, port: Int) {
        repeat(45) {
            if (!process.isAlive) error(process.inputStream.bufferedReader().readText())
            if (runCatching { Socket().use { it.connect(InetSocketAddress(loop, port), 100) } }.isSuccess) return
            Thread.sleep(30)
        }
        error("sing-box did not open the loopback listener")
    }
}
