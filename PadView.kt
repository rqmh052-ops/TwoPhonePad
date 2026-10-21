package com.padlink.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.hypot
import kotlin.math.min

const val T_BTN = 0
const val T_PAD = 1
const val M_HOLD = 0
const val M_TOGGLE = 1
const val M_TURBO = 2
const val M_TAP = 3 // ضغطة واحدة فقط: نقرة قصيرة تُنفَّذ على هاتف الشاشة مهما طال إمساكك
const val M_AIM = 4 // ضغط مستمر + سحب: إصبع الزر يتحرك بنفس حركة إصبعك (إطلاق + تصويب)

val PALETTE = listOf(
    "أزرق" to 0xFF3D7BFF.toInt(), "أخضر" to 0xFF22B573.toInt(), "أحمر" to 0xFFE5484D.toInt(),
    "برتقالي" to 0xFFF08A24.toInt(), "بنفسجي" to 0xFF8E5CF7.toInt(), "وردي" to 0xFFE0449B.toInt(),
    "سماوي" to 0xFF1FB5D1.toInt(), "رمادي" to 0xFF5B6678.toInt()
)

/** عنصر على لوحة التحكم: زر (T_BTN) أو منطقة الكاميرا (T_PAD). المواقع نسب من 0 إلى 1. */
class PadItem(
    var id: Int, var type: Int, var label: String,
    var x: Float, var y: Float, var sw: Float, var sh: Float,
    var color: Int, var mode: Int = M_HOLD, var shape: Int = 0
) {
    var touching = false
    var latched = false
    var sent = false
}

object PadStore {
    private fun prefs(c: Context) = c.getSharedPreferences("pad", Context.MODE_PRIVATE)

    fun defaults(): MutableList<PadItem> {
        val l = ArrayList<PadItem>()
        l += PadItem(0, T_PAD, "منطقة الكاميرا", 0.5f, 0.5f, 0.34f, 0.62f, PALETTE[1].second)
        val ys = floatArrayOf(0.22f, 0.5f, 0.78f)
        for (i in 0 until 3) {
            l += PadItem(i + 1, T_BTN, "${i + 1}", 0.12f, ys[i], 0.24f, 0f, PALETTE[0].second)
            l += PadItem(i + 4, T_BTN, "${i + 4}", 0.88f, ys[i], 0.24f, 0f, PALETTE[3].second)
        }
        return l
    }

    fun load(c: Context): MutableList<PadItem> {
        val s = prefs(c).getString("items", null) ?: return defaults()
        return try {
            val a = JSONArray(s)
            val l = ArrayList<PadItem>()
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                l += PadItem(
                    o.getInt("id"), o.getInt("t"), o.getString("l"),
                    o.getDouble("x").toFloat(), o.getDouble("y").toFloat(),
                    o.getDouble("sw").toFloat(), o.getDouble("sh").toFloat(),
                    o.getInt("c"), o.getInt("m"), o.getInt("s")
                )
            }
            if (l.none { it.type == T_PAD }) defaults() else l
        } catch (_: Throwable) {
            defaults()
        }
    }

    fun save(c: Context, items: List<PadItem>) {
        val a = JSONArray()
        for (it in items) a.put(JSONObject().apply {
            put("id", it.id); put("t", it.type); put("l", it.label)
            put("x", it.x.toDouble()); put("y", it.y.toDouble())
            put("sw", it.sw.toDouble()); put("sh", it.sh.toDouble())
            put("c", it.color); put("m", it.mode); put("s", it.shape)
        })
        prefs(c).edit().putString("items", a.toString()).apply()
    }
}

/** لوحة التحكم المرسومة يدوياً: أزرار حرة الحركة، لمس متعدد، تثبيت، تكرار، ضغطة مطولة للخيارات. */
class PadView(ctx: Context, var items: MutableList<PadItem>) : View(ctx) {

    var editMode = false
        set(v) { field = v; releaseAll(); invalidate() }
    var alphaScale = 1f
        set(v) { field = v; invalidate() }
    var lpMenu = false
    var onSend: (String) -> Unit = {}
    var onMenu: ((PadItem) -> Unit)? = null
    var onChanged: (() -> Unit)? = null

    private class Touch(val item: PadItem, val sx: Float, val sy: Float, val ox: Float, val oy: Float) {
        var lx = sx; var ly = sy; var ax = 0f; var ay = 0f
        var moved = false; var menu = false; var lp: Runnable? = null
    }

