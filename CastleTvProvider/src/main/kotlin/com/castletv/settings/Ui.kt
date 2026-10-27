package com.castletv.settings

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue

/** All settings UI is built in code (no XML resources): backgrounds with focus states for TV remotes. */
object Ui {
    val ACCENT = Color.parseColor("#4F8BFF")

    fun dp(ctx: Context, v: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()

    private fun shape(ctx: Context, fill: Int, radius: Int, stroke: Int = 0, strokeColor: Int = 0) =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(ctx, radius).toFloat()
            if (stroke > 0) setStroke(dp(ctx, stroke), strokeColor)
        }

    private fun states(focused: Drawable, pressed: Drawable?, normal: Drawable) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), focused)
        pressed?.let { addState(intArrayOf(android.R.attr.state_pressed), it) }
        addState(intArrayOf(), normal)
    }

    /** Transparent button background, white ring when focused. */
    fun outline(ctx: Context): Drawable = states(
        shape(ctx, Color.TRANSPARENT, 6, 2, Color.WHITE),
        shape(ctx, Color.parseColor("#33FFFFFF"), 6),
        shape(ctx, Color.TRANSPARENT, 6),
    )

    fun dialogBackground(ctx: Context): Drawable =
        shape(ctx, Color.parseColor("#1A1A1A"), 14, 1, Color.parseColor("#33FFFFFF"))

    fun sectionRow(ctx: Context): Drawable = states(
        shape(ctx, Color.parseColor("#262626"), 12, 2, Color.WHITE),
        null,
        shape(ctx, Color.parseColor("#222222"), 12),
    )

    fun settingsItem(ctx: Context): Drawable = states(
        shape(ctx, Color.parseColor("#1E1E1E"), 10, 2, Color.WHITE),
        shape(ctx, Color.parseColor("#2D2D2D"), 10),
        shape(ctx, Color.parseColor("#1E1E1E"), 10),
    )

    fun circle(color: Int): Drawable = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
}
