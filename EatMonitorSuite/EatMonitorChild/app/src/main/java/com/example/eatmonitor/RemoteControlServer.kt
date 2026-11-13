package com.example.eatmonitor

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Menjalankan:
 * 1. HTTP Control Server (Port 8080)
 * 2. UDP Auto-Discovery Responder & Beacon (Port 8889) -> agar HP Remote langsung menemukan IP tanpa ketik manual
 * 3. Android NSD (mDNS _eatmonitor._tcp.) sebagai jalur auto-discovery kedua
 */
class RemoteControlServer(
    private val context: Context,
    private val httpPort: Int = 8080,
    private val udpDiscoveryPort: Int = 8889,
    private val onCommandReceived: (String) -> String
) {
    private var serverSocket: ServerSocket? = null
    private var udpSocket: DatagramSocket? = null
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null

    @Volatile private var isRunning = false
    private val executor = Executors.newCachedThreadPool()

    private val deviceName: String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    fun start() {
        if (isRunning) return
        isRunning = true

        startHttpServer()
        startUdpDiscoveryResponder()
        startUdpPeriodicBeacon()
        registerNsdService()
    }

    private fun startHttpServer() {
        executor.execute {
            try {
                serverSocket = ServerSocket(httpPort)
                while (isRunning) {
                    val client = serverSocket?.accept() ?: break
                    executor.execute { handleHttpClient(client) }
                }
            } catch (e: Exception) {
                if (isRunning) Log.e("RemoteControlServer", "HTTP Server error", e)
            }
        }
    }

    private fun handleHttpClient(socket: Socket) {
        try {
            socket.use { s ->
                s.soTimeout = 3000
                val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                val out = PrintWriter(s.getOutputStream(), true)

                val requestLine = reader.readLine() ?: return
                val path = requestLine.split(" ").getOrNull(1) ?: "/"

                val body = when {
                    path.startsWith("/trigger") -> onCommandReceived("TRIGGER_BLANK")
                    path.startsWith("/lock") -> onCommandReceived("FORCE_LOCK")
                    path.startsWith("/unlock") -> onCommandReceived("UNLOCK")
                    else -> onCommandReceived("STATUS")
                }

                out.print("HTTP/1.1 200 OK\r\n")
                out.print("Content-Type: application/json; charset=utf-8\r\n")
                out.print("Access-Control-Allow-Origin: *\r\n")
                out.print("Connection: close\r\n\r\n")
                out.print(body)
                out.flush()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Merespons paket UDP "EATMONITOR_DISCOVER" dari HP Orang Tua secara instan.
     */
    private fun startUdpDiscoveryResponder() {
        executor.execute {
            try {
                udpSocket = DatagramSocket(udpDiscoveryPort).apply {
                    broadcast = true
                }
                val buf = ByteArray(256)
                while (isRunning) {
                    val packet = DatagramPacket(buf, buf.size)
                    udpSocket?.receive(packet) ?: break
                    val msg = String(packet.data, 0, packet.length, Charsets.UTF_8).trim()
                    if (msg.startsWith("EATMONITOR_DISCOVER")) {
                        val reply = "EATMONITOR_HERE|$httpPort|$deviceName"
                        val replyBytes = reply.toByteArray(Charsets.UTF_8)
                        val replyPacket = DatagramPacket(
                            replyBytes,
                            replyBytes.size,
                            packet.address,
                            packet.port
                        )
                        udpSocket?.send(replyPacket)
                    }
                }
            } catch (e: Exception) {
                if (isRunning) Log.e("RemoteControlServer", "UDP Responder error", e)
            }
        }
    }

    /**
     * Memancarkan beacon UDP tiap 2 detik ke subnet lokal supaya HP Remote langsung
     * mendeteksi HP Anak bahkan jika router memblokir request broadcast satu arah.
     */
    private fun startUdpPeriodicBeacon() {
        executor.execute {
            while (isRunning) {
                try {
                    val msg = "EATMONITOR_HERE|$httpPort|$deviceName"
                    val bytes = msg.toByteArray(Charsets.UTF_8)
                    val broadcastAddresses = getBroadcastAddresses()
                    for (bcast in broadcastAddresses) {
                        val packet = DatagramPacket(bytes, bytes.size, bcast, udpDiscoveryPort)
                        udpSocket?.send(packet)
                    }
                    Thread.sleep(2000L)
                } catch (_: Exception) {
                    try { Thread.sleep(2000L) } catch (_: Exception) {}
                }
            }
        }
    }

    private fun registerNsdService() {
        try {
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = "EatMonitor-$deviceName"
                serviceType = "_eatmonitor._tcp."
                port = httpPort
            }
            nsdManager = (context.getSystemService(Context.NSD_SERVICE) as? NsdManager)?.also { mgr ->
                registrationListener = object : NsdManager.RegistrationListener {
                    override fun onServiceRegistered(info: NsdServiceInfo) {}
                    override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
                    override fun onServiceUnregistered(info: NsdServiceInfo) {}
                    override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
                }
                mgr.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
            }
        } catch (e: Exception) {
            Log.e("RemoteControlServer", "NSD registration failed", e)
        }
    }

    fun stop() {
        isRunning = false
        try { serverSocket?.close() } catch (_: Exception) {}
        try { udpSocket?.close() } catch (_: Exception) {}
        try {
            registrationListener?.let { nsdManager?.unregisterService(it) }
        } catch (_: Exception) {}
    }

    companion object {
        fun getLocalIpAddress(): String {
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                for (intf in interfaces) {
                    for (addr in intf.inetAddresses) {
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            return addr.hostAddress ?: "0.0.0.0"
                        }
                    }
                }
            } catch (_: Exception) {}
            return "0.0.0.0"
        }

        fun getBroadcastAddresses(): List<InetAddress> {
            val list = mutableListOf<InetAddress>()
            try {
                list.add(InetAddress.getByName("255.255.255.255"))
                val interfaces = NetworkInterface.getNetworkInterfaces()
                for (intf in interfaces) {
                    if (!intf.isUp || intf.isLoopback) continue
                    for (ifaceAddr in intf.interfaceAddresses) {
                        val bcast = ifaceAddr.broadcast
                        if (bcast != null && !list.contains(bcast)) {
                            list.add(bcast)
                        }
                    }
                }
            } catch (_: Exception) {}
            return list
        }
    }
}
