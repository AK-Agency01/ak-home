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
     * The sky as it is now in Baghdad: colour from the hour and the clouds, the sun or moon drawn, the
     * temperature big, and the next hours along the bottom right, each with its own drawn symbol.
     */
    fun weather(item: HeroItem, w: Int, h: Int, radius: Float): Bitmap {
        val (out, c) = canvas(w, h, radius)
        val m = try { JSONObject(item.meta) } catch (_: Exception) { JSONObject() }
        val code = m.optInt("code"); val day = m.optBoolean("day", true); val phase = m.optString("phase")
        // our own colours, dark (user): brand maroon by day, near-black by night, an ember at dawn / dusk,
        // a greyed maroon under clouds and rain — the weather shows in the symbols, the identity in the sky
        val (top, bottom) = when {
            code >= 51 -> 0xFF24141B.toInt() to 0xFF0B0609.toInt()
            phase == "dawn" || phase == "dusk" -> 0xFF4A0626.toInt() to 0xFF3A1208.toInt()
            !day -> 0xFF1C0412.toInt() to 0xFF050206.toInt()
            code >= 3 -> 0xFF341420.toInt() to 0xFF0E070A.toInt()
            else -> 0xFF56042C.toInt() to 0xFF12020A.toInt()
        }
        c.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 0f, h.toFloat(), top, bottom, Shader.TileMode.CLAMP) })
        if (!day && code < 3) {   // a few stars
            val s = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99FFFFFF.toInt() }
            val rnd = java.util.Random(7)
            repeat(40) { c.drawCircle(rnd.nextFloat() * w, rnd.nextFloat() * h * 0.6f, h * (0.003f + rnd.nextFloat() * 0.005f), s) }
        }
        // the big symbol, half behind the numbers
        icon(c, code, day, w * 0.355f, h * 0.36f, h * 0.17f)
        // darken the bottom for the hour row
        c.drawRect(0f, h * 0.55f, w.toFloat(), h.toFloat(), Paint().apply {
            shader = LinearGradient(0f, h * 0.55f, 0f, h.toFloat(), 0, 0x66000000, Shader.TileMode.CLAMP)
        })

        val x0 = w * 0.06f
        c.drawText(m.optString("city"), x0, h * 0.2f, text(1, h * 0.08f, 0xDDFFFFFF.toInt(), Paint.Align.LEFT))
        val deg = m.optString("t") + "°"
        c.drawText(deg, x0 - h * 0.02f, h * 0.62f, text(0, h * 0.42f, Color.WHITE, Paint.Align.LEFT))
        c.drawText(m.optString("word"), x0, h * 0.78f, text(1, h * 0.075f, Color.WHITE, Paint.Align.LEFT))
        c.drawText("↑ ${m.optString("hi")}°    ↓ ${m.optString("lo")}°", x0, h * 0.9f, text(1, h * 0.065f, 0xCCFFFFFF.toInt(), Paint.Align.LEFT))

        // next hours: a glass strip on the right
        val hours = m.optJSONArray("hours")
        if (hours != null && hours.length() > 0) {
            val left = w * 0.47f; val right = w * 0.96f; val y0 = h * 0.2f; val y1 = h * 0.86f
            c.drawRoundRect(RectF(left, y0, right, y1), h * 0.07f, h * 0.07f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x1FFFFFFF })
            c.drawRoundRect(RectF(left, y0, right, y1), h * 0.07f, h * 0.07f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0x33FFFFFF; style = Paint.Style.STROKE; strokeWidth = h * 0.006f
            })
            val n = hours.length(); val col = (right - left) / n
            for (i in 0 until n) {
                val o = hours.getJSONObject(i); val cx = left + col * (i + 0.5f)
                c.drawText(o.optString("h"), cx, y0 + h * 0.13f, text(1, h * 0.055f, 0xCCFFFFFF.toInt()))
                icon(c, o.optInt("c"), o.optBoolean("d", true), cx, (y0 + y1) / 2, h * 0.075f)
                c.drawText(o.optString("t") + "°", cx, y1 - h * 0.07f, text(2, h * 0.075f))
            }
        }
        return out.also { it.prepareToDraw() }
    }

    /** Drawn weather symbols (no emoji font needed): sun, moon, cloud, rain, snow, storm, fog. */
    private fun icon(c: Canvas, code: Int, day: Boolean, cx: Float, cy: Float, r: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        fun sun(x: Float, y: Float, s: Float) {
            p.shader = RadialGradient(x, y, s * 2.2f, 0x66FFD25A, 0, Shader.TileMode.CLAMP); c.drawCircle(x, y, s * 2.2f, p); p.shader = null
            p.color = 0xFFFFC93C.toInt(); p.strokeWidth = s * 0.16f; p.strokeCap = Paint.Cap.ROUND
            for (k in 0 until 8) {
                val a = Math.PI * k / 4
                c.drawLine(x + (s * 1.3f * Math.cos(a)).toFloat(), y + (s * 1.3f * Math.sin(a)).toFloat(),
                    x + (s * 1.7f * Math.cos(a)).toFloat(), y + (s * 1.7f * Math.sin(a)).toFloat(), p)
            }
            p.color = 0xFFFFD86B.toInt(); c.drawCircle(x, y, s, p)
        }
        fun moon(x: Float, y: Float, s: Float) {
            p.shader = RadialGradient(x, y, s * 2f, 0x40DDE6FF, 0, Shader.TileMode.CLAMP); c.drawCircle(x, y, s * 2f, p); p.shader = null
            val path = Path().apply { addCircle(x, y, s, Path.Direction.CW) }
            path.op(Path().apply { addCircle(x + s * 0.55f, y - s * 0.35f, s * 0.85f, Path.Direction.CW) }, Path.Op.DIFFERENCE)
            p.color = 0xFFEEF2FF.toInt(); c.drawPath(path, p)
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
                cloud(cx, cy - r * 0.15f, r * 0.85f, if (dark) 0xFFC9D2DE.toInt() else 0xF2FFFFFF.toInt())
                p.strokeCap = Paint.Cap.ROUND; p.strokeWidth = r * 0.12f
                when {
                    code in 95..99 -> {
                        p.color = 0xFFFFD23C.toInt(); p.style = Paint.Style.FILL
                        c.drawPath(Path().apply {
                            moveTo(cx + r * 0.1f, cy + r * 0.45f); lineTo(cx - r * 0.25f, cy + r * 0.95f); lineTo(cx + r * 0.05f, cy + r * 0.95f)
                            lineTo(cx - r * 0.15f, cy + r * 1.4f); lineTo(cx + r * 0.35f, cy + r * 0.8f); lineTo(cx + r * 0.05f, cy + r * 0.8f); close()
                        }, p)
                    }
                    code in 71..77 || code == 85 || code == 86 -> { p.color = Color.WHITE; for (k in -1..1) c.drawCircle(cx + k * r * 0.45f, cy + r * 0.85f + (k and 1) * r * 0.2f, r * 0.1f, p) }
                    code >= 51 -> { p.color = 0xFF7EC8FF.toInt(); for (k in -1..1) c.drawLine(cx + k * r * 0.45f, cy + r * 0.7f, cx + k * r * 0.45f - r * 0.15f, cy + r * 1.1f, p) }
                }
            }
        }
    }
}
