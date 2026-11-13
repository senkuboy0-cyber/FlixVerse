package com.castletv.settings

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * Line icons drawn with Canvas on a 24×24 grid: no XML vectors, no emoji, no font glyphs.
 * Stroke style: 2px round caps, similar to Feather/Lucide line icons.
 *
 * The intrinsic size must be reported — without it every wrap_content ImageView that
 * shows an icon collapses to its padding and the button becomes invisible while still
 * catching touches (that was the "ghost back button" bug).
 */
class IconDrawable(private val kind: Kind, color: Int = Color.WHITE) : Drawable() {

    enum class Kind { BACK, SEARCH, CLEAR, PENCIL, CHEVRON_UP, CHEVRON_DOWN, RESET }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 2f
        this.color = color
    }

    override fun getIntrinsicWidth() = ICON
    override fun getIntrinsicHeight() = ICON

    private fun line(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float) =
        canvas.drawLine(x1, y1, x2, y2, paint)

    override fun draw(canvas: Canvas) {
        val b = bounds
        val size = minOf(b.width(), b.height()).toFloat()
        if (size <= 0f) return
        canvas.save()
        canvas.translate(b.left + (b.width() - size) / 2f, b.top + (b.height() - size) / 2f)
        canvas.scale(size / 24f, size / 24f)
        when (kind) {
            Kind.BACK -> {
                line(canvas, 20f, 12f, 5f, 12f)
                line(canvas, 11f, 6f, 5f, 12f)
                line(canvas, 11f, 18f, 5f, 12f)
            }
            Kind.SEARCH -> {
                canvas.drawCircle(11f, 11f, 6.5f, paint)
                line(canvas, 15.8f, 15.8f, 20.5f, 20.5f)
            }
            Kind.CLEAR -> {
                line(canvas, 6.5f, 6.5f, 17.5f, 17.5f)
                line(canvas, 17.5f, 6.5f, 6.5f, 17.5f)
            }
            Kind.PENCIL -> {
                // Body + rounded tip (Feather "edit-2")
                val pencil = Path().apply {
                    moveTo(17f, 3f)
                    arcTo(RectF(16.17f, 2.17f, 21.83f, 7.83f), 225f, 180f)
                    lineTo(7.5f, 20.5f)
                    lineTo(2f, 22f)
                    lineTo(3.5f, 16.5f)
                    close()
                }
                canvas.drawPath(pencil, paint)
            }
            Kind.CHEVRON_UP -> {
                line(canvas, 6f, 14.5f, 12f, 8.5f)
                line(canvas, 12f, 8.5f, 18f, 14.5f)
            }
            Kind.CHEVRON_DOWN -> {
                line(canvas, 6f, 9.5f, 12f, 15.5f)
                line(canvas, 12f, 15.5f, 18f, 9.5f)
            }
            Kind.RESET -> {
                // Open circle with an arrow head at the gap (refresh / undo)
                canvas.drawArc(RectF(4.5f, 4.5f, 19.5f, 19.5f), 60f, 280f, false, paint)
                val endAngle = Math.toRadians(340.0)
                val ex = 12f + 7.5f * Math.cos(endAngle).toFloat()
                val ey = 12f + 7.5f * Math.sin(endAngle).toFloat()
                // Two wings pointing back along the arc direction (tangent at 340° is 70°)
                listOf(215.0, 285.0).forEach { deg ->
                    val a = Math.toRadians(deg)
                    line(canvas, ex, ey, ex + 4f * Math.cos(a).toFloat(), ey + 4f * Math.sin(a).toFloat())
                }
            }
        }
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        const val ICON = 24
    }
}

/** Round toggle: accent disc with a check when on, dark disc with a ring when off. */
class CheckCircleDrawable(
    private val onColor: Int,
    private val offFill: Int,
    private val offRing: Int,
    private val checkColor: Int,
) : Drawable() {

    var checked: Boolean = false
        set(value) { field = value; invalidateSelf() }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        strokeCap = Paint.Cap.ROUND
    }
    private val check = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = checkColor
    }

    override fun getIntrinsicWidth() = IconDrawable.ICON
    override fun getIntrinsicHeight() = IconDrawable.ICON

    override fun draw(canvas: Canvas) {
        val b = bounds
        val size = minOf(b.width(), b.height()).toFloat()
        if (size <= 0f) return
        val cx = b.left + b.width() / 2f
        val cy = b.top + b.height() / 2f
        val r = size * 0.42f
        if (checked) {
            fill.color = onColor
            canvas.drawCircle(cx, cy, r, fill)
            val s = size / 24f
            canvas.save()
            canvas.translate(cx - 12f * s, cy - 12f * s)
            canvas.scale(s, s)
            canvas.drawLine(5f, 12.5f, 10f, 17.5f, check)
            canvas.drawLine(10f, 17.5f, 19f, 7f, check)
            canvas.restore()
        } else {
            fill.color = offFill
            ring.color = offRing
            canvas.drawCircle(cx, cy, r, fill)
            canvas.drawCircle(cx, cy, r - 1f, ring)
        }
    }

    override fun setAlpha(alpha: Int) {
        fill.alpha = alpha; ring.alpha = alpha; check.alpha = alpha; invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fill.colorFilter = colorFilter; ring.colorFilter = colorFilter; check.colorFilter = colorFilter; invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
