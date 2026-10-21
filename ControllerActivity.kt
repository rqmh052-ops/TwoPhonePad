package com.padlink.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

/** هاتف التحكم: لوحة أزرار حرة + شريط أدوات قابل للإخفاء + اتصال تلقائي الإعادة. */
class ControllerActivity : Activity() {

    private lateinit var pad: PadView
    private lateinit var link: Link
    private lateinit var strip: LinearLayout
    private lateinit var actions: LinearLayout
    private lateinit var handle: TextView
    private lateinit var status: TextView
    private lateinit var banner: TextView
    private lateinit var editBtn: TextView
    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("ctl", MODE_PRIVATE) }
    private var hidden = false
    private var sens = 2.0f

    private fun dlg() = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        sens = prefs.getFloat("sens", 2.0f)

        pad = PadView(this, PadStore.load(this)).apply {
            lpMenu = prefs.getBoolean("lpMenu", false)
            alphaScale = prefs.getFloat("alpha", 1f)
            onMenu = { itemMenu(it) }
            onChanged = { saveAll() }
        }
        buildUi()
        immersive()

        val t = Target(intent.getIntExtra("kind", 0), intent.getStringExtra("addr") ?: "", intent.getStringExtra("name") ?: "")
        link = Link(this, t, intent.getStringExtra("pin") ?: "") { ev, text, ping ->
            ui.post { onLink(ev, text, ping) }
        }
        pad.onSend = { link.send(it) }
        link.start()
    }

    private fun immersive() {
        window.setDecorFitsSystemWindows(false)
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
    }

    // ───────────── الواجهة ─────────────

    private fun tool(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 18f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(6), dp(12), dp(6))
        setOnClickListener { onClick() }
    }

    private fun buildUi() {
        val root = FrameLayout(this)
        root.addView(pad, FrameLayout.LayoutParams(-1, -1))

        handle = tool("☰") { toggleBar() }
        editBtn = tool("✎") { setEdit(!pad.editMode) }
        status = text("●", 13f, Ui.AMBER, true).apply { setPadding(dp(10), 0, dp(10), 0); gravity = Gravity.CENTER }
        actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            addView(editBtn)
            addView(tool("＋") { addButton() })
            addView(tool("👁") { setHidden(true) })
            addView(tool("⚙") { settings() })
            addView(tool("✕") { confirmExit() })
        }
        strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = bg(0xCC10141C.toInt(), 22, 0x33FFFFFF, 1)
            setPadding(dp(4), dp(2), dp(4), dp(2))
            addView(handle)
            addView(status)
            addView(actions)
        }
        root.addView(strip, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(6) })

        banner = text("", 14f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = bg(0xDDE5484D.toInt(), 14)
            visibility = View.GONE
        }
        root.addView(banner, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(58) })
        setContentView(root)
    }

    private fun toggleBar() {
        if (hidden) { setHidden(false); return }
        val open = actions.visibility != View.VISIBLE
        actions.visibility = if (open) View.VISIBLE else View.GONE
        handle.text = if (open) "◂" else "☰"
    }

    /** إخفاء الأزرار بالكامل (تبقى تعمل) — اضغط النقطة الصغيرة في الأعلى لإظهارها. */
    private fun setHidden(h: Boolean) {
        hidden = h
        actions.visibility = View.GONE
        handle.text = "☰"
        if (h) {
            if (pad.editMode) setEdit(false)
            pad.alphaScale = 0.07f
            strip.alpha = 0.25f
        } else {
            pad.alphaScale = prefs.getFloat("alpha", 1f)
            strip.alpha = 1f
        }
    }

    private fun setEdit(on: Boolean) {
        pad.editMode = on
        editBtn.text = if (on) "▶" else "✎"
        editBtn.setTextColor(if (on) Ui.AMBER else Color.WHITE)
        if (!on) saveAll()
    }

    private fun onLink(ev: Int, text: String, ping: Int) {
        when (ev) {
            EV_CONNECTING -> { status.setTextColor(Ui.AMBER); banner.text = text; banner.visibility = View.VISIBLE }
            EV_OK -> {
                banner.visibility = View.GONE
                status.setTextColor(Ui.GREEN); status.text = "● $text"
                link.send("SENS $sens")
                link.send("CFG ${pad.cfgString()}")
                pad.resync()
                getSharedPreferences("app", MODE_PRIVATE).edit()
                    .putString("lastAddr", intent.getStringExtra("addr")).apply()
            }
            EV_PING -> {
                status.setTextColor(if (ping < 40) Ui.GREEN else if (ping < 100) Ui.AMBER else Ui.RED)
                status.text = "● ${ping}ms"
            }
            EV_LOST -> { status.setTextColor(Ui.RED); banner.text = text; banner.visibility = View.VISIBLE }
            EV_PIN -> {
                toast(text)
                finish()
            }
        }
    }

    private fun saveAll() {
        PadStore.save(this, pad.items)
        if (link.connected) link.send("CFG ${pad.cfgString()}")
    }

    // ───────────── القوائم ─────────────

    private fun addButton() {
        val n = pad.addButton()
        if (n == null) { toast("وصلت للحد الأقصى (${Proto.MAX_BTN} زر)"); return }
        setEdit(true)
        saveAll()
        toast("اسحب الزر لمكانه • اضغط مطولاً للخيارات • ثم حدّد مكانه على هاتف الشاشة")
    }

    private fun modeName(m: Int) = when (m) { M_TOGGLE -> "تبديل (يبقى مضغوطاً)"; M_TURBO -> "تكرار تلقائي ⚡"; else -> "عادي (اضغط وأفلت)" }

    private fun itemMenu(item: PadItem) {
        val names = ArrayList<String>()
        val acts = ArrayList<() -> Unit>()
        fun opt(n: String, a: () -> Unit) { names += n; acts += a }
        if (item.type == T_BTN) {
            opt("وضع الضغط: ${modeName(item.mode)}") { pickMode(item) }
            opt(if (item.latched) "🔓 رفع التثبيت" else "🔒 تثبيت الضغط الآن") { pad.latch(item, !item.latched) }
            opt("الاسم: ${item.label}") { rename(item) }
            opt("الشكل: ${if (item.shape == 0) "دائري" else "مربع مدور"}") { item.shape = 1 - item.shape; saveAll(); pad.invalidate() }
        }
        opt("الحجم") { resize(item) }
        opt("اللون") { pickColor(item) }
        if (item.type == T_BTN) {
            opt("نسخ الزر") { if (pad.duplicate(item) == null) toast("وصلت للحد الأقصى"); saveAll() }
            opt("حذف الزر") { pad.remove(item); saveAll() }
        }
        dlg().setTitle(if (item.type == T_PAD) "منطقة الكاميرا" else "زر ${item.label}")
            .setItems(names.toTypedArray()) { _, i -> acts[i]() }.show()
    }

    private fun pickMode(item: PadItem) {
        dlg().setTitle("وضع الضغط")
            .setItems(arrayOf(modeName(M_HOLD), modeName(M_TOGGLE), modeName(M_TURBO))) { _, i ->
                item.latched = false
                pad.latch(item, false)
                item.mode = i
                saveAll(); pad.invalidate()
            }.show()
    }

    private fun rename(item: PadItem) {
        val et = EditText(this).apply { setText(item.label); setTextColor(Color.WHITE); setSingleLine() }
        dlg().setTitle("اسم الزر").setView(et)
            .setPositiveButton("حفظ") { _, _ ->
                val v = et.text.toString().trim().take(6)
                if (v.isNotEmpty()) item.label = v
                saveAll(); pad.invalidate()
            }.show()
    }

    private fun pickColor(item: PadItem) {
        dlg().setTitle("اللون").setItems(PALETTE.map { it.first }.toTypedArray()) { _, i ->
            item.color = PALETTE[i].second
            saveAll(); pad.invalidate()
        }.show()
    }

    private fun resize(item: PadItem) {
        if (item.type == T_BTN) {
            sliders("حجم الزر", listOf(Triple("الحجم", 10, 45)), listOf((item.sw * 100).toInt())) { v ->
                item.sw = v[0] / 100f; pad.invalidate()
            }
        } else {
            sliders("حجم منطقة الكاميرا", listOf(Triple("العرض", 15, 80), Triple("الارتفاع", 20, 95)),
                listOf((item.sw * 100).toInt(), (item.sh * 100).toInt())) { v ->
                item.sw = v[0] / 100f; item.sh = v[1] / 100f; pad.invalidate()
            }
        }
    }

    /** نافذة بمنزلقات؛ onChange يُستدعى مباشرة أثناء السحب، والحفظ عند «تم». */
    private fun sliders(title: String, specs: List<Triple<String, Int, Int>>, cur: List<Int>, onChange: (List<Int>) -> Unit) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(12), dp(24), 0) }
        val vals = cur.toMutableList()
        specs.forEachIndexed { idx, (label, mn, mx) ->
            val tv = TextView(this).apply { text = "$label: ${vals[idx]}"; setTextColor(Color.WHITE) }
            val sb = SeekBar(this).apply { max = mx - mn; progress = vals[idx] - mn }
            sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    vals[idx] = p + mn; tv.text = "$label: ${p + mn}"; onChange(vals)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
            box.addView(tv); box.addView(sb)
        }
        dlg().setTitle(title).setView(box).setPositiveButton("تم") { _, _ -> saveAll() }.show()
    }

    private fun settings() {
        val names = arrayOf(
            "حساسية الكاميرا: ${"%.1f".format(sens)}",
            "شفافية الأزرار: ${(prefs.getFloat("alpha", 1f) * 100).toInt()}%",
            "ضغطة مطولة تفتح القائمة أثناء اللعب: ${if (pad.lpMenu) "مفعّلة" else "متوقفة"}",
            "استعادة التخطيط الافتراضي",
            "قطع الاتصال والخروج"
        )
        dlg().setTitle("الإعدادات").setItems(names) { _, i ->
            when (i) {
                0 -> sliders("حساسية الكاميرا (÷10)", listOf(Triple("الحساسية", 5, 80)), listOf((sens * 10).toInt())) { v ->
                    sens = v[0] / 10f
                    prefs.edit().putFloat("sens", sens).apply()
                    link.send("SENS $sens")
                }
                1 -> sliders("الشفافية %", listOf(Triple("الشفافية", 20, 100)), listOf((prefs.getFloat("alpha", 1f) * 100).toInt())) { v ->
                    prefs.edit().putFloat("alpha", v[0] / 100f).apply()
                    if (!hidden) pad.alphaScale = v[0] / 100f
                }
                2 -> {
                    pad.lpMenu = !pad.lpMenu
                    prefs.edit().putBoolean("lpMenu", pad.lpMenu).apply()
                    toast(if (pad.lpMenu) "الضغطة المطولة (0.7 ثانية) تفتح القائمة حتى أثناء اللعب" else "القائمة تفتح فقط في وضع التعديل ✎")
                }
                3 -> {
                    pad.releaseAll()
                    pad.items.clear(); pad.items.addAll(PadStore.defaults())
                    saveAll(); pad.invalidate()
                }
                4 -> finish()
            }
        }.show()
    }

    private fun confirmExit() {
        dlg().setTitle("قطع الاتصال؟").setPositiveButton("نعم") { _, _ -> finish() }.setNegativeButton("لا", null).show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (pad.editMode) setEdit(false) else confirmExit()
    }

    override fun onDestroy() {
        pad.releaseAll()
        link.close()
        super.onDestroy()
    }
}
