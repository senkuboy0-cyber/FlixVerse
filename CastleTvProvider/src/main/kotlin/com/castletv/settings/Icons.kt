package com.castletv.settings

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/** Line icons drawn with Canvas on a 24×24 grid (no XML vectors, no emoji). */
class IconDrawable(private val kind: Kind, color: Int = Color.WHITE) : Drawable() {
    enum class Kind { SEARCH, SAVE }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        this.color = color
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val size = minOf(b.width(), b.height()).toFloat()
        if (size <= 0f) return
        val s = size / 24f
        canvas.save()
        canvas.translate(b.left + (b.width() - size) / 2f, b.top + (b.height() - size) / 2f)
        canvas.scale(s, s)
        paint.strokeWidth = 2f
        when (kind) {
            Kind.SEARCH -> {
                canvas.drawCircle(10.5f, 10.5f, 6.5f, paint)
                canvas.drawLine(15.5f, 15.5f, 20.5f, 20.5f, paint)
            }
            Kind.SAVE -> {
                // Floppy disk: body with a cut corner, shutter on top, label circle in the middle
                val body = Path().apply {
                    moveTo(5f, 3f); lineTo(16.5f, 3f); lineTo(21f, 7.5f); lineTo(21f, 19f)
                    quadTo(21f, 21f, 19f, 21f); lineTo(5f, 21f); quadTo(3f, 21f, 3f, 19f)
                    lineTo(3f, 5f); quadTo(3f, 3f, 5f, 3f); close()
                }
                canvas.drawPath(body, paint)
                canvas.drawRoundRect(RectF(7f, 3f, 15f, 8f), 1f, 1f, paint)
                canvas.drawCircle(12f, 14.5f, 3f, paint)
            }
        }
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
