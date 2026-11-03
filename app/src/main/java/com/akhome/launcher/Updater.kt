package com.akhome.launcher

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Updates itself (user, 2026-10-30): once a day AK Home reads a small file on our site
 * (akagency.app/akhome/update.json). When it lists a newer build of this edition, the APK (~250 KB) is downloaded in
 * the background, checked (size + SHA-256) and kept until the home screen offers it. Installing goes through the
 * system's PackageInstaller: one "Install" press on Android 5–11, none on Android 12+ (an app may update itself).
 *
 * update.json: {"versionCode":4,"versionName":"1.0","editions":{"global-tvbox":{"url":…,"sha256":…,"size":…}, …}}
 */
object Updater {
    const val URL_INFO = BuildConfig.UPDATE_URL
    const val ACTION = "com.akhome.launcher.UPDATE_STATUS"
    private const val DAY = 24 * 3_600_000L

    /** Same names as the installer's editions: global-tvbox, global-screens. */
    val edition = BuildConfig.REGION + if (BuildConfig.INPUTS) "-screens" else "-tvbox"

    class Ready(val file: File, val code: Int, val name: String)

    private fun sp(ctx: Context) = ctx.getSharedPreferences("akhome-update", Context.MODE_PRIVATE)
    private fun dir(ctx: Context) = File(ctx.filesDir, "update")

    /** A checked APK newer than this build, waiting to be installed (else null; leftovers are cleaned away). */
    fun ready(ctx: Context): Ready? {
        val s = sp(ctx)
        val code = s.getInt("code", 0)
        val f = File(dir(ctx), "$code.apk")
        if (code <= BuildConfig.VERSION_CODE || !f.exists()) { if (code != 0) clear(ctx); return null }
        return Ready(f, code, s.getString("name", "") ?: "")
    }

    /** "Later": the offer comes back tomorrow. */
    fun snooze(ctx: Context) { sp(ctx).edit().putLong("snooze", System.currentTimeMillis() + DAY).apply() }
    fun snoozed(ctx: Context) = System.currentTimeMillis() < sp(ctx).getLong("snooze", 0)

    private fun clear(ctx: Context) {
        dir(ctx).listFiles()?.forEach { it.delete() }
        sp(ctx).edit().remove("code").remove("name").apply()
    }

    /** Background thread only. At most once a day; true when a new build is ready to offer. */
    fun check(ctx: Context, force: Boolean = false): Boolean {
        val s = sp(ctx)
        val now = System.currentTimeMillis()
        if (!force && now - s.getLong("checked", 0) < DAY) return ready(ctx) != null
        try {
            val info = JSONObject(get("$URL_INFO?d=${now / DAY}"))     // one cache key per day on the CDN
            s.edit().putLong("checked", now).apply()
            val code = info.getInt("versionCode")
            if (code <= BuildConfig.VERSION_CODE || code == s.getInt("bad", 0)) { ready(ctx); return false }
            if (ready(ctx)?.code == code) return true
            val e = info.getJSONObject("editions").optJSONObject(edition) ?: return false
            val size = e.getLong("size"); val sum = e.getString("sha256").lowercase()
            clear(ctx)
            val d = dir(ctx).apply { mkdirs() }
            val part = File(d, "$code.part")
            download(e.getString("url"), part)
            if (part.length() != size || sha256(part) != sum) { part.delete(); return false }
            part.renameTo(File(d, "$code.apk"))
            s.edit().putInt("code", code).putString("name", info.optString("versionName")).apply()
            android.util.Log.i("AKHome", "update $code ready ($edition)")
            return true
        } catch (x: Exception) {
            android.util.Log.w("AKHome", "update check: $x")
            return false
        }
    }

    /** The system installs it; the result comes back to [ACTION] (a confirm screen on Android 5–11). */
    fun install(ctx: Context, r: Ready) {
        val pi = ctx.packageManager.packageInstaller
        val p = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = pi.createSession(p)
        pi.openSession(id).use { s ->
            s.openWrite("base.apk", 0, r.file.length()).use { out -> r.file.inputStream().use { it.copyTo(out) }; s.fsync(out) }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val back = PendingIntent.getBroadcast(ctx, id, Intent(ACTION).setPackage(ctx.packageName), flags)
            s.commit(back.intentSender)
        }
    }

    /** A build this box refuses (other signature …) is never offered again. */
    fun failed(ctx: Context, r: Ready) { sp(ctx).edit().putInt("bad", r.code).apply(); clear(ctx) }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000; readTimeout = 30_000; setRequestProperty("User-Agent", "AKHome/${BuildConfig.VERSION_NAME}")
    }

    private fun get(url: String): String = open(url).run {
        try { if (responseCode != 200) throw java.io.IOException("HTTP $responseCode"); inputStream.bufferedReader().readText() }
        finally { disconnect() }
    }

    private fun download(url: String, to: File) = open(url).run {
        try {
            if (responseCode != 200) throw java.io.IOException("HTTP $responseCode")
            inputStream.use { i -> to.outputStream().use { i.copyTo(it) } }
        } finally { disconnect() }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i -> val b = ByteArray(16384); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