    private val owners = HashMap<Int, Touch>()
    private val turbo = HashMap<PadItem, Runnable>()
    private var padX = 0f
    private var padY = 0f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
    private fun d(v: Float) = v * resources.displayMetrics.density

    private fun col(rgb: Int, a: Float): Int =
        Color.argb((a * alphaScale * 255).toInt().coerceIn(0, 255), Color.red(rgb), Color.green(rgb), Color.blue(rgb))

    private fun mix(c: Int, to: Int, f: Float): Int = Color.rgb(
        (Color.red(c) + (Color.red(to) - Color.red(c)) * f).toInt(),
        (Color.green(c) + (Color.green(to) - Color.green(c)) * f).toInt(),
        (Color.blue(c) + (Color.blue(to) - Color.blue(c)) * f).toInt()
    )

    // ───────────── منطق الحالة ─────────────

    private fun update(it: PadItem) {
        val want = it.latched ||
            (it.touching && (it.mode == M_HOLD || it.mode == M_AIM) && it.type == T_BTN)
        if (want != it.sent) {
            it.sent = want
            onSend(if (want) "D ${it.id}" else "U ${it.id}")
        }
    }

    fun latch(it: PadItem, on: Boolean) {
        it.latched = on
        update(it)
        invalidate()
    }

    private fun startTurbo(it: PadItem) {
        val r = object : Runnable {
            var phase = false
            override fun run() {
                phase = !phase
                it.sent = phase
                onSend(if (phase) "D ${it.id}" else "U ${it.id}")
                invalidate()
                postDelayed(this, if (phase) 35L else 45L)
            }
        }
        turbo[it] = r
        r.run()
    }

    private fun stopTurbo(it: PadItem) {
        turbo.remove(it)?.let { r -> removeCallbacks(r) }
        if (it.sent) {
            it.sent = false
            onSend("U ${it.id}")
        }
    }

    fun releaseAll() {
        for (t in owners.values) t.lp?.let { removeCallbacks(it) }
        owners.clear()
        for (it in items) {
            if (it.type == T_PAD && it.touching) onSend("P U")
            it.touching = false
            it.latched = false
            if (it.type == T_PAD) continue
            stopTurbo(it)
            update(it)
        }
    }

    /** بعد إعادة الاتصال: أعد إرسال الأزرار المضغوطة (المثبّتة). */
    fun resync() {
        for (it in items) if (it.sent && it.type == T_BTN && it.mode != M_TURBO) onSend("D ${it.id}")
    }

    fun cfgString(): String =
        items.filter { it.type == T_BTN }.joinToString(",") { "${it.id}:${it.label.replace(Regex("[,: ]"), "_")}" }

    fun addButton(): PadItem? {
        val id = (1..Proto.MAX_BTN).firstOrNull { n -> items.none { it.id == n } } ?: return null
        val n = PadItem(id, T_BTN, "$id", 0.5f, 0.5f, 0.22f, 0f, PALETTE[4].second)
        items.add(n)
        invalidate()
        return n
    }

    fun duplicate(src: PadItem): PadItem? {
        val n = addButton() ?: return null
        n.label = src.label; n.color = src.color; n.mode = src.mode; n.shape = src.shape; n.sw = src.sw
        n.x = (src.x + 0.08f).coerceAtMost(0.95f); n.y = (src.y + 0.08f).coerceAtMost(0.95f)
        return n
    }

    fun remove(it: PadItem) {
        stopTurbo(it)
        it.latched = false
        update(it)
        items.remove(it)
        invalidate()
    }

    // ───────────── اللمس ─────────────

