package io.github.kzahel.tiptoimanager

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

enum class UiIcon { MENU, BACK, PEN, BOOKS, DOWNLOAD, TRANSFER, STORAGE, CHEVRON, REFRESH, INFO, CHECK, MORE }

/** Small original line icons, drawn at any density. */
class IconDrawable(private val icon: UiIcon, tint: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = tint; strokeWidth = 1.8f; strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND; style = Paint.Style.STROKE
    }
    override fun draw(canvas: Canvas) {
        canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
        fun line(x: Float, y: Float, xx: Float, yy: Float) = canvas.drawLine(x, y, xx, yy, paint)
        fun path(vararg points: Float) {
            val path = Path().apply {
                moveTo(points[0], points[1])
                for (i in 2 until points.size step 2) lineTo(points[i], points[i + 1])
            }
            canvas.drawPath(path, paint)
        }
        when (icon) {
            UiIcon.MENU -> { line(4f, 6f, 20f, 6f); line(4f, 12f, 20f, 12f); line(4f, 18f, 20f, 18f) }
            UiIcon.BACK -> { path(11f, 5f, 4f, 12f, 11f, 19f); line(4f, 12f, 21f, 12f) }
            UiIcon.PEN -> { path(5f, 15f, 15f, 5f, 19f, 9f, 9f, 19f, 4f, 20f, 5f, 15f); line(13f, 7f, 17f, 11f) }
            UiIcon.BOOKS -> {
                canvas.drawRoundRect(RectF(3f, 5f, 8f, 20f), 1f, 1f, paint)
                canvas.drawRoundRect(RectF(10f, 3f, 15f, 20f), 1f, 1f, paint)
                path(18f, 5f, 22f, 19f, 18f, 20f)
            }
            UiIcon.DOWNLOAD -> { line(12f, 3f, 12f, 15f); path(7f, 10f, 12f, 15f, 17f, 10f); path(4f, 16f, 4f, 21f, 20f, 21f, 20f, 16f) }
            UiIcon.TRANSFER -> { path(3f, 7f, 18f, 7f, 14f, 3f); path(21f, 17f, 6f, 17f, 10f, 21f) }
            UiIcon.STORAGE -> { canvas.drawRoundRect(RectF(3f, 4f, 21f, 20f), 3f, 3f, paint); line(3f, 14f, 21f, 14f); line(16f, 17f, 17f, 17f) }
            UiIcon.CHEVRON -> path(9f, 6f, 15f, 12f, 9f, 18f)
            UiIcon.REFRESH -> { canvas.drawArc(RectF(4f, 4f, 20f, 20f), 40f, 280f, false, paint); path(20f, 4f, 20f, 9f, 15f, 9f) }
            UiIcon.INFO -> { canvas.drawCircle(12f, 12f, 9f, paint); line(12f, 11f, 12f, 17f); line(12f, 7f, 12f, 7.1f) }
            UiIcon.CHECK -> path(4f, 12f, 10f, 18f, 21f, 6f)
            UiIcon.MORE -> { line(12f, 5f, 12f, 5.1f); line(12f, 12f, 12f, 12.1f); line(12f, 19f, 12f, 19.1f) }
        }
        canvas.restore()
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated platform method")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
