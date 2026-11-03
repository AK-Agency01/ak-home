package com.akhome.installer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.StateListDrawable
import android.os.SystemClock

/**
 * The AK Home launcher's own look (user, 2026-10-31: «نفس لغة التصميم مال اللانشر بالضبط، نفس الألوان ونفس
 * الكبسولات» — the TV home screen, not the website). Values copied from app/…/Ui.kt and MainActivity.kt:
 * ground #080106 with the brand maroon glowing left of centre; capsules of white glass that fill with maroon when
 * chosen; dark cards with the colour glowing from the corner; the date pink; no violet, no grid, no orb.
 */
object Ak {
    const val BG = 0xFF080106.toInt()
    /** The launcher's card base. */
    const val BASE = 0xFF0B0A0D.toInt()
    const val MAROON = 0xFF7A0E3E.toInt()
    const val DEEP = 0xFF56042C.toInt()
    /** Top of the chosen capsule (launcher pillBg focused). */
    const val HOT = 0xFF9A1450.toInt()
    /** The launcher's date colour (Ui.PINK): soft accent words and marks. */
    const val PINK = 0xFFF6C4D0.toInt()
    // Old names kept so the pages read the same; they now hold launcher colours.
    const val VIOLET = HOT
    const val LAVENDER = PINK
    const val BLUSH = PINK
    const val GREEN = 0xFF2BD96B.toInt()
    const val GOLD = 0xFFFFC95C.toInt()
    const val T2 = 0xBDFFFFFF.toInt()     // 74 % white
    const val T3 = 0x80FFFFFF.toInt()     // 50 % white

    /** Off when the phone has animations turned off. */
    var motion = true

    fun now() = SystemClock.uptimeMillis()
}

/**
 * Page background: the launcher's ground (MainActivity.bakeBackdrop) — near black, the brand maroon glowing a little
 * left of centre, sinking into near black toward the right. Still, like the TV; nothing to animate.
 */
class Backdrop : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    /** Kept for the activity's pause / resume calls; the ground no longer moves. */
    var running = true

    override fun onBoundsChange(b: Rect) {
        // a phone is tall: the glow sits in the upper part, as the TV's sits in its middle
        val w = b.width().toFloat(); val h = b.height().toFloat()
        glow = RadialGradient(w * 0.3f, h * 0.3f, maxOf(w, h) * 0.62f, Ak.DEEP, Ak.DEEP and 0x00FFFFFF, Shader.TileMode.CLAMP)
        side = LinearGradient(0f, 0f, w, 0f, intArrayOf(0x0056042C, 0x2256042C, 0xCC0C0006.toInt()), null, Shader.TileMode.CLAMP)
    }
    private var glow: Shader? = null
    private var side: Shader? = null

    override fun draw(c: Canvas) {
        c.drawColor(Ak.BG)
        p.shader = glow; c.drawRect(bounds, p)
        p.shader = side; c.drawRect(bounds, p)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.OPAQUE
}

/** A small TV outline (screen + stand) on a dark disc with the maroon glowing in, for the found-TV cards. */
class TvIcon(ctx: android.content.Context) : android.view.View(ctx) {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.WHITE; strokeCap = Paint.Cap.ROUND
    }
    private val disc = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val s = minOf(w, h)
        disc.shader = RadialGradient(w * 0.2f, h * 0.2f, s, Ak.HOT, Ak.DEEP, Shader.TileMode.CLAMP)
        // the launcher rounds app logos by 22 %
        c.drawRoundRect(RectF(0f, 0f, w, h), s * 0.22f, s * 0.22f, disc)
        stroke.strokeWidth = s * 0.055f
        val sw = s * 0.5f; val sh = s * 0.32f; val l = (w - sw) / 2; val t = h / 2 - sh * 0.7f
        c.drawRoundRect(RectF(l, t, l + sw, t + sh), s * 0.05f, s * 0.05f, stroke)
        c.drawLine(w / 2 - s * 0.1f, t + sh + s * 0.1f, w / 2 + s * 0.1f, t + sh + s * 0.1f, stroke)
    }
}

/**
 * The launcher's surfaces, borderless (Ui.BORDERLESS). A capsule (radius ≥ half the height): white glass
 * 18 % → 8 %, filling with maroon #9A1450 → #56042C when [lit] (focused / pressed / chosen), exactly like the TV's
 * top pills. A card: the dark base #0B0A0D with the brand maroon glowing from the top-left and a shade at the bottom
 * (Ui.card); [lit] adds the light glass edge the TV draws round the focused card (Ui.GlassRing). [primary]: always
 * the maroon capsule.
 */
