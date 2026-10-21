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

/** اتصال هاتف التحكم: إعادة اتصال تلقائية + نبض (Ping) + قياس التأخير. */
class Link(
    private val ctx: Context,
    private val target: Target,
    private val pin: String,
    private val cb: (Int, String, Int) -> Unit
) {
    @Volatile private var closed = false
    @Volatile private var out: OutputStream? = null
    @Volatile private var sock: Closeable? = null
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
            try {
                runOnce()
            } catch (t: Throwable) {
                if (!closed) cb(EV_LOST, "تعذّر الاتصال: ${t.message ?: t.javaClass.simpleName}", -1)
            }
            connected = false
            out = null
            try { sock?.close() } catch (_: Throwable) {}
            if (closed) break
            cb(EV_LOST, "انقطع الاتصال — إعادة المحاولة…", -1)
            try { Thread.sleep(1500) } catch (_: Throwable) {}
        }
    }

    private fun runOnce() {
        val inp: InputStream
        val o: OutputStream
        if (target.kind == 0) {
            val s = Socket()
            s.tcpNoDelay = true
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
        o.write("HELLO $pin ${Proto.deviceName()}\n".toByteArray()); o.flush()
        val r = inp.bufferedReader()
        val resp = r.readLine() ?: return
        if (!resp.startsWith("OK")) {
            closed = true
            cb(EV_PIN, "رمز PIN غير صحيح", -1)
            return
        }
        out = o
        connected = true
        cb(EV_OK, if (target.kind == 0) "Wi-Fi" else "Bluetooth", -1)
        val pinger = Thread {
            try {
                while (connected && !closed) {
                    send("PING ${SystemClock.elapsedRealtime()}")
                    Thread.sleep(2000)
                }
            } catch (_: Throwable) {
            }
        }.also { it.isDaemon = true; it.start() }
        try {
            r.forEachLine { l ->
                if (l.startsWith("PONG ")) {
                    val ts = l.substring(5).trim().toLongOrNull()
                    if (ts != null) cb(EV_PING, "", (SystemClock.elapsedRealtime() - ts).toInt())
                }
            }
        } finally {
            pinger.interrupt()
        }
    }

    fun send(msg: String) {
        if (closed) return
        writer.execute {
            try {
                out?.write((msg + "\n").toByteArray())
                out?.flush()
            } catch (_: Throwable) {
                try { sock?.close() } catch (_: Throwable) {} // يجبر الحلقة على إعادة الاتصال
            }
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
