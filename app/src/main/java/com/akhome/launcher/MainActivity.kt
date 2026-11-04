package com.akhome.launcher

import android.animation.ObjectAnimator
import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.TransitionDrawable
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.text.format.DateFormat
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Calendar
import java.util.Locale
import kotlin.math.max

class MainActivity : Activity() {

    /** An app as shown on screen: icon, colour and baked card bitmap. */
    private class Entry(val app: App, val icon: Drawable, val color: Int, val name: String, val stamp: Long) {
        var card: Bitmap? = null
    }

    private lateinit var ui: Ui
    private lateinit var prefs: Prefs
    private var lite = false
    private val main = Handler(Looper.getMainLooper())

    private lateinit var root: FrameLayout
    private lateinit var title: TextView
    private lateinit var clock: TextView
    private lateinit var ampm: TextView
    private lateinit var date: TextView
    private lateinit var wifiLabel: TextView
    private lateinit var favCol: LinearLayout
    private lateinit var strip: FrameLayout
    private lateinit var hero: FrameLayout
    private lateinit var heroImg: ImageView
    private lateinit var heroShade: View
    private lateinit var heroLogo: ImageView
    // one saved list per language: the words are written in when fetched
    private val heroLang = if (Lang.ar) "-ar" else ""
    // "v2": lists saved before slides carried a trailer link must not be shown (OK would do nothing until tomorrow)
    private val heroCache by lazy { Flavor.feed?.let { HeroCache(this, it, "trending-v7-" + BuildConfig.REGION + heroLang) } }
    private val extraCaches by lazy { Flavor.extras.map { HeroCache(this, it.feed, it.name + heroLang, it.ttl) } }
    /** Which cache (picture folder) each slide comes from. */
    private val heroSrc = java.util.concurrent.ConcurrentHashMap<String, HeroCache>()
    private lateinit var heroTitle: TextView
    private lateinit var heroMeta: TextView
    private lateinit var heroHint: TextView
    private var ambient: ImageView? = null
    private var eye: View? = null

    private val cache = HashMap<String, Entry>()
    private var all: List<Entry> = emptyList()      // every app, hidden included
    private var shown: List<Entry> = emptyList()    // carousel order
    @Volatile private var dirty = true
    @Volatile private var loading = false
    private var appsLoaded = false
    private var resumed = false

    // carousel
    private val slots = arrayOfNulls<FrameLayout>(7)
    private val carS = floatArrayOf(1f, 0.8f, 0.66f, 0.55f)
    private val carA = floatArrayOf(1f, 0.85f, 0.7f, 0.55f)
    private val carD = FloatArray(4)
    private val glide = DecelerateInterpolator(1.6f)
    private val rimOn by lazy { ui.rim(true) }
    private val rimOff by lazy { ui.rim(false) }
    private var cIndex = 0
    private var okDown = false
    private var okHeld = false
    private var movePkg: String? = null

    // background tint
    private val tintLayers = arrayOfNulls<ImageView>(2)
    private val tintBmp = arrayOfNulls<Bitmap>(2)
    private var tintFront = 0
    private var tintColor = 0
    private var tintPending = 0
    private var tinted = false
    private val tintRun = Runnable { tintTo(tintPending) }

    // hero feed
    private var heroItems: List<HeroItem> = emptyList()
    private var heroPos = -1
    private var heroShown = false
    private var feedThread: Thread? = null

