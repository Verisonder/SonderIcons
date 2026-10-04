package com.verisonder.sondericons

import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Process
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * One build: read the theme's own icons, add an icon for every app it does not cover (and
 * for every app the person customised), write the result next to the theme's, and point
 * "Theme backup" at it. Applying is left to the person, in the Themes app, with one tap.
 */
class Builder(private val ctx: Context) {

    class Base(val zip: ByteArray, val themed: Set<String>, val pattern: Bitmap, val dir: String, val target: Int)

    class Report(val made: Int, val needsOverride: List<String>, val error: String? = null)

    /** The theme's original icons, read from the Themes app's library (never our own build). */
    fun loadBase(theme: ThemeStore.Theme): Base? {
        val own = Prefs.ownIconsId(ctx)
        val origId = if (theme.iconsId != own) theme.iconsId.also { Prefs.setOriginalIconsId(ctx, it) }
                     else Prefs.originalIconsId(ctx) ?: return null
        val zip = ThemeStore.read("${ThemeStore.DATA}/content/icons/$origId.mrc") ?: return null
        var dir: String? = null; var pattern: Bitmap? = null
        val names = HashSet<String>()
        ZipInputStream(ByteArrayInputStream(zip)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val n = e.name
                if (n.endsWith("/icon_pattern.png")) dir = n.substringBeforeLast('/') + "/"
                names += n
            }
        }
        // second pass for the pattern bytes (ZipInputStream cannot rewind)
        ZipInputStream(ByteArrayInputStream(zip)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.name.endsWith("/icon_pattern.png")) { val b = z.readBytes(); pattern = BitmapFactory.decodeByteArray(b, 0, b.size); break }
            }
        }
        val d = dir ?: return null
        val p = pattern ?: return null
        val themed = names.filter { it.startsWith(d) && it.endsWith(".png") }
            .map { it.removePrefix(d).removeSuffix(".png") }.toSet()
        // the theme's own glyphs measured 58px on a 180px icon; scale with the pattern
        return Base(zip, themed, p, d, (p.width * 58f / 180f).toInt())
    }

    /** What this app would draw for [pkg], or null to leave the theme's icon/fallback alone. */
    fun iconFor(pkg: String, base: Base): Pair<Bitmap?, String> {
        when (Prefs.mode(ctx, pkg)) {
            Prefs.Mode.THEME -> return null to "theme"
            Prefs.Mode.CUSTOM -> {
                val f = Prefs.customFile(ctx, pkg)
                val b = if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
                val out = b?.let { GlyphEngine.fromImage(it, base.pattern, base.target) }
                return out to (if (out != null) "custom" else "custom image unusable")
            }
            Prefs.Mode.AUTO -> {
                if (pkg in base.themed) return null to "theme"
                val la = ctx.getSystemService(LauncherApps::class.java)
                val info = la.getActivityList(pkg, Process.myUserHandle()).firstOrNull()
                    ?: return null to "not launchable"
                val layers = GlyphEngine.layersOf(info.getIcon(0))
                val (mask, src) = GlyphEngine.pick(layers)
                return (mask?.let { GlyphEngine.render(it, base.pattern, base.target) }) to src
            }
        }
    }

    fun launchablePackages(): List<String> =
        ctx.getSystemService(LauncherApps::class.java)
            .getActivityList(null, Process.myUserHandle())
            .map { it.applicationInfo.packageName }
            .filter { it != ctx.packageName }
            .distinct().sorted()

    fun build(progress: (String) -> Unit): Report {
        if (!Shell.available()) return Report(0, emptyList(), "Shizuku is not ready")
        val own = Prefs.ownIconsId(ctx)
        progress("Finding Theme backup")
        val theme = ThemeStore.findBackupTheme(Prefs.themeId(ctx), own)
            ?: return Report(0, emptyList(), "No \"Theme backup\" found. Make one with Customize theme.")
        Prefs.setThemeId(ctx, theme.id)
        val base = loadBase(theme) ?: return Report(0, emptyList(), "Could not read the theme's icons")

        val made = LinkedHashMap<String, ByteArray>(); val needs = ArrayList<String>()
        val pkgs = launchablePackages()
        pkgs.forEachIndexed { i, pkg ->
            progress("Drawing ${i + 1} of ${pkgs.size}")
            val (bmp, src) = runCatching { iconFor(pkg, base) }.getOrElse { null to "error" }
            if (bmp != null) made[pkg] = png(bmp)
            else if (src != "theme" && src != "not launchable") needs += pkg
        }

        progress("Writing icons")
        val zip = rebuild(base, made)
        val stage = ThemeStore.stage(ctx)
        val mrc = File(stage, "$own.mrc").apply { writeBytes(zip) }
        val sha = ThemeStore.sha1(zip)
        val origId = Prefs.originalIconsId(ctx)!!
        val meta = ThemeStore.read("${ThemeStore.DATA}/meta/icons/$origId.mrm")
            ?.let { runCatching { JSONObject(String(it)) }.getOrNull() } ?: JSONObject()
        meta.put("localId", own).put("hash", sha).put("size", zip.size)
            .put("onlineId", JSONObject.NULL).put("rightsPath", JSONObject.NULL)
            .put("titles", JSONObject().put("fallback", "SonderIcons"))
            .put("parentResources", org.json.JSONArray().put(
                JSONObject().put("localId", theme.id).put("resourceCode", "theme").put("extraMeta", JSONObject())
                    .put("metaPath", JSONObject.NULL).put("contentPath", JSONObject.NULL)))
        val mrm = File(stage, "$own.mrm").apply { writeText(meta.toString(2)) }

        val remoteMrc = "${ThemeStore.DATA}/content/icons/$own.mrc"
        if (!ThemeStore.put(mrc, remoteMrc) || !ThemeStore.put(mrm, "${ThemeStore.DATA}/meta/icons/$own.mrm"))
            return Report(0, needs, "Could not write to the Themes app's folder")
        val check = Shell.run("sha1sum ${Shell.q(remoteMrc)}").text.trim().split(" ").firstOrNull()
        if (check != sha) return Report(0, needs, "The written file did not match. Nothing was linked.")

        progress("Linking Theme backup")
        if (theme.iconsId != own && !ThemeStore.link(ctx, theme, own))
            return Report(0, needs, "Could not link Theme backup")
        ThemeStore.restartThemes()
        return Report(made.size, needs)
    }

    /** Puts "Theme backup" back on the theme's own icons. */
    fun restore(): String? {
        if (!Shell.available()) return "Shizuku is not ready"
        val theme = ThemeStore.findBackupTheme(Prefs.themeId(ctx), Prefs.ownIconsId(ctx)) ?: return "No Theme backup found"
        val orig = Prefs.originalIconsId(ctx) ?: return "The original icons are not known"
        if (!ThemeStore.link(ctx, theme, orig)) return "Could not link the original icons"
        ThemeStore.restartThemes()
        return null
    }

    private fun png(b: Bitmap) = ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

    /** Every original entry kept, ours added; stored, not deflated, like the theme's own. */
    private fun rebuild(base: Base, made: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zo ->
            fun put(name: String, data: ByteArray) {
                val e = ZipEntry(name).apply {
                    method = ZipEntry.STORED; size = data.size.toLong(); compressedSize = size
                    crc = CRC32().also { it.update(data) }.value
                }
                zo.putNextEntry(e); zo.write(data); zo.closeEntry()
            }
            val replaced = made.keys.map { base.dir + it + ".png" }.toSet()
            ZipInputStream(ByteArrayInputStream(base.zip)).use { zi ->
                while (true) {
                    val e = zi.nextEntry ?: break
                    if (e.isDirectory || e.name in replaced) continue
                    put(e.name, zi.readBytes())
                }
            }
            for ((pkg, data) in made) put(base.dir + pkg + ".png", data)
        }
        return out.toByteArray()
    }
}
