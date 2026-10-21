package com.padlink.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.method.DigitsKeyListener
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var ipBox: EditText
    private val permListener =
        Shizuku.OnRequestPermissionResultListener { _, _ -> runOnUiThread { refresh() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Shizuku.addRequestPermissionResultListener(permListener)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permListener)
        super.onDestroy()
    }

    private fun buildUi(): View {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }

        fun title(t: String) = TextView(this).apply {
            text = t
            textSize = 18f
            setPadding(0, pad, 0, pad / 2)
        }

        fun button(t: String, onClick: () -> Unit) = Button(this).apply {
            text = t
            setOnClickListener { onClick() }
        }

        // ① هاتف الشاشة
        col.addView(title("① هاتف الشاشة (اللعبة عليه)"))
        status = TextView(this)
        col.addView(status)
        col.addView(button("1) منح صلاحية Shizuku") { grantShizuku() })
        col.addView(button("2) منح «العرض فوق التطبيقات»") {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        })
        col.addView(button("3) تشغيل الاستقبال") {
            startForegroundService(Intent(this, ReceiverService::class.java))
            toast("يعمل ✓ — لتعديل الأزرار: افتح اللعبة، اسحب الإشعارات، اضغط «تعديل الأزرار»")
        })
        col.addView(button("إيقاف الاستقبال") {
            stopService(Intent(this, ReceiverService::class.java))
        })

        // ② هاتف الكيبورد
        col.addView(title("② هاتف الكيبورد"))
        ipBox = EditText(this).apply {
            hint = "IP هاتف الشاشة، مثال 192.168.1.5"
            keyListener = DigitsKeyListener.getInstance("0123456789.")
            setText(getSharedPreferences("app", MODE_PRIVATE).getString("ip", ""))
        }
        col.addView(ipBox)
        col.addView(button("اتصال") {
            val ip = ipBox.text.toString().trim()
            if (ip.isEmpty()) {
                toast("اكتب الـ IP")
            } else {
                getSharedPreferences("app", MODE_PRIVATE).edit().putString("ip", ip).apply()
                startActivity(Intent(this, ControllerActivity::class.java).putExtra("ip", ip))
            }
        })

        return ScrollView(this).apply { addView(col) }
    }

    private fun grantShizuku() {
        try {
            when {
                !Shizuku.pingBinder() -> toast("شغّل تطبيق Shizuku أولاً")
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED ->
                    toast("الصلاحية ممنوحة ✓")
                else -> Shizuku.requestPermission(1001)
            }
        } catch (t: Throwable) {
            toast("Shizuku: ${t.message}")
        }
    }

    private fun refresh() {
        val sh = try {
            when {
                !Shizuku.pingBinder() -> "Shizuku: غير شغال ✗"
                Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED ->
                    "Shizuku: شغال لكن الصلاحية غير ممنوحة ✗"
                else -> "Shizuku: جاهز ✓"
            }
        } catch (t: Throwable) {
            "Shizuku: ؟"
        }
        val ov = if (Settings.canDrawOverlays(this)) "✓" else "✗"
        status.text = "$sh\nالعرض فوق التطبيقات: $ov\nعنوان IP: ${localIps()}\nالمنفذ: ${ReceiverService.PORT}"
    }

    private fun localIps(): String = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && (it.name.contains("wlan") || it.name.startsWith("ap")) }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .joinToString(" / ") { it.hostAddress ?: "" }
            .ifEmpty { "غير متصل بشبكة واي فاي" }
    } catch (t: Throwable) {
        "؟"
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
}
