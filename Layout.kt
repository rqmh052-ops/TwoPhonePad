package com.padlink.app

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.view.Display

/** هاتف الشاشة: مكان كل زر على شاشة اللعبة (نسبة 0..1). الرقم 0 = بداية سحب الكاميرا. */
object Layout {
    private val defaults = mapOf(
        0 to (0.65f to 0.40f), 1 to (0.90f to 0.35f), 2 to (0.90f to 0.55f),
        3 to (0.80f to 0.75f), 4 to (0.90f to 0.75f), 5 to (0.15f to 0.75f), 6 to (0.25f to 0.85f)
    )

    private fun prefs(c: Context) = c.getSharedPreferences("layout", Context.MODE_PRIVATE)

    fun get(c: Context, id: Int): Pair<Float, Float> {
        val d = defaults[id] ?: ((0.1f + 0.06f * (id % 12)) to (0.15f + 0.05f * (id % 14)))
        val p = prefs(c)
        return p.getFloat("x$id", d.first) to p.getFloat("y$id", d.second)
    }

    fun put(c: Context, id: Int, x: Float, y: Float) {
        prefs(c).edit().putFloat("x$id", x).putFloat("y$id", y).apply()
    }

    /** يخزّن قائمة الأزرار التي أرسلها هاتف التحكم (id:اسم) ليعرضها المحرّر. */
    fun setCfg(c: Context, cfg: String) {
        val ids = ArrayList<Int>()
        val e = prefs(c).edit()
        for (part in cfg.split(",")) {
            val kv = part.split(":", limit = 2)
            val id = kv[0].trim().toIntOrNull() ?: continue
            if (id !in 1..Proto.MAX_BTN) continue
            ids += id
            e.putString("l$id", kv.getOrElse(1) { "$id" })
        }
        e.putString("ids", ids.joinToString(",")).apply()
    }

    fun ids(c: Context): List<Int> {
        val s = prefs(c).getString("ids", null)
        val l = s?.split(",")?.mapNotNull { it.toIntOrNull() } ?: emptyList()
        return if (l.isEmpty()) (1..6).toList() else l
    }

    fun label(c: Context, id: Int): String =
        if (id == 0) "C" else prefs(c).getString("l$id", "$id") ?: "$id"

    fun reset(c: Context) = prefs(c).edit().clear().apply()

    @Suppress("DEPRECATION")
    fun screenSize(c: Context): Pair<Float, Float> {
        val dm = c.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val p = Point()
        dm.getDisplay(Display.DEFAULT_DISPLAY).getRealSize(p)
        return p.x.toFloat() to p.y.toFloat()
    }
}
