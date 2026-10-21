package com.padlink.app

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** طبقة تعديل الأزرار فوق اللعبة: اسحب كل رقم فوق زره ثم اضغط «تم». */
class Overlay(private val ctx: Context, private val onClosed: () -> Unit) {

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val root = FrameLayout(ctx)
    private val markers = LinkedHashMap<Int, TextView>()
    private val d = ctx.resources.displayMetrics.density
    private val size = (60 * d).toInt()

    fun show() {
        root.setBackgroundColor(0x99000000.toInt())

        val hint = TextView(ctx).apply {
            text = "اسحب كل رقم فوق زره في اللعبة (C = مكان بداية سحب الكاميرا)"
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
        }
        val done = Button(ctx).apply {
            text = "تم"
            setOnClickListener { save(); close() }
        }
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, (24 * d).toInt(), 0, 0)
            addView(hint)
            addView(done)
        }
        root.addView(
            bar,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
        )

        for (id in 0..Layout.BUTTONS) {
            val tv = TextView(ctx).apply {
                text = if (id == 0) "C" else "$id"
                setTextColor(Color.WHITE)
                textSize = 20f
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (id == 0) 0xCC2E7D32.toInt() else 0xCC1E88E5.toInt())
                    setStroke((2 * d).toInt(), Color.WHITE)
                }
            }
            var ox = 0f
            var oy = 0f
            tv.setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { ox = v.x - e.rawX; oy = v.y - e.rawY }
                    MotionEvent.ACTION_MOVE -> { v.x = e.rawX + ox; v.y = e.rawY + oy }
                }
                true
            }
            markers[id] = tv
            root.addView(tv, FrameLayout.LayoutParams(size, size))
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        wm.addView(root, lp)
        root.post { place() }
    }

    private fun place() {
        val (sw, sh) = Layout.screenSize(ctx)
        val loc = IntArray(2)
        root.getLocationOnScreen(loc)
        for ((id, v) in markers) {
            val (fx, fy) = Layout.get(ctx, id)
            v.x = fx * sw - loc[0] - size / 2f
            v.y = fy * sh - loc[1] - size / 2f
        }
    }

    private fun save() {
        val (sw, sh) = Layout.screenSize(ctx)
        val loc = IntArray(2)
        root.getLocationOnScreen(loc)
        for ((id, v) in markers) {
            val cx = loc[0] + v.x + size / 2f
            val cy = loc[1] + v.y + size / 2f
            Layout.put(ctx, id, cx / sw, cy / sh)
        }
    }

    fun close() {
        try { wm.removeView(root) } catch (_: Throwable) {}
        onClosed()
    }
}
