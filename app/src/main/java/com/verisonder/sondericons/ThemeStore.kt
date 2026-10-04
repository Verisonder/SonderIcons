package com.verisonder.sondericons

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * The Themes app's local library, as far as this app needs it.
 *
 * What HyperOS does, found by reading the Themes app on a Global ROM:
 *  - Every theme component lives as `.data/content/<code>/<id>.mrc` (a zip) with metadata
 *    `.data/meta/<code>/<id>.mrm` (JSON) in the Themes app's external folder.
 *  - A theme's `.mrm` lists its components by id; each component points back to its theme.
 *  - The metadata `hash` is a plain SHA-1 of the `.mrc` and `size` is its length. Nothing
 *    is signed.
 *  - A theme the system made itself (titled "Theme backup", made by Customize theme) has no
 *    rights record and applies without asking a server. A theme added by hand does ask, and
 *    fails. So this app never adds a theme: it points "Theme backup" at a new icons file.
 *
 * Shell UID can read and write this folder but not `/data/system/theme`, which is where the
 * applied copy lives. The Themes app copies into that itself when the theme is applied.
 */
object ThemeStore {
    const val DATA = "/sdcard/Android/data/com.android.thememanager/files/MIUI/theme/.data"
    const val APPLIED_ICONS = "/data/system/theme/icons"
    const val THEMES_PKG = "com.android.thememanager"

    class Theme(val id: String, val iconsId: String, val json: JSONObject, val savedAt: Long = 0)

    fun stage(ctx: Context): File = File(ctx.getExternalFilesDir(null), "stage").apply { mkdirs() }

    fun read(path: String): ByteArray? = Shell.run("cat ${Shell.q(path)}").let { if (it.ok) it.out else null }

    /** Copies a file from this app's own folder to [remote]. Shell can read the former. */
    fun put(local: File, remote: String): Boolean =
        Shell.run("cp ${Shell.q(local.absolutePath)} ${Shell.q(remote)}").ok

    fun sha1(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun iconsIdOf(json: JSONObject): String? {
        val subs = json.optJSONArray("subResources") ?: return null
        for (i in 0 until subs.length()) {
            val s = subs.getJSONObject(i)
            if (s.optString("resourceCode") == "icons") return s.optString("localId")
        }
        return null
    }

    /** Every "Theme backup" in the library, and which one is on screen now (if any). */
    class Lookup(val themes: List<Theme>, val applied: Theme?)

    /**
     * Lists the "Theme backup" themes and works out which one is applied.
     *
     * The applied one is found by SHA-1: `/data/system/theme/icons` is a copy of one theme's
     * icons file, and each icons file's metadata records its hash. A theme this app has
     * linked but not yet been applied still counts if its *original* icons ([originalIconsId])
     * are what is on screen. No match means the phone is on some other theme, and nothing
     * is guessed: the person is asked.
     */
    fun lookup(ownIconsId: String?, originalIconsId: String?): Lookup {
        val list = Shell.run("grep -l '\"Theme backup\"' $DATA/meta/theme/*.mrm 2>/dev/null").text
            .lines().map { it.trim() }.filter { it.endsWith(".mrm") }
        val themes = list.mapNotNull { path ->
            val json = runCatching { JSONObject(String(read(path) ?: return@mapNotNull null)) }.getOrNull()
                ?: return@mapNotNull null
            val icons = iconsIdOf(json) ?: return@mapNotNull null
            val time = Shell.run("stat -c %Y ${Shell.q(path)}").text.trim().toLongOrNull() ?: 0L
            Theme(json.optString("localId"), icons, json, time * 1000)
        }.sortedByDescending { it.savedAt }
        val applied = Shell.run("sha1sum $APPLIED_ICONS").text.trim().split(" ").firstOrNull()
        fun hashOf(iconsId: String?) = iconsId?.let { id ->
            read("$DATA/meta/icons/$id.mrm")?.let { runCatching { JSONObject(String(it)).optString("hash") }.getOrNull() }
        }
        val match = if (applied.isNullOrEmpty()) null else themes.firstOrNull { t ->
            hashOf(t.iconsId) == applied || (t.iconsId == ownIconsId && hashOf(originalIconsId) == applied)
        }
        return Lookup(themes, match)
    }

    /** The theme's launcher preview, if the Themes app kept one. */
    fun preview(themeId: String): ByteArray? {
        val dir = "$DATA/preview/theme/$themeId"
        val name = Shell.run("ls ${Shell.q(dir)} 2>/dev/null").text.lines().map { it.trim() }
            .let { names -> names.firstOrNull { it.contains("launcher_0") } ?: names.firstOrNull { it.endsWith(".jpg") || it.endsWith(".png") } }
            ?: return null
        return read("$dir/$name")
    }

    /** Titles of icons files this app (or the PC tool that came before it) has written. */
    private val OUR_TITLES = setOf("SonderIcons", "Nidal icons", "Nidal icon test")

    fun iconsMeta(iconsId: String): JSONObject? =
        read("$DATA/meta/icons/$iconsId.mrm")?.let { runCatching { JSONObject(String(it)) }.getOrNull() }

    fun isOurs(iconsId: String): Boolean =
        iconsMeta(iconsId)?.optJSONObject("titles")?.optString("fallback") in OUR_TITLES

    /**
     * The theme's own icons, for a theme that currently points at one of ours. Every icons
     * file records the theme it belongs to, so the original is the one naming [themeId]
     * as its parent that we did not write.
     */
    fun findOriginalIcons(themeId: String): String? =
        Shell.run("grep -l ${Shell.q(themeId)} $DATA/meta/icons/*.mrm 2>/dev/null").text.lines()
            .map { it.trim() }.filter { it.endsWith(".mrm") }
            .map { it.substringAfterLast('/').removeSuffix(".mrm") }
            .firstOrNull { !isOurs(it) }

    /** Points [theme]'s icons at [iconsId]. Returns false if the write did not land. */
    fun link(ctx: Context, theme: Theme, iconsId: String): Boolean {
        val json = JSONObject(theme.json.toString())
        val subs = json.getJSONArray("subResources")
        for (i in 0 until subs.length()) {
            val s = subs.getJSONObject(i)
            if (s.optString("resourceCode") == "icons") s.put("localId", iconsId)
        }
        val f = File(stage(ctx), "theme.mrm").apply { writeText(json.toString(2)) }
        val remote = "$DATA/meta/theme/${theme.id}.mrm"
        if (!put(f, remote)) return false
        return Shell.run("grep -c ${Shell.q(iconsId)} ${Shell.q(remote)}").text.trim().toIntOrNull()?.let { it > 0 } == true
    }

    /** The Themes app only rescans its library when it starts. */
    fun restartThemes() { Shell.run("am force-stop $THEMES_PKG") }
}
