package com.akhome.installer

import android.content.Context

/** One AK Home the installer carries. Both editions are the same app (one AK Home per TV). */
enum class Edition(val asset: String, val title: Int, val sub: Int) {
    GLOBAL_BOX("editions/global-tvbox.apk", R.string.tvbox, R.string.tvbox_sub),
    GLOBAL_SCREENS("editions/global-screens.apk", R.string.screens, R.string.screens_sub);

    val pkg get() = PKG
    val activity get() = "$PKG/.MainActivity"

    companion object { const val PKG = "com.akhome.launcher"; val PACKAGES = listOf(PKG) }
}

enum class Kind { TV, BOX }

/** What we learned about the TV once connected, and why we think so. */
class TvInfo(
    val sdk: Int, val brand: String, val model: String, val android: String,
    val kind: Kind, val kindWhy: String, val userApps: Int,
    /** false when nothing told us TV set or box for sure: then the installer asks the user. */
    val kindSure: Boolean = true,
) {
    val name: String get() = listOf(brand, model).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "Android TV" }
}

/** A launcher the installer will switch off, with a name people recognise. */
class Home(val pkg: String, val label: String)

/**
 * Everything the installer does on the TV, as plain shell commands over ADB.
 *
 * Making AK Home the home screen for good means switching the stock launcher off (`pm disable-user`, reversible):
 * Google/Android TV launchers grab the Home key even when another home is chosen, many Chinese ROMs forget the
 * "Always" choice, and Android 7 has no command to pick a home. What was switched off is written on the TV itself
 * (RESTORE file), so "Back to original" works from any phone, even after the installer was reinstalled.
 * Nothing is ever deleted from the TV except AK Home itself, and only when asked.
 */
class Tv(private val ctx: Context, private val adb: AdbClient, private val fix: (String) -> Unit = {}) {
    companion object {
        private const val TMP = "/data/local/tmp/akhome.apk"
        private const val RESTORE = "/data/local/tmp/akhome-restore.txt"

        /** Known stock launchers (names people see), also used on TVs too old (Android 5/6) to list their homes. */
        private val KNOWN = linkedMapOf(
            "com.google.android.tvlauncher" to "Google TV home", "com.google.android.apps.tv.launcherx" to "Google TV home",
            "com.google.android.leanbacklauncher" to "Android TV home", "com.android.tv.launcher" to "Android TV home",
            "com.rockchips.android.leanbacklauncher" to "Box home (Rockchip)", "com.droidlogic.launcher.provider" to "Box home (Amlogic)",
            "com.amlogic.launcher" to "Box home (Amlogic)", "com.mbx.launcher" to "Box home", "com.android.launcher3" to "Android home",
            "com.android.launcher" to "Android home", "com.mstar.tv.tvlauncher" to "TV home (MStar)", "com.tcl.cyberui" to "TCL home",
            "com.hisense.homepage" to "Hisense home", "com.xiaomi.mitv.tvlauncher" to "Mi TV home",
            "com.ak.home" to "AK Home 4 (old)",
        )

        /** TV makers: a TV set, not a box, when nothing better tells us. */
        private val TV_BRANDS = listOf("tcl", "hisense", "skyworth", "konka", "changhong", "sharp", "sony", "philips", "xiaomi",
            "haier", "panasonic", "toshiba", "vestel", "shinon", "lg", "samsung", "thomson", "jvc", "grundig", "nokia")

        /** Homes we never switch off: setup wizards and the Settings fallback home keep the TV usable. */
        private fun essential(pkg: String) = pkg in Edition.PACKAGES || pkg == "android" ||
            listOf("settings", "setup", "provision", "fallback", "wizard").any { pkg.contains(it, ignoreCase = true) }
    }

    // ---------------------------------------------------------------- what is this TV?

