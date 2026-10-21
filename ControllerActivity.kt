package com.padlink.app

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

/** هاتف الكيبورد: أزرار 1..6 + منطقة كاميرا في الوسط، وكلها ترسل لهاتف الشاشة. */
class ControllerActivity : Activity() {

    private val io = Executors.newSingleThreadExecutor()
    private var socket: Socket? = null
    private var out: OutputStream? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(buildUi())
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        connect(intent.getStringExtra("ip") ?: "")
    }

    private fun connect(ip: String) {
        io.execute {
            try {
                val s = Socket()
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(ip, ReceiverService.PORT), 4000)
                socket = s
                out = s.getOutputStream()
                runOnUiThread { Toast.makeText(this, "متصل ✓", Toast.LENGTH_SHORT).show() }
            } catch (t: Throwable) {
                runOnUiThread {
                    Toast.makeText(this, "فشل الاتصال بـ $ip", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    private fun send(msg: String) {
        io.execute {
            try {
                out?.write((msg + "\n").toByteArray())
                out?.flush()
            } catch (_: Throwable) {
            }
        }
    }

    private fun button(id: Int): TextView = TextView(this).apply {
        text = "$id"
        textSize = 28f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setBackgroundColor(0xFF333333.toInt())
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.setBackgroundColor(0xFF1E88E5.toInt())
                    send("D $id")
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.setBackgroundColor(0xFF333333.toInt())
                    send("U $id")
                }
            }
            true
        }
    }

    private fun pad(): View = TextView(this).apply {
        text = "منطقة الكاميرا\nحرّك إصبعك هنا"
        gravity = Gravity.CENTER
        setTextColor(Color.LTGRAY)
        setBackgroundColor(0xFF1B3A1B.toInt())
        var lx = 0f
        var ly = 0f
        setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lx = e.x; ly = e.y
                    send("P D")
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.x - lx).toInt()
                    val dy = (e.y - ly).toInt()
                    if (dx != 0 || dy != 0) {
                        send("P M $dx $dy")
                        lx = e.x; ly = e.y
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> send("P U")
            }
            true
        }
    }

    private fun column(vararg ids: Int): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setMotionEventSplittingEnabled(true)
        for (id in ids) {
            addView(button(id), LinearLayout.LayoutParams(MATCH, 0, 1f).apply { setMargins(8, 8, 8, 8) })
        }
    }

    private fun buildUi(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setMotionEventSplittingEnabled(true)
        setBackgroundColor(Color.BLACK)
        addView(column(1, 2, 3), LinearLayout.LayoutParams(0, MATCH, 1f))
        addView(pad(), LinearLayout.LayoutParams(0, MATCH, 2f).apply { setMargins(8, 8, 8, 8) })
        addView(column(4, 5, 6), LinearLayout.LayoutParams(0, MATCH, 1f))
    }

    override fun onDestroy() {
        // إغلاق الاتصال يجعل هاتف الشاشة يرفع كل الأزرار تلقائياً
        io.execute { try { socket?.close() } catch (_: Throwable) {} }
        io.shutdown()
        super.onDestroy()
    }
}
