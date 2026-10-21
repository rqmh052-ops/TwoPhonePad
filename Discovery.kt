package com.padlink.app

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.SystemClock
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class Found(val kind: Int, val addr: String, val name: String, val via: String)

/** اكتشاف تلقائي بثلاث طرق متوازية: بث UDP + mDNS (NSD) + فحص الشبكة/البوابة (يشمل الهوت سبوت). */
object Discovery {

    fun gateway(ctx: Context): String? = try {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        cm.getLinkProperties(cm.activeNetwork)?.routes
            ?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
            ?.gateway?.hostAddress
    } catch (_: Throwable) {
        null
    }

    private fun broadcasts(): List<InetAddress> {
        val l = ArrayList<InetAddress>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) ia.broadcast?.let { l += it }
            }
        } catch (_: Throwable) {
        }
        try { l += InetAddress.getByName("255.255.255.255") } catch (_: Throwable) {}
        return l.distinct()
    }

    /** يرجع دالة إيقاف. onFound تُستدعى من خيوط خلفية. */
    fun start(ctx: Context, onFound: (Found) -> Unit): () -> Unit {
        val stopped = AtomicBoolean(false)
        var udp: DatagramSocket? = null
        var nsd: NsdManager? = null
        var nsdL: NsdManager.DiscoveryListener? = null
        val pool = Executors.newFixedThreadPool(48)

        // 1) بث UDP
        Thread {
            try {
                val s = DatagramSocket().apply { broadcast = true; soTimeout = 500 }
                udp = s
                val msg = "PADLINK?".toByteArray()
                val buf = ByteArray(256)
                var rounds = 0
                while (!stopped.get() && rounds < 12) {
                    for (b in broadcasts()) {
                        try { s.send(DatagramPacket(msg, msg.size, b, Proto.UDP_PORT)) } catch (_: Throwable) {}
                    }
                    val end = SystemClock.uptimeMillis() + 1500
                    while (SystemClock.uptimeMillis() < end && !stopped.get()) {
                        try {
                            val p = DatagramPacket(buf, buf.size)
                            s.receive(p)
                            val parts = String(p.data, 0, p.length).split("|")
                            if (parts[0] == "PADLINK!" && p.address is Inet4Address) {
                                onFound(Found(0, p.address.hostAddress ?: continue, parts.getOrElse(2) { "?" }, "UDP"))
                            }
                        } catch (_: SocketTimeoutException) {
                        }
                    }
                    rounds++
                }
            } catch (_: Throwable) {
            }
        }.start()

        // 2) mDNS
        try {
            val m = ctx.getSystemService(NsdManager::class.java)
            val dl = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(t: String) {}
                override fun onDiscoveryStopped(t: String) {}
                override fun onStartDiscoveryFailed(t: String, e: Int) {}
                override fun onStopDiscoveryFailed(t: String, e: Int) {}
                override fun onServiceLost(si: NsdServiceInfo) {}
                @Suppress("DEPRECATION")
                override fun onServiceFound(si: NsdServiceInfo) {
                    try {
                        m.resolveService(si, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(s: NsdServiceInfo, e: Int) {}
                            override fun onServiceResolved(r: NsdServiceInfo) {
                                val h = r.host
                                if (h is Inet4Address) {
                                    onFound(Found(0, h.hostAddress ?: return, r.serviceName.removePrefix("PadLink-"), "mDNS"))
                                }
                            }
                        })
                    } catch (_: Throwable) {
                    }
                }
            }
            nsd = m; nsdL = dl
            m.discoverServices(Proto.NSD_TYPE, NsdManager.PROTOCOL_DNS_SD, dl)
        } catch (_: Throwable) {
        }

        // 3) فحص البوابة + كل عناوين الشبكة /24
        Thread {
            val mine = Proto.localIps().map { it.second }
            fun probe(ip: String) {
                pool.execute {
                    if (stopped.get()) return@execute
                    try {
                        Socket().use { it.connect(InetSocketAddress(ip, Proto.TCP_PORT), 400) }
                        onFound(Found(0, ip, ip, "Scan"))
                    } catch (_: Throwable) {
                    }
                }
            }
            gateway(ctx)?.let { probe(it) }
            for (prefix in mine.map { it.substringBeforeLast('.') }.distinct()) {
                for (i in 1..254) {
                    val ip = "$prefix.$i"
                    if (ip !in mine) probe(ip)
                }
            }
            pool.shutdown()
        }.start()

        return {
            stopped.set(true)
            try { udp?.close() } catch (_: Throwable) {}
            try { nsdL?.let { nsd?.stopServiceDiscovery(it) } } catch (_: Throwable) {}
            pool.shutdownNow()
        }
    }
}

/** بحث البلوتوث: الأجهزة المقترنة + الأجهزة القريبة. */
class BtScanner(private val ctx: Context, private val onFound: (Found) -> Unit) {
    private val ad = ctx.getSystemService(BluetoothManager::class.java)?.adapter
    private var registered = false

    private val rx = object : BroadcastReceiver() {
        @Suppress("DEPRECATION")
        override fun onReceive(c: Context, i: Intent) {
            try {
                if (i.action != BluetoothDevice.ACTION_FOUND) return
                val d: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33)
                    i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                else i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                if (d != null) onFound(Found(1, d.address, d.name ?: d.address, "Bluetooth"))
            } catch (_: Throwable) {
            }
        }
    }

    fun start() {
        try {
            val f = IntentFilter(BluetoothDevice.ACTION_FOUND)
            if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(rx, f, Context.RECEIVER_EXPORTED)
            else ctx.registerReceiver(rx, f)
            registered = true
            ad?.bondedDevices?.forEach { onFound(Found(1, it.address, it.name ?: it.address, "BT مقترن")) }
            ad?.startDiscovery()
        } catch (_: Throwable) {
        }
    }

    fun stop() {
        try { ad?.cancelDiscovery() } catch (_: Throwable) {}
        try { if (registered) ctx.unregisterReceiver(rx) } catch (_: Throwable) {}
        registered = false
    }
}
