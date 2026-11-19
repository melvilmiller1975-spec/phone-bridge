package com.todd.charlie

import android.content.Context

class Prefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("charlie", Context.MODE_PRIVATE)

    var url: String
        get() = p.getString("url", "") ?: ""
        set(v) = p.edit().putString("url", v).apply()
    var key: String
        get() = p.getString("key", "") ?: ""
        set(v) = p.edit().putString("key", v).apply()
    val hasSettings get() = url.isNotBlank() && key.length >= 20

    var approved: Set<String>
        get() = p.getStringSet("approved", emptySet())?.toSet() ?: emptySet()
        set(v) = p.edit().putStringSet("approved", v).apply()
    var allowAll: Boolean
        get() = p.getBoolean("allowAll", false)
        set(v) = p.edit().putBoolean("allowAll", v).apply()
    var screenControl: Boolean
        get() = p.getBoolean("screenControl", false)
        set(v) = p.edit().putBoolean("screenControl", v).apply()
    var autoStart: Boolean
        get() = p.getBoolean("autoStart", true)
        set(v) = p.edit().putBoolean("autoStart", v).apply()
    var linkWanted: Boolean
        get() = p.getBoolean("linkWanted", false)
        set(v) = p.edit().putBoolean("linkWanted", v).apply()
}
