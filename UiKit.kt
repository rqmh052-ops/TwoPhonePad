package com.padlink.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

object Ui {
    val BG = 0xFF0E1116.toInt()
    val CARD = 0xFF1A1F29.toInt()
    val CARD2 = 0xFF252C3A.toInt()
    val BLUE = 0xFF3D7BFF.toInt()
    val GREEN = 0xFF22B573.toInt()
    val RED = 0xFFE5484D.toInt()
    val AMBER = 0xFFFFB020.toInt()
    val TEXT = 0xFFE8ECF1.toInt()
    val MUTED = 0xFF8B95A5.toInt()
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

fun Context.bg(color: Int, radius: Int = 14, strokeColor: Int = 0, strokeDp: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (strokeDp > 0) setStroke(dp(strokeDp), strokeColor)
    }

fun Context.text(t: String, size: Float = 14f, color: Int = Ui.TEXT, bold: Boolean = false): TextView =
    TextView(this).apply {
        text = t
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

/** زر مخصّص (ليس زر النظام): خلفية مدورة + تأثير لمس. */
fun Context.chip(t: String, color: Int, onClick: () -> Unit): TextView = TextView(this).apply {
    text = t
    textSize = 15f
    setTextColor(Color.WHITE)
    typeface = Typeface.DEFAULT_BOLD
    gravity = Gravity.CENTER
    setPadding(dp(16), dp(13), dp(16), dp(13))
    background = RippleDrawable(ColorStateList.valueOf(0x55FFFFFF), bg(color, 14), null)
    isClickable = true
    setOnClickListener { onClick() }
}

fun Context.card(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(16), dp(14), dp(16), dp(16))
    background = bg(Ui.CARD, 20)
}

fun LinearLayout.put(v: View, top: Int = 0, w: Int = ViewGroup.LayoutParams.MATCH_PARENT): View {
    addView(v, LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = context.dp(top)
    })
    return v
}
