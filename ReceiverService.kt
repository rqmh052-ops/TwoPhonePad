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
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
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
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/** هاتف الشاشة: يستقبل الأوامر من 4 قنوات (TCP، UDP اكتشاف، mDNS، Bluetooth) وينفذها كلمس. */
class ReceiverService : Service() {

    companion object {
        const val ACTION_EDIT = "padlink.EDIT"
        const val ACTION_STOP = "padlink.STOP"
        const val ACTION_DOT = "padlink.DOT"
        private const val CHANNEL = "padlink"
        private const val DEAD_MS = 6000L // لا نبض من هاتف التحكم هذه المدة => نقطع ونرفع كل الأزرار
        private const val TAP_MS = 45L    // مدة «ضغطة واحدة»

        @Volatile var running = false
        @Volatile var peer: String? = null
        @Volatile var via: String = ""
        @Volatile var tcpOk = false
        @Volatile var udpOk = false
        @Volatile var nsdOk = false
        @Volatile var btOk = false

        /** سبب آخر انقطاع (للتشخيص) ووقته (uptimeMillis). */
        @Volatile var lastDrop: String = ""
        @Volatile var lastDropAt = 0L
    }

    private var tcp: ServerSocket? = null
    private var udp: DatagramSocket? = null
    private var bt: Closeable? = null
    private var nsd: NsdManager? = null
    private var nsdL: NsdManager.RegistrationListener? = null
    private var overlay: Overlay? = null
    private var dot: KeepDot? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private var started = false
    private val main = Handler(Looper.getMainLooper())

