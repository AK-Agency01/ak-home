package com.akhome.launcher

import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/*
 * Every edition (user, 2026-11-06): the weather where the TV is and the big matches, mixed into the card. Official,
 * lasting, keyless sources only; every fetch is small and runs on the card's background thread.
 * Words follow the box language (Lang): Modern Standard Arabic or English.
 */

/** Where the TV is, roughly: a city, known from the address its request comes from. */
class Place(val lat: Double, val lon: Double, val city: String, val country: String)

/**
 * Our own server answers (akagency.app, nothing is stored and the box sends nothing), twice a day. Works on any TV
 * in the world without asking for a location permission.
 */
private object Here {
    private var known: Place? = null
    private var at = 0L

    fun place(): Place? {
        if (known != null && System.currentTimeMillis() - at < 12 * 3_600_000L) return known
        try {
            val o = JSONObject(Net.get("https://akagency.app/akhome/where.json"))
            if (!o.isNull("lat") && !o.isNull("lon")) {
                known = Place(o.getDouble("lat"), o.getDouble("lon"), o.optString("city"), o.optString("country"))
                at = System.currentTimeMillis()
            }
        } catch (_: Exception) { /* the last place stays good */ }
        return known
    }
}

/** The weather where the TV is: degrees, sky, today's high and low (Open-Meteo, no key). [home] = when the place is unknown. */
class WeatherFeed(private val home: Place? = null) : HeroFeed {
    private var cityOf = ""
    private var arabic = ""

    /** The city in the box's language. One language per line: on an Arabic box a name only Latin letters know is left out. */
    private fun cityName(p: Place): String {
        if (!Lang.ar) return p.city
        if (p.city.isEmpty() || p.city.any { it in '\u0600'..'\u06FF' }) return p.city
        if (p.city != cityOf) try {
            val r = JSONObject(Net.get("https://geocoding-api.open-meteo.com/v1/search?count=5&language=ar&format=json&name=" +
                java.net.URLEncoder.encode(p.city, "UTF-8"))).optJSONArray("results")
            val hit = (0 until (r?.length() ?: 0)).map { r!!.getJSONObject(it) }
                .firstOrNull { p.country.isEmpty() || it.optString("country_code").equals(p.country, true) }
            arabic = hit?.optString("name").orEmpty().takeIf { n -> n.any { it in '\u0600'..'\u06FF' } }.orEmpty()
            cityOf = p.city
        } catch (_: Exception) { return "" }
        return arabic
    }

    override fun fetch(): List<HeroItem> {
        val place = Here.place() ?: home ?: throw java.io.IOException("place unknown")   // keep the last good slide
        val o = JSONObject(Net.get("https://api.open-meteo.com/v1/forecast?latitude=${place.lat}&longitude=${place.lon}" +
            "&current=temperature_2m,weather_code,is_day&hourly=temperature_2m,weather_code,is_day" +
            "&daily=temperature_2m_max,temperature_2m_min,sunrise,sunset&timezone=auto&forecast_days=2"))
        val cur = o.getJSONObject("current"); val day = o.getJSONObject("daily")
        val code = cur.getInt("weather_code"); val isDay = cur.optInt("is_day", 1) == 1
        val t = Math.round(cur.getDouble("temperature_2m"))
        // dawn / dusk: within 45 min of sunrise or sunset ("2026-10-30T06:02" → minutes of the day)
        fun mins(s: String) = s.substringAfter('T').split(':').let { it[0].toInt() * 60 + it[1].toInt() }
        val now = mins(cur.getString("time"))
        val phase = when {
            Math.abs(now - mins(day.getJSONArray("sunrise").getString(0))) < 45 -> "dawn"
            Math.abs(now - mins(day.getJSONArray("sunset").getString(0))) < 45 -> "dusk"
            else -> ""
        }
        // the next five hours, every second hour
        val hr = o.getJSONObject("hourly"); val times = hr.getJSONArray("time")
        val first = (0 until times.length()).firstOrNull { times.getString(it) > cur.getString("time") } ?: 0
        val hours = JSONArray()
        for (k in 0 until 5) {
            val i = first + k * 2; if (i >= times.length()) break
            val h24 = mins(times.getString(i)) / 60
            hours.put(JSONObject().put("h", String.format(Locale.US, "%02d:00", h24))
                .put("c", hr.getJSONArray("weather_code").getInt(i)).put("d", hr.getJSONArray("is_day").getInt(i) == 1)
                .put("t", Math.round(hr.getJSONArray("temperature_2m").getDouble(i))))
        }
        val city = cityName(place).ifEmpty { Lang.t("Weather now", "الطقس الآن") }
        val meta = JSONObject().put("city", city).put("t", t).put("code", code).put("day", isDay).put("phase", phase)
            .put("word", word(code, isDay)).put("hours", hours)
            .put("hi", Math.round(day.getJSONArray("temperature_2m_max").getDouble(0)))
            .put("lo", Math.round(day.getJSONArray("temperature_2m_min").getDouble(0)))
        return listOf(HeroItem("weather", "$city   $t°", meta.toString(), "", "", 0, "", "weather"))
    }

