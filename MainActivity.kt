package com.padlink.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.text.method.DigitsKeyListener
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku

class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("app", MODE_PRIVATE) }

    private lateinit var recvSec: LinearLayout
    private lateinit var ctlSec: LinearLayout
    private lateinit var tabRecv: TextView
    private lateinit var tabCtl: TextView
    private lateinit var startBtn: TextView
    private lateinit var dotBtn: TextView
    private lateinit var infoTv: TextView
    private lateinit var scanTv: TextView
    private lateinit var devList: LinearLayout
    private val dots = HashMap<String, View>()

    private val found = LinkedHashMap<String, Found>()
    private var stopDisc: (() -> Unit)? = null
    private var bt: BtScanner? = null

    private val permListener =
        Shizuku.OnRequestPermissionResultListener { _, _ -> runOnUiThread { refresh() } }

    private val tick = object : Runnable {
        override fun run() { refresh(); ui.postDelayed(this, 1000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Shizuku.addRequestPermissionResultListener(permListener)
        askPermissions()
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG
        setContentView(buildUi())
        showRole(prefs.getInt("role", 0))
    }

    override fun onResume() { super.onResume(); ui.post(tick) }
    override fun onPause() { ui.removeCallbacks(tick); stopScan(); super.onPause() }
    override fun onDestroy() { Shizuku.removeRequestPermissionResultListener(permListener); super.onDestroy() }

    private fun askPermissions() {
        val l = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            l += Manifest.permission.BLUETOOTH_CONNECT
            l += Manifest.permission.BLUETOOTH_SCAN
            l += Manifest.permission.BLUETOOTH_ADVERTISE
        } else {
            l += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 33) l += Manifest.permission.POST_NOTIFICATIONS
        val need = l.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 7)
    }

    // ───────────── البناء ─────────────

    private fun buildUi(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(16), dp(44), dp(16), dp(24))
        }
        col.put(text("PadLink", 30f, Ui.TEXT, true))
        col.put(text("حوّل هاتفك إلى يد تحكم للهاتف الآخر — بدون أي كابلات", 13f, Ui.MUTED), 2)

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = bg(Ui.CARD, 16)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        tabRecv = tab("📺  هاتف الشاشة") { showRole(0) }
        tabCtl = tab("🎮  هاتف التحكم") { showRole(1) }
        tabs.addView(tabRecv, LinearLayout.LayoutParams(0, -2, 1f))
        tabs.addView(tabCtl, LinearLayout.LayoutParams(0, -2, 1f))
        col.put(tabs, 16)

        recvSec = buildReceiver()
        ctlSec = buildController()
        col.put(recvSec, 14)
        col.put(ctlSec, 14)
        return ScrollView(this).apply { setBackgroundColor(Ui.BG); isFillViewport = true; addView(col) }
    }

    private fun tab(t: String, onClick: () -> Unit) = TextView(this).apply {
        text = t; textSize = 15f; gravity = Gravity.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setPadding(0, dp(12), 0, dp(12))
        setOnClickListener { onClick() }
    }

    private fun showRole(r: Int) {
        prefs.edit().putInt("role", r).apply()
        recvSec.visibility = if (r == 0) View.VISIBLE else View.GONE
        ctlSec.visibility = if (r == 1) View.VISIBLE else View.GONE
        fun style(t: TextView, on: Boolean) {
            t.setTextColor(if (on) Color.WHITE else Ui.MUTED)
            t.background = if (on) bg(Ui.BLUE, 12) else null
        }
        style(tabRecv, r == 0); style(tabCtl, r == 1)
        if (r == 1) startScan() else stopScan()
    }

    private fun statusRow(key: String, label: String, action: String?, onAction: () -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val dot = View(this@MainActivity).apply { background = bg(Ui.RED, 6) }
            dots[key] = dot
            addView(dot, LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginEnd = dp(10) })
            addView(text(label, 15f), LinearLayout.LayoutParams(0, -2, 1f))
            if (action != null) addView(chip(action, Ui.CARD2, onAction).apply { textSize = 13f; setPadding(dp(14), dp(8), dp(14), dp(8)) })
        }

    private fun buildReceiver(): LinearLayout {
        val sec = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val c1 = card()
        c1.put(text("الجاهزية", 17f, Ui.TEXT, true))
        c1.put(statusRow("shizuku", "Shizuku (حقن اللمس)", "منح") { grantShizuku() }, 12)
        c1.put(statusRow("overlay", "العرض فوق التطبيقات", "منح") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }, 10)
        c1.put(statusRow("bt", "البلوتوث (مفعّل + صلاحية)", "تفعيل") { enableBt() }, 10)
        c1.put(statusRow("batt", "استثناء توفير البطارية (يمنع قتل التطبيق)", "منح") { requestBattery() }, 10)
        sec.put(c1)

        startBtn = chip("تشغيل الاستقبال", Ui.GREEN) { toggleService() }
        sec.put(startBtn, 12)

        val c2 = card()
        c2.put(text("بيانات الاتصال", 17f, Ui.TEXT, true))
        infoTv = text("", 14f, Ui.TEXT).apply {
            setLineSpacing(dp(4).toFloat(), 1f)
            setOnClickListener {
                val ip = Proto.localIps().firstOrNull()?.second ?: return@setOnClickListener
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("ip", ip))
                toast("تم نسخ $ip")
            }
        }
        c2.put(infoTv, 10)
        sec.put(c2, 12)

        sec.put(chip("تعديل أماكن الأزرار على اللعبة", Ui.BLUE) {
            if (!Settings.canDrawOverlays(this)) toast("فعّل «العرض فوق التطبيقات» أولاً")
            else {
                startForegroundService(Intent(this, ReceiverService::class.java).setAction(ReceiverService.ACTION_EDIT))
                toast("اضغط زر الرئيسية وافتح اللعبة — الأرقام ستبقى فوقها")
            }
        }, 12)
        dotBtn = chip("", Ui.CARD2) { toggleDot() }
        sec.put(dotBtn, 10)
        sec.put(chip("جعل البلوتوث قابلاً للاكتشاف (5 دقائق)", Ui.CARD2) {
            try {
                startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                    .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300))
            } catch (t: Throwable) { toast("تعذّر: ${t.message}") }
        }, 10)
        return sec
    }

    private fun input(hint: String): EditText = EditText(this).apply {
        this.hint = hint
        setHintTextColor(Ui.MUTED); setTextColor(Ui.TEXT); textSize = 15f
        background = bg(Ui.CARD2, 12)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        setSingleLine()
    }

    private fun buildController(): LinearLayout {
        val sec = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val last = prefs.getString("lastAddr", null)
        if (last != null) {
            sec.put(chip("↻ إعادة الاتصال بآخر جهاز: ${prefs.getString("lastName", last)}", Ui.GREEN) {
                connect(Target(prefs.getInt("lastKind", 0), last, prefs.getString("lastName", last) ?: last))
            })
        }

        val c1 = card()
        c1.put(text("أجهزة قريبة (بحث تلقائي)", 17f, Ui.TEXT, true))
        scanTv = text("", 12f, Ui.MUTED)
        c1.put(scanTv, 4)
        devList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        c1.put(devList, 8)
        c1.put(chip("إعادة البحث", Ui.CARD2) { startScan() }, 10)
        sec.put(c1, if (last != null) 12 else 0)

        val c2 = card()
        c2.put(text("اتصال يدوي", 17f, Ui.TEXT, true))
        val ip = input("IP هاتف الشاشة، مثال 192.168.1.5").apply {
            keyListener = DigitsKeyListener.getInstance("0123456789.")
            setText(prefs.getString("ip", ""))
        }
        val pin = input("رمز PIN (4 أرقام) الظاهر على هاتف الشاشة").apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefs.getString("pin_manual", ""))
        }
        c2.put(ip, 10); c2.put(pin, 8)
        c2.put(chip("اتصال", Ui.BLUE) {
            val a = ip.text.toString().trim(); val p = pin.text.toString().trim()
            if (a.isEmpty() || p.isEmpty()) toast("اكتب الـ IP والـ PIN")
            else {
                prefs.edit().putString("ip", a).putString("pin_manual", p).putString("pin_$a", p).apply()
                connect(Target(0, a, a), p)
            }
        }, 10)
        sec.put(c2, 12)
        sec.put(text("نصيحة: لو ما في واي فاي، شغّل هوتسبوت على أحد الهاتفين وصل الآخر به — يشتغل الاتصال نفسه، أو استعمل البلوتوث.", 12f, Ui.MUTED), 12)
        return sec
    }

    // ───────────── الاتصال ─────────────

    private fun connect(t: Target, pin: String? = null) {
        val p = pin ?: prefs.getString("pin_${t.addr}", null)
        if (p == null) {
            val et = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; setTextColor(Color.WHITE); hint = "PIN" }
            AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("رمز PIN لـ ${t.name}").setMessage("يظهر في تبويب «هاتف الشاشة» على الهاتف الآخر")
                .setView(et)
                .setPositiveButton("اتصال") { _, _ ->
                    val v = et.text.toString().trim()
                    if (v.isNotEmpty()) { prefs.edit().putString("pin_${t.addr}", v).apply(); connect(t, v) }
                }.show()
            return
        }
        prefs.edit().putString("lastAddr", t.addr).putString("lastName", t.name).putInt("lastKind", t.kind)
            .putString("pin_${t.addr}", p).apply()
        stopScan()
        startActivity(Intent(this, ControllerActivity::class.java)
            .putExtra("kind", t.kind).putExtra("addr", t.addr).putExtra("name", t.name).putExtra("pin", p))
    }

    private fun startScan() {
        stopScan()
        found.clear(); renderDevices()
        scanTv.text = "جارٍ البحث: Wi-Fi (UDP + mDNS + فحص الشبكة) + بلوتوث…"
        stopDisc = Discovery.start(this) { f -> add(f) }
        bt = BtScanner(this) { f -> add(f) }.also { it.start() }
    }

    private fun stopScan() {
        stopDisc?.invoke(); stopDisc = null
        bt?.stop(); bt = null
    }

    private fun add(f: Found) = runOnUiThread {
        val key = "${f.kind}:${f.addr}"
        val old = found[key]
        // لا نستبدل اسماً حقيقياً باسم IP
        if (old == null || (old.name == old.addr && f.name != f.addr)) found[key] = f
        else if (!old.via.contains(f.via)) found[key] = old.copy(via = old.via + "+" + f.via)
        renderDevices()
    }

    private fun renderDevices() {
        devList.removeAllViews()
        if (found.isEmpty()) {
            scanTv.text = scanTv.text.toString().ifEmpty { "لا أجهزة بعد" }
            return
        }
        for (f in found.values.sortedBy { it.kind }) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = bg(Ui.CARD2, 14)
                setPadding(dp(14), dp(10), dp(14), dp(10))
                setOnClickListener { connect(Target(f.kind, f.addr, f.name)) }
            }
            row.addView(text((if (f.kind == 0) "📶 " else "🔵 ") + f.name, 15f, Ui.TEXT, true))
            row.addView(text("${f.addr}  •  ${f.via}", 12f, Ui.MUTED))
            devList.put(row, 8)
        }
    }

    // ───────────── الاستقبال ─────────────

    private fun toggleService() {
        if (ReceiverService.running) {
            stopService(Intent(this, ReceiverService::class.java))
            ReceiverService.running = false
        } else {
            startForegroundService(Intent(this, ReceiverService::class.java))
            toast("يعمل ✓ — لتعديل الأزرار اضغط الزر الأزرق أو إشعار PadLink")
        }
        ui.postDelayed({ refresh() }, 300)
    }

    private fun requestBattery() {
        try {
            val pm = getSystemService(PowerManager::class.java)
            if (pm.isIgnoringBatteryOptimizations(packageName)) { toast("مستثنى بالفعل ✓"); return }
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Throwable) {
            try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            catch (t: Throwable) { toast("تعذّر: ${t.message}") }
        }
    }

    /** المربع الصغير «1» فوق التطبيقات (اختياري) — يبقي النظام يعتبر التطبيق ظاهراً فلا يقتله. */
    private fun toggleDot() {
        val on = !prefs.getBoolean("dot", false)
        if (on && !Settings.canDrawOverlays(this)) { toast("فعّل «العرض فوق التطبيقات» أولاً"); return }
        prefs.edit().putBoolean("dot", on).apply()
        if (ReceiverService.running) {
            startForegroundService(Intent(this, ReceiverService::class.java).setAction(ReceiverService.ACTION_DOT))
        }
        refresh()
    }

    private fun enableBt() {
        askPermissions()
        try {
            val ad = getSystemService(BluetoothManager::class.java)?.adapter
            if (ad != null && !ad.isEnabled) startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } catch (_: Throwable) {}
    }

    private fun grantShizuku() {
        try {
            when {
                !Shizuku.pingBinder() -> toast("شغّل تطبيق Shizuku أولاً")
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> toast("الصلاحية ممنوحة ✓")
                else -> Shizuku.requestPermission(1001)
            }
        } catch (t: Throwable) {
            toast("Shizuku: ${t.message}")
        }
    }

    private fun refresh() {
        fun dot(k: String, ok: Boolean) { dots[k]?.background = bg(if (ok) Ui.GREEN else Ui.RED, 6) }
        val shizuku = try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) { false }
        dot("shizuku", shizuku)
        dot("overlay", Settings.canDrawOverlays(this))
        val btPerm = Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        val btOn = try { getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true } catch (_: Throwable) { false }
        dot("bt", btPerm && btOn)
        dot("batt", try { getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName) } catch (_: Throwable) { false })
        val dotOn = prefs.getBoolean("dot", false)
        dotBtn.text = if (dotOn) "نقطة الإبقاء «1» فوق التطبيقات: مفعّلة ✓" else "نقطة الإبقاء «1» فوق التطبيقات (اختياري): متوقفة"
        dotBtn.background = bg(if (dotOn) Ui.GREEN else Ui.CARD2, 14)

        val run = ReceiverService.running
        startBtn.text = if (run) "إيقاف الاستقبال" else "تشغيل الاستقبال"
        startBtn.background = bg(if (run) Ui.RED else Ui.GREEN, 14)

        fun mk(ok: Boolean) = if (ok) "✓" else "✗"
        val ips = Proto.localIps()
        val sb = StringBuilder()
        sb.append(if (ips.isEmpty()) "عناوين IP: لا توجد شبكة (شغّل واي فاي أو هوتسبوت)\n"
        else "عناوين IP (اضغط لنسخ الأول):\n" + ips.joinToString("\n") { "   ${it.first}  →  ${it.second}" } + "\n")
        sb.append("المنفذ: ${Proto.TCP_PORT}\n")
        sb.append("رمز PIN: ${Proto.pin(this)}\n")
        sb.append("بلوتوث: ${try { getSystemService(BluetoothManager::class.java)?.adapter?.name ?: "؟" } catch (_: Throwable) { "؟" }}\n\n")
        sb.append("القنوات:  Wi-Fi/TCP ${mk(ReceiverService.tcpOk)}   اكتشاف UDP ${mk(ReceiverService.udpOk)}\n")
        sb.append("                 mDNS ${mk(ReceiverService.nsdOk)}   Bluetooth ${mk(ReceiverService.btOk)}\n\n")
        val p = ReceiverService.peer
        sb.append(if (p != null) "🟢 متصل الآن: $p عبر ${ReceiverService.via}" else "⚪ لا يوجد جهاز متصل")
        if (ReceiverService.lastDrop.isNotEmpty()) {
            val ago = (SystemClock.uptimeMillis() - ReceiverService.lastDropAt) / 1000
            sb.append("\n\nآخر انقطاع: ${ReceiverService.lastDrop} (قبل $ago ث)")
        }
        Injector.lastError?.let { sb.append("\n\n⚠ حقن اللمس: $it") }
        infoTv.text = sb.toString()
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
}
