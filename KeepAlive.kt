package com.padlink.app

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/**
 * يمنع أندرويد من التخلي عن شبكة الواي فاي/الهوتسبوت لأنها «بدون إنترنت».
 * بدون هذا يتحقق النظام من الإنترنت بعد ثوانٍ، ثم قد يقطع الشبكة أو يحوّل الاتصال للبيانات
 * فينقطع الاتصال بين الهاتفين. طلب شبكة صريح (requestNetwork) يُبقيها، وربط العملية بها يُثبّت المسار.
 */
object NetKeeper {
    private const val TAG = "PadLink"
    private var cb: ConnectivityManager.NetworkCallback? = null
    private var users = 0

    @Synchronized
    fun start(ctx: Context) {
        users++
        if (cb != null) return
        try {
            val cm = ctx.applicationContext.getSystemService(ConnectivityManager::class.java)
            val req = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            val c = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    try { cm.bindProcessToNetwork(network) } catch (t: Throwable) { Log.e(TAG, "bind failed", t) }
                }

                override fun onLost(network: Network) {
                    try { cm.bindProcessToNetwork(null) } catch (_: Throwable) {}
                }
            }
            cm.requestNetwork(req, c)
            cb = c
        } catch (t: Throwable) {
            Log.e(TAG, "NetKeeper failed", t)
        }
    }

    @Synchronized
    fun stop(ctx: Context) {
        if (users > 0) users--
        if (users > 0) return
        val c = cb ?: return
        cb = null
        try {
            val cm = ctx.applicationContext.getSystemService(ConnectivityManager::class.java)
            cm.unregisterNetworkCallback(c)
            cm.bindProcessToNetwork(null)
        } catch (_: Throwable) {
        }
    }
}

/**
 * مربع صغير جداً يعرض «1» فوق كل التطبيقات (اختياري). نافذة ظاهرة فوق التطبيقات تجعل أندرويد
 * يعامل العملية كأنها ظاهرة للمستخدم فلا يجمّدها أو يقتلها أثناء اللعبة. لا يلتقط اللمس إطلاقاً.
 */
class KeepDot(private val ctx: Context) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: TextView? = null

    fun show() {
        if (view != null || !Settings.canDrawOverlays(ctx)) return
        val tv = TextView(ctx).apply {
            text = "1"
            textSize = 8f
            includeFontPadding = false
            setTextColor(Color.WHITE)
            setBackgroundColor(0x66000000)
            gravity = Gravity.CENTER
            setPadding(ctx.dp(3), 0, ctx.dp(3), 0)
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            alpha = 0.7f // أقل من 0.8 حتى لا يحجب أندرويد 12+ اللمس تحته
        }
        try {
            wm.addView(tv, lp)
            view = tv
        } catch (t: Throwable) {
            Log.e("PadLink", "dot failed", t)
        }
    }

    fun hide() {
        view?.let { try { wm.removeView(it) } catch (_: Throwable) {} }
        view = null
    }
}
