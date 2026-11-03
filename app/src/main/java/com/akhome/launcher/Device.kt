package com.akhome.launcher

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.io.File

/** Decides once per process whether the box is weak enough for lite mode. */
object Device {
    @Volatile private var cached: Boolean? = null

    /** Lite = keep the glass look but drop real blur, glow passes and long animations. */
    fun isLite(ctx: Context): Boolean {
        cached?.let { return it }
        val forced = Prefs(ctx).liteOverride
        val lite = forced ?: autoLite(ctx)
        cached = lite
        return lite
    }

    fun reset() { cached = null }

    private fun autoLite(ctx: Context): Boolean {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val ramGb = mem.totalMem / (1024.0 * 1024 * 1024)
        val maxMhz = maxCpuMhz()
        val only32 = Build.SUPPORTED_64_BIT_ABIS.isEmpty()
        val m = ctx.resources.displayMetrics
        val uhd = maxOf(m.widthPixels, m.heightPixels) > 2000      // 4K panels move 4× the pixels per frame
        return am.isLowRamDevice ||
            (uhd && ramGb < 3.0) ||
            ramGb < 1.6 ||
            Build.VERSION.SDK_INT < 24 ||
            Runtime.getRuntime().availableProcessors() < 4 ||
            (maxMhz in 1..1500) ||
            (only32 && ramGb < 2.5)
    }

    private fun maxCpuMhz(): Int = try {
        File("/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq").readText().trim().toInt() / 1000
    } catch (_: Exception) { 0 }

    fun summary(ctx: Context): String {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return "sdk=${Build.VERSION.SDK_INT} ram=${mem.totalMem / 1048576}MB lowRam=${am.isLowRamDevice} " +
            "cpus=${Runtime.getRuntime().availableProcessors()} maxMhz=${maxCpuMhz()} " +
            "abi64=${Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()} lite=${isLite(ctx)}"
    }
}
