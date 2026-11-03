package com.akhome.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.text.Html
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/** One slide of the big card on the right. */
class HeroItem(
    val id: String,
    val title: String,
    val meta: String,
    val imageUrl: String,
    val logoUrl: String = "",
    val color: Int = 0,
    val link: String = "",          // what OK does: "yt:<video id>", "ys:<YouTube search>", "app:<package>", "" = nothing
    val kind: String = "",          // "" = a title with artwork, "match" = two team logos, "weather" = a drawn glyph
) {
    @Volatile var image: Bitmap? = null
    @Volatile var logo: Bitmap? = null

    fun toJson(): JSONObject = JSONObject().put("id", id).put("t", title).put("m", meta).put("i", imageUrl)
        .put("l", logoUrl).put("c", color).put("k", link).put("y", kind)

    companion object {
        fun fromJson(o: JSONObject) = HeroItem(o.getString("id"), o.getString("t"), o.getString("m"), o.getString("i"),
            o.optString("l"), o.optInt("c"), o.optString("k"), o.optString("y"))
    }
}

/** Where the big card gets its titles from (Netflix public Top 10 + TVmaze). */
interface HeroFeed {
    fun fetch(): List<HeroItem>
}

/** The box's language decides every word (user, 2026-10-30): Modern Standard Arabic on Arabic systems, else English. */
object Lang {
    val ar: Boolean get() = java.util.Locale.getDefault().language == "ar"
    fun t(en: String, ar: String) = if (this.ar) ar else en
}

/** A second source mixed into the card, refreshed on its own clock (matches every half hour, weather hourly…). */
class Extra(val name: String, val feed: HeroFeed, val ttl: Long, val often: Boolean = false)

object Net {
    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000; readTimeout = 15_000
        setRequestProperty("Accept-Encoding", "gzip")
        setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36")
    }

    fun get(url: String): String = read(url, Int.MAX_VALUE) { false }

    /** Reads text until [done] says so or [limit] chars, then hangs up (saves bandwidth on slow boxes). */
    fun read(url: String, limit: Int, done: (CharSequence) -> Boolean): String {
        val c = open(url)
        try {
            val raw = c.inputStream
            val input = if (c.contentEncoding.equals("gzip", true)) GZIPInputStream(raw) else raw
            val sb = StringBuilder()
            input.bufferedReader().use { r ->
                val buf = CharArray(16_384)
                while (sb.length < limit) {
                    val n = r.read(buf); if (n < 0) break
                    sb.append(buf, 0, n)
                    if (done(sb)) break
                }
            }
            return sb.toString()
        } finally { c.disconnect() }
    }

    /** POSTs a JSON body and returns the text answer (GraphQL sources). */
    fun post(url: String, json: String): String {
        val c = open(url)
        try {
            c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(json.toByteArray()) }
            val raw = c.inputStream
            val input = if (c.contentEncoding.equals("gzip", true)) GZIPInputStream(raw) else raw
            return input.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    fun bytes(url: String): ByteArray? = try {
        val c = open(url)
        try { c.inputStream.use { it.readBytes() } } finally { c.disconnect() }
    } catch (_: Exception) { null }

    /** Decodes a picture at roughly [targetW] wide. [opaque] pictures use RGB_565 to halve memory. */
    fun decode(bytes: ByteArray, targetW: Int, opaque: Boolean): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetW) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = if (opaque) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
        })
    }
}

/**
 * Keeps the feed and its pictures on disk so the card shows instantly after a reboot, even offline,
 * and the network is touched at most once a day.
 */
class HeroCache(ctx: Context, private val feed: HeroFeed, private val name: String, private val ttl: Long = 24 * 3_600_000L) {
    private val dir = File(ctx.cacheDir, "hero-$name").apply { mkdirs() }
    private val list = File(ctx.filesDir, "hero-$name.json")

    fun cached(): List<HeroItem> = try {
        val arr = JSONObject(list.readText()).getJSONArray("items")
        (0 until arr.length()).map { HeroItem.fromJson(arr.getJSONObject(it)) }
    } catch (_: Exception) { emptyList() }

    fun stale(): Boolean = !list.exists() || System.currentTimeMillis() - list.lastModified() > ttl

    fun refresh(): List<HeroItem> {
        val items = feed.fetch()
        if (items.isEmpty()) return emptyList()
        list.writeText(JSONObject().put("items", JSONArray(items.map { it.toJson() })).toString())
        // a crest is drawn from its dark-background twin when there is one (Slides.match): keep both, or every
        // refresh (matches: each 30 min) would delete and download them again
        val keep = items.flatMap { listOf(it.imageUrl) + it.logoUrl.split('|') }
            .flatMap { u -> listOf(u, u.replace("/500/", "/500-dark/")) }.map { file(it).name }.toSet()
        dir.listFiles()?.forEach { if (it.name !in keep) it.delete() }
        return items
    }

