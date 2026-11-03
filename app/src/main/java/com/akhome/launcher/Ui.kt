package com.akhome.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Size unit and drawing recipes of the AK Home look.
 * Sizes use dp = screenWidth / 1280, so the layout is identical on every panel regardless of density.
 */
class Ui(ctx: Context) {
    val w: Int
    val h: Int
    val dp: Float
    val light: Typeface
    val med: Typeface
    val bold: Typeface

    init {
        val m = ctx.resources.displayMetrics
        w = max(m.widthPixels, m.heightPixels)
        h = min(m.widthPixels, m.heightPixels)
        dp = w / 1280f
        light = font(ctx, "rx_light.ttf", Typeface.create("sans-serif-light", Typeface.NORMAL))
        med = font(ctx, "rx_med.ttf", Typeface.DEFAULT)
        bold = Typeface.create(med, Typeface.BOLD)
    }

    fun px(v: Float): Int = (v * dp).roundToInt()
    fun px(v: Int): Int = px(v.toFloat())

    /** Text size in raw px: s x dp x 1.25 (the 4.x scale factor). */
    fun text(tv: TextView, tf: Typeface, s: Float, color: Int): TextView = tv.apply {
        typeface = tf
        setTextSize(TypedValue.COMPLEX_UNIT_PX, s * dp * 1.25f)
        setTextColor(color)
        includeFontPadding = false
    }

    // Shared layout grid from the 4-column tray of the old versions.
    private val trayPad = px(14)
    private val gap = px(16)
    private val tileW = ((0.8f * w).roundToInt() - 2 * trayPad - 3 * gap) / 4
    val inset: Int = (w - (2 * trayPad + 4 * tileW + 3 * gap)) / 2 + trayPad
    val anchorY: Int = (0.6f * h).roundToInt()

    /** Carousel card size. */
    val cw: Int = (0.24f * w).toInt()
    val ch: Int = cw * 9 / 16

    /** Edge of a card / pill. Borderless trial: nothing when idle, a small white bar along the bottom when focused. */
    fun rim(focused: Boolean, radiusDp: Int = 16): android.graphics.drawable.Drawable =
        if (BORDERLESS) when {
            !focused -> android.graphics.drawable.ColorDrawable(0)
            // user's pick after trying a bar, a shine and a dot: a very light glass edge, a little outside the card
            // (a 3D feel, user) — favourites too; a huge radius just rounds the pill fully
            radiusDp >= 50 -> GlassRing(px(radiusDp).toFloat(), px(5).toFloat(), px(1.5f).toFloat())
            else -> GlassRing(px(radiusDp).toFloat(), px(6).toFloat(), px(1.5f).toFloat())
        }
        else edge(focused, radiusDp)

    private fun edge(focused: Boolean, radiusDp: Int): GradientDrawable = GradientDrawable().apply {
        setColor(Color.TRANSPARENT)
        cornerRadius = px(radiusDp).toFloat()
        if (focused) setStroke(px(2.5f), Color.WHITE) else setStroke(px(1), 0x26FFFFFF)
    }

