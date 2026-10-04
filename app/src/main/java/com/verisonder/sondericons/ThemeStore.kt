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

    class Theme(val id: String, val iconsId: String, val json: JSONObject)

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

    /**
     * Finds the "Theme backup" this phone is using.
     *
     * There can be several. The right one is whichever owns the icons that are applied
     * now, matched by SHA-1 against `/data/system/theme/icons`, with [knownId] (the one this
     * app linked last time) as the next best answer.
     */
    fun findBackupTheme(knownId: String?, ownIconsId: String?): Theme? {
        val list = Shell.run("grep -l '\"Theme backup\"' $DATA/meta/theme/*.mrm 2>/dev/null").text
            .lines().map { it.trim() }.filter { it.endsWith(".mrm") }
        val themes = list.mapNotNull { path ->
            val json = runCatching { JSONObject(String(read(path) ?: return@mapNotNull null)) }.getOrNull()
                ?: return@mapNotNull null
            val icons = iconsIdOf(json) ?: return@mapNotNull null
            Theme(json.optString("localId"), icons, json)
        }
        if (themes.isEmpty()) return null
        val applied = Shell.run("sha1sum $APPLIED_ICONS").text.trim().split(" ").firstOrNull()
        if (!applied.isNullOrEmpty()) {
            themes.firstOrNull { t ->
                val meta = read("$DATA/meta/icons/${t.iconsId}.mrm")?.let { runCatching { JSONObject(String(it)) }.getOrNull() }
                meta?.optString("hash") == applied
            }?.let { return it }
        }
        return themes.firstOrNull { it.id == knownId }
            ?: themes.firstOrNull { it.iconsId == ownIconsId }
            ?: themes.first()
    }

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