    /**
     * Loads (from disk, else network) one item and composes its whole slide into a single bitmap of the card size:
     * the full artwork on the right (never cropped), melting into the title's own colour on the left,
     * where the logo and text sit. One opaque bitmap = one cheap texture, no overlay views.
     */
    fun load(item: HeroItem, cardW: Int, cardH: Int, radius: Float) {
        if (item.kind == "match") {
            if (item.image == null) item.image = Slides.match(item, cardW, cardH, radius) { u, w -> picture(u, w, Bitmap.Config.ARGB_8888) }
            return
        }
        if (item.kind == "weather") { if (item.image == null) item.image = Slides.weather(item, cardW, cardH, radius); return }
        if (item.image == null) {
            val art = picture(item.imageUrl, cardW, Bitmap.Config.ARGB_8888) ?: return
            item.image = compose(art, item.color, cardW, cardH, radius).also { art.recycle(); it.prepareToDraw() }
        }
        if (item.logo == null && item.logoUrl.isNotEmpty())
            item.logo = picture(item.logoUrl, (cardW * 0.34f).toInt(), Bitmap.Config.ARGB_8888)?.also { it.prepareToDraw() }
    }

    private fun compose(art: Bitmap, color: Int, w: Int, h: Int, radius: Float): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(out)
        c.clipPath(android.graphics.Path().apply {
            addRoundRect(android.graphics.RectF(0f, 0f, w.toFloat(), h.toFloat()), radius, radius, android.graphics.Path.Direction.CW)
        })
        // no colour from the feed: the artwork's own average, one pixel's worth of work
        val base = deep(if (color != 0) color else Bitmap.createScaledBitmap(art, 1, 1, true).let { val px = it.getPixel(0, 0); it.recycle(); px })
        c.drawColor(base)
        // cinematic, full bleed (user: no flat coloured side): the artwork covers the whole card, cropped
        // top and bottom, kept a little above centre where faces usually are
        val scale = maxOf(w.toFloat() / art.width, h.toFloat() / art.height)
        val sw = w / scale; val sh = h / scale
        val sx = (art.width - sw) * 0.75f; val sy = (art.height - sh) * 0.3f
        val p = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG or android.graphics.Paint.ANTI_ALIAS_FLAG)
        c.drawBitmap(art, android.graphics.Rect(sx.toInt(), sy.toInt(), (sx + sw).toInt(), (sy + sh).toInt()),
            android.graphics.Rect(0, 0, w, h), p)
        // one smooth shade from the left (the title's colour sunk towards black), so text reads and no edge shows
        val shade = blend(base, 0xFF050507.toInt(), 0.45f) and 0x00FFFFFF
        p.shader = android.graphics.LinearGradient(0f, 0f, w * 0.72f, 0f,
            intArrayOf(shade or (0xF2 shl 24), shade or (0xDC shl 24), shade or (0x80 shl 24), shade or (0x20 shl 24), shade),
            floatArrayOf(0f, 0.32f, 0.58f, 0.82f, 1f), android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w * 0.72f + 1, h.toFloat(), p)
        // floor for the meta line, and a breath of shade at the top so the rim never meets a white sky
        p.shader = android.graphics.LinearGradient(0f, h * 0.5f, 0f, h.toFloat(), 0, 0xB0000000.toInt(), android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(0f, h * 0.5f, w.toFloat(), h.toFloat(), p)
        p.shader = android.graphics.LinearGradient(0f, 0f, 0f, h * 0.22f, 0x40000000, 0, android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h * 0.22f, p)
        return out
    }

    private fun blend(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) * (1 - t) + Color.red(b) * t).toInt(),
        (Color.green(a) * (1 - t) + Color.green(b) * t).toInt(),
        (Color.blue(a) * (1 - t) + Color.blue(b) * t).toInt())

    /** The title's colour made deep enough to carry white text. */
    private fun deep(c: Int): Int {
        if (c == 0) return 0xFF101014.toInt()
        val hsv = FloatArray(3); Color.colorToHSV(c, hsv)
        hsv[2] = hsv[2].coerceIn(0.10f, 0.22f); hsv[1] = hsv[1].coerceAtMost(0.75f)
        return Color.HSVToColor(hsv)
    }

    private fun picture(url: String, w: Int, cfg: Bitmap.Config): Bitmap? {
        val f = file(url)
        val bytes = if (f.exists()) f.readBytes() else Net.bytes(url)?.also { f.writeBytes(it) } ?: return null
        return try { Net.decode(bytes, w, cfg == Bitmap.Config.RGB_565) } catch (_: Exception) { f.delete(); null }
    }

    private fun file(url: String) = File(dir, Integer.toHexString(url.hashCode()))
}

