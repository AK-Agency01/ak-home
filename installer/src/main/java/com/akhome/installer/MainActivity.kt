package com.akhome.installer

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import io.github.muntashirakon.adb.AdbPairingRequiredException
import java.util.concurrent.Executors

/**
 * The whole installer in one screen that swaps pages: start → find TV → (pair) → connect → edition → install → done,
 * and start → find TV → connect → back to the original. All ADB work runs on one worker thread.
 */
class MainActivity : Activity() {
    private enum class Mode { INSTALL, RESTORE }

    private val io = Executors.newSingleThreadExecutor()
    private lateinit var root: FrameLayout
    private lateinit var light: Typeface
    private lateinit var med: Typeface
    private var mode = Mode.INSTALL
    private var finder: Finder? = null
    private var client: AdbClient? = null
    private var onStart = true
    private val found = LinkedHashMap<String, Found>()   // by host; network (5555) and wireless entries merged
    private var list: LinearLayout? = null
    private var hint: TextView? = null

    /** Language chosen in the app ("ar" / "en"); empty = the phone's language. */
    override fun attachBaseContext(base: android.content.Context) {
        val lang = base.getSharedPreferences("installer", MODE_PRIVATE).getString("lang", "") ?: ""
        if (lang.isEmpty()) { super.attachBaseContext(base); return }
        val conf = android.content.res.Configuration(base.resources.configuration).apply { setLocale(java.util.Locale(lang)) }
        super.attachBaseContext(base.createConfigurationContext(conf))
    }

