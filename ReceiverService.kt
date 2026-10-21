package com.padlink.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.math.abs

/** هاتف الشاشة: يستقبل أوامر هاتف الكيبورد وينفذها كلمس على الشاشة. */
class ReceiverService : Service() {

    companion object {
        const val ACTION_EDIT = "padlink.EDIT"
        const val PORT = 5555
        private const val CHANNEL = "padlink"
        private const val SENS = 2.0f // حساسية الكاميرا (غيّرها حسب ذوقك)
    }

    private var server: ServerSocket? = null
    private var overlay: Overlay? = null

    // حالة سحب الكاميرا
    private var startX = 0f
    private var startY = 0f
    private var camX = 0f
    private var camY = 0f

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        if (intent?.action == ACTION_EDIT) showEditor()
        if (server == null) startServer()
        return START_STICKY
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "PadLink", NotificationManager.IMPORTANCE_LOW)
        )
        val editPi = PendingIntent.getService(
            this, 1,
            Intent(this, ReceiverService::class.java).setAction(ACTION_EDIT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("PadLink يعمل")
            .setContentText("وأنت داخل اللعبة اضغط «تعديل الأزرار»")
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_edit),
                    "تعديل الأزرار", editPi
                ).build()
            )
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

    private fun startServer() {
        Thread {
            var ss: ServerSocket? = null
            try {
                ss = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(PORT))
                }
                server = ss
                while (!ss.isClosed) {
                    val c = ss.accept()
                    c.tcpNoDelay = true
                    serve(c)
                }
            } catch (t: Throwable) {
                Log.e("PadLink", "server stopped", t)
            } finally {
                try { ss?.close() } catch (_: Throwable) {}
                server = null
            }
        }.start()
    }

    private fun serve(c: Socket) {
        try {
            Injector.ensure()
            c.getInputStream().bufferedReader().forEachLine { handle(it) }
        } catch (_: Throwable) {
        } finally {
            Injector.releaseAll() // لا يبقى أي زر عالقاً لو انقطع الاتصال
            try { c.close() } catch (_: Throwable) {}
        }
    }

    // الأوامر:  D 3 (زر 3 نزل)  |  U 3 (زر 3 ارتفع)  |  P D / P M dx dy / P U  (منطقة الكاميرا)
    private fun handle(line: String) {
        try {
            val t = line.trim().split(" ")
            val (w, h) = Layout.screenSize(this)
            when (t[0]) {
                "D" -> {
                    val id = t[1].toInt()
                    if (id in 1..Layout.BUTTONS) {
                        val (fx, fy) = Layout.get(this, id)
                        Injector.down(id, fx * w, fy * h)
                    }
                }
                "U" -> {
                    val id = t[1].toInt()
                    if (id in 1..Layout.BUTTONS) Injector.up(id)
                }
                "P" -> when (t[1]) {
                    "D" -> {
                        val (fx, fy) = Layout.get(this, 0)
                        startX = fx * w; startY = fy * h
                        camX = startX; camY = startY
                        Injector.down(0, camX, camY)
                    }
                    "M" -> {
                        camX += t[2].toFloat() * SENS
                        camY += t[3].toFloat() * SENS
                        if (abs(camX - startX) > w * 0.18f || abs(camY - startY) > h * 0.25f) {
                            // رجّع الإصبع لنقطة البداية حتى لا يصل لحافة الشاشة ويتوقف
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
        overlay?.close()
        try { server?.close() } catch (_: Throwable) {}
        Injector.releaseAll()
        super.onDestroy()
    }
}
