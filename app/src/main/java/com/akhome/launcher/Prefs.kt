package com.akhome.launcher

import android.content.Context

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("akhome", Context.MODE_PRIVATE)

    /** null = automatic, true/false = forced by the user. */
    var liteOverride: Boolean?
        get() = if (sp.contains("lite")) sp.getBoolean("lite", false) else null
        set(v) { sp.edit().apply { if (v == null) remove("lite") else putBoolean("lite", v) }.apply() }

    var hidden: Set<String>
        get() = sp.getStringSet("hidden", emptySet())!!.toSet()
        set(v) { sp.edit().putStringSet("hidden", v).apply() }

    /** Package order for the carousel; packages not listed go after, alphabetically. */
    var order: List<String>
        get() = sp.getString("order", "")!!.split(',').filter { it.isNotEmpty() }
        set(v) { sp.edit().putString("order", v.joinToString(",")).apply() }

    fun fav(i: Int): String? = sp.getString("fav$i", null)
    fun setFav(i: Int, pkg: String) { sp.edit().putString("fav$i", pkg).apply() }
}
