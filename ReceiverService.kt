package com.padlink.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.math.abs

/** هاتف الشاشة: يستقبل الأوامر من 4 قنوات (TCP، UDP اكتشاف، mDNS، Bluetooth) وينفذها كلمس. */
class ReceiverService : Service() {

    companion object {
        const val ACTION_EDIT = "padlink.EDIT"
        const val ACTION_STOP = "padlink.STOP"
        private const val CHANNEL = "padlink"

        @Volatile var running = false
        @Volatile var peer: String? = null
        @Volatile var via: String = ""
        @Volatile var tcpOk = false
        @Volatile var udpOk = false
        @Volatile var nsdOk = false
        @Volatile var btOk = false
    }

    private var tcp: ServerSocket? = null
    private var udp: DatagramSocket? = null
    private var bt: Closeable? = null
    private var nsd: NsdManager? = null
    private var nsdL: NsdManager.RegistrationListener? = null
    private var overlay: Overlay? = null
    private var started = false

    private val lock = Any()
    private var current: Closeable? = null
    @Volatile private var sens = 2.0f

    // حالة سحب الكاميرا
    private var startX = 0f
    private var startY = 0f
    private var camX = 0f
    private var camY = 0f

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        goForeground()
        if (intent?.action == ACTION_EDIT) showEditor()
        if (!started) {
            started = true
            running = true
            startTcp(); startUdp(); startNsd(); startBt()
        }
        return START_STICKY
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "PadLink", NotificationManager.IMPORTANCE_LOW))
        fun pi(code: Int, act: String) = PendingIntent.getService(
            this, code, Intent(this, ReceiverService::class.java).setAction(act),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("PadLink يعمل")
            .setContentText("وأنت داخل اللعبة اضغط «تعديل الأزرار»")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_menu_edit), "تعديل الأزرار", pi(1, ACTION_EDIT)).build())
            .addAction(Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "إيقاف", pi(2, ACTION_STOP)).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }

    private fun showEditor() {
        if (overlay != null) return
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "فعّل «العرض فوق التطبيقات» أولاً", Toast.LENGTH_LONG).show()
            return
        }
        overlay = Overlay(this) { overlay = null }.also { it.show() }
    }

    // ───────────── القنوات ─────────────

    private fun startTcp() {
        Thread {
            try {
                val ss = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(Proto.TCP_PORT)) }
                tcp = ss
                tcpOk = true
                while (!ss.isClosed) {
                    val c = ss.accept()
                    c.tcpNoDelay = true
                    Thread { serve(c.getInputStream(), c.getOutputStream(), "Wi-Fi", c) }.start()
                }
            } catch (t: Throwable) {
                Log.e("PadLink", "tcp stopped", t)
            } finally {
                tcpOk = false
            }
        }.start()
    }

    private fun startUdp() {
        Thread {
            try {
                val s = DatagramSocket(null).apply {
                    reuseAddress = true; broadcast = true; bind(InetSocketAddress(Proto.UDP_PORT))
                }
                udp = s
                udpOk = true
                val buf = ByteArray(256)
                while (!s.isClosed) {
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    if (String(p.data, 0, p.length).startsWith("PADLINK?")) {
                        val r = "PADLINK!|${Proto.TCP_PORT}|${Proto.deviceName()}".toByteArray()
                        s.send(DatagramPacket(r, r.size, p.address, p.port))
                    }
                }
            } catch (t: Throwable) {
                Log.e("PadLink", "udp stopped", t)
            } finally {
                udpOk = false
            }
        }.start()
    }

    private fun startNsd() {
        try {
            val info = NsdServiceInfo().apply {
                serviceName = "PadLink-" + Proto.deviceName()
                serviceType = Proto.NSD_TYPE
                port = Proto.TCP_PORT
            }
            val l = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(si: NsdServiceInfo) { nsdOk = true }
                override fun onRegistrationFailed(si: NsdServiceInfo, code: Int) { nsdOk = false }
                override fun onServiceUnregistered(si: NsdServiceInfo) { nsdOk = false }
                override fun onUnregistrationFailed(si: NsdServiceInfo, code: Int) {}
            }
            nsdL = l
            nsd = getSystemService(NsdManager::class.java).also {
                it.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
            }
        } catch (t: Throwable) {
            Log.e("PadLink", "nsd failed", t)
        }
    }

    private fun startBt() {
        Thread {
            while (running) {
                try {
                    val ad = getSystemService(BluetoothManager::class.java)?.adapter
                    if (ad == null || !ad.isEnabled) { Thread.sleep(3000); continue }
                    val ss = ad.listenUsingInsecureRfcommWithServiceRecord("PadLink", Proto.BT_UUID)
                    bt = ss
                    btOk = true
                    while (running) {
                        val s = ss.accept()
                        Thread { serve(s.inputStream, s.outputStream, "Bluetooth", s) }.start()
                    }
                } catch (t: Throwable) {
                    Log.e("PadLink", "bt loop", t)
                } finally {
                    btOk = false
                    try { bt?.close() } catch (_: Throwable) {}
                }
                try { Thread.sleep(3000) } catch (_: Throwable) {}
            }
        }.start()
    }

    // ───────────── الجلسة ─────────────

    private fun serve(inp: InputStream, out: OutputStream, how: String, sock: Closeable) {
        var last = SystemClock.uptimeMillis()
        val wd = Thread {
            try {
                while (true) {
                    Thread.sleep(2000)
                    if (SystemClock.uptimeMillis() - last > 7000) { sock.close(); break }
                }
            } catch (_: Throwable) {
            }
        }.also { it.isDaemon = true; it.start() }
        var owner = false
        try {
            val r = inp.bufferedReader()
            val first = r.readLine() ?: return
            val t = first.split(" ", limit = 3)
            if (t[0] != "HELLO" || t.getOrNull(1) != Proto.pin(this)) {
                out.write("NO\n".toByteArray()); out.flush()
                return
            }
            synchronized(lock) {
                try { current?.close() } catch (_: Throwable) {}
                current = sock
                owner = true
                peer = t.getOrElse(2) { "?" }
                via = how
            }
            Injector.ensure()
            out.write("OK ${Proto.deviceName()}\n".toByteArray()); out.flush()
            r.forEachLine { line ->
                last = SystemClock.uptimeMillis()
                handle(line, out)
            }
        } catch (_: Throwable) {
        } finally {
            wd.interrupt()
            synchronized(lock) {
                if (current === sock) {
                    current = null; peer = null; via = ""
                } else {
                    owner = false
                }
            }
            if (owner) Injector.releaseAll() // لا يبقى أي زر عالقاً لو انقطع الاتصال
            try { sock.close() } catch (_: Throwable) {}
        }
    }

    private fun handle(line: String, out: OutputStream) {
        try {
            val t = line.trim().split(" ")
            when (t[0]) {
                "PING" -> { out.write("PONG ${t.getOrElse(1) { "0" }}\n".toByteArray()); out.flush() }
                "CFG" -> Layout.setCfg(this, t.getOrElse(1) { "" })
                "SENS" -> sens = t[1].toFloat().coerceIn(0.3f, 10f)
                "D" -> {
                    val id = t[1].toInt()
                    if (id in 1..Proto.MAX_BTN) {
                        val (w, h) = Layout.screenSize(this)
                        val (fx, fy) = Layout.get(this, id)
                        Injector.down(id, fx * w, fy * h)
                    }
                }
                "U" -> {
                    val id = t[1].toInt()
                    if (id in 1..Proto.MAX_BTN) Injector.up(id)
                }
                "P" -> when (t[1]) {
                    "D" -> {
                        val (w, h) = Layout.screenSize(this)
                        val (fx, fy) = Layout.get(this, 0)
                        startX = fx * w; startY = fy * h
                        camX = startX; camY = startY
                        Injector.down(0, camX, camY)
                    }
                    "M" -> {
                        val (w, h) = Layout.screenSize(this)
                        camX += t[2].toFloat() * sens
                        camY += t[3].toFloat() * sens
                        if (abs(camX - startX) > w * 0.18f || abs(camY - startY) > h * 0.25f) {
                            Injector.up(0)
                            camX = startX; camY = startY
                            Injector.down(0, camX, camY)
                        } else {
                            Injector.move(0, camX, camY)
                        }
                    }
                    "U" -> Injector.up(0)
                }
            }
        } catch (_: Throwable) {
        }
    }

    override fun onDestroy() {
        running = false
        overlay?.close()
        try { current?.close() } catch (_: Throwable) {}
        try { tcp?.close() } catch (_: Throwable) {}
        try { udp?.close() } catch (_: Throwable) {}
        try { bt?.close() } catch (_: Throwable) {}
        try { nsdL?.let { nsd?.unregisterService(it) } } catch (_: Throwable) {}
        tcpOk = false; udpOk = false; nsdOk = false; btOk = false; peer = null
        Injector.releaseAll()
        super.onDestroy()
    }
}