    /**
     * Focused card (user, 2026-10-30): a very light, glassy white edge drawn [out] px outside the card, so it floats
     * around it instead of boxing it in. Draws beyond its bounds: the parent must not clip children.
     */
    class GlassRing(private val radius: Float, private val out: Float, private val width: Float) : android.graphics.drawable.Drawable() {
        private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.STROKE }
        private val r = android.graphics.RectF()
        override fun draw(c: android.graphics.Canvas) {
            r.set(bounds); r.inset(-out, -out)
            val rad = radius + out
            // soft halo first, then the fine line: reads as light on glass, not as a frame
            p.strokeWidth = width * 4; p.color = 0x14FFFFFF; c.drawRoundRect(r, rad, rad, p)
            p.strokeWidth = width; p.color = 0x99FFFFFF.toInt(); c.drawRoundRect(r, rad, rad, p)
        }
        override fun setAlpha(a: Int) { p.alpha = a }
        override fun setColorFilter(f: android.graphics.ColorFilter?) { p.colorFilter = f }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }


    /** Glass pill (top-right buttons). */
    fun pillBg(focused: Boolean): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        // without the edge, the focused pill says so by filling with the brand maroon
        if (BORDERLESS) { if (focused) intArrayOf(0xFF9A1450.toInt(), 0xFF56042C.toInt()) else intArrayOf(0x2EFFFFFF, 0x14FFFFFF) }
        else if (focused) intArrayOf(0x55FFFFFF, 0x30FFFFFF) else intArrayOf(0x2EFFFFFF, 0x14FFFFFF),
    ).apply {
        cornerRadius = px(100).toFloat()
        if (!BORDERLESS) { if (focused) setStroke(px(2.5f), Color.WHITE) else setStroke(px(1), 0x4DFFFFFF) }
    }

    /** App card: black base, app colour glowing from the top-left corner, logo + bold name. Corners baked in. */
    /**
     * Bitmaps are never painted above 1080p detail: on a 4K panel they are drawn at half size and scaled up by the
     * GPU (invisible from the sofa) — a quarter of the memory, which matters on cheap 4K sets with 1–2 GB RAM.
     */
    val paintScale: Float = min(1f, 1920f / w)

    fun card(icon: Drawable, name: String, color: Int, cw: Int = this.cw, ch: Int = this.ch): Bitmap {
        val b = Bitmap.createBitmap(max(1, (cw * paintScale).toInt()), max(1, (ch * paintScale).toInt()), Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.scale(paintScale, paintScale)
        val r = px(16).toFloat()
        c.clipPath(Path().apply { addRoundRect(RectF(0f, 0f, cw.toFloat(), ch.toFloat()), r, r, Path.Direction.CW) })
        c.drawColor(BASE)
        val g = glow(color)
        GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            colors = intArrayOf(0xC0 shl 24 or g, 0x30 shl 24 or g, g)
            setGradientCenter(-0.05f, -0.15f)
            gradientRadius = 1.05f * cw
            setBounds(0, 0, cw, ch)
            draw(c)
        }
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0, 0x8C000000.toInt())).apply {
            setBounds(0, 0, cw, ch); draw(c)
        }
        val logo = 0.34f * ch
        val space = 0.07f * ch
        val room = 0.86f * cw - logo - space
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.WHITE; typeface = bold; textSize = 0.17f * ch }
        var label: CharSequence = name
        if (tp.measureText(name) > room) {
            tp.textSize = max(tp.textSize * room / tp.measureText(name), 0.12f * ch)
            label = TextUtils.ellipsize(name, tp, room, TextUtils.TruncateAt.END)
        }
        val tw = tp.measureText(label, 0, label.length)
        var x = (cw - (logo + space + tw)) / 2f
        val cy = ch / 2f
        c.save()
        val lr = RectF(x, cy - logo / 2, x + logo, cy + logo / 2)
        c.clipPath(Path().apply { addRoundRect(lr, 0.22f * logo, 0.22f * logo, Path.Direction.CW) })
        icon.constantState?.newDrawable()?.mutate()?.apply {
            setBounds(lr.left.toInt(), lr.top.toInt(), lr.right.toInt(), lr.bottom.toInt()); draw(c)
        }
        c.restore()
        x += logo + space
        c.drawText(label, 0, label.length, x, cy - (tp.ascent() + tp.descent()) / 2, tp)
        return b
    }

    /** Capsule background of a favourite button, glowing from its left edge. */
    fun favBg(color: Int, bw: Int, bh: Int): Bitmap {
        val b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.clipPath(Path().apply {
            addRoundRect(RectF(0f, 0f, bw.toFloat(), bh.toFloat()), bh / 2f, bh / 2f, Path.Direction.CW)
        })
        c.drawColor(BASE)
        val g = glow(color)
        GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            colors = intArrayOf(0xB8 shl 24 or g, 0x28 shl 24 or g, g)
            setGradientCenter(-0.05f, 0.5f)
            gradientRadius = 0.75f * bw
            setBounds(0, 0, bw, bh); draw(c)
        }
        return b
    }

    /** Full-screen tint baked at 1/8 size: app colour from the top-left over near black. */
    fun tint(bmp: Bitmap, color: Int) {
        bmp.eraseColor(BASE)
        val c = Canvas(bmp)
        val g = glow(color)
        GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            colors = intArrayOf(0xB0 shl 24 or g, 0x28 shl 24 or g, g)
            setGradientCenter(-0.05f, -0.15f)
            gradientRadius = bmp.width.toFloat()
            setBounds(0, 0, bmp.width, bmp.height); draw(c)
        }
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0, 0x80000000.toInt())).apply {
            setBounds(0, 0, bmp.width, bmp.height); draw(c)
        }
    }

    companion object {
        const val BASE = 0xFF0B0A0D.toInt()
        const val MAROON = 0xFF56042C.toInt()
        const val DEFAULT_COLOR = 0xFF7A0E3E.toInt()
        /** Trial (user, 2026-10-30): cards and pills without the white edge. */
        const val BORDERLESS = true
        const val PINK = 0xFFF6C4D0.toInt()

        /** The bright version of a colour used for every glow: a bit more saturated, value at least 0.85. RGB only. */
        fun glow(c: Int): Int {
            val hsv = FloatArray(3)
            Color.colorToHSV(c, hsv)
            hsv[1] = min(1f, hsv[1] * 1.15f)
            hsv[2] = max(hsv[2], 0.85f)
            return Color.HSVToColor(hsv) and 0x00FFFFFF
        }

        /** Dominant vivid colour of an icon (weighted by saturation^2 x value). */
        fun tone(d: Drawable): Int = try {
            val b = Bitmap.createBitmap(16, 9, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            d.constantState?.newDrawable()?.mutate()?.apply { setBounds(0, 0, 16, 9); draw(c) } ?: run {
                d.setBounds(0, 0, 16, 9); d.draw(c)
            }
            var wr = 0.0; var wg = 0.0; var wb = 0.0; var wt = 0.0
            val hsv = FloatArray(3)
            for (y in 0 until 9) for (x in 0 until 16) {
                val p = b.getPixel(x, y)
                if (Color.alpha(p) < 128) continue
                Color.colorToHSV(p, hsv)
                val wgt = (hsv[1] * hsv[1] * hsv[2]).toDouble()
                wr += Color.red(p) * wgt; wg += Color.green(p) * wgt; wb += Color.blue(p) * wgt; wt += wgt
            }
            b.recycle()
            if (wt < 2.0) DEFAULT_COLOR else {
                val out = Color.rgb((wr / wt).toInt(), (wg / wt).toInt(), (wb / wt).toInt())
                Color.colorToHSV(out, hsv)
                hsv[1] = min(1f, hsv[1] * 1.1f)
                hsv[2] = min(hsv[2], 0.62f)
                Color.HSVToColor(hsv)
            }
        } catch (_: Exception) { DEFAULT_COLOR }

        private fun font(ctx: Context, name: String, fallback: Typeface): Typeface = try {
            Typeface.createFromAsset(ctx.assets, name)
        } catch (_: Exception) { fallback }
    }
}
