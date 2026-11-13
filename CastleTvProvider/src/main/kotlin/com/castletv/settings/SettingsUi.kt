package com.castletv.settings

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.widget.TextView

/**
 * Small UI toolkit for the Castle settings screen: the shared palette, dp/text
 * factories and rounded background helpers. Everything is code-drawn — no XML
 * resources, no emoji, no external assets.
 */
internal object SettingsTheme {
    val BG = Color.parseColor("#0E0F13")
    val SURFACE = Color.parseColor("#171A20")
    val SURFACE_HI = Color.parseColor("#242833")
    val STROKE = Color.parseColor("#2A2F38")
    val TEXT = Color.parseColor("#F4F6F9")
    val SUBTLE = Color.parseColor("#9AA1AD")
    val FAINT = Color.parseColor("#6B7280")
    val ACCENT = Color.parseColor("#4F8BFF")
    val ACCENT_HI = Color.parseColor("#6FA0FF")
    val ACCENT_DIM = (0x40 shl 24) or (ACCENT and 0x00FFFFFF)
    val PRESS = Color.parseColor("#33FFFFFF")
    val DANGER = Color.parseColor("#E57373")
}

internal fun Context.dp(v: Int): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

/** Plain label with the shared text style. */
internal fun Context.text(s: String, size: Float, color: Int, bold: Boolean = false) =
    TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

internal fun Context.shape(fill: Int, radius: Int, stroke: Int = 0, strokeColor: Int = 0) =
    GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        if (stroke > 0) setStroke(dp(stroke), strokeColor)
    }

internal fun states(focused: Drawable, pressed: Drawable?, normal: Drawable) = StateListDrawable().apply {
    addState(intArrayOf(android.R.attr.state_focused), focused)
    pressed?.let { addState(intArrayOf(android.R.attr.state_pressed), it) }
    addState(intArrayOf(), normal)
}

/** Transparent at rest, press tint + white ring on focus — for controls inside larger cards. */
internal fun Context.ghost(radius: Int = 12): Drawable = states(
    shape(SettingsTheme.PRESS, radius, 2, Color.WHITE),
    shape(SettingsTheme.PRESS, radius),
    shape(Color.TRANSPARENT, radius),
)

/**
 * Round icon-button background: a visible dark disc at rest (so the buttons on each row
 * can actually be seen), brighter on press and a white ring on focus for the TV remote.
 */
internal fun Context.buttonBg(radius: Int = 12): Drawable = states(
    shape(SettingsTheme.SURFACE_HI, radius, 2, Color.WHITE),
    shape(SettingsTheme.SURFACE_HI, radius),
    shape(SettingsTheme.SURFACE, radius, 1, SettingsTheme.STROKE),
)