    fun info(): TvInfo {
        val sdk = adb.prop("ro.build.version.sdk").toIntOrNull() ?: 21
        val brand = adb.prop("ro.product.brand").replaceFirstChar { it.uppercase() }
        val model = adb.prop("ro.product.model")

        // TV set or box: HDMI-CEC role first (0 = TV, 4 = playback box), then HDMI inputs, then the maker
        val cec = adb.prop("ro.hdmi.device_type")
        val hdmiIn = adb.sh("dumpsys tv_input | grep -ci 'hdmi'").trim().toIntOrNull() ?: 0
        val maker = "$brand ${adb.prop("ro.product.manufacturer")}".lowercase()
        val (kind, kindWhy) = when {
            cec.split(',').map { it.trim() }.contains("0") -> Kind.TV to ctx.getString(R.string.why_cec_tv)
            cec.split(',').map { it.trim() }.contains("4") -> Kind.BOX to ctx.getString(R.string.why_cec_box)
            hdmiIn > 0 -> Kind.TV to ctx.getString(R.string.why_hdmi)
            TV_BRANDS.any { maker.contains(it) } -> Kind.TV to ctx.getString(R.string.why_maker, brand)
            else -> Kind.BOX to ctx.getString(R.string.why_no_hdmi)
        }
        // sure = a real sign (CEC role, HDMI inputs, TV maker); "no HDMI inputs" alone is a guess → ask
        val kindSure = kindWhy != ctx.getString(R.string.why_no_hdmi)
        val userApps = adb.sh("pm list packages -3").lines().count { it.startsWith("package:") }
        return TvInfo(sdk, brand, model, adb.prop("ro.build.version.release"), kind, kindWhy, userApps, kindSure)
    }

    /** The edition that suits this TV. */
    fun suggest(info: TvInfo): Edition = if (info.kind == Kind.TV) Edition.GLOBAL_SCREENS else Edition.GLOBAL_BOX

    /** Launchers that answer the Home key now, apart from AK Home and the essential ones: these get switched off. */
    fun stockHomes(info: TvInfo): List<Home> {
        val found = if (info.sdk >= 24) {
            adb.sh("cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME")
                .lines().map { it.trim() }.filter { Regex("^[\\w.]+/[\\w.$]+$").matches(it) }.map { it.substringBefore('/') }
        } else {
            val enabled = adb.sh("pm list packages -e").lines().map { it.removePrefix("package:").trim() }.toSet()
            KNOWN.keys.filter { it in enabled }
        }
        // Only real launchers: the ROM's own (system apps) or ones we know. Ordinary apps the user installed are never
        // touched even when they declare a HOME screen (Cinemana, Cinemana 4K and CEE do) — found on onn, 2026-10-29.
        val system = adb.sh("pm list packages -s").lines().map { it.removePrefix("package:").trim() }.toSet()
        return found.distinct().filterNot(::essential).filter { it in KNOWN || it in system }.map { Home(it, label(it)) }
    }

    // ---------------------------------------------------------------- install (with the doctor)

    /** Streams the edition APK from the installer's assets to the TV and installs it, fixing what it can. */
    fun install(e: Edition, progress: (Int) -> Unit) {
        val size = ctx.assets.openFd(e.asset).use { it.length }
        // doctor: room for the copy (AK Home is small, but some boxes are full to the last MB)
        if (freeKb("/data/local/tmp") in 0 until (size / 1024 + 20_000)) {
            fix(ctx.getString(R.string.fix_space))
            adb.sh("rm -f /data/local/tmp/*.apk"); adb.sh("pm trim-caches 999G")
        }
        var out = ""
        for (attempt in 1..3) {
            try { ctx.assets.open(e.asset).use { adb.push(it, size, TMP, progress) } }
            catch (x: Exception) { if (attempt == 3) throw x; fix(ctx.getString(R.string.fix_resend)); continue }
            out = adb.sh("pm install -r $TMP")
            if (out.contains("Success")) break
            when {
                out.contains("UPDATE_INCOMPATIBLE") || out.contains("SIGNATURES") -> {
                    fix(ctx.getString(R.string.fix_replace)); adb.sh("pm uninstall ${e.pkg}")
                }
                out.contains("VERSION_DOWNGRADE") -> {
                    fix(ctx.getString(R.string.fix_downgrade))
                    out = adb.sh("pm install -r -d $TMP"); if (out.contains("Success")) break
                }
                out.contains("INSUFFICIENT_STORAGE") -> {
                    fix(ctx.getString(R.string.fix_space)); adb.sh("pm trim-caches 999G")
                }
                out.contains("NO_CERTIFICATES") || out.contains("PARSE") || out.contains("CORRUPT") -> fix(ctx.getString(R.string.fix_resend))
                out.contains("USER_RESTRICTED") -> throw Problem(ctx.getString(R.string.problem_restricted))
                else -> if (attempt == 3) break
            }
        }
        adb.sh("rm -f $TMP")
        if (!out.contains("Success")) throw Problem(ctx.getString(R.string.problem_refused, out.lines().lastOrNull { it.isNotBlank() } ?: "-"))
        // AK Home updates itself from akagency.app (1.0+): let it install its own update without a trip to the TV's
        // settings (Android 8+; many boxes hide that page). Only AK Home, nothing else; an old TV just says "unknown".
        adb.sh("appops set ${e.pkg} REQUEST_INSTALL_PACKAGES allow")
    }

