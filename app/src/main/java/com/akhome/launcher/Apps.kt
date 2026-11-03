package com.akhome.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable

data class App(
    val pkg: String,
    val label: String,
    val component: ComponentName,
    val tv: Boolean,          // has a LEANBACK_LAUNCHER entry
) {
    fun launchIntent(): Intent = Intent(Intent.ACTION_MAIN)
        .addCategory(if (tv) Intent.CATEGORY_LEANBACK_LAUNCHER else Intent.CATEGORY_LAUNCHER)
        .setComponent(component)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
}

object Apps {
    /** Things that are never shown as app cards (they have their own buttons or are plumbing). */
    private val skip = setOf(
        "com.android.tv.settings", "com.android.settings", "com.google.android.tvlauncher",
        "com.google.android.leanbacklauncher", "com.google.android.apps.tv.launcherx",
        "com.android.vending.tv", "com.google.android.tungsten.setupwraith",
    )

    /**
     * The box's own "App List" / app drawer (e.g. com.android.appdrawer on Allwinner boxes): it shows every app in
     * the stock look, so in AK Home it opens our All apps instead (user, 2026-10-29).
     */
    fun isDrawer(pkg: String): Boolean = listOf("appdrawer", "applist", "allapps", "app_list").any { pkg.contains(it, ignoreCase = true) }

    fun load(ctx: Context): List<App> {
        val pm = ctx.packageManager
        val byPkg = LinkedHashMap<String, App>()
        fun add(list: List<ResolveInfo>, tv: Boolean) {
            for (ri in list) {
                val ai = ri.activityInfo ?: continue
                if (ai.packageName == ctx.packageName || ai.packageName in skip) continue
                if (!ai.enabled || !ai.applicationInfo.enabled) continue
                if (byPkg[ai.packageName]?.tv == true) continue
                byPkg[ai.packageName] = App(ai.packageName, ri.loadLabel(pm).toString().trim(),
                    ComponentName(ai.packageName, ai.name), tv)
            }
        }
        add(query(pm, Intent.CATEGORY_LEANBACK_LAUNCHER), tv = true)
        add(query(pm, Intent.CATEGORY_LAUNCHER), tv = false)
        return byPkg.values.toList()
    }

    /** Applies the saved order; unknown packages go after in label order. */
    fun ordered(all: List<App>, prefs: Prefs, preferred: List<String>, ignoreSaved: Boolean = false): List<App> {
        val saved = if (ignoreSaved) preferred else prefs.order.ifEmpty { preferred }
        val rank = saved.withIndex().associate { it.value to it.index }
        return all.sortedWith(compareBy<App> { rank[it.pkg] ?: Int.MAX_VALUE }.thenBy { it.label.lowercase() })
    }

    private fun query(pm: PackageManager, cat: String): List<ResolveInfo> =
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(cat), 0)

    fun icon(ctx: Context, app: App): Drawable = try {
        ctx.packageManager.getActivityIcon(app.component)
    } catch (_: Exception) { ctx.packageManager.defaultActivityIcon }

    fun banner(ctx: Context, app: App): Drawable? = try {
        ctx.packageManager.getActivityBanner(app.component)
            ?: ctx.packageManager.getApplicationBanner(app.pkg)
    } catch (_: Exception) { null }
}
