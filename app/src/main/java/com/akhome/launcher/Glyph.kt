package com.akhome.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Small white vector icons for the pills, drawn in code so the APK stays tiny. */
class Glyph(ctx: Context, private val kind: Int) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeCap = Paint.Cap.ROUND }
    private val rect = RectF()       // reused: nothing is allocated while drawing
    private val path = Path()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val s = min(w, h); val cx = w / 2; val cy = h / 2
        when (kind) {
            WIFI -> {
                p.style = Paint.Style.STROKE; p.strokeWidth = 0.11f * s
                val oy = cy + 0.36f * s
                for (k in 0..1) {
                    val r = (if (k == 0) 0.48f else 0.72f) * s
                    rect.set(cx - r, oy - r, cx + r, oy + r); c.drawArc(rect, 225f, 90f, false, p)
                }
                p.style = Paint.Style.FILL
                c.drawCircle(cx, oy - 0.06f * s, 0.1f * s, p)
            }
            GEAR -> {
                p.style = Paint.Style.STROKE; p.strokeWidth = 0.14f * s
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    c.drawLine(cx + 0.3f * s * cos(a).toFloat(), cy + 0.3f * s * sin(a).toFloat(),
                        cx + 0.44f * s * cos(a).toFloat(), cy + 0.44f * s * sin(a).toFloat(), p)
                }
                p.strokeWidth = 0.12f * s
                c.drawCircle(cx, cy, 0.26f * s, p)
            }
            GRID -> {
                p.style = Paint.Style.FILL
                val q = 0.3f * s; val g = 0.12f * s; val r = 0.09f * s
                val x0 = cx - q - g / 2; val y0 = cy - q - g / 2
                for (i in 0..1) for (j in 0..1) {
                    val x = x0 + i * (q + g); val y = y0 + j * (q + g)
                    rect.set(x, y, x + q, y + q); c.drawRoundRect(rect, r, r, p)
                }
            }
            INPUTS -> {
                p.style = Paint.Style.STROKE; p.strokeWidth = 0.1f * s
                rect.set(cx - 0.38f * s, cy - 0.26f * s, cx + 0.38f * s, cy + 0.26f * s)
                c.drawRoundRect(rect, 0.06f * s, 0.06f * s, p)
                c.drawLine(cx - 0.16f * s, cy, cx + 0.1f * s, cy, p)
                c.drawLine(cx + 0.1f * s, cy, cx, cy - 0.1f * s, p)
                c.drawLine(cx + 0.1f * s, cy, cx, cy + 0.1f * s, p)
            }
            BOLT -> {
                p.style = Paint.Style.FILL
                path.reset()
                for (i in BOLT_PTS.indices step 2) {
                    val x = BOLT_PTS[i] * w; val y = BOLT_PTS[i + 1] * h
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                c.drawPath(path, p)
            }
        }
    }

    companion object {
        const val WIFI = 1; const val GEAR = 2; const val GRID = 3; const val INPUTS = 4; const val BOLT = 5
        private val BOLT_PTS = floatArrayOf(0.6f, 0f, 0.16f, 0.57f, 0.47f, 0.57f, 0.37f, 1f, 0.86f, 0.4f, 0.54f, 0.4f, 0.7f, 0f)
    }
}