    private fun word(code: Int, day: Boolean) = when (code) {
        0 -> if (day) Lang.t("Sunny", "مشمس") else Lang.t("Clear", "صافٍ")
        1 -> if (day) Lang.t("Mostly sunny", "مشمس غالباً") else Lang.t("Mostly clear", "صافٍ غالباً")
        2 -> Lang.t("Partly cloudy", "غائم جزئياً")
        3 -> Lang.t("Cloudy", "غائم")
        45, 48 -> Lang.t("Fog", "ضباب")
        in 51..57 -> Lang.t("Drizzle", "رذاذ")
        in 61..67, in 80..82 -> Lang.t("Rain", "مطر")
        in 71..77, 85, 86 -> Lang.t("Snow", "ثلج")
        in 95..99 -> Lang.t("Thunderstorm", "عاصفة رعدية")
        else -> ""
    }
}

/**
 * Only the matches people actually stop for (user: the five big leagues, the Champions League, big national teams):
 * a big club or big national team on the pitch, or a World Cup / Euro / Copa game. Today and tomorrow, at most six.
 * ESPN's open scoreboard JSON, one small request per competition.
 */
class MatchFeed : HeroFeed {
    private class Comp(val code: String, val en: String, val ar: String, val weight: Int, val all: Boolean = false) {
        val name get() = Lang.t(en, ar)
    }

    private val comps = listOf(
        Comp("fifa.world", "World Cup", "كأس العالم", 90, all = true), Comp("uefa.euro", "Euro", "كأس أمم أوروبا", 80, all = true),
        Comp("conmebol.america", "Copa América", "كوبا أمريكا", 70, all = true),
        Comp("uefa.champions", "Champions League", "دوري أبطال أوروبا", 60),
        Comp("fifa.worldq.afc", "World Cup Qualifiers", "تصفيات كأس العالم", 55), Comp("afc.asian.cup", "Asian Cup", "كأس آسيا", 55),
        Comp("uefa.nations", "Nations League", "دوري الأمم الأوروبية", 40),
        Comp("fifa.worldq.uefa", "World Cup Qualifiers", "تصفيات كأس العالم", 40), Comp("fifa.friendly", "Friendly", "مباراة ودية", 20),
        Comp("esp.1", "LaLiga", "الدوري الإسباني", 35), Comp("eng.1", "Premier League", "الدوري الإنكليزي", 35),
        Comp("ita.1", "Serie A", "الدوري الإيطالي", 30), Comp("ger.1", "Bundesliga", "الدوري الألماني", 30),
        Comp("fra.1", "Ligue 1", "الدوري الفرنسي", 25),
    )

