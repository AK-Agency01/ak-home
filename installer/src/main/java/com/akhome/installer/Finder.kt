package com.akhome.installer

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A TV seen on the network. [pairPort] > 0 while its "Pair device with code" screen is open (Android 11+). */
data class Found(val host: String, val port: Int, val label: String, val tls: Boolean, val pairPort: Int = 0)

/**
 * Finds TVs on the phone's Wi-Fi two ways at once:
 * - a quick knock on port 5555 of every address in the /24 (network ADB, most Android TV boxes);
 * - mDNS announcements of Android 11+ "Wireless debugging" (connect and pairing services).
 */
class Finder(private val ctx: Context, private val onDone: () -> Unit = {}, private val onFound: (Found) -> Unit) {
    private val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val listeners = mutableListOf<NsdManager.DiscoveryListener>()
    private var lock: WifiManager.MulticastLock? = null
    @Volatile private var running = false

    private companion object { val PORTS = intArrayOf(5555, 8888) }

    /** The phone's own IPv4 address on Wi-Fi, e.g. 192.168.0.23 (null when not on a network). */
    fun myAddress(): Inet4Address? {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val lp: LinkProperties = cm.getLinkProperties(cm.activeNetwork) ?: return null
        return lp.linkAddresses.map { it.address }.filterIsInstance<Inet4Address>().firstOrNull { !it.isLoopbackAddress }
    }

    fun start() {
        running = true
        lock = (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createMulticastLock("akhome").apply { setReferenceCounted(false); acquire() }
        browse("_adb-tls-connect._tcp", pairing = false)
        browse("_adb-tls-pairing._tcp", pairing = true)
        Thread { scan() }.apply { isDaemon = true }.start()
    }

    fun stop() {
        running = false
        listeners.forEach { try { nsd.stopServiceDiscovery(it) } catch (_: Exception) {} }
        listeners.clear()
        try { lock?.release() } catch (_: Exception) {}
    }

    private fun scan() {
        val me = myAddress() ?: return
        val b = me.address
        val pool = Executors.newFixedThreadPool(48)
        for (i in 1..254) {
            if (i == (b[3].toInt() and 0xFF)) continue
            val host = "${b[0].toInt() and 0xFF}.${b[1].toInt() and 0xFF}.${b[2].toInt() and 0xFF}.$i"
            pool.execute {
                if (!running) return@execute
                // 5555 = standard network ADB; 8888 = what some boxes open by themselves (onn)
                for (port in PORTS) try {
                    Socket().use { it.connect(InetSocketAddress(host, port), 400) }
                    onFound(Found(host, port, host, tls = false)); break
                } catch (_: Exception) {}
            }
        }
        pool.shutdown(); pool.awaitTermination(20, TimeUnit.SECONDS)
        if (running) onDone()          // the address sweep is over (mDNS keeps listening until stop())
    }

    @Suppress("DEPRECATION")
    private fun browse(type: String, pairing: Boolean) {
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, e: Int) {}
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
            override fun onServiceLost(s: NsdServiceInfo) {}
            override fun onServiceFound(s: NsdServiceInfo) {
                nsd.resolveService(s, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(s: NsdServiceInfo, e: Int) {}
                    override fun onServiceResolved(r: NsdServiceInfo) {
                        val host = (r.host as? Inet4Address)?.hostAddress ?: return
                        onFound(if (pairing) Found(host, 0, r.serviceName, tls = true, pairPort = r.port)
                                else Found(host, r.port, r.serviceName, tls = true))
                    }
                })
            }
        }
        try { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, l); listeners += l } catch (_: Exception) {}
    }
}