    private fun switchLanguage() {
        val ar = resources.configuration.locales[0].language == "ar"
        getSharedPreferences("installer", MODE_PRIVATE).edit().putString("lang", if (ar) "en" else "ar").apply()
        recreate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        light = font("rx_light.ttf"); med = font("rx_med.ttf")
        Ak.motion = android.provider.Settings.Global.getFloat(contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
        root = FrameLayout(this).apply { background = back }
        setContentView(root)
        showStart()
    }

    private val back = Backdrop()
    override fun onResume() { super.onResume(); back.running = true }
    override fun onPause() { super.onPause(); back.running = false }

    override fun onDestroy() {
        finder?.stop()
        io.execute { try { client?.close() } catch (_: Exception) {} }
        io.shutdown()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (onStart) super.onBackPressed() else showStart() }

    // ---------------------------------------------------------------- pages

    private fun showStart() {
        finder?.stop(); finder = null
        // the launcher's order: the eyes in the corner, the big title, a pink line under it (the TV's date),
        // the capsules, then the actions
        page(start = true,
            logo(46).apply { atStart = true },
            GradientText(this, flow = true).apply {
                text = getString(R.string.app_name); typeface = med; setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
                if (!arabic) letterSpacing = -0.02f
                pad(top = 18)
            },
            eyebrow(getString(R.string.eyebrow_start)),
            text(getString(R.string.tagline), 16f, Ak.T2, light).apply { pad(top = 2, bottom = 6) },
            floaters(getString(R.string.chip_wifi) to Ak.GREEN, getString(R.string.chip_nopc) to Ak.PINK, getString(R.string.chip_free) to Ak.GOLD),
            space(14),
            button(getString(R.string.find_tv), primary = true) { mode = Mode.INSTALL; showFind() },
            button(getString(R.string.restore)) { mode = Mode.RESTORE; showFind() },
            space(18),
            attention(getString(R.string.guide_btn)) { showGuide() },
            // language button: shows the other language, in that language
            link(getString(R.string.other_language)) { switchLanguage() },
        )
    }

    /**
     * Turning on debugging, step by step: number, words, and a real TV screenshot with the option to press circled
     * (assets/guide/<ar|en>/<n>.jpg, taken on onn; e-mail addresses covered). Pictures follow the app's language.
     */
    private fun showGuide() {
        val lang = if (resources.configuration.locales[0].language == "ar") "ar" else "en"
        val steps = listOf(R.string.guide_1, R.string.guide_2, R.string.guide_3, R.string.guide_4, R.string.guide_5, R.string.guide_6)
        val views = mutableListOf<View>(title(getString(R.string.guide_title)))
        steps.forEachIndexed { i, s ->
            val parts = mutableListOf<View>(step("${i + 1}", getString(s)))
            val shot = try { android.graphics.BitmapFactory.decodeStream(assets.open("guide/$lang/${i + 1}.jpg")) } catch (_: Exception) { null }
            if (shot != null) parts += ImageView(this).apply {
                setImageBitmap(shot); adjustViewBounds = true
                // rounded picture inside the glass card
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(14).toFloat()) }
                }
                clipToOutline = true
                layoutParams = LinearLayout.LayoutParams(-1, -2)
            }
            views += panel(*parts.toTypedArray())
        }
        views += button(getString(R.string.back)) { showStart() }
        page(start = false, *views.toTypedArray())
    }
    private fun showFind() {
        found.clear()
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        hint = text(getString(R.string.searching), 15f, Ak.T2, light)
        // a spinner next to the words while the search runs, so it is clear something is working
        val spin = ProgressBar(this).apply { isIndeterminate = true; indeterminateTintList = android.content.res.ColorStateList.valueOf(Ak.VIOLET) }
        val status = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(spin, LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(12) })
            addView(hint, LinearLayout.LayoutParams(0, -2, 1f))
            setPadding(0, 0, 0, dp(12))
        }
        val again = chip(getString(R.string.search_again)) { showFind() }.apply { visibility = View.GONE }
        val ip = edit(getString(R.string.manual_hint), InputType.TYPE_CLASS_PHONE)
        page(start = false,
            title(getString(if (mode == Mode.INSTALL) R.string.find_tv else R.string.restore)),
            status, list!!, again, space(10),
            panel(
                text(getString(R.string.manual), 14f, Ak.T2, light),
                ip,
                button(getString(R.string.go), primary = true) {
                // "192.168.1.20" or "192.168.1.20:5556" (some boxes use another port)
                val s = ip.text.toString().trim(); if (s.isEmpty()) return@button
                val host = s.substringBefore(':'); val port = s.substringAfter(':', "").toIntOrNull() ?: 5555
                    connect(Found(host, port, host, tls = false))
                },
            ),
            space(4),
            attention(getString(R.string.guide_btn)) { showGuide() },
        )
        val myList = list
        val done = {
            runOnUiThread {
                if (list !== myList) return@runOnUiThread          // left this page meanwhile
                spin.visibility = View.GONE; again.visibility = View.VISIBLE
                hint?.text = if (found.isEmpty()) getString(R.string.none_yet)
                    else resources.getQuantityString(R.plurals.found_n, found.size, found.size)
            }
        }
        finder?.stop()
        finder = Finder(this, onDone = done) { f -> runOnUiThread { add(f) } }.also {
            if (it.myAddress() == null) { hint?.text = getString(R.string.no_wifi); spin.visibility = View.GONE; again.visibility = View.VISIBLE }
            else it.start()
        }
    }
    private fun add(f: Found) {
        val old = found[f.host]
        val merged = when {
            old == null -> f
            f.pairPort > 0 -> old.copy(pairPort = f.pairPort, label = if (old.tls) old.label else f.label)
            f.tls && !old.tls -> old                                   // keep the plain 5555 entry, it needs no pairing
            else -> old.copy(port = if (old.port == 0) f.port else old.port)
        }
        found[f.host] = merged
        val l = list ?: return
        l.removeAllViews()
        for (x in found.values) {
            // title: the TV's name when it announces one, else its address; below: address (if not shown) + how
            val named = x.label != x.host
            val how = getString(if (x.tls) R.string.wireless else R.string.network)
            l.addView(card(if (named) x.label else x.host, if (named) "${x.host}  ·  $how" else how) { connect(x) },
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        }
    }

    private fun showPair(f: Found) {
        val code = edit(getString(R.string.pair_code), InputType.TYPE_CLASS_NUMBER)
        val port = edit(getString(R.string.pair_port), InputType.TYPE_CLASS_NUMBER).apply {
            if (f.pairPort > 0) setText(f.pairPort.toString())
        }
        page(start = false,
            title(getString(R.string.pair_title)),
            text(getString(R.string.pair_steps), 15f, Ak.T2, light).apply { pad(bottom = 16) },
            code, port,
            button(getString(R.string.pair_btn), primary = true) {
                val c = code.text.toString().trim(); val p = port.text.toString().trim().toIntOrNull() ?: found[f.host]?.pairPort ?: 0
                if (c.length >= 6 && p > 0) pair(f, p, c)
            },
            button(getString(R.string.back)) { showFind() },
        )
    }

    private fun showBusy(msg: String, sub: String? = null) {
        page(start = false,
            space(50),
            logo(64),
            // the eyes no longer ripple: a maroon spinner says "working"
            ProgressBar(this).apply {
                isIndeterminate = true; indeterminateTintList = android.content.res.ColorStateList.valueOf(Ak.PINK)
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(18) }
            },
            text(msg, 18f, Color.WHITE, med).apply { gravity = Gravity.CENTER; pad(top = 12) },
            *(sub?.let { arrayOf<View>(text(it, 15f, Ak.T2, light).apply { gravity = Gravity.CENTER; pad(top = 10) }) } ?: emptyArray()),
        )
    }

    private fun showError(msg: String) {
        page(start = false,
            title("⚠"),
            text(msg, 16f, Color.WHITE, light).apply { pad(bottom = 24) },
            button(getString(R.string.again), primary = true) { showStart() },
        )
    }

    /**
     * The plan page. One plain question with big buttons — which device (TV box / TV set) — preset to what we
     * detected and changeable with one tap. Below: what will stay and what will be switched off.
     */
    private fun showPlan(tv: Tv, info: TvInfo, homes: List<Home>) {
        val editions = Edition.values().filter { has(it.asset) }
        val start = tv.suggest(info).takeIf { it in editions } ?: editions.first()
        // device type: chosen by itself when the detection is sure; otherwise nothing chosen and the user is asked
        var screen: Boolean? = if (info.kindSure) start == Edition.GLOBAL_SCREENS else null
        fun edition() = (if (screen == true) Edition.GLOBAL_SCREENS else Edition.GLOBAL_BOX).takeIf { it in editions } ?: start
        val go = button("", primary = true) {
            if (screen == null) return@button      // asked first: TV set or box?
            installEdition(tv, info, homes, edition())
        }
        val planText = text("", 14f, Ak.T2, light).apply { pad(bottom = 12) }
        val kindRow = LinearLayout(this)
        fun refresh() {
            for (i in 0 until kindRow.childCount) kindRow.getChildAt(i).isSelected = screen != null && (i == 1) == screen
            val e = edition()
            go.text = if (screen == null) getString(R.string.pick_kind_first) else getString(R.string.install_btn, getString(e.title))
            go.alpha = if (screen == null) 0.45f else 1f
            planText.text = planFor(info, homes)
        }
        fun option(row: LinearLayout, label: String, detected: Boolean, pick: () -> Unit) {
            row.addView(text(if (detected) "$label  ✓" else label, 17f, Color.WHITE, med).apply {
                gravity = Gravity.CENTER; setPadding(dp(12), dp(18), dp(12), dp(18))
                background = selectable(); isClickable = true; isFocusable = true
                setOnClickListener { pick(); refresh() }
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(5); marginEnd = dp(5) })
        }
        option(kindRow, getString(R.string.kind_box), info.kindSure && info.kind == Kind.BOX) { screen = false }
        option(kindRow, getString(R.string.kind_tv), info.kindSure && info.kind == Kind.TV) { screen = true }
        val views = mutableListOf<View>(
            title(info.name),
            text("Android ${info.android}  ·  " + getString(R.string.detected_hint), 13f, Ak.T3, light).apply { pad(bottom = 10) },
            eyebrow(getString(R.string.q_kind)),
            if (info.kindSure) text(getString(if (info.kind == Kind.TV) R.string.detected_kind_tv else R.string.detected_kind_box, info.kindWhy), 13f, Ak.T3, light)
            else text(getString(R.string.kind_unsure), 14f, Ak.BLUSH, med),
            kindRow,
            eyebrow(getString(R.string.plan_title)).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(18) },
            planText, go,
            button(getString(R.string.back)) { showStart() },
        )
        page(start = false, *views.toTypedArray())
        refresh()
        go.requestFocus()
    }
    /** What will stay and be switched off — shown before anything is done. */
    private fun planFor(info: TvInfo, homes: List<Home>) = listOf(
        "✓  " + getString(R.string.plan_keep, info.userApps),
        if (homes.isEmpty()) "✓  " + getString(R.string.plan_none_off)
        else "•  " + getString(R.string.plan_off, homes.joinToString(", ") { it.label }),
        "✓  " + getString(R.string.plan_nothing_else),
    ).joinToString("\n\n")

    private fun has(asset: String) = try { assets.openFd(asset).close(); true } catch (_: Exception) { false }
    private fun showRestore(tv: Tv, info: TvInfo, off: List<String>) {    // off: names of what comes back
        val remove = CheckBox(this).apply {
            text = getString(R.string.remove_too); setTextColor(Color.WHITE); typeface = light; isChecked = true
            buttonTintList = android.content.res.ColorStateList.valueOf(Ak.VIOLET)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        }
        page(start = false,
            title(getString(R.string.restore_title, info.name)),
            text(if (off.isEmpty()) getString(R.string.restore_nothing) else getString(R.string.restore_list, off.joinToString(", ")),
                15f, Ak.T2, light).apply { pad(bottom = 16) },
            remove, space(16),
            button(getString(R.string.restore_btn), primary = true) {
                val rm = remove.isChecked
                showBusy(getString(R.string.restore_btn) + "…")
                io.execute {
                    try {
                        val ok = tv.restore(info, rm)
                        ui { if (ok) done(getString(R.string.restored), null) else done(getString(R.string.restore_kept_title), getString(R.string.restore_kept)) }
                    }
                    catch (e: Exception) { ui { showError(explain(e)) } }
                }
            },
            button(getString(R.string.back)) { showStart() },
        )
    }

    private fun done(title: String, sub: String?) {
        page(start = false,
            space(40), logo(),
            text(title, 22f, Color.WHITE, med).apply { gravity = Gravity.CENTER; pad(top = 12) },
            *(sub?.let { arrayOf<View>(text(it, 15f, Ak.T2, light).apply { gravity = Gravity.CENTER; pad(top = 10, bottom = 30) }) } ?: emptyArray()),
            button(getString(R.string.again), primary = true) { showStart() },
        )
    }

    // ---------------------------------------------------------------- work (worker thread)

    private var last: Found? = null

    private fun connect(f: Found) {
        finder?.stop(); finder = null
        if (f.tls && f.port == 0) { showPair(f); return }     // only the pairing screen is open on the TV
        last = f
        showBusy(getString(R.string.connecting, f.host), getString(R.string.accept_on_tv))
        io.execute {
            try {
                val tv = Tv(this, open(f)) { }
                val info = tv.info()
                if (mode == Mode.INSTALL) {
                    val homes = tv.stockHomes(info)
                    ui { showPlan(tv, info, homes) }
                }
                else { val off = tv.toRestore().map { tv.label(it) }; ui { showRestore(tv, info, off) } }
            } catch (e: AdbPairingRequiredException) {
                ui { showPair(f) }
            } catch (e: Exception) {
                android.util.Log.w("AKI", "connect failed: " + generateSequence(e as Throwable) { it.cause }.joinToString(" <- ") { "${it.javaClass.name}: ${it.message}" }, e)
                ui { showError(explain(e)) }
            }
        }
    }

    /** A fresh ADB connection to [f] (the previous one, if any, is closed). Worker thread only. */
    private fun open(f: Found): AdbClient {
        AdbKeys.load(this)
        try { client?.close() } catch (_: Exception) {}
        val c = AdbClient()
        if (!c.connect(f.host, f.port)) throw java.io.IOException("refused")
        client = c
        return c
    }

    /** Doctor's words for a connection problem it cannot fix by itself. */
    private fun explain(e: Throwable): String {
        val all = generateSequence(e) { it.cause }.joinToString(" | ") { "${it.javaClass.simpleName} ${it.message}" }
        return when {
            e is Problem -> e.message ?: ""
            all.contains("ECONNREFUSED") || all.contains("refused", true) -> getString(R.string.problem_refused_conn)
            all.contains("Authentication", true) || all.contains("Unauthori", true) -> getString(R.string.problem_auth)
            all.contains("timed out", true) || all.contains("Timeout", true) -> getString(R.string.problem_timeout)
            all.contains("EHOSTUNREACH") || all.contains("ENETUNREACH") || all.contains("NoRoute") -> getString(R.string.problem_net)
            else -> getString(R.string.connect_failed, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun pair(f: Found, pairPort: Int, code: String) {
        showBusy(getString(R.string.pair_btn) + "…")
        io.execute {
            try {
                AdbKeys.load(this)
                AdbClient().use { if (!it.pair(f.host, pairPort, code)) throw java.io.IOException("wrong code") }
                // after pairing the TV announces its connect port over mDNS within a few seconds
                var port = f.port
                if (port == 0) {
                    val wait = Finder(this) { x -> if (x.host == f.host && x.tls && x.port > 0) port = x.port }
                    wait.start()
                    val end = System.currentTimeMillis() + 15_000
                    while (port == 0 && System.currentTimeMillis() < end) Thread.sleep(250)
                    wait.stop()
                }
                if (port == 0) throw java.io.IOException("TV not announcing wireless debugging")
                ui { connect(f.copy(port = port, tls = true)) }
            } catch (e: Exception) {
                ui { showError(explain(e)) }
            }
        }
    }

    private fun installEdition(first: Tv, info: TvInfo, homes: List<Home>, e: Edition) {
        val steps = listOf(R.string.step_copy, R.string.step_home, R.string.step_check)
        val rows = steps.map { step("·", getString(it)) }
        val pct = text("", 14f, Ak.T3, light)
        val bar = ProgressLine(this).apply { layoutParams = LinearLayout.LayoutParams(-1, dp(3)) }
        val log = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page(start = false, logo(56), title("${getString(e.title)} → ${info.name}"), *rows.toTypedArray(), bar, pct, log)
        // the line moves step by step (and with the copy's % inside the first step)
        fun progress(i: Int, p: Int = 0) = ui { bar.fraction = (i + p / 100f) / steps.size }
        fun mark(i: Int, s: String) = ui { (rows[i].getChildAt(0) as TextView).text = s }
        val doctor: (String) -> Unit = { msg -> ui { log.addView(text("🔧  $msg", 14f, Ak.BLUSH, light)) } }
        io.execute {
            var at = 0
            var tv = client?.let { Tv(this, it, doctor) } ?: first
            // doctor: a dropped Wi-Fi connection is reconnected once and the step repeated
            fun <T> step(block: () -> T): T = try { block() } catch (x: java.io.IOException) {
                val f = last ?: throw x
                doctor(getString(R.string.fix_reconnect)); tv = Tv(this, open(f), doctor); block()
            }
            try {
                val iHome = steps.indexOf(R.string.step_home); val iCheck = steps.indexOf(R.string.step_check)
                mark(0, "…"); step { tv.install(e) { p -> progress(0, p); ui { pct.text = if (p < 100) "$p %" else "" } } }; mark(0, "✓"); at = iHome
                progress(iHome)
                mark(iHome, "…"); step { tv.makeHome(info, e, homes) }; mark(iHome, "✓"); at = iCheck
                progress(iCheck)
                mark(iCheck, "…")
                if (!step { tv.isHome(info, e) }) throw Problem(getString(R.string.problem_not_home))
                if (!step { tv.runs(e) }) throw Problem(getString(R.string.problem_crash, info.name))
                mark(iCheck, "✓"); progress(steps.size)
                ui { done(getString(R.string.done_title), getString(R.string.done_sub)) }
            } catch (x: Exception) {
                android.util.Log.w("AKI", "install failed", x)
                mark(at, "✕")
                ui { showError(explain(x)) }
            }
        }
    }
    // ---------------------------------------------------------------- look (same glass as AK Home)

    private fun ui(block: () -> Unit) = runOnUiThread(block)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun font(name: String): Typeface = try { Typeface.createFromAsset(assets, name) } catch (_: Exception) { Typeface.DEFAULT }

    private fun page(start: Boolean, vararg views: View) {
        onStart = start
        val l = list
        if (l == null || l !in views) { list = null; hint = null }   // found TVs only update the find page
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(48), dp(24), dp(32))
        }
        for (v in views) col.addView(v, ((v.layoutParams as? LinearLayout.LayoutParams) ?: LinearLayout.LayoutParams(-1, -2)).apply { bottomMargin = dp(14) })
        root.removeAllViews()
        root.addView(ScrollView(this).apply { isFillViewport = true; addView(col) })
    }

    private fun text(s: String, sp: Float, color: Int, tf: Typeface) = TextView(this).apply {
        text = s; setTextColor(color); typeface = tf; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setLineSpacing(0f, 1.15f)
    }

    private fun TextView.pad(top: Int = 0, bottom: Int = 0) = setPadding(0, dp(top), 0, dp(bottom))

    /** Headings: large and tight like the site's (tight letters in Latin only). */
    private fun title(s: String) = text(s, 26f, Color.WHITE, med).apply {
        pad(bottom = 12); if (!arabic) letterSpacing = -0.03f; setLineSpacing(0f, if (arabic) 1.25f else 1.08f)
    }

    // fixed height: a bare View with wrap_content would take all the room left and push the rest off screen
    private fun space(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(-1, dp(h)) }

    private val eyes by lazy { try { android.graphics.BitmapFactory.decodeStream(assets.open("ak_eye.png")) } catch (_: Exception) { null } }

    /** The AK logo inside the app: the two eyes alone, as on the TV ([height] = the eyes' height in dp). */
    private fun logo(height: Int = 64) = Orb(this, eyes).apply {
        layoutParams = LinearLayout.LayoutParams(-1, dp(height))
    }

    private val px get() = resources.displayMetrics.density

    /** Main action: the launcher's chosen capsule (maroon). Secondary: its glass capsule, maroon while pressed. */
    private fun button(s: String, primary: Boolean = false, action: () -> Unit) = text(s, 17f, Color.WHITE, med).apply {
        gravity = Gravity.CENTER; setPadding(dp(20), dp(17), dp(20), dp(17))
        background = GlassDrawable.states(dp(100).toFloat(), px, primary)
        if (primary) {
            elevation = dp(8).toFloat()
            if (android.os.Build.VERSION.SDK_INT >= 28) { outlineSpotShadowColor = Ak.DEEP; outlineAmbientShadowColor = Ak.DEEP }
        }
        isClickable = true; isFocusable = true
        setOnClickListener { action() }
    }

    private val arabic get() = resources.configuration.locales[0].language == "ar"

    /** A short line in the launcher's date pink, above or under a heading. */
    private fun eyebrow(s: String) = GradientText(this, flow = false).apply {
        text = s; typeface = med; setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        layoutParams = LinearLayout.LayoutParams(-2, -2)
    }

    /** A row of the launcher's top capsules (white glass, no edge), each with a small coloured dot. Still, like the TV. */
    private fun floaters(vararg items: Pair<String, Int>) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        items.forEachIndexed { i, (s, dot) ->
            val chip = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(8), dp(13), dp(8))
                background = GlassDrawable(dp(100).toFloat(), px)
                addView(View(context).apply {
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(dot) }
                }, LinearLayout.LayoutParams(dp(7), dp(7)).apply { marginEnd = dp(7) })
                addView(text(s, 12.5f, Color.WHITE, med).apply { maxLines = 1 })
            }
            addView(chip, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) marginStart = dp(8) })
        }
        layoutParams = LinearLayout.LayoutParams(-1, -2)
    }

    /** Small clear-glass pill, centred (e.g. "Search again"). */
    private fun chip(s: String, action: () -> Unit) = text(s, 15f, Color.WHITE, med).apply {
        gravity = Gravity.CENTER; setPadding(dp(26), dp(11), dp(26), dp(11))
        background = GlassDrawable.states(dp(100).toFloat(), px)
        isClickable = true; isFocusable = true; setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL }
    }

    private fun link(s: String, action: () -> Unit) = text(s, 15f, Ak.BLUSH, light).apply {
        gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(12)); isClickable = true; isFocusable = true; setOnClickListener { action() }
    }

    /**
     * The "turn on debugging first" link, made to be noticed (user, 2026-10-29): words with a pulsing dot beside
     * them, like an unread badge. In the site's colours since 2026-10-30 (no blue / red there): lavender words and a
     * glowing gold dot — the site's third status colour.
     */
    private fun attention(s: String, action: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
        setPadding(dp(8), dp(12), dp(8), dp(12)); isClickable = true; isFocusable = true
        val dot = View(context).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ak.GOLD) }
            elevation = dp(5).toFloat()
            if (android.os.Build.VERSION.SDK_INT >= 28) { outlineSpotShadowColor = Ak.GOLD; outlineAmbientShadowColor = Ak.GOLD }
        }
        addView(dot, LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(10) })
        addView(text(s, 16f, Ak.LAVENDER, med))
        android.animation.ObjectAnimator.ofFloat(dot, View.ALPHA, 1f, 0.25f).apply {
            duration = 700; repeatMode = android.animation.ValueAnimator.REVERSE
            repeatCount = android.animation.ValueAnimator.INFINITE; start()
        }
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL }
        setOnClickListener { action() }
    }

    /** Glass card that shows the chosen option (selected) lit, with a white rim. */
    private fun selectable() = GlassDrawable.states(dp(20).toFloat(), px)

    /** A group of controls on one glass panel. */
    private fun panel(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(18), dp(18), dp(18))
        background = GlassDrawable(dp(24).toFloat(), px)
        views.forEachIndexed { i, v -> addView(v, ((v.layoutParams as? LinearLayout.LayoutParams) ?: LinearLayout.LayoutParams(-1, -2)).apply { if (i > 0) topMargin = dp(12) }) }
    }

    /** Found TV: TV icon, address and connection type, chevron — one glass card. */
    private fun card(t: String, sub: String, action: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = GlassDrawable.states(dp(22).toFloat(), px)
        isClickable = true; isFocusable = true
        addView(TvIcon(context), LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(14) })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(t, 19f, Color.WHITE, med))
            addView(text(sub, 13f, Ak.T2, light).apply { pad(top = 3) })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        // chevron points forward in the reading direction
        addView(text(if (resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) "‹" else "›", 26f, Ak.T3, light))
        setOnClickListener { action() }
    }
    private fun step(mark: String, s: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(text(mark, 18f, Ak.BLUSH, med).apply { minWidth = dp(36) })
        addView(text(s, 16f, Color.WHITE, light), LinearLayout.LayoutParams(0, -2, 1f))
    }

    private fun edit(hintText: String, type: Int) = EditText(this).apply {
        hint = hintText; inputType = type; setTextColor(Color.WHITE); setHintTextColor(0x66FFFFFF); typeface = light
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f); setPadding(dp(18), dp(14), dp(18), dp(14))
        background = GlassDrawable(dp(16).toFloat(), px)
    }
}