    /** Arabic names for the opponents a big team meets (not "big" themselves). */
    private val others = mapOf(
        "Brighton & Hove Albion" to "برايتون", "Aston Villa" to "أستون فيلا", "West Ham United" to "وست هام", "Everton" to "إيفرتون",
        "Nottingham Forest" to "نوتنغهام فورست", "Crystal Palace" to "كريستال بالاس", "Fulham" to "فولهام", "Brentford" to "برينتفورد",
        "AFC Bournemouth" to "بورنموث", "Wolverhampton Wanderers" to "وولفرهامبتون", "Leeds United" to "ليدز يونايتد",
        "Burnley" to "بيرنلي", "Sunderland" to "سندرلاند", "Sevilla" to "إشبيلية", "Valencia" to "فالنسيا", "Villarreal" to "فياريال",
        "Real Sociedad" to "ريال سوسيداد", "Athletic Club" to "أتلتيك بلباو", "Real Betis" to "ريال بيتيس", "Girona" to "جيرونا",
        "Celta Vigo" to "سيلتا فيغو", "Osasuna" to "أوساسونا", "Getafe" to "خيتافي", "Mallorca" to "مايوركا", "Rayo Vallecano" to "رايو فايكانو",
        "Lazio" to "لاتسيو", "Atalanta" to "أتالانتا", "Fiorentina" to "فيورنتينا", "Bologna" to "بولونيا", "Torino" to "تورينو",
        "RB Leipzig" to "لايبزيغ", "Eintracht Frankfurt" to "آينتراخت فرانكفورت", "VfB Stuttgart" to "شتوتغارت",
        "Borussia Mönchengladbach" to "مونشنغلادباخ", "VfL Wolfsburg" to "فولفسبورغ", "AS Monaco" to "موناكو", "Lyon" to "ليون",
        "Lille" to "ليل", "Nice" to "نيس", "Lens" to "لانس", "Rennes" to "رين", "Benfica" to "بنفيكا", "Porto" to "بورتو",
        "Sporting CP" to "سبورتينغ لشبونة", "PSV Eindhoven" to "آيندهوفن", "Ajax" to "أياكس", "Celtic" to "سيلتك", "Galatasaray" to "غلطة سراي",
        "Club Brugge" to "كلوب بروج", "Shakhtar Donetsk" to "شاختار", "Red Bull Salzburg" to "سالزبورغ",
        "Iran" to "إيران", "Jordan" to "الأردن", "Qatar" to "قطر", "United Arab Emirates" to "الإمارات", "Kuwait" to "الكويت",
        "Oman" to "عُمان", "Bahrain" to "البحرين", "Syria" to "سوريا", "Palestine" to "فلسطين", "Japan" to "اليابان",
        "South Korea" to "كوريا الجنوبية", "Australia" to "أستراليا", "Egypt" to "مصر", "Algeria" to "الجزائر", "Tunisia" to "تونس",
        "Mexico" to "المكسيك", "United States" to "أمريكا", "Colombia" to "كولومبيا", "Switzerland" to "سويسرا", "Denmark" to "الدنمارك",
        "Poland" to "بولندا", "Austria" to "النمسا", "Scotland" to "اسكتلندا", "Wales" to "ويلز", "Serbia" to "صربيا", "Turkey" to "تركيا",
    )

    /** Arabic names for the teams that matter; also the "big team" list. */
    private val names = mapOf(
        "Real Madrid" to "ريال مدريد", "Barcelona" to "برشلونة", "Atletico Madrid" to "أتلتيكو مدريد", "Atlético Madrid" to "أتلتيكو مدريد",
        "Manchester City" to "مانشستر سيتي", "Liverpool" to "ليفربول", "Arsenal" to "آرسنال", "Chelsea" to "تشيلسي",
        "Manchester United" to "مانشستر يونايتد", "Tottenham Hotspur" to "توتنهام", "Newcastle United" to "نيوكاسل",
        "Bayern Munich" to "بايرن ميونخ", "Borussia Dortmund" to "بوروسيا دورتموند", "Bayer Leverkusen" to "باير ليفركوزن",
        "Juventus" to "يوفنتوس", "Internazionale" to "إنتر ميلان", "AC Milan" to "ميلان", "Napoli" to "نابولي", "AS Roma" to "روما",
        "Paris Saint-Germain" to "باريس سان جيرمان", "Marseille" to "مارسيليا",
        "Iraq" to "العراق", "Saudi Arabia" to "السعودية", "Argentina" to "الأرجنتين", "Brazil" to "البرازيل", "France" to "فرنسا",
        "Spain" to "إسبانيا", "England" to "إنكلترا", "Germany" to "ألمانيا", "Portugal" to "البرتغال", "Netherlands" to "هولندا",
        "Italy" to "إيطاليا", "Belgium" to "بلجيكا", "Croatia" to "كرواتيا", "Uruguay" to "الأوروغواي", "Morocco" to "المغرب",
    )