    private fun hit(x: Float, y: Float): PadItem? {
        val w = width.toFloat(); val h = height.toFloat(); val m = min(w, h)
        for (it in items.asReversed()) {
            val cx = it.x * w; val cy = it.y * h
            if (it.type == T_PAD) {
                if (x in (cx - it.sw * w / 2)..(cx + it.sw * w / 2) && y in (cy - it.sh * h / 2)..(cy + it.sh * h / 2)) return it
            } else {
                val r = it.sw * m / 2
                val ok = if (it.shape == 0) hypot(x - cx, y - cy) <= r else (Math.abs(x - cx) <= r && Math.abs(y - cy) <= r)
                if (ok) return it
            }
        }
        return null
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val it = hit(e.getX(i), e.getY(i))
                if (it != null) down(it, e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) move(e.getPointerId(i), e.getX(i), e.getY(i))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> up(e.getPointerId(e.actionIndex))
            MotionEvent.ACTION_CANCEL -> for (pid in owners.keys.toList()) up(pid)
        }
        return true
    }

    private fun down(it: PadItem, pid: Int, x: Float, y: Float) {
        val t = Touch(it, x, y, it.x * width - x, it.y * height - y)
        owners[pid] = t
        if (editMode || lpMenu) {
            val r = Runnable {
                if (!t.moved) {
                    t.menu = true
                    if (!editMode) release(t)
                    onMenu?.invoke(it)
                }
            }
            t.lp = r
            postDelayed(r, if (editMode) 450L else 700L)
        }
        if (editMode) return
        padX = x; padY = y
        it.touching = true
        when {
            it.type == T_PAD -> onSend("P D")
            it.mode == M_TOGGLE -> { it.latched = !it.latched; update(it) }
            it.mode == M_TURBO -> startTurbo(it)
            it.mode == M_TAP -> onSend("T ${it.id}")
            else -> update(it)
        }
        invalidate()
    }

    private fun move(pid: Int, x: Float, y: Float) {
        val t = owners[pid] ?: return
        val it = t.item
        if (t.menu) return
        if (!t.moved && hypot(x - t.sx, y - t.sy) > d(if (editMode) 8f else 14f)) {
            t.moved = true
            t.lp?.let { removeCallbacks(it) }
        }
        if (editMode) {
            if (t.moved) {
                it.x = ((x + t.ox) / width).coerceIn(0.03f, 0.97f)
                it.y = ((y + t.oy) / height).coerceIn(0.05f, 0.95f)
                invalidate()
            }
            return
        }
        if (it.type == T_PAD) {
            t.ax += x - t.lx; t.ay += y - t.ly
            val ix = t.ax.toInt(); val iy = t.ay.toInt()
            if (ix != 0 || iy != 0) {
                onSend("P M $ix $iy")
                t.ax -= ix; t.ay -= iy
            }
            padX = x; padY = y
            invalidate()
        } else if (it.mode == M_AIM && it.touching) {
            t.ax += x - t.lx; t.ay += y - t.ly
            val ix = t.ax.toInt(); val iy = t.ay.toInt()
            if (ix != 0 || iy != 0) {
                onSend("DM ${it.id} $ix $iy")
                t.ax -= ix; t.ay -= iy
            }
        }
        t.lx = x; t.ly = y
    }

    private fun up(pid: Int) {
        val t = owners.remove(pid) ?: return
        t.lp?.let { removeCallbacks(it) }
        if (editMode) {
            if (t.moved) onChanged?.invoke()
            return
        }
        if (!t.menu) release(t)
    }

    private fun release(t: Touch) {
        val it = t.item
        it.touching = false
        when {
            it.type == T_PAD -> onSend("P U")
            it.mode == M_TURBO -> stopTurbo(it)
            else -> update(it)
        }
        invalidate()
    }

    // ───────────── الرسم ─────────────

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val m = min(w, h)
        fill.shader = LinearGradient(0f, 0f, 0f, h, 0xFF121722.toInt(), 0xFF06080C.toInt(), Shader.TileMode.CLAMP)
        cv.drawRect(0f, 0f, w, h, fill)
        fill.shader = null
        if (editMode) {
            fill.color = 0x22FFFFFF
            var gx = d(24f)
            while (gx < w) { var gy = d(24f); while (gy < h) { cv.drawCircle(gx, gy, d(1.2f), fill); gy += d(24f) }; gx += d(24f) }
            txt.color = 0x99FFFFFF.toInt(); txt.textSize = d(12f)
            cv.drawText("وضع التعديل — اسحب لتحريك • اضغط مطولاً للخيارات", w / 2, h - d(10f), txt)
        }
        for (it in items) if (it.type == T_PAD) drawPad(cv, it, w, h) else drawBtn(cv, it, w, h, m)
    }

    private fun drawPad(cv: Canvas, it: PadItem, w: Float, h: Float) {
        val cx = it.x * w; val cy = it.y * h
        val r = RectF(cx - it.sw * w / 2, cy - it.sh * h / 2, cx + it.sw * w / 2, cy + it.sh * h / 2)
        fill.color = col(it.color, if (it.touching) 0.30f else 0.16f)
        cv.drawRoundRect(r, d(22f), d(22f), fill)
        stroke.strokeWidth = d(2f); stroke.color = col(it.color, 0.7f)
        stroke.pathEffect = if (editMode) DashPathEffect(floatArrayOf(d(8f), d(6f)), 0f) else null
        cv.drawRoundRect(r, d(22f), d(22f), stroke)
        stroke.pathEffect = null
        stroke.strokeWidth = d(1f); stroke.color = col(Color.WHITE, 0.10f)
        cv.drawLine(cx, r.top + d(14f), cx, r.bottom - d(14f), stroke)
        cv.drawLine(r.left + d(14f), cy, r.right - d(14f), cy, stroke)
        txt.color = col(Color.WHITE, 0.45f); txt.textSize = d(13f)
        cv.drawText(it.label, cx, r.bottom - d(14f), txt)
        if (it.touching && !editMode) {
            fill.color = col(it.color, 0.45f); cv.drawCircle(padX, padY, d(30f), fill)
            stroke.strokeWidth = d(2f); stroke.color = col(Color.WHITE, 0.8f); cv.drawCircle(padX, padY, d(30f), stroke)
        }
    }

    private fun drawBtn(cv: Canvas, it: PadItem, w: Float, h: Float, m: Float) {
        val cx = it.x * w; val cy = it.y * h
        val down = it.sent || it.touching
        val rad = it.sw * m / 2 * (if (down) 0.94f else 1f)
        val rect = RectF(cx - rad, cy - rad, cx + rad, cy + rad)
        val corner = rad * 0.38f
        if (down) {
            stroke.strokeWidth = d(5f); stroke.color = col(it.color, 0.45f)
            if (it.shape == 0) cv.drawCircle(cx, cy, rad * 1.13f, stroke)
            else cv.drawRoundRect(RectF(rect).apply { inset(-rad * 0.13f, -rad * 0.13f) }, corner, corner, stroke)
        }
        fill.setShadowLayer(d(10f), 0f, d(4f), col(Color.BLACK, 0.7f))
        fill.color = col(it.color, 1f)
        if (it.shape == 0) cv.drawCircle(cx, cy, rad, fill) else cv.drawRoundRect(rect, corner, corner, fill)
        fill.clearShadowLayer()
        val top = mix(it.color, Color.WHITE, if (down) 0.45f else 0.22f)
        val bot = mix(it.color, Color.BLACK, if (down) 0.10f else 0.32f)
        fill.shader = LinearGradient(cx, cy - rad, cx, cy + rad, col(top, 1f), col(bot, 1f), Shader.TileMode.CLAMP)
        if (it.shape == 0) cv.drawCircle(cx, cy, rad, fill) else cv.drawRoundRect(rect, corner, corner, fill)
        fill.shader = null
        stroke.strokeWidth = d(if (it.latched) 3.5f else 2f)
        stroke.color = if (it.latched) col(Ui.AMBER, 1f) else col(Color.WHITE, if (down) 0.9f else 0.35f)
        if (it.shape == 0) cv.drawCircle(cx, cy, rad, stroke) else cv.drawRoundRect(rect, corner, corner, stroke)
        if (editMode) {
            stroke.strokeWidth = d(1.5f); stroke.color = col(Color.WHITE, 0.5f)
            stroke.pathEffect = DashPathEffect(floatArrayOf(d(6f), d(5f)), 0f)
            cv.drawCircle(cx, cy, rad + d(7f), stroke)
            stroke.pathEffect = null
        }
        val len = it.label.length
        txt.textSize = (rad * 0.72f / (1f + 0.28f * (len - 1).coerceAtLeast(0))).coerceAtLeast(rad * 0.3f)
        txt.color = col(Color.WHITE, 1f)
        val fm = txt.fontMetrics
        cv.drawText(it.label, cx, cy - (fm.ascent + fm.descent) / 2, txt)
        if (it.mode != M_HOLD) {
            txt.textSize = rad * 0.34f
            cv.drawText(
                when (it.mode) { M_TURBO -> "⚡"; M_TAP -> "×1"; M_AIM -> "◎"; else -> "⇅" },
                cx, cy + rad * 0.78f, txt
            )
        }
    }
}
