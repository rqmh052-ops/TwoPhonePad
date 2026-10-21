package com.padlink.app

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** طبقة تعديل الأزرار فوق اللعبة: اسحب كل زر فوق مكانه في اللعبة ثم «تم». */
class Overlay(private val ctx: Context, private val onClosed: () -> Unit) {

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val root = FrameLayout(ctx)
    private val markers = LinkedHashMap<Int, TextView>()
    private val size = ctx.dp(58)

    fun show() {
        root.setBackgroundColor(0xAA000000.toInt())

        val hint = ctx.text("اسحب كل زر فوق مكانه في اللعبة (C = بداية سحب الكاميرا)", 13f, Color.WHITE).apply {
            gravity = Gravity.CENTER
            setPadding(ctx.dp(12), 0, ctx.dp(12), 0)
        }
        val done = ctx.chip("تم ✓", Ui.GREEN) { save(); close() }
        val cancel = ctx.chip("إلغاء", Ui.RED) { close() }
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(ctx.dp(8), ctx.dp(24), ctx.dp(8), ctx.dp(8))
            addView(cancel)
            addView(hint, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(done)
        }
        root.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        for (id in listOf(0) + Layout.ids(ctx)) {
            val lab = Layout.label(ctx, id)
            val tv = TextView(ctx).apply {
                text = lab
                setTextColor(Color.WHITE)
                textSize = if (lab.length > 2) 13f else 20f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (id == 0) 0xDD22B573.toInt() else 0xDD3D7BFF.toInt())
                    setStroke(ctx.dp(2), Color.WHITE)
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