    override fun fetch(): List<HeroItem> {
        // yesterday, today and tomorrow (user, 2026-10-31: yesterday's and today's big games stay, finished ones
        // with their score)
        val day0 = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val from = day0.timeInMillis - 24 * 3_600_000L
        val until = day0.apply { add(Calendar.DAY_OF_YEAR, 2) }.timeInMillis
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        // ESPN's match days run from 07:00 UTC; these are the ones our three days touch
        val key = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val wanted = generateSequence(from - ESPN_DAY) { it + 24 * 3_600_000L }.takeWhile { it < until - ESPN_DAY }
            .map { key.format(it) }.toList()
        var ok = false
        val found = comps.flatMap { comp ->
            val base = "https://site.api.espn.com/apis/site/v2/sports/soccer/${comp.code}/scoreboard"
            var cup = ""
            val events = try {
                // no date: ESPN answers with one match day (sometimes yesterday's) plus the season's calendar, so
                // only the days of ours that have games are asked for — a league resting costs one small request
                val o = JSONObject(Net.get(base))
                ok = true
                val league = o.optJSONArray("leagues")?.optJSONObject(0)
                cup = league?.optJSONArray("logos")?.let { l ->
                    // the version for dark backgrounds when there is one
                    (0 until l.length()).map { l.getJSONObject(it) }
                        .let { all -> all.firstOrNull { it.optJSONArray("rel")?.toString()?.contains("dark") == true } ?: all.firstOrNull() }
                        ?.optString("href")
                } ?: ""
                val cal = league?.optJSONArray("calendar")
                val days = if (cal != null && cal.length() > 0 && cal.opt(0) is String)
                    (0 until cal.length()).map { cal.getString(it).take(10) }.toHashSet() else null
                val have = o.optJSONObject("day")?.optString("date")
                val pages = listOf(o) + wanted.filter { it != have && (days == null || it in days) }.mapNotNull { d ->
                    try { JSONObject(Net.get("$base?dates=" + d.replace("-", ""))) } catch (_: Exception) { null }
                }
                val seen = HashSet<String>()
                pages.flatMap { p -> p.optJSONArray("events").let { a -> (0 until (a?.length() ?: 0)).map { a!!.getJSONObject(it) } } }
                    .filter { seen.add(it.optString("id")) }
            } catch (_: Exception) { emptyList() }
            events.mapNotNull { ev ->
                val start = try { iso.parse(ev.getString("date").take(16))!!.time } catch (_: Exception) { return@mapNotNull null }
                if (start < from || start >= until) return@mapNotNull null
                val cs = ev.getJSONArray("competitions").getJSONObject(0).getJSONArray("competitors")
                val teams = (0 until cs.length()).map { cs.getJSONObject(it) }.sortedBy { if (it.optString("homeAway") == "home") 0 else 1 }
                if (teams.size != 2) return@mapNotNull null
                val en = teams.map { it.getJSONObject("team").optString("displayName") }
                val big = en.count { it in names }
                if (big == 0 && !comp.all) return@mapNotNull null
                Triple(comp.weight + big * 30, start, item(comp, cup, ev, teams, en, start))
            }
        }
        if (!ok) throw java.io.IOException("no scoreboard reachable")   // keep the last good list, retry soon
        return found.sortedByDescending { it.first }.take(8).sortedBy { it.second }.map { it.third }
    }