/**
 * What the world is watching this week: the ten most-watched films and ten series from the public weekly
 * Top 10 pages at netflix.com/tudum/top10 (official wide artwork, title logo, colour), with series ratings
 * from the open TVmaze API. No account, no key, and no app on the box is needed — the card only shows titles.
 */
class TrendingFeed(
    private val base: String = "https://www.netflix.com/tudum/top10",
    /** The rank part of the meta line; a country page says where, e.g. "#2 in Saudi Arabia". */
    private val rank: (Int) -> String = { Lang.t("#$it this week", "المرتبة $it هذا الأسبوع") },
) : HeroFeed {
    private val card = Regex(
        "data-entity-id=\"top10-[^\"]*-(\\d+)\"[\\s\\S]*?background-image:url\\(([^)]+)\\);--artwork-gradient:(#[0-9A-Fa-f]{6})" +
            "[\\s\\S]*?<img src=\"([^\"]+)\" alt=\"([^\"]*)\""
    )

    override fun fetch(): List<HeroItem> {
        val films = page(base, "Movie")
        val shows = page("$base/tv", "Series")
        return (0 until maxOf(films.size, shows.size)).flatMap { listOfNotNull(films.getOrNull(it), shows.getOrNull(it)) }
    }

    /** "★ 8.1   ·   2025" for a series from TVmaze; empty when unknown (never blocks the feed). */
    private fun seriesInfo(name: String): String = try {
        val o = JSONObject(Net.get("https://api.tvmaze.com/singlesearch/shows?q=" + java.net.URLEncoder.encode(name, "UTF-8")))
        val r = o.optJSONObject("rating")?.optDouble("average", Double.NaN) ?: Double.NaN
        val year = o.optString("premiered").take(4).takeIf { it.length == 4 && it.all(Char::isDigit) }
        listOfNotNull(if (!r.isNaN() && r > 0) "★ " + String.format(java.util.Locale.US, "%.1f", r) else null, year).joinToString("   ·   ")
    } catch (_: Exception) { "" }

    private fun page(url: String, kind: String): List<HeroItem> {
        // the ten cards sit in the first ~560 KB of the page; stop reading after the tenth
        val html = Net.read(url, 900_000) { sb -> tenthDone(sb) }
        return card.findAll(html).take(10).mapIndexed { i, m ->
            val (id, img, color, logo, alt) = m.destructured
            val full = decode(alt)
            val cut = full.indexOf(": ")
            val name = if (kind == "Series" && cut > 0) full.substring(0, cut) else full
            val season = if (kind == "Series" && cut > 0) full.substring(cut + 2) else null
            val info = if (kind == "Series") seriesInfo(name) else ""
            // "Limited Series" says it all: shown instead of "Series", not next to it
            // "Limited Series", also as "<subtitle>: Limited Series"
            val limited = season != null && season.endsWith("Limited Series")
            val kindText = when {
                limited -> Lang.t("Limited Series", "مسلسل قصير")
                kind == "Series" -> Lang.t("Series", "مسلسل")
                else -> Lang.t("Movie", "فيلم")
            }
            // "Season 4" / "Part 2" in Arabic; anything else (e.g. "S11: Boston") is left out on Arabic boxes:
            // one language per line, or the reading order jumbles
            val seasonText = season?.takeIf { !limited }?.let { s ->
                if (!Lang.ar) s else Regex("^(Season|Part|Volume) (\\d+)$").find(s)?.let { m ->
                    (if (m.groupValues[1] == "Season") "الموسم " else "الجزء ") + m.groupValues[2]
                }
            }
            val meta = listOf(kindText, seasonText, info.takeIf { it.isNotEmpty() },
                rank(i + 1)).filterNotNull().filter { it.isNotEmpty() }
            HeroItem(id, name, meta.joinToString("   ·   "), img, logo, parse(color), "ys:$name official trailer")
        }.toList()
    }

    /** True once the tenth card has started and its logo <img> has arrived. */
    private fun tenthDone(sb: CharSequence): Boolean {
        val s = sb as StringBuilder
        var n = 0; var i = 0; var last = -1
        while (true) { i = s.indexOf(MARK, i); if (i < 0) break; n++; last = i; i += MARK.length }
        return n >= 10 && s.indexOf("alt=\"", last) > 0 && s.length - last > 4_000
    }

    private companion object { const val MARK = "data-entity-id=\"top10-" }

    @Suppress("DEPRECATION")
    private fun decode(s: String): String = Html.fromHtml(s).toString()

    private fun parse(hex: String): Int = try { Color.parseColor(hex) } catch (_: Exception) { 0 }
}
