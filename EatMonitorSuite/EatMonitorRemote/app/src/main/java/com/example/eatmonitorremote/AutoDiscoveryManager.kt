package com.example.eatmonitorremote

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.concurrent.Executors

data class DiscoveredChildDevice(
    val hostIp: String,
    val port: Int,
    val deviceName: String
)

/**
 * Mencari HP Anak secara otomatis tanpa perlu ketik IP menggunakan 2 jalur paralel:
 * 1. UDP Broadcast & Beacon Listener (Port 8889) - instan (< 1 detik)
 * 2. Android NSD / mDNS (_eatmonitor._tcp.)
 */
class AutoDiscoveryManager(
    private val context: Context,
    private val udpPort: Int = 8889,
    private val onDeviceFound: (DiscoveredChildDevice) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newCachedThreadPool()

    @Volatile private var isSearching = false
    private var udpSocket: DatagramSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    fun startDiscovery() {
        if (isSearching) return
        isSearching = true

        acquireMulticastLock()
        startUdpDiscoveryLoop()
        startNsdDiscovery()
    }

    fun triggerRescanBroadcast() {
        executor.execute {
            sendUdpDiscoveryPacket()
        }
    }

    private fun acquireMulticastLock() {
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifi?.createMulticastLock("eatmonitor_discovery_lock")?.apply {
                setReferenceCounted(true)
                acquire()
            }
        } catch (_: Exception) {}
    }

    private fun startUdpDiscoveryLoop() {
        // Thread 1: Mendengarkan balasan / beacon "EATMONITOR_HERE|port|namaHP"
        executor.execute {
            try {
                udpSocket = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(udpPort))
                }
                val buf = ByteArray(256)
                while (isSearching) {
                    val packet = DatagramPacket(buf, buf.size)
                    udpSocket?.receive(packet) ?: break
                    val msg = String(packet.data, 0, packet.length, Charsets.UTF_8).trim()
                    if (msg.startsWith("EATMONITOR_HERE|")) {
                        val parts = msg.split("|")
                        val port = parts.getOrNull(1)?.toIntOrNull() ?: 8080
                        val name = parts.getOrNull(2) ?: "HP Anak"
                        val ip = packet.address.hostAddress ?: continue
                        notifyFound(DiscoveredChildDevice(ip, port, name))
                    }
                }
            } catch (_: Exception) {}
        }

        // Thread 2: Mengirim probe "EATMONITOR_DISCOVER" tiap 2 detik
        executor.execute {
            while (isSearching) {
                sendUdpDiscoveryPacket()
                try { Thread.sleep(2000L) } catch (_: Exception) {}
            }
        }
    }

    private fun sendUdpDiscoveryPacket() {
        try {
            val data = "EATMONITOR_DISCOVER".toByteArray(Charsets.UTF_8)
            val targets = getBroadcastAddresses()
            for (addr in targets) {
                val packet = DatagramPacket(data, data.size, addr, udpPort)
                udpSocket?.send(packet)
            }
        } catch (_: Exception) {}
    }

    private fun startNsdDiscovery() {
        try {
            nsdManager = (context.getSystemService(Context.NSD_SERVICE) as? NsdManager)?.also { mgr ->
                val listener = object : NsdManager.DiscoveryListener {
                    override fun onDiscoveryStarted(regType: String) {}
                    override fun onServiceFound(service: NsdServiceInfo) {
                        if (service.serviceType.contains("_eatmonitor")) {
                            mgr.resolveService(service, object : NsdManager.ResolveListener {
                                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                                override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                                    val hostIp = resolvedInfo.host?.hostAddress ?: return
                                    val port = resolvedInfo.port
                                    val cleanName = resolvedInfo.serviceName.removePrefix("EatMonitor-")
                                    notifyFound(DiscoveredChildDevice(hostIp, port, cleanName))
                                }
                            })
                        }
                    }
                    override fun onServiceLost(service: NsdServiceInfo) {}
                    override fun onDiscoveryStopped(serviceType: String) {}
                    override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
                    override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
                }
                discoveryListener = listener
                mgr.discoverServices("_eatmonitor._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
            }
        } catch (_: Exception) {}
    }

    private fun notifyFound(device: DiscoveredChildDevice) {
        mainHandler.post {
            onDeviceFound(device)
        }
    }

    fun stopDiscovery() {
        isSearching = false
        try { udpSocket?.close() } catch (_: Exception) {}
        try {
            discoveryListener?.let { nsdManager?.stopServiceDiscovery(it) }
        } catch (_: Exception) {}
        try {
            if (multicastLock?.isHeld == true) multicastLock?.release()
        } catch (_: Exception) {}
    }

    private fun getBroadcastAddresses(): List<InetAddress> {
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
