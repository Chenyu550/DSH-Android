package io.github.jackwener.dsh.core.platform.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Finds this phone's own adbd services over mDNS: `_adb-tls-connect._tcp` (the wireless-debugging
 * port) and `_adb-tls-pairing._tcp` (only while the "pair with code" dialog is open). The LAN may
 * advertise other phones too, so only services whose address belongs to one of our interfaces count;
 * the connection itself always goes to 127.0.0.1.
 */
class AdbMdns(context: Context, private val type: String, private val onPort: (Int?) -> Unit) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var stopped = false
    private val ports = mutableMapOf<String, Int>()

    private val discovery = object : NsdManager.DiscoveryListener {
        override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) { Log.w(TAG, "discovery failed $errorCode") }
        override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
        override fun onDiscoveryStarted(serviceType: String?) {}
        override fun onDiscoveryStopped(serviceType: String?) {}
        override fun onServiceFound(info: NsdServiceInfo) { resolve(info, 0) }
        override fun onServiceLost(info: NsdServiceInfo) {
            synchronized(ports) { ports.remove(info.serviceName) }
            report()
        }
    }

    fun start(): AdbMdns {
        runCatching { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, discovery) }.onFailure { Log.w(TAG, "discoverServices", it) }
        return this
    }

    fun stop() {
        if (stopped) return
        stopped = true
        runCatching { nsd.stopServiceDiscovery(discovery) }
    }

    @Suppress("DEPRECATION") // resolveService: still the one call that works from API 30 to 36.
    private fun resolve(info: NsdServiceInfo, attempt: Int) {
        if (stopped) return
        runCatching {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(si: NsdServiceInfo?, errorCode: Int) {
                    // One resolve at a time before Android 14: try again shortly.
                    if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE && attempt < 10) main.postDelayed({ resolve(info, attempt + 1) }, 300)
                }
                override fun onServiceResolved(si: NsdServiceInfo) {
                    val host = si.host ?: return
                    if (!isLocal(host) || si.port <= 0) return
                    synchronized(ports) { ports[si.serviceName] = si.port }
                    report()
                }
            })
        }.onFailure { Log.w(TAG, "resolveService", it) }
    }

    private fun report() { if (!stopped) onPort(synchronized(ports) { ports.values.lastOrNull() }) }

    companion object {
        private const val TAG = "AdbMdns"
        const val TLS_CONNECT = "_adb-tls-connect._tcp"
        const val TLS_PAIRING = "_adb-tls-pairing._tcp"

        fun isLocal(host: InetAddress): Boolean = host.isLoopbackAddress || runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().any { nif -> nif.inetAddresses.toList().any { it == host } }
        }.getOrDefault(false)
    }
}