class GlassDrawable(
    private val radius: Float,
    private val px: Float,                 // 1 dp in pixels
    private val primary: Boolean = false,
    private val lit: Boolean = false,
) : Drawable() {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG)
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val path = Path()
    private val rect = RectF()
    private var capsule = false
    private var r = 0f

    override fun onBoundsChange(b: Rect) {
        rect.set(b)
        capsule = radius * 2 >= rect.height()
        r = minOf(radius, rect.height() / 2)
        path.reset(); path.addRoundRect(rect, r, r, Path.Direction.CW)
        glow.shader = null; shade.shader = null
        fill.shader = when {
            primary || (capsule && lit) -> LinearGradient(0f, rect.top, 0f, rect.bottom,
                if (primary && lit) 0xFFB01A5E.toInt() else Ak.HOT, Ak.DEEP, Shader.TileMode.CLAMP)
            capsule -> LinearGradient(0f, rect.top, 0f, rect.bottom, 0x2EFFFFFF, 0x14FFFFFF, Shader.TileMode.CLAMP)
            else -> {
                // the launcher card: base, the maroon lit up (Ui.glow) from just outside the top-left corner, shade
                val g = 0xD9145F
                glow.shader = RadialGradient(rect.left - rect.width() * 0.05f, rect.top - rect.height() * 0.15f,
                    rect.width() * 1.05f, intArrayOf(0x8C shl 24 or g, 0x22 shl 24 or g, g), floatArrayOf(0f, 0.5f, 1f),
                    Shader.TileMode.CLAMP)
                shade.shader = LinearGradient(0f, rect.top, 0f, rect.bottom, 0, 0x59000000, Shader.TileMode.CLAMP)
                null
            }
        }
        if (fill.shader == null) fill.color = 0xE60B0A0D.toInt()
        halo.strokeWidth = px * 6; halo.color = 0x14FFFFFF
        edge.strokeWidth = px * 1.5f; edge.color = 0x99FFFFFF.toInt()
    }

    override fun draw(c: Canvas) {
        c.drawPath(path, fill)
        if (glow.shader != null) { c.drawPath(path, glow); c.drawPath(path, shade) }
        if (lit && !capsule && !primary) {
            // soft halo first, then the fine line: light on glass, not a frame (drawn just inside the card)
            c.drawPath(path, halo)
            val e = RectF(rect).apply { inset(px, px) }
            c.drawRoundRect(e, r - px, r - px, edge)
        }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        /** Normal / pressed-focused-selected surface for one control. */
        fun states(radius: Float, px: Float, primary: Boolean = false) = StateListDrawable().apply {
            val on = GlassDrawable(radius, px, primary, lit = true)
            addState(intArrayOf(android.R.attr.state_pressed), on)
            addState(intArrayOf(android.R.attr.state_focused), on)
            addState(intArrayOf(android.R.attr.state_selected), on)
            addState(intArrayOf(), GlassDrawable(radius, px, primary))
        }
    }
}

/**
 * The AK logo as the launcher shows it: the two eyes on their own — no ball and no rings behind them (user) —
 * the second one winking now and then (scaleY 1 → 0.08 → 1 over 280 ms, as on the TV).
 */
class Orb(ctx: android.content.Context, private val logo: Bitmap?) : android.view.View(ctx) {
    private val img = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()
    private val tick = Runnable { invalidate() }
    /** Eyes to the reading-start side (the launcher's corner logo) instead of the middle. */
    var atStart = false

    override fun onDraw(c: Canvas) {
        val bmp = logo ?: return
        val w = width.toFloat(); val h = height.toFloat()
        val gap = h * 0.06f
        val eh = h; val ew = eh * bmp.width / bmp.height
        if (ew * 2 + gap > w) return
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        val x0 = when { !atStart -> (w - ew * 2 - gap) / 2; rtl -> w - ew * 2 - gap; else -> 0f }
        val t = if (Ak.motion) Ak.now() else 0L
        val ph = ((t + 3000) % 9000L)
        val wink = if (ph < 280) 0.08f + 0.92f * kotlin.math.abs(ph - 140f) / 140f else 1f
        dst.set(x0, 0f, x0 + ew, eh)
        c.drawBitmap(bmp, null, dst, img)
        val y0 = eh * 0.38f                                           // the disc's centre, like the TV's pivot
        dst.set(x0 + ew + gap, y0 - y0 * wink, x0 + ew * 2 + gap, y0 + (eh - y0) * wink)
        c.drawBitmap(bmp, null, dst, img)
        if (Ak.motion && isAttachedToWindow) { removeCallbacks(tick); postDelayed(tick, if (ph < 300) 16L else 9000L - ph) }
    }
}

/**
 * Was the site's gradient headline. The launcher's titles are plain white ([flow]); small labels above a heading
 * take the launcher's date pink.
 */
class GradientText(ctx: android.content.Context, flow: Boolean) : android.widget.TextView(ctx) {
    init { setTextColor(if (flow) Color.WHITE else Ak.PINK) }
}

/** A thin progress line: white track, maroon → pink fill from the reading-start side. */
class ProgressLine(ctx: android.content.Context) : android.view.View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x1FFFFFFF }
    var fraction = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        c.drawRoundRect(0f, 0f, w, h, h / 2, h / 2, track)
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        val l = if (rtl) w * (1 - fraction) else 0f; val r = if (rtl) w else w * fraction
        p.shader = LinearGradient(l, 0f, r, 0f, Ak.HOT, Ak.PINK, Shader.TileMode.CLAMP)
        c.drawRoundRect(l, 0f, r, h, h / 2, h / 2, p)
    }
}
