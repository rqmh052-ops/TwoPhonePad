package com.padlink.app

import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.SystemClock
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

/** kind: 0 = Wi-Fi/TCP (addr = IP)  |  1 = Bluetooth (addr = MAC) */
class Target(val kind: Int, val addr: String, val name: String)

const val EV_CONNECTING = 0
const val EV_OK = 1
const val EV_LOST = 2
const val EV_PIN = 3
const val EV_PING = 4

/**
 * اتصال هاتف التحكم: إعادة اتصال تلقائية + نبض (Ping) + قياس التأخير.
 * حارس (Watchdog): لو لم يصل أي رد من هاتف الشاشة خلال DEAD_MS يُقطع الاتصال فوراً ويُعاد —
 * بدونه كان الاتصال الميت يبقى معلّقاً (يظهر أخضر لكن لا يعمل) حتى تخرج وتدخل يدوياً.
 */
class Link(
    private val ctx: Context,
    private val target: Target,
    private val pin: String,
    private val cb: (Int, String, Int) -> Unit
) {
    private companion object {
        const val PING_MS = 1000L
        const val DEAD_MS = 6000L
    }

    @Volatile private var closed = false
    @Volatile private var out: OutputStream? = null
    @Volatile private var sock: Closeable? = null
    @Volatile private var lastRx = 0L
    @Volatile private var dropWhy: String? = null
    @Volatile private var established = false
    @Volatile var connected = false
        private set
    private val writer = Executors.newSingleThreadExecutor()
    private var thread: Thread? = null

    fun start() {
        thread = Thread { loop() }.also { it.start() }
    }

    private fun loop() {
        while (!closed) {
            cb(EV_CONNECTING, "جارٍ الاتصال بـ ${target.name}…", -1)
            dropWhy = null
            established = false
            val t0 = SystemClock.uptimeMillis()
            var why: String? = null
            try {
                runOnce()
            } catch (t: Throwable) {
                why = t.message ?: t.javaClass.simpleName
            }
            why = dropWhy ?: why
            connected = false
            out = null
            try { sock?.close() } catch (_: Throwable) {}
            if (closed) break
            val secs = (SystemClock.uptimeMillis() - t0) / 1000
            cb(
                EV_LOST,
                (if (established) "انقطع الاتصال بعد $secs ث" else "تعذّر الاتصال") +
                    (if (why != null) ": $why" else "") + " — إعادة المحاولة…",
                -1
            )
            try { Thread.sleep(1000) } catch (_: Throwable) {}
        }
    }

    private fun runOnce() {
        val inp: InputStream
        val o: OutputStream
        if (target.kind == 0) {
            val s = Socket()
            s.tcpNoDelay = true
            s.keepAlive = true
            sock = s
            s.connect(InetSocketAddress(target.addr, Proto.TCP_PORT), 3000)
            inp = s.getInputStream(); o = s.getOutputStream()
        } else {
            val ad = ctx.getSystemService(BluetoothManager::class.java).adapter
            ad.cancelDiscovery()
            val s = ad.getRemoteDevice(target.addr).createInsecureRfcommSocketToServiceRecord(Proto.BT_UUID)
            sock = s
            s.connect()
            inp = s.inputStream; o = s.outputStream
        }

        // الحارس: يبدأ بعد نجاح فتح القناة (يشمل مرحلة المصافحة)
        lastRx = SystemClock.uptimeMillis()
        val wd = Thread {
            try {
                while (!closed) {
                    Thread.sleep(1000)
                    val idle = SystemClock.uptimeMillis() - lastRx
                    if (idle > DEAD_MS) {
                        dropWhy = "لا رد من هاتف الشاشة منذ ${idle / 1000} ث"
                        try { sock?.close() } catch (_: Throwable) {}
                        break
                    }
                }
            } catch (_: Throwable) {
            }
        }.also { it.isDaemon = true; it.start() }

        var pinger: Thread? = null
        try {
            o.write("HELLO $pin ${Proto.deviceName()}\n".toByteArray()); o.flush()
            val r = inp.bufferedReader()
            val resp = r.readLine()
            if (resp == null) {
                dropWhy = "أغلق هاتف الشاشة الاتصال أثناء المصافحة"
                return
            }
            if (!resp.startsWith("OK")) {
                closed = true
                cb(EV_PIN, "رمز PIN غير صحيح", -1)
                return
            }
            lastRx = SystemClock.uptimeMillis()
            out = o
            connected = true
            established = true
            cb(EV_OK, if (target.kind == 0) "Wi-Fi" else "Bluetooth", -1)
            pinger = Thread {
                try {
                    while (connected && !closed) {
                        send("PING ${SystemClock.elapsedRealtime()}")
                        Thread.sleep(PING_MS)
                    }
                } catch (_: Throwable) {
                }
            }.also { it.isDaemon = true; it.start() }
            r.forEachLine { l ->
                lastRx = SystemClock.uptimeMillis()
                if (l.startsWith("PONG ")) {
                    val ts = l.substring(5).trim().toLongOrNull()
                    if (ts != null) cb(EV_PING, "", (SystemClock.elapsedRealtime() - ts).toInt())
                }
            }
            if (dropWhy == null) dropWhy = "أغلق هاتف الشاشة الاتصال"
        } finally {
            wd.interrupt()
            pinger?.interrupt()
        }
    }

    fun send(msg: String) {
        if (closed) return
        try {
            writer.execute {
                try {
                    out?.write((msg + "\n").toByteArray())
                    out?.flush()
                } catch (t: Throwable) {
                    if (dropWhy == null) dropWhy = "فشل الإرسال: ${t.message ?: t.javaClass.simpleName}"
                    try { sock?.close() } catch (_: Throwable) {} // يجبر الحلقة على إعادة الاتصال
                }
            }
        } catch (_: Throwable) {
        }
    }

    fun close() {
        closed = true
        connected = false
        try { sock?.close() } catch (_: Throwable) {}
        thread?.interrupt()
        writer.shutdown()
    }
}
