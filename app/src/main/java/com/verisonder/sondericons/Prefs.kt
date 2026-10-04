package com.verisonder.sondericons

import android.content.Context
import java.io.File
import java.util.UUID

/** What the person chose for each app, and the ids this app needs to remember. */
object Prefs {
    enum class Mode { AUTO, CUSTOM, LETTER, THEME }

    private fun sp(ctx: Context) = ctx.getSharedPreferences("sondericons", Context.MODE_PRIVATE)

    fun mode(ctx: Context, pkg: String): Mode =
        runCatching { Mode.valueOf(sp(ctx).getString("mode:$pkg", null) ?: "AUTO") }.getOrDefault(Mode.AUTO)

    fun setMode(ctx: Context, pkg: String, mode: Mode) =
        sp(ctx).edit().putString("mode:$pkg", mode.name).apply()

    /** Glyph size, as a multiple of the theme's own glyph size. */
    fun scale(ctx: Context, pkg: String): Float = sp(ctx).getFloat("scale:$pkg", 1f)
    fun setScale(ctx: Context, pkg: String, v: Float) = sp(ctx).edit().putFloat("scale:$pkg", v).apply()
    fun globalScale(ctx: Context): Float = sp(ctx).getFloat("globalScale", 1f)
    fun setGlobalScale(ctx: Context, v: Float) = sp(ctx).edit().putFloat("globalScale", v).apply()

    /**
     * How a glyph is made. Global defaults, and per-app overrides where a field is set.
     * source: auto | glyph | shape | cutout.  stroke: null = auto, else -1..2 px.
     * sensitivity: how readily colour separation counts a pixel as logo (1 = default).
     */
    data class Tuning(val source: String = "auto", val stroke: Int? = null, val sensitivity: Float = 1f, val crisp: Boolean = false)

    fun globalTuning(ctx: Context) = sp(ctx).let {
        Tuning(it.getString("t:source", "auto")!!, it.getInt("t:stroke", Int.MIN_VALUE).takeIf { v -> v != Int.MIN_VALUE },
            it.getFloat("t:sens", 1f), it.getBoolean("t:crisp", false))
    }
    fun setGlobalTuning(ctx: Context, t: Tuning) = sp(ctx).edit()
        .putString("t:source", t.source).putInt("t:stroke", t.stroke ?: Int.MIN_VALUE)
        .putFloat("t:sens", t.sensitivity).putBoolean("t:crisp", t.crisp).apply()

    /** The per-app tuning, falling back to the global value for every field not overridden. */
    fun tuning(ctx: Context, pkg: String): Tuning {
        val g = globalTuning(ctx); val p = sp(ctx)
        return Tuning(
            p.getString("t:source:$pkg", null) ?: g.source,
            if (p.contains("t:stroke:$pkg")) p.getInt("t:stroke:$pkg", 0).takeIf { it != Int.MIN_VALUE } else g.stroke,
            if (p.contains("t:sens:$pkg")) p.getFloat("t:sens:$pkg", 1f) else g.sensitivity,
            if (p.contains("t:crisp:$pkg")) p.getBoolean("t:crisp:$pkg", false) else g.crisp,
        )
    }
    fun setTuning(ctx: Context, pkg: String, t: Tuning) = sp(ctx).edit()
        .putString("t:source:$pkg", t.source).putInt("t:stroke:$pkg", t.stroke ?: Int.MIN_VALUE)
        .putFloat("t:sens:$pkg", t.sensitivity).putBoolean("t:crisp:$pkg", t.crisp).apply()
    fun hasTuning(ctx: Context, pkg: String) = sp(ctx).contains("t:source:$pkg")
    fun clearTuning(ctx: Context, pkg: String) = sp(ctx).edit()
        .remove("t:source:$pkg").remove("t:stroke:$pkg").remove("t:sens:$pkg").remove("t:crisp:$pkg").apply()

    fun styleId(ctx: Context): String? = sp(ctx).getString("style", null)
    fun setStyleId(ctx: Context, id: String) = sp(ctx).edit().putString("style", id).apply()

    fun customFile(ctx: Context, pkg: String) = File(File(ctx.filesDir, "custom").apply { mkdirs() }, "$pkg.png")

    /** This app's own icons resource id. Made once, then kept, so rebuilds replace in place. */
    fun ownIconsId(ctx: Context): String = sp(ctx).getString("ownIconsId", null)
        ?: UUID.randomUUID().toString().also { sp(ctx).edit().putString("ownIconsId", it).apply() }

    /** The theme's own icons id, so restore can put it back. */
    fun originalIconsId(ctx: Context): String? = sp(ctx).getString("origIconsId", null)
    fun setOriginalIconsId(ctx: Context, id: String) = sp(ctx).edit().putString("origIconsId", id).apply()

    /** A Theme backup the person chose by hand. Only used when the one on screen isn't a backup. */
    fun chosenThemeId(ctx: Context): String? = sp(ctx).getString("chosenThemeId", null)
    fun setChosenThemeId(ctx: Context, id: String?) = sp(ctx).edit().putString("chosenThemeId", id).apply()
}
