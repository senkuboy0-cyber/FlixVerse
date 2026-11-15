package com.streamverse.setting

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * Line icons drawn with Canvas on a 24×24 grid: no XML vectors, no emoji, no font glyphs.
 * Stroke style: 2px round caps, similar to Feather/Lucide line icons.
 * Intrinsic size is reported so wrap_content ImageViews never collapse to zero.
 */
class SvIconDrawable(private val kind: Kind, color: Int = Color.WHITE) : Drawable() {

    enum class Kind { BACK, PLUS, TRASH, FILE, LINK, PLAYLIST, RESET, CHECK, INFO, CLOSE }

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
            Kind.PLUS -> {
                line(canvas, 12f, 5f, 12f, 19f)
                line(canvas, 5f, 12f, 19f, 12f)
            }
            Kind.TRASH -> {
                // Lid, handle and body with two inner ridges (Feather "trash-2")
                line(canvas, 4f, 7f, 20f, 7f)
                line(canvas, 9.5f, 7f, 9.5f, 4.5f)
                line(canvas, 14.5f, 7f, 14.5f, 4.5f)
                val body = android.graphics.Path().apply {
                    moveTo(7f, 7f)
                    lineTo(8f, 20f)
                    lineTo(16f, 20f)
                    lineTo(17f, 7f)
                }
                canvas.drawPath(body, paint)
                line(canvas, 10.2f, 11f, 10.2f, 16.5f)
                line(canvas, 13.8f, 11f, 13.8f, 16.5f)
            }
            Kind.FILE -> {
                // Document with a folded corner
                val doc = android.graphics.Path().apply {
                    moveTo(6f, 3f)
                    lineTo(13f, 3f)
                    lineTo(18f, 8f)
                    lineTo(18f, 21f)
                    lineTo(6f, 21f)
                    close()
                }
                canvas.drawPath(doc, paint)
                line(canvas, 13f, 3f, 13f, 8f)
                line(canvas, 13f, 8f, 18f, 8f)
            }
            Kind.LINK -> {
                // Two chain capsules joined in the middle
                val left = RectF(2f, 8.5f, 12f, 15.5f)
                val right = RectF(12f, 8.5f, 22f, 15.5f)
                canvas.drawRoundRect(left, 3.5f, 3.5f, paint)
                canvas.drawRoundRect(right, 3.5f, 3.5f, paint)
            }
            Kind.PLAYLIST -> {
                // List lines plus a play triangle
                line(canvas, 5f, 7f, 15f, 7f)
                line(canvas, 5f, 12f, 13f, 12f)
                line(canvas, 5f, 17f, 11f, 17f)
                val play = android.graphics.Path().apply {
                    moveTo(14.5f, 13.5f)
                    lineTo(20f, 17f)
                    lineTo(14.5f, 20.5f)
                    close()
                }
                canvas.drawPath(play, paint)
            }
            Kind.RESET -> {
                // Open circle with an arrow head at the gap (refresh / restart)
                canvas.drawArc(RectF(4.5f, 4.5f, 19.5f, 19.5f), 60f, 280f, false, paint)
                val endAngle = Math.toRadians(340.0)
                val ex = 12f + 7.5f * Math.cos(endAngle).toFloat()
                val ey = 12f + 7.5f * Math.sin(endAngle).toFloat()
                listOf(215.0, 285.0).forEach { deg ->
                    val a = Math.toRadians(deg)
                    line(canvas, ex, ey, ex + 4f * Math.cos(a).toFloat(), ey + 4f * Math.sin(a).toFloat())
                }
            }
            Kind.CHECK -> {
                line(canvas, 5f, 12.5f, 10f, 17.5f)
                line(canvas, 10f, 17.5f, 19f, 7f)
            }
            Kind.INFO -> {
                canvas.drawCircle(12f, 12f, 8.5f, paint)
                line(canvas, 12f, 11f, 12f, 16f)
                canvas.drawPoint(12f, 8f, paint)
            }
            Kind.CLOSE -> {
                line(canvas, 6.5f, 6.5f, 17.5f, 17.5f)
                line(canvas, 17.5f, 6.5f, 6.5f, 17.5f)
            }
        }
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha; invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter; invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        const val ICON = 24
    }
}