    /** Switches the stock launchers off (remembering them on the TV) and makes AK Home the home, checking it took. */
    fun makeHome(info: TvInfo, e: Edition, homes: List<Home>) {
        val all = (switchedOff() + homes.map { it.pkg }).distinct()
        adb.sh("echo '${all.joinToString("\n")}' > $RESTORE")
        for (h in homes) adb.sh("pm disable-user --user 0 ${h.pkg}")
        pickHome(info, e)
        if (!isHome(info, e)) {
            // doctor: a launcher we did not know about, or one that came back — switch off whatever still answers Home
            val left = stockHomes(info)
            if (left.isNotEmpty()) {
                fix(ctx.getString(R.string.fix_other_home, left.joinToString { it.label }))
                adb.sh("echo '${(all + left.map { it.pkg }).distinct().joinToString("\n")}' > $RESTORE")
                for (h in left) adb.sh("pm disable-user --user 0 ${h.pkg}")
                pickHome(info, e)
            }
        }
        adb.sh("am start -a android.intent.action.MAIN -c android.intent.category.HOME")
    }

    private fun pickHome(info: TvInfo, e: Edition) {
        when {
            info.sdk >= 29 -> adb.sh("cmd role add-role-holder android.app.role.HOME ${e.pkg} 0")
            info.sdk >= 24 -> adb.sh("cmd package set-home-activity ${e.activity}")
        }
    }

    /** True when the Home key now leads to this edition. */
    fun isHome(info: TvInfo, e: Edition): Boolean = if (info.sdk >= 24)
        adb.sh("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME")
            .lines().any { it.trim().startsWith("${e.pkg}/") }
    else stockHomes(info).isEmpty()

    /** Doctor's last check: AK Home actually runs (no crash on this TV). */
    fun runs(e: Edition): Boolean {
        Thread.sleep(2500)
        if (adb.sh("pidof ${e.pkg}").isNotBlank()) return true
        fix(ctx.getString(R.string.fix_start))
        adb.sh("am start -n ${e.activity}")
        Thread.sleep(3000)
        return adb.sh("pidof ${e.pkg}").isNotBlank()
    }

    // ---------------------------------------------------------------- back to the original

    /** Launchers AK Home switched off earlier on this TV (empty when nothing to undo). */
    fun switchedOff(): List<String> =
        adb.sh("cat $RESTORE 2>/dev/null").lines().map { it.trim() }.filter { it.isNotEmpty() && !it.contains(' ') }

    /** A launcher's name in the app's language (Arabic names when the app is in Arabic). */
    fun label(pkg: String): String {
        val en = KNOWN[pkg] ?: return pkg
        if (ctx.resources.configuration.locales[0].language != "ar") return en
        return en.replace("Google TV home", "واجهة Google TV").replace("Android TV home", "واجهة Android TV")
            .replace("Box home", "واجهة البوكس").replace("TV home", "واجهة التلفزيون").replace("Android home", "واجهة أندرويد")
            .replace("TCL home", "واجهة TCL").replace("Hisense home", "واجهة Hisense").replace("Mi TV home", "واجهة Mi TV")
            .replace("AK Home 4 (old)", "AK Home 4 (القديم)")
    }

    /** Back to the TV's own home screen: stock launchers on again, AK Home removed (if asked). */
    fun restore(info: TvInfo, removeAkHome: Boolean) {
        val off = switchedOff()
        for (p in off) adb.sh("pm enable $p")
        if (info.sdk >= 29 && off.isNotEmpty()) adb.sh("cmd role add-role-holder android.app.role.HOME ${off.first()} 0")
        if (removeAkHome) for (p in Edition.PACKAGES) adb.sh("pm uninstall $p")
        adb.sh("rm -f $RESTORE")
        adb.sh("input keyevent 3")
    }

    private fun freeKb(path: String): Long =
        adb.sh("df $path | tail -1").split(Regex("\\s+")).getOrNull(3)?.let { v ->
            when { v.endsWith("G") -> (v.dropLast(1).toDoubleOrNull() ?: -1.0) * 1_048_576; v.endsWith("M") -> (v.dropLast(1).toDoubleOrNull() ?: -1.0) * 1024
                   v.endsWith("K") -> v.dropLast(1).toDoubleOrNull() ?: -1.0; else -> v.toDoubleOrNull() ?: -1.0 }
        }?.toLong() ?: -1
}

/** A problem the doctor could not fix, explained for the user. */
class Problem(msg: String) : Exception(msg)
