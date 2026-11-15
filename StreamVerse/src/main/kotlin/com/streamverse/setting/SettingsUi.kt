package com.streamverse.setting

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.widget.TextView

/**
 * Small UI toolkit for the StreamVerse settings screen: the shared palette, dp/text
 * factories and rounded background helpers. Everything is code-drawn — no XML
 * resources, no emoji, no external assets.
 */
internal object StreamVerseTheme {
    val BG = Color.parseColor("#0B0C10")
    val SURFACE = Color.parseColor("#14161C")
    val SURFACE_HI = Color.parseColor("#20242E")
    val STROKE = Color.parseColor("#272C36")
    val TEXT = Color.parseColor("#F3F5F9")
    val SUBTLE = Color.parseColor("#99A1AD")
    val FAINT = Color.parseColor("#676E7A")
    val ACCENT = Color.parseColor("#8B5CF6")
    val ACCENT_HI = Color.parseColor("#A78BFA")
    val ACCENT_DIM = (0x40 shl 24) or (ACCENT and 0x00FFFFFF)
    val PRESS = Color.parseColor("#33FFFFFF")
    val DANGER = Color.parseColor("#E57373")
    val OK = Color.parseColor("#4ADE80")
}

internal fun Context.dp(v: Int): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

/** Plain label with the shared text style. */
internal fun Context.svText(s: String, size: Float, color: Int, bold: Boolean = false) =
    TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

internal fun Context.svShape(fill: Int, radius: Int, stroke: Int = 0, strokeColor: Int = 0) =
    GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        if (stroke > 0) setStroke(dp(stroke), strokeColor)
    }

internal fun svStates(focused: Drawable, pressed: Drawable?, normal: Drawable) = StateListDrawable().apply {
    addState(intArrayOf(android.R.attr.state_focused), focused)
    pressed?.let { addState(intArrayOf(android.R.attr.state_pressed), it) }
    addState(intArrayOf(), normal)
}

/** Transparent at rest, press tint + white ring on focus — for controls inside larger cards. */
internal fun Context.ghost(radius: Int = 12): Drawable = svStates(
    svShape(StreamVerseTheme.PRESS, radius, 2, Color.WHITE),
    svShape(StreamVerseTheme.PRESS, radius),
    svShape(Color.TRANSPARENT, radius),
)

/**
 * Round icon-button background: a visible dark disc at rest (so the buttons on each row
 * can actually be seen), brighter on press and a white ring on focus for the TV remote.
 */
internal fun Context.buttonBg(radius: Int = 12): Drawable = svStates(
    svShape(StreamVerseTheme.SURFACE_HI, radius, 2, Color.WHITE),
    svShape(StreamVerseTheme.SURFACE_HI, radius),
    svShape(StreamVerseTheme.SURFACE, radius, 1, StreamVerseTheme.STROKE),
)

/** Filled accent button: white ring on focus, brighter on press. */
internal fun Context.accentBg(radius: Int = 14): Drawable = svStates(
    svShape(StreamVerseTheme.ACCENT, radius, 2, Color.WHITE),
    svShape(StreamVerseTheme.ACCENT_HI, radius),
    svShape(StreamVerseTheme.ACCENT, radius),
)

/** Rounded single-line input field background. */
internal fun Context.fieldBg(focused: Boolean, radius: Int = 12): Drawable = if (focused) {
    svShape(StreamVerseTheme.SURFACE_HI, radius, 2, StreamVerseTheme.ACCENT)
} else {
    svShape(StreamVerseTheme.SURFACE, radius, 1, StreamVerseTheme.STROKE)
}
