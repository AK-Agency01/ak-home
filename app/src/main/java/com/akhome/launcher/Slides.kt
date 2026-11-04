package com.akhome.launcher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import org.json.JSONObject

/**
 * Slides that are drawn, not photographed: a match (broadcast style) and the weather.
 * Everything is painted once into one opaque bitmap of the card size, so on screen it costs
 * exactly what a film slide costs. Their data travels in [HeroItem.meta] as JSON.
 */
object Slides {
    /** light, medium, bold — set by the activity (Readex Pro). */
    @Volatile var fonts: Array<Typeface> = arrayOf(Typeface.DEFAULT, Typeface.DEFAULT, Typeface.DEFAULT_BOLD)

    private fun canvas(w: Int, h: Int, radius: Float): Pair<Bitmap, Canvas> {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.clipPath(Path().apply { addRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), radius, radius, Path.Direction.CW) })
        return out to c
    }

    private fun text(tf: Int, size: Float, color: Int = Color.WHITE, align: Paint.Align = Paint.Align.CENTER) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = fonts[tf]; textSize = size; this.color = color; textAlign = align }

    /** True when a crest's visible pixels are mostly dark (checked on a 12×12 copy: cheap). */
    private fun dark(b: Bitmap): Boolean {
        val s = Bitmap.createScaledBitmap(b, 12, 12, true)
        var lum = 0f; var n = 0
        for (y in 0 until 12) for (x in 0 until 12) {
            val px = s.getPixel(x, y)
            if (Color.alpha(px) < 128) continue
            lum += (0.299f * Color.red(px) + 0.587f * Color.green(px) + 0.114f * Color.blue(px)) / 255f; n++
        }
        if (s !== b) s.recycle()
        return n > 0 && lum / n < 0.38f
    }

    /** A team colour that glows on dark: never black, never washed out. */
    private fun glow(c: Int): Int {
        val hsv = FloatArray(3); Color.colorToHSV(c, hsv)
        if (hsv[1] < 0.15f) return 0xFF5A6478.toInt()          // white / grey kits
        hsv[2] = hsv[2].coerceIn(0.55f, 0.85f)
        return Color.HSVToColor(hsv)
    }

    // ------------------------------------------------------------------ match

    /**
     * Home crest on the right (Arabic reads right to left), away on the left, each over a glow of its colour;
     * the competition's logo and name on top; the kick-off time or the score big in the middle; the short
     * team names under the crests. imageUrl = home crest, logoUrl = "away crest|away colour|competition logo".
     */
    fun match(item: HeroItem, w: Int, h: Int, radius: Float, picture: (String, Int) -> Bitmap?): Bitmap {
        val (out, c) = canvas(w, h, radius)
        val m = try { JSONObject(item.meta) } catch (_: Exception) { JSONObject() }
        val parts = item.logoUrl.split('|')
        val away = parts.getOrNull(1)?.toIntOrNull() ?: item.color
        // home side first in reading order: on the right in Arabic, on the left in English
        val hx = if (Lang.ar) 0.80f else 0.20f; val ax = 1f - hx
        c.drawColor(0xFF0B0F1A.toInt())
        val g = Paint(Paint.ANTI_ALIAS_FLAG)
        for ((x, col) in listOf(hx to item.color, ax to away)) {
            g.shader = RadialGradient(w * x, h * 0.5f, h * 0.95f, glow(col) and 0x00FFFFFF or (0x88 shl 24), 0, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), g)
        }
        // a faint floor so the names sit on something
        g.shader = LinearGradient(0f, h * 0.6f, 0f, h.toFloat(), 0, 0x88000000.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, h * 0.6f, w.toFloat(), h.toFloat(), g)

        val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        fun put(b: Bitmap?, cx: Float, cy: Float, box: Float) {
            b ?: return
            val s = box / maxOf(b.width, b.height); val dw = b.width * s; val dh = b.height * s
            c.drawBitmap(b, null, RectF(cx - dw / 2, cy - dh / 2, cx + dw / 2, cy + dh / 2), p); b.recycle()
        }
        // no white halo behind the crests (user, 2026-10-31: it greyed the colours and looked like a stain).
        // Dark crests (Tottenham navy, Juventus black) take ESPN's own version for dark backgrounds instead;
        // a crest that is still dark gets its team colour glowing a little stronger behind it.
        fun crest(url: String) = (if ("/500/" in url) picture(url.replace("/500/", "/500-dark/"), (h * 0.56f).toInt()) else null)
            ?: picture(url, (h * 0.56f).toInt())
        val crests = listOf(Triple(hx, crest(item.imageUrl), item.color), Triple(ax, crest(parts[0]), away))
        for ((x, b, col) in crests) {
            if (b == null || !dark(b)) continue
            g.shader = RadialGradient(w * x, h * 0.44f, h * 0.34f, glow(col) and 0x00FFFFFF or (0x99 shl 24), 0, Shader.TileMode.CLAMP)
            c.drawCircle(w * x, h * 0.44f, h * 0.34f, g)
        }
        g.shader = null
        for ((x, b) in crests) put(b, w * x, h * 0.44f, h * 0.52f)
        parts.getOrNull(2)?.takeIf { it.startsWith("http") }?.let { put(picture(it, (h * 0.2f).toInt()), w * 0.5f, h * 0.17f, h * 0.17f) }

        c.drawText(m.optString("comp"), w * 0.5f, h * 0.36f, text(1, h * 0.068f, 0xB3FFFFFF.toInt()))
        c.drawText(m.optString("ha"), w * hx, h * 0.88f, text(2, h * 0.11f))
        c.drawText(m.optString("aa"), w * ax, h * 0.88f, text(2, h * 0.11f))

        // centre: time (before) or score (live / over), with a small tag above it
        val state = m.optInt("state")
        val big = text(2, h * (if (state == 0) 0.15f else 0.22f))
        c.drawText(m.optString("big"), w * 0.5f, h * (if (state == 0) 0.76f else 0.77f), big)
        val tag = m.optString("tag")
        if (tag.isNotEmpty()) {
            val tp = text(1, h * 0.06f)
            val tw = tp.measureText(tag) + h * 0.09f
            val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = when (state) { 1 -> 0xFFD7263D.toInt(); 2 -> 0x33FFFFFF; else -> 0x26FFFFFF }
            }
            val top = h * 0.48f
            c.drawRoundRect(RectF(w * 0.5f - tw / 2, top, w * 0.5f + tw / 2, top + h * 0.1f), h * 0.05f, h * 0.05f, bg)
            c.drawText(tag, w * 0.5f, top + h * 0.072f, tp)
        }
        return out.also { it.prepareToDraw() }
    }

    // ------------------------------------------------------------------ weather

    /**
     * The weather in the AK Agency app's look (user, 2026-11-04: "only that design language, nothing overlapping"):
     * the night ground with the brand maroon glowing from the left, two frosted glass cards side by side —
     * now (city, big degrees, the word in blush, high / low) and the next hours (hour, symbol, degrees).
     * Every line is measured and shrunk to fit its own box, so nothing can run into anything. Drawn once per slide.
     */
    fun weather(item: HeroItem, w: Int, h: Int, radius: Float): Bitmap {
        val (out, c) = canvas(w, h, radius)
        val m = try { JSONObject(item.meta) } catch (_: Exception) { JSONObject() }
        val code = m.optInt("code"); val day = m.optBoolean("day", true)
        // ground: AK night + the maroon glow from the left (dimmer at night)
        c.drawColor(AK_NIGHT)
        c.drawPaint(Paint().apply {
            shader = RadialGradient(0f, h * 0.35f, w * 0.75f, if (day) 0x8C9A1450.toInt() else 0x5C56042C, 0, Shader.TileMode.CLAMP)
        })
        val pad = h * 0.09f; val gap = h * 0.06f; val r = h * 0.08f
        val now = RectF(pad, pad, w * 0.46f, h - pad)
        val next = RectF(now.right + gap, pad, w - pad, h - pad)
        glass(c, now, r); glass(c, next, r)

        // now: four bands, top to bottom — city / degrees + symbol / word / high-low. The symbol owns a square
        // beside the degrees and is clipped to it, so it can never sit behind a line and no line behind it.
        val hp = now.height(); val ip = hp * 0.11f
        val inner = now.width() - ip * 2
        line(c, m.optString("city"), now.left + ip, now.top + hp * 0.2f, inner, 0, hp * 0.085f, AK_MUTED)
        val sq = hp * 0.36f                                                     // band 0.27 … 0.63
        val box = RectF(now.right - ip - sq, now.top + hp * 0.27f, now.right - ip, now.top + hp * 0.27f + sq)
        icon(c, code, day, box)
        deg(c, m.optString("t"), now.left + ip, now.top + hp * 0.6f, box.left - hp * 0.05f - (now.left + ip), 1, hp * 0.4f, Color.WHITE, false)
        line(c, m.optString("word"), now.left + ip, now.top + hp * 0.76f, inner, 1, hp * 0.085f, AK_BLUSH)
        // high / low on one line: shrunk together if they would not fit
        val hl = text(0, hp * 0.072f, AK_SECOND, Paint.Align.LEFT)
        val hi = "↑ " + m.optString("hi"); val lo = "↓ " + m.optString("lo")
        val need = degWidth(hl, hi) + hl.textSize * 1.4f + degWidth(hl, lo)
        if (need > inner) hl.textSize *= inner / need
        val hx = deg(c, hi, now.left + ip, now.top + hp * 0.9f, inner, 0, hl.textSize, AK_SECOND, false)
        deg(c, lo, hx + hl.textSize * 1.4f, now.top + hp * 0.9f, inner, 0, hl.textSize, AK_SECOND, false)

        // next hours: equal columns, nothing shared between them
        val hours = m.optJSONArray("hours")
        if (hours != null && hours.length() > 0) {
            val n = hours.length(); val col = next.width() / n; val cp = col * 0.1f; val hn = next.height()
            for (i in 0 until n) {
                val o = hours.getJSONObject(i); val cx = next.left + col * (i + 0.5f)
                // bands: hour 0.12…0.26 / symbol 0.34…0.66 / degrees 0.73…0.86
                center(c, o.optString("h"), cx, next.top + hn * 0.24f, col - cp * 2, 0, hn * 0.08f, AK_MUTED)
                val s = minOf(hn * 0.32f, col - cp * 2)
                icon(c, o.optInt("c"), o.optBoolean("d", true), RectF(cx - s / 2, next.top + hn * 0.5f - s / 2, cx + s / 2, next.top + hn * 0.5f + s / 2))
                deg(c, o.optString("t"), cx, next.top + hn * 0.84f, col - cp * 2, 1, hn * 0.11f, Color.WHITE, true)
            }
        }
        return out.also { it.prepareToDraw() }
    }

    // AK Agency app colours (constants/theme.ts)
    private const val AK_NIGHT = 0xFF080106.toInt()
    private const val AK_BLUSH = 0xFFF6C4D0.toInt()
    private const val AK_SECOND = 0xBDFFFFFF.toInt()   // textSecondary, white 74 %
    private const val AK_MUTED = 0x99FFFFFF.toInt()    // textMuted, white 60 %

    /** The app's Glass card: see-through card fill, maroon glow from the top-left corner, light sheen → shade. */
    private fun glass(c: Canvas, b: RectF, r: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0x611C0E16; c.drawRoundRect(b, r, r, p)                       // rgba(28,14,22,0.38)
        p.color = 0; p.shader = RadialGradient(b.left, b.top, b.height() * 1.4f, 0x4D9A1450, 0, Shader.TileMode.CLAMP)
        c.drawRoundRect(b, r, r, p)                                             // rgba(154,20,80,0.30)
        p.shader = LinearGradient(0f, b.top, 0f, b.bottom, intArrayOf(0x17FFFFFF, 0x05FFFFFF, 0x2E000000), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawRoundRect(b, r, r, p)
    }

    /** One line, left-aligned at [x], shrunk until it fits [max] wide (never cut, never over the next box). */
    private fun line(c: Canvas, s: String, x: Float, y: Float, max: Float, tf: Int, size: Float, color: Int) {
        if (s.isBlank() || max <= 0f) return
        val p = text(tf, size, color, Paint.Align.LEFT); fit(p, s, max); c.drawText(s, x, y, p)
    }

    private fun center(c: Canvas, s: String, x: Float, y: Float, max: Float, tf: Int, size: Float, color: Int) {
        if (s.isBlank() || max <= 0f) return
        val p = text(tf, size, color); fit(p, s, max); c.drawText(s, x, y, p)
    }

    /**
     * Degrees: the number, then the ° as its own small ring a clear gap after it (in Readex Pro the ° glyph sits on
     * the last digit). Left-aligned at [x], or centred on it; shrunk to [max]. Returns the right edge.
     */
    private fun deg(c: Canvas, num: String, x: Float, y: Float, max: Float, tf: Int, size: Float, color: Int, centred: Boolean): Float {
        if (num.isBlank()) return x
        val p = text(tf, size, color, Paint.Align.LEFT)
        val total = degWidth(p, num); if (total > max && max > 0f) p.textSize *= max / total
        val nw = p.measureText(num); val all = degWidth(p, num); val sz = p.textSize
        val x0 = if (centred) x - all / 2 else x
        c.drawText(num, x0, y, p)
        val ringR = sz * 0.085f
        c.drawCircle(x0 + nw + sz * 0.07f + ringR, y - sz * 0.70f + ringR, ringR, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; style = Paint.Style.STROKE; strokeWidth = sz * 0.045f
        })
        return x0 + all
    }

    /** Width of a number, the gap and its ring. */
    private fun degWidth(p: Paint, num: String) = p.measureText(num) + p.textSize * (0.07f + 0.17f + 0.03f)

    private fun fit(p: Paint, s: String, max: Float) {
        val wd = p.measureText(s); if (wd > max) p.textSize *= max / wd
    }

    /** A symbol inside its own square: sized so every symbol fits (sun rays reach 1.7 r) and clipped to it. */
    private fun icon(c: Canvas, code: Int, day: Boolean, box: RectF) {
        val r = minOf(box.width(), box.height()) / 2f / 1.8f
        c.save(); c.clipRect(box)
        icon(c, code, day, box.centerX(), box.centerY(), r)
        c.restore()
    }

    /** Drawn weather symbols (no emoji font needed): sun, moon, cloud, rain, snow, storm, fog. */
    private fun icon(c: Canvas, code: Int, day: Boolean, cx: Float, cy: Float, r: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        fun sun(x: Float, y: Float, s: Float) {
            p.color = AK_BLUSH; p.strokeWidth = s * 0.16f; p.strokeCap = Paint.Cap.ROUND
            for (k in 0 until 8) {
                val a = Math.PI * k / 4
                c.drawLine(x + (s * 1.3f * Math.cos(a)).toFloat(), y + (s * 1.3f * Math.sin(a)).toFloat(),
                    x + (s * 1.7f * Math.cos(a)).toFloat(), y + (s * 1.7f * Math.sin(a)).toFloat(), p)
            }
            p.color = AK_BLUSH; c.drawCircle(x, y, s, p)
        }
        fun moon(x: Float, y: Float, s: Float) {
            val path = Path().apply { addCircle(x, y, s, Path.Direction.CW) }
            path.op(Path().apply { addCircle(x + s * 0.55f, y - s * 0.35f, s * 0.85f, Path.Direction.CW) }, Path.Op.DIFFERENCE)
            p.color = AK_BLUSH; c.drawPath(path, p)
        }
        fun cloud(x: Float, y: Float, s: Float, color: Int) {
            p.color = color
            c.drawCircle(x - s * 0.55f, y + s * 0.15f, s * 0.55f, p)
            c.drawCircle(x + s * 0.05f, y - s * 0.2f, s * 0.75f, p)
            c.drawCircle(x + s * 0.7f, y + s * 0.2f, s * 0.5f, p)
            c.drawRoundRect(RectF(x - s * 1.1f, y + s * 0.05f, x + s * 1.2f, y + s * 0.7f), s * 0.35f, s * 0.35f, p)
        }
        val clear = code <= 1; val part = code == 2
        when {
            clear -> if (day) sun(cx, cy, r) else moon(cx, cy, r)
            part -> { if (day) sun(cx + r * 0.45f, cy - r * 0.45f, r * 0.7f) else moon(cx + r * 0.45f, cy - r * 0.45f, r * 0.7f); cloud(cx - r * 0.1f, cy + r * 0.15f, r * 0.8f, 0xF2FFFFFF.toInt()) }
            code == 45 || code == 48 -> {
                p.color = 0xCCFFFFFF.toInt(); p.strokeWidth = r * 0.16f; p.strokeCap = Paint.Cap.ROUND
                for (k in -1..1) c.drawLine(cx - r * (1f - k * 0.15f), cy + k * r * 0.45f, cx + r * (1f + k * 0.1f), cy + k * r * 0.45f, p)
            }
            else -> {
                val dark = code >= 51
                cloud(cx, cy - r * 0.15f, r * 0.85f, if (dark) 0xBDFFFFFF.toInt() else 0xF2FFFFFF.toInt())
                p.strokeCap = Paint.Cap.ROUND; p.strokeWidth = r * 0.12f
                when {
                    code in 95..99 -> {
                        p.color = AK_BLUSH; p.style = Paint.Style.FILL
                        c.drawPath(Path().apply {
                            moveTo(cx + r * 0.1f, cy + r * 0.45f); lineTo(cx - r * 0.25f, cy + r * 0.95f); lineTo(cx + r * 0.05f, cy + r * 0.95f)
                            lineTo(cx - r * 0.15f, cy + r * 1.4f); lineTo(cx + r * 0.35f, cy + r * 0.8f); lineTo(cx + r * 0.05f, cy + r * 0.8f); close()
                        }, p)
                    }
                    code in 71..77 || code == 85 || code == 86 -> { p.color = Color.WHITE; for (k in -1..1) c.drawCircle(cx + k * r * 0.45f, cy + r * 0.85f + (k and 1) * r * 0.2f, r * 0.1f, p) }
                    code >= 51 -> { p.color = AK_BLUSH; for (k in -1..1) c.drawLine(cx + k * r * 0.45f, cy + r * 0.7f, cx + k * r * 0.45f - r * 0.15f, cy + r * 1.1f, p) }
                }
            }
        }
    }
}
