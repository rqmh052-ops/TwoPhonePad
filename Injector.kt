package com.padlink.app

import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.Method

/**
 * يحقن لمسات (مع دعم أكثر من إصبع) عبر Shizuku.
 * لا يلمس ملفات اللعبة ولا ذاكرتها إطلاقاً؛ فقط لمس على الشاشة مثل الإصبع.
 */
object Injector {
    private const val TAG = "PadLink"

    private var inputManager: Any? = null
    private var injectMethod: Method? = null
    private var lastTry = 0L

    /** آخر خطأ حقن (null = يعمل). يظهر في شاشة الاستقبال للتشخيص. */
    @Volatile var lastError: String? = null

    private val active = LinkedHashMap<Int, FloatArray>() // رقم الإصبع -> [x, y]
    private var downTime = 0L

    @Synchronized
    fun ensure(): Boolean {
        if (injectMethod != null) return true
        val now = SystemClock.uptimeMillis()
        if (now - lastTry < 2000) return false
        lastTry = now
        return try {
            HiddenApiBypass.addHiddenApiExemptions("L")
            val binder = SystemServiceHelper.getSystemService("input")
            val stub = Class.forName("android.hardware.input.IInputManager\$Stub")
            inputManager = stub.getMethod("asInterface", IBinder::class.java)
                .invoke(null, ShizukuBinderWrapper(binder))
            injectMethod = Class.forName("android.hardware.input.IInputManager")
                .getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType!!)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Injector init failed (is Shizuku running + permission granted?)", t)
            lastError = "Shizuku غير جاهز: ${t.message ?: t.javaClass.simpleName}"
            false
        }
    }

    @Synchronized
    fun down(id: Int, x: Float, y: Float) {
        if (active.containsKey(id)) return
        val first = active.isEmpty()
        if (first) downTime = SystemClock.uptimeMillis()
        active[id] = floatArrayOf(x, y)
        val index = active.keys.indexOf(id)
        send(
            if (first) MotionEvent.ACTION_DOWN
            else MotionEvent.ACTION_POINTER_DOWN or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        )
    }

    @Synchronized
    fun move(id: Int, x: Float, y: Float) {
        val p = active[id] ?: return
        p[0] = x
        p[1] = y
        send(MotionEvent.ACTION_MOVE)
    }

    @Synchronized
    fun up(id: Int) {
        if (!active.containsKey(id)) return
        val index = active.keys.indexOf(id)
        send(
            if (active.size == 1) MotionEvent.ACTION_UP
            else MotionEvent.ACTION_POINTER_UP or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        )
        active.remove(id)
    }

    /** موقع الإصبع الحالي (نسخة) أو null لو غير مضغوط. */
    @Synchronized
    fun pos(id: Int): FloatArray? = active[id]?.copyOf()

    @Synchronized
    fun releaseAll() {
        for (id in active.keys.toList()) up(id)
    }

    private fun send(action: Int) {
        if (!ensure()) return
        val n = active.size
        val props = Array(n) { MotionEvent.PointerProperties() }
        val coords = Array(n) { MotionEvent.PointerCoords() }
        var i = 0
        for ((id, p) in active) {
            props[i].id = id
            props[i].toolType = MotionEvent.TOOL_TYPE_FINGER
            coords[i].x = p[0]
            coords[i].y = p[1]
            coords[i].pressure = 1f
            coords[i].size = 1f
            i++
        }
        val ev = MotionEvent.obtain(
            downTime, SystemClock.uptimeMillis(), action, n, props, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
        )
        try {
            injectMethod?.invoke(inputManager, ev, 0) // 0 = ASYNC
            lastError = null
        } catch (t: Throwable) {
            Log.e(TAG, "inject failed", t)
            val c = t.cause ?: t
            lastError = c.message ?: c.javaClass.simpleName
            // لو مات ربط Shizuku يبقى الكائن القديم ميتاً للأبد؛ نصفّره ليُعاد بناؤه تلقائياً (كل ثانيتين كحد أقصى)
            injectMethod = null
            inputManager = null
            active.clear() // النظام لم يستلم هذه الأصابع؛ نبدأ نظيفاً بعد إعادة البناء
        } finally {
            ev.recycle()
        }
    }
}
