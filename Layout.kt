package com.padlink.app

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.view.Display

/** مكان كل زر على شاشة اللعبة (نسبة من 0 إلى 1). الرقم 0 = نقطة بداية سحب الكاميرا. */
object Layout {
    const val BUTTONS = 6

    private val defaults = mapOf(
        0 to (0.65f to 0.40f),
        1 to (0.90f to 0.35f),
        2 to (0.90f to 0.55f),
        3 to (0.80f to 0.75f),
        4 to (0.90f to 0.75f),
        5 to (0.15f to 0.75f),
        6 to (0.25f to 0.85f)
    )

    private fun prefs(c: Context) = c.getSharedPreferences("layout", Context.MODE_PRIVATE)

    fun get(c: Context, id: Int): Pair<Float, Float> {
        val d = defaults[id] ?: (0.5f to 0.5f)
        val p = prefs(c)
        return p.getFloat("x$id", d.first) to p.getFloat("y$id", d.second)
    }

    fun put(c: Context, id: Int, x: Float, y: Float) {
        prefs(c).edit().putFloat("x$id", x).putFloat("y$id", y).apply()
    }

    @Suppress("DEPRECATION")
    fun screenSize(c: Context): Pair<Float, Float> {
        val dm = c.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val p = Point()
        dm.getDisplay(Display.DEFAULT_DISPLAY).getRealSize(p)
        return p.x.toFloat() to p.y.toFloat()
    }
}