    private var allDlg: Dialog? = null
    private var favAnimated = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        Slides.fonts = arrayOf(ui.light, ui.med, ui.bold)
        prefs = Prefs(this)
        lite = Device.isLite(this)
        android.util.Log.i("AKHome", "${BuildConfig.REGION} ${BuildConfig.VERSION_NAME} ${Device.summary(this)} screen=${ui.w}x${ui.h}")
        build()
        setContentView(root)
        registerReceivers()
        if (!lite && SystemClock.uptimeMillis() < 300_000) showSplash()
        loadApps()
        main.postDelayed(wink, 4000)
        startFeed()
        main.postDelayed(updCheck, 90_000)      // not while the box is still starting up
    }

    // ---------------------------------------------------------------- layout

    private fun build() {
        root = FrameLayout(this).apply { layoutDirection = View.LAYOUT_DIRECTION_LTR; clipChildren = false }
        bakeBackdrop()
        setupTint()
        if (!lite) ambient = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_XY; alpha = 0f
            root.addView(this, FrameLayout.LayoutParams(-1, -1))
        }

        // header: logo + clock on the left, pills on the right
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(ooView(46f))
        val clockRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        clock = ui.text(TextView(this), ui.light, 46f, Color.WHITE)
        ampm = ui.text(TextView(this), ui.med, 13f, 0xCCFFFFFF.toInt())
        date = ui.text(TextView(this), ui.med, 15f, Ui.PINK)
        clockRow.addView(clock)
        clockRow.addView(ampm, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = ui.px(6); topMargin = ui.px(14) })
        clockRow.addView(View(this).apply { setBackgroundColor(0x59FFFFFF) },
            LinearLayout.LayoutParams(ui.px(1), ui.px(30)).apply { leftMargin = ui.px(18); rightMargin = ui.px(18) })
        clockRow.addView(date)
        left.addView(clockRow, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.px(8) })
        header.addView(left, LinearLayout.LayoutParams(0, -2, 1f))

        val pills = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false }
        pills.addView(boostPill())
        pills.addView(pill(Glyph.WIFI, getString(R.string.wifi)) { open(Intent("android.settings.WIFI_SETTINGS")) }
            .also { wifiLabel = it.getChildAt(1) as TextView })
        pills.addView(pill(Glyph.GEAR, getString(R.string.settings)) { open(Intent("android.settings.SETTINGS")) }
            .apply {
                setOnLongClickListener { cycleLite(); true }
                setOnKeyListener(okKeys({ open(Intent("android.settings.SETTINGS")) }, { cycleLite() }))
            })
        if (BuildConfig.INPUTS) pills.addView(pill(Glyph.INPUTS, getString(R.string.inputs)) { openInputs() })
        pills.addView(pill(Glyph.GRID, getString(R.string.all_apps)) { openAll() })
        for (i in 0 until pills.childCount) (pills.getChildAt(i).layoutParams as LinearLayout.LayoutParams).leftMargin = ui.px(10)
        header.addView(pills)
        root.addView(header, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply {
            leftMargin = ui.inset; rightMargin = ui.inset; topMargin = (0.07f * ui.h).toInt()
        })

        // favourites under the clock
        favCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LTR; clipChildren = false }
        root.addView(favCol, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.LEFT).apply {
            leftMargin = ui.inset; topMargin = (0.27f * ui.h).toInt()
        })

        // big name of whatever is focused
        title = ui.text(TextView(this), ui.med, 34f, Color.WHITE).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.LEFT
            textDirection = if (Build.VERSION.SDK_INT >= 23) View.TEXT_DIRECTION_FIRST_STRONG_LTR else View.TEXT_DIRECTION_FIRST_STRONG
        }
        // stops before the hero card, which now reaches down to this line
        val heroW = heroSize().first
        // fixed height: changing the text on every move then only redraws it, no layout pass of the screen
        root.addView(title, FrameLayout.LayoutParams(-1, (34f * 1.25f * 1.4f * ui.dp).toInt(), Gravity.TOP).apply {
            leftMargin = ui.inset; rightMargin = ui.inset + heroW + ui.px(24); topMargin = ui.anchorY - ui.px(58)
        })

        buildHero()
        buildCarousel()
    }

    /** Bakes the first-frame background into a 1/8 bitmap so it costs nothing to draw. */
    private fun bakeBackdrop() {
        val b = Bitmap.createBitmap(max(1, ui.w / 8), max(1, ui.h / 8), Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(b)
        c.drawColor(0xFF080106.toInt())
        GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            colors = intArrayOf(Ui.MAROON, Ui.MAROON and 0x00FFFFFF)
            setGradientCenter(0.36f, 0.38f); gradientRadius = 0.62f * b.width
            setBounds(0, 0, b.width, b.height); draw(c)
        }
        GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0x0056042C, 0x2256042C, 0xCC0C0006.toInt())).apply {
            setBounds(0, 0, b.width, b.height); draw(c)
        }
        window.setBackgroundDrawable(BitmapDrawable(resources, b).apply { isFilterBitmap = true })
    }

    private fun setupTint() {
        for (i in 0..1) {
            tintBmp[i] = Bitmap.createBitmap(max(1, ui.w / 8), max(1, ui.h / 8), Bitmap.Config.ARGB_8888)
            tintLayers[i] = ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_XY; alpha = 0f
                setImageBitmap(tintBmp[i])
                root.addView(this, FrameLayout.LayoutParams(-1, -1))
            }
            if (lite) break   // lite: one layer, swapped instantly
        }
    }

    /** The OO logo: one ring + the winking eye, from the AK Home artwork. */
    private fun ooView(size: Float): FrameLayout {
        val h = ui.px(size); val w = (h * 0.7631579f).toInt()
        return FrameLayout(this).apply {
            addView(ImageView(context).apply { setImageBitmap(asset("ak_o1.png")); scaleType = ImageView.ScaleType.FIT_XY },
                FrameLayout.LayoutParams(w, h))
            val e = ImageView(context).apply {
                setImageBitmap(asset("ak_eye.png")); scaleType = ImageView.ScaleType.FIT_XY
                pivotX = 0.5f * w; pivotY = 0.4078947f * h
            }
            addView(e, FrameLayout.LayoutParams(w, h).apply { leftMargin = w })
            tag = e
            if (eye == null) eye = e
        }
    }

    private fun asset(name: String): Bitmap? = try { assets.open(name).use { android.graphics.BitmapFactory.decodeStream(it) } } catch (_: Exception) { null }

    private fun pill(glyph: Int, label: String, action: () -> Unit): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(ui.px(16), ui.px(9), ui.px(18), ui.px(9))
        isFocusable = true; isClickable = true
        background = ui.pillBg(false)
        addView(Glyph(context, glyph), LinearLayout.LayoutParams(ui.px(16), ui.px(16)).apply { rightMargin = ui.px(8) })
        addView(ui.text(TextView(context), ui.med, 13f, Color.WHITE).apply { text = label })
        setOnClickListener { action() }
        onFocusChangeListener = View.OnFocusChangeListener { v, f ->
            v.background = ui.pillBg(f); zoom(v, if (f) 1.06f else 1f, 100)
        }
    }

    private fun boostPill(): LinearLayout = LinearLayout(this).apply {
        gravity = Gravity.CENTER
        setPadding(ui.px(12), ui.px(12), ui.px(12), ui.px(12))
        isFocusable = true; isClickable = true
        background = ui.pillBg(false)
        addView(Glyph(context, Glyph.BOLT), LinearLayout.LayoutParams(ui.px(22), ui.px(22)))
        setOnClickListener { boost() }
        onFocusChangeListener = View.OnFocusChangeListener { v, f -> v.background = ui.pillBg(f); zoom(v, if (f) 1.06f else 1f, 100) }
    }

    /** Focus zoom: a short glide everywhere (one view, cheap even on weak boxes) instead of a jump. */
    /**
     * OK / long-OK / MENU for any card or button, decided from key repeats instead of the View long-press timer:
     * cheap remotes (and `input --longpress`) send the repeat at once, so the timer never fires and the
     * release would count as a click. Holding OK opens [onLong] and the release is then ignored.
     */
    private fun okKeys(onClick: () -> Unit, onLong: () -> Unit) = View.OnKeyListener { _, code, ev ->
        val down = ev.action == KeyEvent.ACTION_DOWN
        when (code) {
            KeyEvent.KEYCODE_MENU -> { if (down && ev.repeatCount == 0) onLong(); true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                if (down) {
                    if (ev.repeatCount == 0) { okDown = true; okHeld = false }
                    else if (okDown && !okHeld) { okHeld = true; onLong() }
                } else {
                    if (okDown && !okHeld) onClick()
                    okDown = false; okHeld = false
                }
                true
            }
            else -> false
        }
    }

    private fun zoom(v: View, s: Float, ms: Long) {
        v.animate().scaleX(s).scaleY(s).setDuration(ms + 40).setInterpolator(glide).start()
    }

    // ---------------------------------------------------------------- carousel

    private fun buildCarousel() {
        for (k in 1..3) carD[k] = carD[k - 1] + (carS[k - 1] + carS[k]) * ui.cw / 2f + ui.px(14)
        val stripH = ui.ch + ui.px(40)
        strip = FrameLayout(this).apply {
            clipChildren = false; isFocusable = true; isFocusableInTouchMode = true
            setOnKeyListener { _, code, ev -> carKey(code, ev) }
            onFocusChangeListener = View.OnFocusChangeListener { _, _ -> carCenter() }
        }
        for (i in 0 until 7) {
            val s = FrameLayout(this).apply {
                foregroundGravity = Gravity.FILL
                // air-mouse / touch remotes: tap the middle card to open it, a side card to bring it to the middle
                setOnClickListener { v ->
                    val k = slots.indexOf(v) - 3
                    if (k == 0) (tag as? Entry)?.let { launch(it.app) }
                    else { strip.requestFocus(); repeat(kotlin.math.abs(k)) { carMove(if (k > 0) 1 else -1) } }
                }
            }
            s.addView(ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_XY }, FrameLayout.LayoutParams(-1, -1))
            strip.addView(s, FrameLayout.LayoutParams(ui.cw, ui.ch, Gravity.CENTER))
            slots[i] = s
        }
        root.addView(strip, FrameLayout.LayoutParams(-1, stripH, Gravity.TOP).apply {
            topMargin = ui.anchorY + (ui.h - ui.anchorY - stripH) / 2
        })
    }

    private fun carPlace(s: FrameLayout, k: Int, animate: Boolean) {
        val a = kotlin.math.abs(k)
        val x = if (k < 0) -carD[a] else carD[a]
        val img = s.getChildAt(0)
        if (animate) {
            // long enough to read as a glide, not a jump: same cost per frame, just more in-between frames;
            // shorter while the remote is pressed fast or held, so the strip keeps up with the finger
            val ms = if (fastMoves) 140L else 220L
            s.animate().translationX(x).scaleX(carS[a]).scaleY(carS[a]).setDuration(ms).setInterpolator(glide).start()
            img.animate().alpha(carA[a]).setDuration(ms).start()
        } else {
            s.animate().cancel(); img.animate().cancel()
            s.translationX = x; s.scaleX = carS[a]; s.scaleY = carS[a]; img.alpha = carA[a]
        }
    }

    private fun carBind(s: FrameLayout, idx: Int) {
        val n = shown.size
        val e = shown[((idx % n) + n) % n]
        s.tag = e
        (s.getChildAt(0) as ImageView).setImageBitmap(cardOf(e))
    }

    /**
     * With fewer than 7 apps (a TV fresh out of the box) each app may appear only once:
     * slots further than the ring allows are hidden instead of repeating the same card.
     */
    private fun carVisibility() {
        val n = shown.size
        val left = (n - 1) / 2; val right = n / 2
        for (i in 0 until 7) {
            val k = i - 3
            slots[i]!!.visibility = if (n >= 7 || (k >= -left && k <= right)) View.VISIBLE else View.INVISIBLE
        }
    }

    private fun cardOf(e: Entry): Bitmap = e.card ?: ui.card(e.icon, e.name, e.color).also { e.card = it }

    private fun renderCarousel(pkg: String?) {
        if (shown.isEmpty()) { strip.visibility = View.INVISIBLE; return }
        strip.visibility = View.VISIBLE
        val n = shown.size
        if (pkg != null) shown.indexOfFirst { it.app.pkg == pkg }.takeIf { it >= 0 }?.let { cIndex = it }
        cIndex = ((cIndex % n) + n) % n
        for (i in 0 until 7) { carBind(slots[i]!!, cIndex + i - 3); carPlace(slots[i]!!, i - 3, false) }
        carVisibility()
        carCenter()
    }

    private var lastMove = 0L
    private var fastMoves = false

    private fun carMove(dir: Int) {
        val n = shown.size
        if (n == 0) return
        val now = android.os.SystemClock.uptimeMillis()
        fastMoves = now - lastMove < 300; lastMove = now
        cIndex = ((cIndex + dir) % n + n) % n
        if (dir > 0) {
            val first = slots[0]!!
            System.arraycopy(slots, 1, slots, 0, 6); slots[6] = first
            carBind(first, cIndex + 3); carPlace(first, 3, false)
        } else {
            val last = slots[6]!!
            System.arraycopy(slots, 0, slots, 1, 6); slots[0] = last
            carBind(last, cIndex - 3); carPlace(last, -3, false)
        }
        for (i in 0 until 7) carPlace(slots[i]!!, i - 3, true)
        carVisibility()
        carCenter()
    }

    private fun carCenter() {
        val focused = strip.hasFocus()
        for (i in 0 until 7) slots[i]!!.foreground = if (i == 3 && focused) rimOn else rimOff
        val e = slots[3]!!.tag as? Entry ?: return
        strip.tag = e
        if (focused) {
            showTitle(movePkg?.let { getString(R.string.move_hint, e.name) } ?: e.name)
            setGlow(e.color)
        }
    }

    private fun carKey(code: Int, ev: KeyEvent): Boolean {
        val down = ev.action == KeyEvent.ACTION_DOWN
        when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (down) { if (movePkg != null) moveStep(if (code == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1) else carMove(if (code == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1) }
                return true
            }
            KeyEvent.KEYCODE_MENU -> { if (down && movePkg == null) (strip.tag as? Entry)?.let { showMenu(it) }; return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                if (movePkg != null) { if (down) endMove(); return true }
                if (down) {
                    if (ev.repeatCount == 0) okDown = true
                    else if (!okHeld && okDown) { okHeld = true; (strip.tag as? Entry)?.let { showMenu(it) } }
                } else {
                    if (okHeld) okHeld = false
                    else if (okDown) (strip.tag as? Entry)?.let { launch(it.app) }
                    okDown = false
                }
                return true
            }
            KeyEvent.KEYCODE_BACK -> { if (movePkg != null) { if (down) endMove(); return true } }
        }
        return false
    }

    // ---------------------------------------------------------------- move mode

    private fun startMove(e: Entry) {
        movePkg = e.app.pkg
        renderCarousel(e.app.pkg)
        strip.requestFocus()
        carCenter()
    }

    private fun moveStep(d: Int) {
        val pkg = movePkg ?: return
        val list = shown.toMutableList()
        val i = list.indexOfFirst { it.app.pkg == pkg }
        val j = i + d
        if (i < 0 || j < 0 || j >= list.size) return
        list.add(j, list.removeAt(i))
        shown = list
        prefs.order = list.map { it.app.pkg }
        renderCarousel(pkg)
    }

    private fun endMove() {
        movePkg = null
        carCenter()
        Toast.makeText(this, R.string.saved_place, Toast.LENGTH_SHORT).show()
    }

    // ---------------------------------------------------------------- background tint

    private fun setGlow(color: Int) {
        tintPending = color
        main.removeCallbacks(tintRun)
        main.postDelayed(tintRun, if (lite) 400L else 280L)
    }

    private fun tintTo(c: Int) {
        if (c == tintColor && tinted) return
        tintColor = c
        if (lite) {
            ui.tint(tintBmp[0]!!, c)
            tintLayers[0]!!.apply { alpha = 1f; invalidate() }
        } else {
            val back = 1 - tintFront
            ui.tint(tintBmp[back]!!, c)
            tintLayers[back]!!.invalidate()
            if (!tinted) {
                tintLayers[back]!!.alpha = 1f; tintLayers[tintFront]!!.alpha = 0f
            } else {
                tintLayers[back]!!.animate().alpha(1f).setDuration(300).start()
                tintLayers[tintFront]!!.animate().alpha(0f).setDuration(300).start()
            }
            tintFront = back
        }
        if (!tinted) { tinted = true; window.setBackgroundDrawable(ColorDrawable(Ui.BASE)) }
    }

    // ---------------------------------------------------------------- favourites

    private fun updateFavs() {
        favCol.removeAllViews()
        val byPkg = shown.associateBy { it.app.pkg }
        val used = LinkedHashSet<String>()
        val picks = arrayOfNulls<Entry>(3)
        for (i in 0..2) prefs.fav(i)?.let { p -> byPkg[p]?.takeIf { used.add(p) }?.let { picks[i] = it } }
        val fill = (Flavor.favourites + shown.map { it.app.pkg }).iterator()
        for (i in 0..2) {
            while (picks[i] == null && fill.hasNext()) {
                val p = fill.next(); byPkg[p]?.takeIf { used.add(p) }?.let { picks[i] = it }
            }
        }
        favPicks = picks.map { it?.app?.pkg }
        val bw = ui.px(195); val bh = ui.px(42)
        var lastId = View.NO_ID
        picks.forEachIndexed { i, e ->
            e ?: return@forEachIndexed
            val b = favButton(i, e, bw, bh)
            b.id = View.generateViewId(); lastId = b.id
            b.nextFocusRightId = hero.id
            favCol.addView(b, LinearLayout.LayoutParams(bw, bh).apply { if (i > 0) topMargin = ui.px(10) })
            if (!favAnimated && !lite) {
                b.alpha = 0f; b.translationY = 20f
                b.animate().alpha(1f).translationY(0f).setDuration(450).setStartDelay(300L + 90 * i)
                    .setInterpolator(OvershootInterpolator(1.5f)).start()
            }
        }
        favAnimated = true
        // UP from the app strip lands on the favourites (the card is one step RIGHT from there)
        strip.nextFocusUpId = lastId
    }

    private fun favButton(slot: Int, e: Entry, bw: Int, bh: Int): FrameLayout = FrameLayout(this).apply {
        isFocusable = true; isClickable = true; isLongClickable = true
        background = BitmapDrawable(resources, ui.favBg(e.color, bw, bh))
        foreground = ui.rim(false, 100)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LTR
            setPadding(ui.px(14), ui.px(9), ui.px(18), ui.px(9))
        }
        row.addView(ImageView(context).apply {
            setImageDrawable(e.icon.constantState?.newDrawable()?.mutate() ?: e.icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = GradientDrawable().apply { setColor(0x01000000); cornerRadius = ui.px(6).toFloat() }
            clipToOutline = true
        }, LinearLayout.LayoutParams(ui.px(22), ui.px(22)).apply { rightMargin = ui.px(10) })
        row.addView(ui.text(TextView(context), ui.bold, 14f, Color.WHITE).apply {
            text = e.name; maxLines = 1; ellipsize = TextUtils.TruncateAt.END; maxWidth = ui.px(160)
        })
        addView(row, FrameLayout.LayoutParams(-1, -1))
        setOnClickListener { launch(e.app) }                 // touch / mouse
        setOnLongClickListener { pickFav(slot); true }
        setOnKeyListener(okKeys({ launch(e.app) }, { pickFav(slot) }))
        onFocusChangeListener = View.OnFocusChangeListener { v, f ->
            (v as FrameLayout).foreground = ui.rim(f, 100)
            zoom(v, if (f) 1.06f else 1f, 150)
            if (f) { showTitle(e.name); setGlow(e.color) }
        }
    }

    private fun pickFav(slot: Int) {
        val list = shown
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(R.string.pick_fav)
            .setItems(list.map { it.name }.toTypedArray()) { _, which ->
                // the other buttons keep their apps; if the chosen app sits in another button, the two swap
                val now = favPicks.toMutableList()
                val pkg = list[which].app.pkg
                val other = now.indexOf(pkg)
                if (other >= 0 && other != slot) now[other] = now[slot]
                now[slot] = pkg
                now.forEachIndexed { i, p -> if (p != null) prefs.setFav(i, p) }
                updateFavs()
                favCol.getChildAt(slot)?.requestFocus()
            }.show()
    }

    private var favPicks: List<String?> = emptyList()

    // ---------------------------------------------------------------- hero card

    /**
     * 2.2:1, half-way between the old wide 2.7:1 card (cut the top and bottom off every picture) and a tall 16:9
     * one (user, 2026-10-31: "in between"). The artwork loses only a little at the top and bottom.
     */
    private fun heroSize(): Pair<Int, Int> { val h = (0.355f * ui.h).toInt(); return (h * 2.2f).toInt() to h }

    private fun buildHero() {
        val (w, h) = heroSize()
        hero = FrameLayout(this).apply {
            background = GradientDrawable().apply { setColor(0xFF1A0610.toInt()); cornerRadius = ui.px(16).toFloat() }
            clipToOutline = true; alpha = 0f; isFocusable = false
            if (!lite) setLayerType(View.LAYER_TYPE_HARDWARE, null)
        }
        heroImg = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        hero.addView(heroImg, FrameLayout.LayoutParams(-1, -1))
        heroShade = View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.RIGHT_LEFT, intArrayOf(0xF0100308.toInt(), 0x90100308.toInt(), 0))
        }
        hero.addView(heroShade, FrameLayout.LayoutParams(-1, -1))
        hero.addView(View(this).apply { background = ui.rim(false) }, FrameLayout.LayoutParams(-1, -1))
        // logo + text on the left, over the title's own colour (the artwork fills the right side)
        val text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.LEFT }
        heroLogo = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_START; adjustViewBounds = true
            maxWidth = (0.42f * w).toInt(); maxHeight = (0.34f * h).toInt(); visibility = View.GONE
        }
        text.addView(heroLogo, LinearLayout.LayoutParams(-2, -2))
        heroTitle = ui.text(TextView(this), ui.med, 24f, Color.WHITE).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; maxWidth = (0.62f * w).toInt()
        }
        heroMeta = ui.text(TextView(this), ui.light, 12f, 0xCCFFFFFF.toInt())
        text.addView(heroTitle, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.px(10) })
        text.addView(heroMeta, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.px(6) })
        hero.addView(text, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.LEFT).apply {
            leftMargin = ui.px(26); bottomMargin = ui.px(24)
        })
        // "▶ Trailer" in the top corner while the card has focus, so people know OK plays it (user)
        heroHint = ui.text(TextView(this), ui.med, 11f, Color.WHITE).apply {
            setPadding(ui.px(12), ui.px(5), ui.px(14), ui.px(5)); visibility = View.GONE
            background = GradientDrawable().apply { setColor(0x8C000000.toInt()); cornerRadius = ui.px(100).toFloat() }
        }
        hero.addView(heroHint, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.RIGHT).apply {
            topMargin = ui.px(14); rightMargin = ui.px(14)
        })
        // LEFT / RIGHT browse, UP / DOWN leave it. OK does what the slide says (trailer, highlights) or nothing.
        hero.id = View.generateViewId()
        hero.setOnKeyListener { _, code, ev ->
            when (code) {
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (ev.action == KeyEvent.ACTION_DOWN) heroStep(if (code == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1)
                    true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                    if (ev.action == KeyEvent.ACTION_UP) heroItems.getOrNull(heroPos)?.let { heroOpen(it) }
                    true
                }
                else -> false
            }
        }
        // the card clips to its rounded outline, so its focus edge (drawn just outside it) lives on a twin view above it
        val ring = View(this).apply { isFocusable = false }
        hero.onFocusChangeListener = View.OnFocusChangeListener { v, f ->
            if (Ui.BORDERLESS) ring.background = if (f) ui.rim(true) else null   // background: View.foreground is API 23+
            else (v as FrameLayout).foreground = if (f) ui.rim(true) else null
            zoom(v, if (f) 1.04f else 1f, 150); zoom(ring, if (f) 1.04f else 1f, 150)
            heroHintFor(heroItems.getOrNull(heroPos))
            if (f) {
                val item = heroItems.getOrNull(heroPos)
                showTitle(item?.title ?: getString(R.string.hero_title), film = true)
                setGlow(item?.color?.takeIf { it != 0 } ?: Ui.DEFAULT_COLOR)
            }
        }
        // just under the top pills, ending above the app strip (room for the 4 % focus zoom on both sides)
        root.addView(hero, FrameLayout.LayoutParams(w, h, Gravity.TOP or Gravity.RIGHT).apply {
            rightMargin = ui.inset; topMargin = (0.255f * ui.h).toInt()
        })
        root.addView(ring, FrameLayout.LayoutParams(hero.layoutParams as FrameLayout.LayoutParams))
    }

    /** Before the first titles arrive (first boot offline): an AK Home glass card instead of an empty hole. */
    private fun heroFallback() {
        if (heroItems.isNotEmpty()) return
        if (Flavor.feed == null) { hero.visibility = View.GONE; return }
        hero.visibility = View.VISIBLE
        val lp = hero.layoutParams
        val logo = asset("ak_o1.png")?.let { BitmapDrawable(resources, it) } ?: ColorDrawable(Ui.MAROON)
        heroImg.scaleType = ImageView.ScaleType.FIT_XY
        heroImg.setImageBitmap(ui.card(logo, getString(R.string.hero_title), Ui.DEFAULT_COLOR, lp.width, lp.height))
        heroShade.visibility = View.GONE
        heroLogo.visibility = View.GONE
        heroTitle.text = ""
        heroMeta.text = getString(R.string.hero_offline)
        showHero()
    }

    private fun showHero() {
        if (heroShown) return
        heroShown = true; hero.isFocusable = true
        // the layer only serves the fade-in; kept, it would be repainted on every frame of the slow drift
        if (lite) hero.alpha = 1f
        else hero.animate().alpha(1f).setDuration(800).withEndAction { hero.setLayerType(View.LAYER_TYPE_NONE, null) }.start()
    }

    private fun showHeroItem(item: HeroItem) {
        val bmp = item.image ?: return
        hero.visibility = View.VISIBLE
        heroImg.scaleType = ImageView.ScaleType.FIT_XY   // already composed at the card size
        heroShade.visibility = View.GONE
        val old = heroImg.drawable
        val next = BitmapDrawable(resources, bmp)
        // weak boxes swap at once, exactly as in 1.0: on the 1 GB Android 7 emulator each fade frame cost ~70 ms
        if (lite || old == null || !heroShown) heroImg.setImageDrawable(next)
        else {
            // a soft, still cross-fade (user, 2026-10-31). The old "settle-in zoom" scaled the outgoing picture up to
            // 107 % in one frame before fading — that jump is what looked rough, so it is gone. One TransitionDrawable
            // in one view (A95X: 0 % janky frames; two views with an alpha animation measured 6 %), built from the
            // last bitmap only, so no chain of old slides is kept alive.
            val prev = (old as? TransitionDrawable)?.getDrawable(1) ?: old
            TransitionDrawable(arrayOf(prev, next)).apply {
                isCrossFadeEnabled = true; heroImg.setImageDrawable(this); startTransition(1_000)
            }
        }
        val logo = item.logo
        heroLogo.setImageBitmap(logo)
        heroLogo.visibility = if (logo != null) View.VISIBLE else View.GONE
        heroTitle.visibility = if (logo != null) View.GONE else View.VISIBLE
        heroTitle.text = item.title; heroMeta.text = item.meta
        // matches and weather carry all their text inside the drawn slide
        val drawn = item.kind.isNotEmpty()
        if (drawn) { heroLogo.visibility = View.GONE; heroTitle.visibility = View.GONE }
        heroMeta.visibility = if (drawn) View.GONE else View.VISIBLE
        if (!lite) {
            for (t in arrayOf(heroLogo, heroTitle, heroMeta)) { t.alpha = 0f; t.translationX = 20f; t.animate().alpha(1f).translationX(0f).setDuration(700).start() }
            ambient?.let { a ->
                a.setImageBitmap(softBlur(bmp))
                a.animate().alpha(0.32f).setDuration(1200).start()
            }
        }
        heroHintFor(item)
        if (hero.hasFocus()) { showTitle(item.title, film = true); if (item.color != 0) setGlow(item.color) }
        showHero()
    }

    /** Cheap blur for the ambient glass: shrink hard, let bilinear filtering spread it back out. */
    private fun softBlur(b: Bitmap): Bitmap {
        val small = Bitmap.createScaledBitmap(b, 24, max(1, 24 * b.height / b.width), true)
        return Bitmap.createScaledBitmap(small, 96, max(1, 96 * b.height / b.width), true)
    }

    private fun heroStep(d: Int) {
        if (heroItems.isEmpty()) return
        heroPos = ((heroPos + d) % heroItems.size + heroItems.size) % heroItems.size
        val item = heroItems[heroPos]
        // only the shown slide and its neighbours stay in memory (~1.2 MB each); the rest come back from disk
        val n = heroItems.size
        heroItems.forEachIndexed { i, it ->
            val dist = minOf((i - heroPos + n) % n, (heroPos - i + n) % n)
            if (dist > 1) { it.image = null; it.logo = null }
        }
        if (item.image != null) showHeroItem(item)
        else Thread { loadHero(item); main.post { if (heroItems.getOrNull(heroPos) === item) showHeroItem(item) } }.start()
        // keep the next picture ready so the switch never waits on the network
        heroItems.getOrNull((heroPos + d + heroItems.size) % heroItems.size)?.takeIf { it.image == null }?.let { n ->
            Thread { loadHero(n) }.start()
        }
    }

    /** Slides are composed at exactly the card size, so nothing is scaled when drawn. */
    private fun loadHero(item: HeroItem) {
        val lp = hero.layoutParams
        val s = ui.paintScale   // ≤ 1080p detail, the ImageView scales it to the card
        (heroSrc[item.id] ?: heroCache)?.load(item, (lp.width * s).toInt(), (lp.height * s).toInt(), ui.px(16) * s)
    }

    /** The corner hint: shown only while the card has focus and the slide has something to play. */
    private fun heroHintFor(item: HeroItem?) {
        val link = item?.link.orEmpty()
        if (canOpen.size > 300) canOpen.clear()   // new matches every day: never let it grow for months
        val can = link.isNotEmpty() && canOpen.getOrPut(link) { heroIntent(item!!) != null }
        heroHint.visibility = if (hero.hasFocus() && can) View.VISIBLE else View.GONE
        heroHint.text = if (item?.kind == "match") "↗  " + Lang.t("Match page", "صفحة المباراة") else "▶  " + Lang.t("Trailer", "الإعلان")
    }

    /** OK on the card: the first thing in the slide's link that this box can do. */
    private fun heroOpen(item: HeroItem) { heroIntent(item)?.let { open(it) } }

    /** Asked once per link: a box without a browser shows no hint for a match it cannot open. */
    private val canOpen = HashMap<String, Boolean>()

    private fun heroIntent(item: HeroItem): Intent? {
        for (a in item.link.split('|')) {
            val arg = a.substringAfter(':')
            val i = when (a.substringBefore(':')) {
                "app" -> packageManager.getLeanbackLaunchIntentForPackage(arg) ?: packageManager.getLaunchIntentForPackage(arg)
                "yt" -> Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$arg"))
                "web" -> Intent(Intent.ACTION_VIEW, Uri.parse(arg))
                "ys" -> Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(arg)))
                    .setPackage("com.google.android.youtube.tv")
                else -> null
            } ?: continue
            if (i.resolveActivity(packageManager) == null) continue
            return i
        }
        return null
    }

    /**
     * Every source's list, mixed: titles one from each source in turn, so no kind comes twice in a row;
     * the short "often" ones (matches, weather) come back every fifth slide instead of once a round.
     */
    private fun heroMix(lists: List<List<HeroItem>>): List<HeroItem> {
        val often = HashSet<Int>().apply { Flavor.extras.forEachIndexed { i, e -> if (e.often) add(i + 1) } }
        val seen = HashSet<String>(); val titles = ArrayList<HeroItem>()
        val rest = lists.filterIndexed { i, _ -> i !in often }
        for (i in 0 until (rest.maxOfOrNull { it.size } ?: 0))
            for (l in rest) l.getOrNull(i)?.takeIf { seen.add(it.id) }?.let { titles += it }
        val short = lists.filterIndexed { i, _ -> i in often }.flatten()
        if (short.isEmpty()) return titles
        if (titles.isEmpty()) return short
        val out = ArrayList<HeroItem>(); var k = 0
        // four titles between two of them (user, 2026-10-31: films and series came round too rarely)
        titles.forEachIndexed { i, t -> if (i % 4 == 0) out += short[k++ % short.size]; out += t }
        return out
    }

    private fun startFeed() {
        val cache = heroCache ?: return
        // the world's Top 10 first in each round, then the extras (matches, weather…)
        val sources = listOf(cache) + extraCaches
        feedThread = Thread {
            val lists = sources.map { c -> c.cached().also { l -> l.forEach { heroSrc[it.id] = c } } }.toMutableList()
            var wait = 0
            // "never checked": must not depend on uptime (a box rebooted < 1 h ago would wait an hour)
            var checked = SystemClock.elapsedRealtime() - 10_000_000L
            try {
                // 1) what we saved last time: instant, works offline
                val saved = heroMix(lists)
                if (saved.isNotEmpty()) { loadHero(saved.first()); main.post { setHeroItems(saved) } }
                while (!isDestroyed) {
                    if (!resumed) { Thread.sleep(1000); continue }
                    // 2) each source on its own clock (Top 10 daily, matches every 30 min); looked at every 5 min
                    if (SystemClock.elapsedRealtime() - checked > 300_000) {
                        checked = SystemClock.elapsedRealtime()
                        sources.forEachIndexed { n, c ->
                            if (c.stale() || lists[n].isEmpty()) try {
                                val fresh = c.refresh()
                                if (fresh.isNotEmpty()) {
                                    lists[n].forEach { heroSrc.remove(it.id) }   // yesterday's matches must not pile up
                                    fresh.forEach { heroSrc[it.id] = c }; lists[n] = fresh
                                    // show each source as soon as it arrives: a first boot never waits for all of them
                                    val mixed = heroMix(lists)
                                    if (heroItems.isEmpty()) loadHero(mixed.first())
                                    main.post { setHeroItems(mixed) }; wait = 0
                                }
                            } catch (_: Exception) { }   // next look in 5 min
                        }
                    }
                    Thread.sleep(500)   // 2 wake-ups a second is plenty for an 8–12 s slide timer
                    if (!hero.hasFocus()) wait++
                    if (wait >= (if (lite) 24 else 16)) { wait = 0; main.post { heroStep(1) } }
                }
            } catch (_: InterruptedException) { }
        }.apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }
    }

    private fun setHeroItems(items: List<HeroItem>) {
        val keep = heroItems.getOrNull(heroPos)?.id
        heroItems = items
        heroPos = (items.indexOfFirst { it.id == keep }.takeIf { it >= 0 } ?: 0) - 1
        heroStep(1)
    }

    // ---------------------------------------------------------------- apps

    private fun loadApps() {
        dirty = true
        if (loading) return
        loading = true
        Thread {
            while (dirty) {
                dirty = false
                val list = query()
                main.post { render(list) }
            }
            loading = false
        }.start()
    }

    private fun query(): List<Entry> {
        val pm = packageManager
        return Apps.load(this).map { app ->
            val stamp = try { pm.getPackageInfo(app.pkg, 0).lastUpdateTime } catch (_: Exception) { 0L }
            val e = cache[app.pkg]?.takeIf { it.stamp == stamp && it.app == app } ?: run {
                val icon = hiIcon(app)
                val name = app.label.replace(" Leanback", "") + if (app.pkg == "com.shabakaty.cbox.cinemanb") " 4K" else ""
                Entry(app, icon, Ui.tone(icon), name, stamp).also { cache[app.pkg] = it }
            }
            if (e.card == null) e.card = ui.card(e.icon, e.name, e.color).also { it.prepareToDraw() }   // paint + upload off the UI thread
            e
        }
    }

    private fun hiIcon(app: App): Drawable = try {
        val ai = packageManager.getActivityInfo(app.component, 0)
        val res = packageManager.getResourcesForApplication(ai.applicationInfo)
        val id = if (ai.icon != 0) ai.icon else ai.applicationInfo.icon
        res.getDrawableForDensity(id, 640, null) ?: Apps.icon(this, app)
    } catch (_: Exception) { Apps.icon(this, app) }

    private fun render(list: List<Entry>) {
        val focusedPkg = (strip.tag as? Entry)?.app?.pkg
        val hidden = prefs.hidden
        val byPkg = list.associateBy { it.app.pkg }
        val ordered = Apps.ordered(list.map { it.app }, prefs, Flavor.order).mapNotNull { byPkg[it.pkg] }
        all = ordered
        shown = ordered.filter { it.app.pkg !in hidden }
        renderCarousel(focusedPkg)
        prewarm(shown)
        val favFocused = favCol.findFocus() != null
        updateFavs()
        // keep the user where they are (a pill, the card…) when the list refreshes in the background
        if (!appsLoaded || currentFocus == null || favFocused) strip.requestFocus()
        appsLoaded = true
        heroFallback()
    }

    /**
     * Draws every card once, 1 px big, under the opaque background so the GPU gets all textures up front.
     * Otherwise each card is uploaded the first time it slides in, which is what makes the first pass stutter.
     */
    private fun prewarm(list: List<Entry>) {
        val warm = FrameLayout(this)
        // the first ~40 cover every strip position users reach quickly; a box with 200 apps must not upload them all
        for (e in list.take(20) + list.takeLast(20).filter { it !in list.take(20) })
            warm.addView(ImageView(this).apply { setImageBitmap(cardOf(e)); scaleType = ImageView.ScaleType.FIT_XY },
            FrameLayout.LayoutParams(1, 1))
        root.addView(warm, 0, FrameLayout.LayoutParams(1, 1))
        main.postDelayed({ root.removeView(warm) }, 1500)
    }

    private fun launch(app: App) {
        if (Apps.isDrawer(app.pkg)) { openAll(); return }     // the box's App List → our All apps
        lastLaunched = app.pkg
        try { startActivity(app.launchIntent()) } catch (_: Exception) {
            packageManager.getLaunchIntentForPackage(app.pkg)?.let { open(it) }
        }
    }

    private fun open(i: Intent) {
        try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
    }

    // ---------------------------------------------------------------- menus

    private fun showMenu(e: Entry) {
        val pkg = e.app.pkg
        val pinned = prefs.order.firstOrNull() == pkg
        val hidden = pkg in prefs.hidden
        val items = arrayOf(
            getString(R.string.move_app),
            getString(if (pinned) R.string.unpin_first else R.string.pin_first),
            getString(if (hidden) R.string.show else R.string.hide),
            getString(R.string.app_info),
            getString(R.string.uninstall),
        )
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(e.app.label)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> { allDlg?.dismiss(); if (!hidden) startMove(e) }
                    1 -> {
                        val order = shown.map { it.app.pkg }.toMutableList()
                        order.remove(pkg)
                        if (!pinned) order.add(0, pkg)
                        else {
                            // back to where the default order would put it among the current apps
                            val def = Apps.ordered(all.map { it.app }, prefs, Flavor.order, ignoreSaved = true).map { it.pkg }
                            val rank = def.indexOf(pkg)
                            val at = order.indexOfFirst { def.indexOf(it) > rank }.takeIf { it >= 0 } ?: order.size
                            order.add(at, pkg)
                        }
                        prefs.order = order; loadApps()
                    }
                    2 -> { prefs.hidden = if (hidden) prefs.hidden - pkg else prefs.hidden + pkg; loadApps(); if (allDlg != null) { allDlg?.dismiss(); main.postDelayed({ openAll() }, 300) } }
                    3 -> open(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
                    4 -> open(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")))
                }
            }.show()
    }

    private fun openAll() {
        val d = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LTR
            setBackgroundColor(Ui.BASE); setPadding(ui.px(56), ui.px(40), ui.px(56), 0); clipToPadding = false
        }
        col.addView(ui.text(TextView(this), ui.med, 32f, Color.WHITE).apply { text = getString(R.string.all_apps) })
        // the list spans the full screen width and keeps the side space inside itself, so a focused card in the
        // first or last column grows into that space instead of being cut at the edge
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; clipChildren = false }
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; clipChildren = false
            setPadding(0, ui.px(4), 0, ui.px(33))     // the rows' own padding grew by 7 dp: same first/last card position
        }
        val cw = (ui.w - 2 * ui.px(56) - 4 * ui.px(18)) / 5; val chh = cw * 9 / 16
        val hidden = prefs.hidden
        var row: LinearLayout? = null
        all.filterNot { Apps.isDrawer(it.app.pkg) }.forEachIndexed { i, e ->     // no App List inside All apps
            if (i % 5 == 0) row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; clipChildren = false
                // room for the zoom AND the glass edge just outside the focused card (16 dp), inside the row: a row clips
                // what its cards draw beyond its own bounds (Android 10 ignored clipChildren=false here)
                setPadding(ui.px(56), ui.px(16), ui.px(56), ui.px(16)); clipToPadding = false
            }
                // the rows overlap by 14 dp so the space between cards stays 18 dp, exactly as before
                .also { grid.addView(it, LinearLayout.LayoutParams(-1, -2).apply { if (i > 0) topMargin = -ui.px(14) }) }
            val card = FrameLayout(this).apply {
                background = GradientDrawable().apply { setColor(Ui.BASE); cornerRadius = ui.px(16).toFloat() }
                // the card bitmap has its corners baked in; clipping to the outline would also cut away the glass edge
                // that is drawn just outside the focused card (BORDERLESS)
                clipToOutline = !Ui.BORDERLESS; isFocusable = true; isClickable = true; isLongClickable = true
                foreground = ui.rim(false)
                addView(ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_XY; setImageBitmap(cardOf(e)) }, FrameLayout.LayoutParams(-1, -1))
                if (e.app.pkg in hidden) alpha = 0.45f
                setOnClickListener { d.dismiss(); launch(e.app) }
                setOnLongClickListener { showMenu(e); true }
                setOnKeyListener(okKeys({ d.dismiss(); launch(e.app) }, { showMenu(e) }))
                onFocusChangeListener = View.OnFocusChangeListener { v, f -> (v as FrameLayout).foreground = ui.rim(f); zoom(v, if (f) 1.08f else 1f, 150) }
            }
            row!!.addView(card, LinearLayout.LayoutParams(cw, chh).apply { if (i % 5 != 0) leftMargin = ui.px(18) })
        }
        scroll.addView(grid)
        col.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { leftMargin = -ui.px(56); rightMargin = -ui.px(56) })
        d.setContentView(col)
        d.setOnDismissListener { if (allDlg === d) allDlg = null }
        allDlg = d
        d.show()
        (grid.getChildAt(0) as? LinearLayout)?.getChildAt(0)?.apply { isFocusableInTouchMode = true; requestFocus() }
    }

    // ---------------------------------------------------------------- inputs (Screens edition)

    /**
     * Our own glass panel with the TV's inputs (HDMI 1, HDMI 2, AV …), in the AK Home look — not the system's.
     * Only when the TV lists no inputs to apps do we fall back to the system panel (if it has one).
     */
    private fun openInputs() {
        val tim = getSystemService(Context.TV_INPUT_SERVICE) as? android.media.tv.TvInputManager
        val inputs = try { tim?.tvInputList.orEmpty().filter { Build.VERSION.SDK_INT < 24 || !it.isHidden(this) } }
            catch (_: Exception) { emptyList() }
        fun label(info: android.media.tv.TvInputInfo): String {
            // custom labels ("PlayStation", "Receiver" …) exist from Android 7; older TVs get the input's own name
            val custom = if (Build.VERSION.SDK_INT >= 24) info.loadCustomLabel(this)?.toString() else null
            return custom?.takeIf { it.isNotBlank() } ?: info.loadLabel(this).toString()
        }
        // HDMI first, then AV / component / others, the TV tuner last; by name inside each group
        val list = inputs.map { it to label(it) }.sortedWith(compareBy({ (i, _) ->
            when (i.type) { android.media.tv.TvInputInfo.TYPE_HDMI -> 0; android.media.tv.TvInputInfo.TYPE_TUNER -> 2; else -> 1 }
        }, { it.second }))
        if (list.isEmpty()) {
            val panel = Intent("com.android.tv.action.VIEW_INPUTS")
            if (packageManager.resolveActivity(panel, 0) != null) open(panel)
            else Toast.makeText(this, R.string.no_inputs, Toast.LENGTH_SHORT).show()
            return
        }
        // see-through: the home screen stays visible, dimmed, behind the glass buttons
        val d = Dialog(this, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; layoutDirection = View.LAYOUT_DIRECTION_LTR
            setBackgroundColor(0xD90B0A0D.toInt()); clipChildren = false
        }
        col.addView(ui.text(TextView(this), ui.med, 32f, Color.WHITE).apply { text = getString(R.string.inputs) },
            LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = ui.px(36) })
        // something plugged in and on (user: "show where something is connected"): the TV's own input state.
        // Only real sockets (HDMI / AV); a tuner always reports "connected".
        fun live(info: android.media.tv.TvInputInfo) = info.isPassthroughInput &&
            (try { tim?.getInputState(info.id) } catch (_: Exception) { null }) == android.media.tv.TvInputManager.INPUT_STATE_CONNECTED
        var first: View? = null
        // up to 4 per row; wraps on TVs with many inputs
        var row: LinearLayout? = null
        list.forEachIndexed { i, (info, name) ->
            if (i % 4 == 0) row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; clipChildren = false
                setPadding(ui.px(24), ui.px(10), ui.px(24), ui.px(10)); clipToPadding = false
            }.also { col.addView(it, LinearLayout.LayoutParams(-1, -2)) }
            val b = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
                setPadding(ui.px(28), ui.px(18), ui.px(32), ui.px(18)); minimumWidth = ui.px(220)
                isFocusable = true; isClickable = true; background = ui.pillBg(false)
                addView(Glyph(context, Glyph.INPUTS), LinearLayout.LayoutParams(ui.px(24), ui.px(24)).apply { rightMargin = ui.px(12) })
                addView(ui.text(TextView(context), ui.med, 20f, Color.WHITE).apply { text = name; maxLines = 1 })
                if (live(info)) {
                    // green dot + "Connected" after the name
                    addView(View(context).apply {
                        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF2BD96B.toInt()) }
                    }, LinearLayout.LayoutParams(ui.px(10), ui.px(10)).apply { leftMargin = ui.px(14); rightMargin = ui.px(6) })
                    addView(ui.text(TextView(context), ui.light, 13f, 0xFF7CF0A6.toInt()).apply {
                        text = getString(R.string.input_connected); maxLines = 1
                    })
                    if (first == null) first = this
                }
                setOnClickListener { d.dismiss(); showInput(info) }
                onFocusChangeListener = View.OnFocusChangeListener { v, f -> v.background = ui.pillBg(f); zoom(v, if (f) 1.08f else 1f, 120) }
            }
            row!!.addView(b, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = ui.px(12); rightMargin = ui.px(12) })
        }
        d.setContentView(col)
        d.show()
        // the panel opens on the first input that has something connected (else the first one)
        (first ?: (col.getChildAt(1) as? LinearLayout)?.getChildAt(0))?.apply { isFocusableInTouchMode = true; requestFocus() }
    }

    private fun showInput(info: android.media.tv.TvInputInfo) {
        val uri = if (info.isPassthroughInput) android.media.tv.TvContract.buildChannelUriForPassthroughInput(info.id)
            else android.media.tv.TvContract.buildChannelsUriForInput(info.id)
        try { startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: Exception) { Toast.makeText(this, R.string.no_inputs, Toast.LENGTH_SHORT).show() }
    }

    // ---------------------------------------------------------------- boost, lite, net, clock

    private fun boost() {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        fun avail() = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.availMem / 1048576
        val before = avail()
        Toast.makeText(this, R.string.boost_start, Toast.LENGTH_SHORT).show()
        Thread {
            // installed apps only: the TV's own parts (remote, inputs, picture — Xiaomi keeps them in background apps)
            // are never touched
            val sys = android.content.pm.ApplicationInfo.FLAG_SYSTEM or android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
            for (ai in packageManager.getInstalledApplications(0))
                if (ai.packageName != packageName && ai.flags and sys == 0) am.killBackgroundProcesses(ai.packageName)
            System.gc()
            main.postDelayed({
                val after = avail()
                Toast.makeText(this, getString(R.string.boost_done, max(0L, after - before).toInt(), after.toInt()), Toast.LENGTH_LONG).show()
            }, 1500)
        }.start()
    }

    /** Long-press Settings: automatic → forced on → forced off → automatic. */
    private fun cycleLite() {
        val next = when (prefs.liteOverride) { null -> true; true -> false; false -> null }
        prefs.liteOverride = next
        Device.reset()
        Toast.makeText(this, when (next) { null -> R.string.lite_auto; true -> R.string.lite_on; false -> R.string.lite_off }, Toast.LENGTH_SHORT).show()
        recreate()
    }

    @Suppress("DEPRECATION")
    private fun updateNet() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val n = cm.activeNetworkInfo
        wifiLabel.text = getString(when {
            n == null || !n.isConnected -> R.string.offline
            n.type == ConnectivityManager.TYPE_ETHERNET -> R.string.ethernet
            else -> R.string.wifi
        })
    }

    private fun updateClock() {
        val c = Calendar.getInstance()
        val hr = c.get(Calendar.HOUR_OF_DAY); val mi = c.get(Calendar.MINUTE)
        if (DateFormat.is24HourFormat(this)) {
            clock.text = String.format(Locale.US, "%02d:%02d", hr, mi); ampm.visibility = View.GONE
        } else {
            clock.text = String.format(Locale.US, "%d:%02d", if (hr % 12 == 0) 12 else hr % 12, mi)
            ampm.visibility = View.VISIBLE; ampm.text = getString(if (hr < 12) R.string.am else R.string.pm)
        }
        val days = resources.getStringArray(R.array.days); val months = resources.getStringArray(R.array.months)
        date.text = "${days[c.get(Calendar.DAY_OF_WEEK) - 1]}  ${c.get(Calendar.DAY_OF_MONTH)} ${months[c.get(Calendar.MONTH)]}"
    }

    private val wink = object : Runnable {
        override fun run() {
            val e = eye ?: return
            if (!e.isAttachedToWindow) return
            if (resumed) ObjectAnimator.ofFloat(e, View.SCALE_Y, 1f, 0.08f, 1f).setDuration(280).start()
            main.postDelayed(this, 9000)
        }
    }

    private fun showSplash() {
        val decor = window.decorView as ViewGroup
        val s = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                gradientType = GradientDrawable.RADIAL_GRADIENT
                colors = intArrayOf(0xFF5A0F2E.toInt(), 0xFF070206.toInt()); gradientRadius = 0.55f * ui.w
            }
            isClickable = true
        }
        val logo = ooView(120f).apply { alpha = 0f; scaleX = 0.85f; scaleY = 0.85f }
        s.addView(logo, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        decor.addView(s, FrameLayout.LayoutParams(-1, -1))
        logo.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(200).setDuration(800).start()
        (logo.tag as? View)?.let { ObjectAnimator.ofFloat(it, View.SCALE_Y, 1f, 0.08f, 1f).apply { duration = 280; startDelay = 1700 }.start() }
        s.animate().alpha(0f).setStartDelay(3000).setDuration(600).withEndAction { decor.removeView(s) }.start()
    }

    // ---------------------------------------------------------------- self-update (see Updater)

    private var updDlg: Dialog? = null

    /** Once a day (Updater keeps the clock); looks again every 6 h for boxes that never sleep. */
    private val updCheck = object : Runnable {
        override fun run() {
            Thread { if (Updater.check(this@MainActivity)) main.post { offerUpdate() } }
                .apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
            main.postDelayed(this, 6 * 3_600_000L)
        }
    }

    /** Only on the home screen, never over an app, a menu or while an app is being moved. */
    private fun offerUpdate() {
        if (!resumed || updDlg != null || allDlg != null || movePkg != null || Updater.snoozed(this)) return
        val r = Updater.ready(this) ?: return
        val d = Dialog(this, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; clipChildren = false
            setBackgroundColor(0xD90B0A0D.toInt())
        }
        col.addView(ooView(64f), LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = ui.px(22) })
        col.addView(ui.text(TextView(this), ui.med, 30f, Color.WHITE).apply { text = getString(R.string.update_title); gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = ui.px(10) })
        val sub = ui.text(TextView(this), ui.light, 17f, 0xBDFFFFFF.toInt()).apply {
            text = getString(R.string.update_body, r.name); gravity = Gravity.CENTER
        }
        col.addView(sub, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = ui.px(34) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; clipChildren = false }
        fun button(label: Int, act: () -> Unit) = LinearLayout(this).apply {
            gravity = Gravity.CENTER; minimumWidth = ui.px(200)
            setPadding(ui.px(30), ui.px(16), ui.px(30), ui.px(16))
            isFocusable = true; isClickable = true; background = ui.pillBg(false)
            addView(ui.text(TextView(context), ui.med, 20f, Color.WHITE).apply { text = getString(label) })
            setOnClickListener { act() }
            onFocusChangeListener = View.OnFocusChangeListener { v, f -> v.background = ui.pillBg(f); zoom(v, if (f) 1.08f else 1f, 120) }
        }.also { row.addView(it, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = ui.px(12); rightMargin = ui.px(12) }) }
        val now = button(R.string.update_now) {
            sub.text = getString(R.string.update_installing); row.visibility = View.INVISIBLE
            Thread {
                try { Updater.install(this, r) }
                catch (x: Exception) { main.post { d.dismiss(); Updater.failed(this, r); Toast.makeText(this, R.string.update_failed, Toast.LENGTH_LONG).show() } }
            }.start()
        }
        button(R.string.update_later) { Updater.snooze(this); d.dismiss() }
        col.addView(row, LinearLayout.LayoutParams(-1, -2))
        d.setOnDismissListener { updDlg = null; strip.requestFocus() }
        d.setContentView(col)
        updDlg = d
        d.show()
        now.apply { isFocusableInTouchMode = true; requestFocus() }
    }

    /** What the system says about the install. Success needs nothing: the new AK Home starts in its place. */
    private val updStatus = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val r = Updater.ready(c)
            when (i.getIntExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, -999)) {
                android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION") val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                    try { startActivity(confirm!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { updDlg?.dismiss() }
                    return
                }
                android.content.pm.PackageInstaller.STATUS_SUCCESS -> return
                android.content.pm.PackageInstaller.STATUS_FAILURE_ABORTED -> Updater.snooze(c)     // "Cancel" on the system screen
                android.content.pm.PackageInstaller.STATUS_FAILURE_CONFLICT, android.content.pm.PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
                android.content.pm.PackageInstaller.STATUS_FAILURE_INVALID -> {
                    r?.let { Updater.failed(c, it) }; Toast.makeText(c, R.string.update_failed, Toast.LENGTH_LONG).show()
                }
                else -> { Updater.snooze(c); Toast.makeText(c, R.string.update_failed, Toast.LENGTH_LONG).show() }
            }
            android.util.Log.w("AKHome", "update: ${i.getStringExtra(android.content.pm.PackageInstaller.EXTRA_STATUS_MESSAGE)}")
            updDlg?.dismiss()
        }
    }

    // ---------------------------------------------------------------- lifecycle

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_TIME_TICK, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> updateClock()
                @Suppress("DEPRECATION") ConnectivityManager.CONNECTIVITY_ACTION -> updateNet()
                Intent.ACTION_SCREEN_ON -> if (!lite) showSplash()
                else -> loadApps()
            }
        }
    }

    // the export flag only exists from API 33 and is passed there; lint cannot see the version branch
    @Suppress("DEPRECATION")
    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerReceivers() {
        val sys = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK); addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(ConnectivityManager.CONNECTIVITY_ACTION); addAction(Intent.ACTION_SCREEN_ON)
        }
        val pkgs = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED); addAction(Intent.ACTION_PACKAGE_REPLACED); addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, sys, Context.RECEIVER_EXPORTED); registerReceiver(receiver, pkgs, Context.RECEIVER_EXPORTED)
        } else { registerReceiver(receiver, sys); registerReceiver(receiver, pkgs) }
        val upd = IntentFilter(Updater.ACTION)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(updStatus, upd, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(updStatus, upd)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        // back here without Home (Back key, app closed): forget the app, so the next Home goes to the first app.
        // With Home, onNewIntent has already used it (it runs before onResume).
        lastLaunched = null
        updateClock(); updateNet()
        if (dirty) loadApps()
        main.postDelayed({ offerUpdate() }, 1500)    // let the strip settle first
    }

    override fun onPause() { super.onPause(); resumed = false }

    /**
     * Home key. Coming back from an app: the strip waits on that app. Home again while already here:
     * back to the first app in the middle. (onNewIntent runs before onResume, so `resumed` tells the two apart.)
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val wasHere = resumed && allDlg == null
        allDlg?.dismiss(); allDlg = null
        movePkg = null
        val back = lastLaunched?.takeIf { !wasHere && shown.any { e -> e.app.pkg == it } }
        if (back != null) renderCarousel(back) else { cIndex = 0; renderCarousel(null) }
        lastLaunched = null
        strip.requestFocus()
    }

    private var lastLaunched: String? = null

    /** Big title: app names at full size; film/series names smaller, so they never run into the card. */
    private fun showTitle(text: CharSequence, film: Boolean = false) {
        val size = (if (film) 21f else 34f) * ui.dp * 1.25f
        if (title.textSize != size) {
            title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size)
            title.gravity = Gravity.LEFT or if (film) Gravity.BOTTOM else Gravity.TOP     // same baseline area
        }
        title.text = text
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { /* home screen: nothing behind it */ }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            (currentFocus?.tag as? Entry)?.let { showMenu(it); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        try { unregisterReceiver(receiver) } catch (_: Exception) { }
        try { unregisterReceiver(updStatus) } catch (_: Exception) { }
        updDlg?.dismiss()
        feedThread?.interrupt()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
