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