    private companion object { const val ESPN_DAY = 7 * 3_600_000L }

    private fun item(comp: Comp, cup: String, ev: JSONObject, teams: List<JSONObject>, en: List<String>, start: Long): HeroItem {
        val t = teams.map { it.getJSONObject("team") }
        // one language per card (user): Arabic on Arabic boxes when both names are known, otherwise both in English
        val ar = en.map { names[it] ?: others[it] }.let { a -> if (Lang.ar && a.all { it != null }) a.map { it!! } else en }
        val st = ev.optJSONObject("status")
        val state = st?.optJSONObject("type")?.optString("state") ?: "pre"
        // digits run left to right: each score sits on its own crest's side (home is on the right in Arabic)
        val hs = teams[0].optString("score", "0"); val aws = teams[1].optString("score", "0")
        val score = if (Lang.ar) "$aws - $hs" else "$hs - $aws"
        // what Slides.match draws: big = time or score, tag = the small pill above it
        val m = JSONObject().put("comp", comp.name)
            .put("ha", t[0].optString("abbreviation").ifEmpty { en[0].take(3).uppercase() })
            .put("aa", t[1].optString("abbreviation").ifEmpty { en[1].take(3).uppercase() })
        when (state) {
            "in" -> m.put("state", 1).put("big", score).put("tag", Lang.t("LIVE  ", "مباشر  ") + (st?.optString("displayClock") ?: ""))
            "post" -> m.put("state", 2).put("big", score).put("tag", Lang.t("Full time", "انتهت") +
                (day(start).takeIf { it != Lang.t("Today", "اليوم") }?.let { "  ·  $it" } ?: ""))
            else -> m.put("state", 0).put("big", clock(start)).put("tag", day(start))
        }
        fun color(o: JSONObject) = try { Color.parseColor("#" + o.optString("color")) } catch (_: Exception) { 0xFF1E2A3A.toInt() }
        // OK opens the match's page (user, 2026-10-31): line-ups, score, events; a finished match without a
        // browser on the box falls back to its highlights on YouTube
        val page = ev.optJSONArray("links")?.let { l -> (0 until l.length()).map { l.getJSONObject(it) } }
            ?.firstOrNull { it.optJSONArray("rel")?.toString()?.contains("summary") == true }?.optString("href")
            ?: "https://www.espn.com/soccer/match/_/gameId/" + ev.optString("id")
        val link = "web:$page" + if (state == "post") "|ys:${en[0]} vs ${en[1]} highlights" else ""
        return HeroItem("m-" + ev.optString("id") + "-" + state, "${ar[0]}  ×  ${ar[1]}", m.toString(),
            t[0].optString("logo"), t[1].optString("logo") + "|" + color(t[1]) + "|" + cup, color(t[0]), link, "match")
    }

    private fun day(ms: Long): String {
        val c = Calendar.getInstance(); val today = c.get(Calendar.DAY_OF_YEAR)
        val tomorrow = c.apply { add(Calendar.DAY_OF_YEAR, 1) }.get(Calendar.DAY_OF_YEAR)
        val yesterday = c.apply { add(Calendar.DAY_OF_YEAR, -2) }.get(Calendar.DAY_OF_YEAR)
        c.timeInMillis = ms
        return when (c.get(Calendar.DAY_OF_YEAR)) {
            today -> Lang.t("Today", "اليوم")
            tomorrow -> Lang.t("Tomorrow", "غداً")
            yesterday -> Lang.t("Yesterday", "أمس")
            else -> SimpleDateFormat("EEEE", if (Lang.ar) Locale("ar") else Locale.US).format(ms)
        }
    }

    /** 24-hour kick-off time in the box's time zone (user). */
    private fun clock(ms: Long): String = SimpleDateFormat("HH:mm", Locale.US).format(ms)
}