    private val lock = Any()
    private var current: Closeable? = null
    private var currentWhy: AtomicReference<String?>? = null
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
        try {
            goForeground()
        } catch (t: Throwable) {
            // أندرويد 12+ قد يمنع بدء خدمة أمامية من الخلفية؛ نتوقف بهدوء بدل الانهيار
            Log.e("PadLink", "startForeground refused", t)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_EDIT) showEditor()
        refreshDot()
        if (!started) {
            started = true
            running = true
            holdLocks()
            NetKeeper.start(this)
            startTcp(); startUdp(); startNsd(); startBt()
        }
        return START_STICKY
    }

    /** يمنع النوم: CPU + واي فاي. يبقيان أثناء اللعب والشاشة مطفأة الشبكة غير مهدّأة. */
    private fun holdLocks() {
        try {
            val pm = getSystemService(PowerManager::class.java)
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "padlink:rx").apply {
                setReferenceCounted(false); acquire()
            }
        } catch (t: Throwable) {
            Log.e("PadLink", "wakelock failed", t)
        }
        try {
            val wm = applicationContext.getSystemService(WifiManager::class.java)
            wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "padlink:rx").apply {
                setReferenceCounted(false); acquire()
            }
        } catch (t: Throwable) {
            Log.e("PadLink", "wifilock failed", t)
        }
    }

    /** المربع الصغير «1» فوق التطبيقات: اختياري من شاشة التطبيق. */
    private fun refreshDot() {
        val want = getSharedPreferences("app", MODE_PRIVATE).getBoolean("dot", false) &&
            Settings.canDrawOverlays(this)
        if (want) {
            if (dot == null) dot = KeepDot(this).also { it.show() }
        } else {
            dot?.hide()
            dot = null
        }
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
            while (running) {
                var ss: ServerSocket? = null
                try {
                    val server = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(Proto.TCP_PORT)) }
                    ss = server
                    tcp = server
                    tcpOk = true
                    while (running && !server.isClosed) {
                        val c = try {
                            server.accept()
                        } catch (t: Throwable) {
                            if (server.isClosed) break
                            Log.e("PadLink", "tcp accept failed", t)
                            Thread.sleep(200)
                            continue
                        }
                        c.tcpNoDelay = true
                        Thread { serve(c.getInputStream(), c.getOutputStream(), "Wi-Fi", c) }.start()
                    }
                } catch (t: Throwable) {
                    Log.e("PadLink", "tcp stopped", t)
                } finally {
                    tcpOk = false
                    try { ss?.close() } catch (_: Throwable) {}
                }
                // لا نموت أبداً: نعيد فتح المنفذ طالما الخدمة تعمل
                if (running) try { Thread.sleep(1000) } catch (_: Throwable) {}
            }
        }.start()
    }

    private fun startUdp() {
        Thread {
            while (running) {
                var s: DatagramSocket? = null
                try {
                    val sk = DatagramSocket(null).apply {
                        reuseAddress = true; broadcast = true; bind(InetSocketAddress(Proto.UDP_PORT))
                    }
                    s = sk
                    udp = sk
                    udpOk = true
                    val buf = ByteArray(256)
                    while (running && !sk.isClosed) {
                        val p = DatagramPacket(buf, buf.size)
                        sk.receive(p)
                        if (String(p.data, 0, p.length).startsWith("PADLINK?")) {
                            val r = "PADLINK!|${Proto.TCP_PORT}|${Proto.deviceName()}".toByteArray()
                            sk.send(DatagramPacket(r, r.size, p.address, p.port))
                        }
                    }
                } catch (t: Throwable) {
                    Log.e("PadLink", "udp stopped", t)
                } finally {
                    udpOk = false
                    try { s?.close() } catch (_: Throwable) {}
                }
                if (running) try { Thread.sleep(1000) } catch (_: Throwable) {}
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
        try { Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY) } catch (_: Throwable) {}
        val last = AtomicLong(SystemClock.uptimeMillis())
        val why = AtomicReference<String?>(null)
        val wd = Thread {
            try {
                while (true) {
                    Thread.sleep(1000)
                    val idle = SystemClock.uptimeMillis() - last.get()
                    if (idle > DEAD_MS) {
                        why.compareAndSet(null, "لا نبض من هاتف التحكم منذ ${idle / 1000} ث")
                        sock.close()
                        break
                    }
                }
            } catch (_: Throwable) {
            }
        }.also { it.isDaemon = true; it.start() }
        var owner = false
        var t0 = 0L
        try {
            val r = inp.bufferedReader()
            val first = r.readLine() ?: return
            val t = first.split(" ", limit = 3)
            if (t[0] != "HELLO" || t.getOrNull(1) != Proto.pin(this)) {
                out.write("NO\n".toByteArray()); out.flush()
                return
            }
            synchronized(lock) {
                currentWhy?.compareAndSet(null, "استُبدل باتصال جديد")
                try { current?.close() } catch (_: Throwable) {}
                current = sock
                currentWhy = why
                owner = true
                peer = t.getOrElse(2) { "?" }
                via = how
            }
            t0 = SystemClock.uptimeMillis()
            Injector.ensure()
            out.write("OK ${Proto.deviceName()}\n".toByteArray()); out.flush()
            r.forEachLine { line ->
                last.set(SystemClock.uptimeMillis())
                handle(line, out)
            }
            why.compareAndSet(null, "أغلق هاتف التحكم الاتصال")
        } catch (t: Throwable) {
            why.compareAndSet(null, t.message ?: t.javaClass.simpleName)
        } finally {
            wd.interrupt()
            synchronized(lock) {
                if (current === sock) {
                    current = null; currentWhy = null; peer = null; via = ""
                } else {
                    owner = false
                }
            }
            if (owner) Injector.releaseAll() // لا يبقى أي زر عالقاً لو انقطع الاتصال
            if (t0 != 0L) {
                lastDrop = "${why.get() ?: "؟"} • بعد ${(SystemClock.uptimeMillis() - t0) / 1000} ث"
                lastDropAt = SystemClock.uptimeMillis()
            }
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
                "T" -> { // ضغطة واحدة: نزول ثم رفع تلقائي بعد TAP_MS
                    val id = t[1].toInt()
                    if (id in 1..Proto.MAX_BTN) {
                        val (w, h) = Layout.screenSize(this)
                        val (fx, fy) = Layout.get(this, id)
                        Injector.down(id, fx * w, fy * h)
                        main.postDelayed({ Injector.up(id) }, TAP_MS)
                    }
                }
                "DM" -> { // سحب إصبع زر مضغوط (تصويب أثناء الضغط)
                    val id = t[1].toInt()
                    if (id in 1..Proto.MAX_BTN) aim(id, t[2].toFloat(), t[3].toFloat())
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

    /** يحرّك إصبع الزر المضغوط بنفس انزياح إصبع هاتف التحكم (× الحساسية). عند الابتعاد يعيد الضغط من مكان الزر. */
    private fun aim(id: Int, dx: Float, dy: Float) {
        val p = Injector.pos(id) ?: return
        val (w, h) = Layout.screenSize(this)
        val (fx, fy) = Layout.get(this, id)
        val ox = fx * w
        val oy = fy * h
        val nx = p[0] + dx * sens
        val ny = p[1] + dy * sens
        if (abs(nx - ox) > w * 0.18f || abs(ny - oy) > h * 0.25f ||
            nx < 8f || nx > w - 8f || ny < 8f || ny > h - 8f
        ) {
            Injector.up(id)
            Injector.down(id, ox, oy)
        } else {
            Injector.move(id, nx, ny)
        }
    }

    override fun onDestroy() {
        running = false
        overlay?.close()
        dot?.hide(); dot = null
        try { wake?.takeIf { it.isHeld }?.release() } catch (_: Throwable) {}
        try { wifi?.takeIf { it.isHeld }?.release() } catch (_: Throwable) {}
        NetKeeper.stop(this)
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
