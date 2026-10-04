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

    /** The theme's original icons file, entry by entry, in order. */
    class Base(val entries: LinkedHashMap<String, ByteArray>, val themed: Set<String>, val pattern: Bitmap, val dir: String, val target: Int) {
        fun themeIcon(pkg: String): Bitmap? = entries[dir + pkg + ".png"]?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
    }

    /** What an app will look like, and why. */
    enum class Kind { DRAWN, CUSTOM, THEME, MISSING }
    class Result(val bitmap: Bitmap?, val kind: Kind, val note: String)

    class Report(val made: Int, val needsOverride: List<String>, val error: String? = null)

    /** Which theme a build would change, and whether that had to be chosen by hand. */
    class Target(val theme: ThemeStore.Theme?, val onScreen: Boolean, val all: List<ThemeStore.Theme>)

    /**
     * The one on screen wins. Otherwise only a theme the person picked, never a guess:
     * changing a backup that isn't applied does nothing visible and looks like a failure.
     */
    fun target(): Target {
        val l = ThemeStore.lookup(Prefs.ownIconsId(ctx), Prefs.originalIconsId(ctx))
        if (l.applied != null) return Target(l.applied, true, l.themes)
        val chosen = l.themes.firstOrNull { it.id == Prefs.chosenThemeId(ctx) }
        return Target(chosen, false, l.themes)
    }

    /** The theme's original icons, read from the Themes app's library (never our own build). */
    fun loadBase(theme: ThemeStore.Theme): Base? {
        // Start from the designer's icons, never from a file we wrote: building on our own
        // output would keep icons from apps since uninstalled and count ours as the theme's.
        val current = theme.iconsId
        val origId = if (current != Prefs.ownIconsId(ctx) && !ThemeStore.isOurs(current)) current
                     else Prefs.originalIconsId(ctx)?.takeIf { !ThemeStore.isOurs(it) }
                         ?: ThemeStore.findOriginalIcons(theme.id) ?: return null
        Prefs.setOriginalIconsId(ctx, origId)
        val zip = ThemeStore.read("${ThemeStore.DATA}/content/icons/$origId.mrc") ?: return null
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(zip)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (!e.isDirectory) entries[e.name] = z.readBytes()
            }
        }
        val patternName = entries.keys.firstOrNull { it.endsWith("/icon_pattern.png") } ?: return null
        val dir = patternName.substringBeforeLast('/') + "/"
        val pb = entries.getValue(patternName)
        val pattern = BitmapFactory.decodeByteArray(pb, 0, pb.size) ?: return null
        val themed = entries.keys.filter { it.startsWith(dir) && it.endsWith(".png") }
            .map { it.removePrefix(dir).removeSuffix(".png") }.toSet()
        // the theme's own glyphs measured 58px on a 180px icon; scale with the pattern
        return Base(entries, themed, pattern, dir, (pattern.width * 58f / 180f).toInt())
    }

    /** What [pkg] will look like after a build, with a reason a person can read. */
    fun resultFor(pkg: String, base: Base): Result {
        val target = (base.target * Prefs.scale(ctx, pkg) * Prefs.globalScale(ctx)).toInt()
        return when (Prefs.mode(ctx, pkg)) {
            Prefs.Mode.THEME -> Result(base.themeIcon(pkg), Kind.THEME,
                if (pkg in base.themed) "Theme icon" else "Theme's traced outline")
            Prefs.Mode.LETTER -> Result(GlyphEngine.letter(label(pkg), base.pattern, target), Kind.CUSTOM, "First letter of its name")
            Prefs.Mode.CUSTOM -> {
                val f = Prefs.customFile(ctx, pkg)
                val b = if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
                val out = b?.let { GlyphEngine.fromImage(it, base.pattern, target) }
                if (out != null) Result(out, Kind.CUSTOM, "Your image")
                else Result(null, Kind.MISSING, "That image has no clear shape. Try one with a transparent background.")
            }
            Prefs.Mode.AUTO -> {
                if (pkg in base.themed) return Result(base.themeIcon(pkg), Kind.THEME, "Theme icon")
                val la = ctx.getSystemService(LauncherApps::class.java)
                val info = la.getActivityList(pkg, Process.myUserHandle()).firstOrNull()
                    ?: return Result(null, Kind.MISSING, "Not on the home screen")
                val (mask, src) = GlyphEngine.pick(GlyphEngine.layersOf(rawIcon(info.activityInfo) ?: info.getIcon(0)))
                if (mask == null) Result(null, Kind.MISSING, "No clear shape found. Pick an image for it.")
                else Result(GlyphEngine.render(mask, base.pattern, target), Kind.DRAWN, when (src) {
                    "mono" -> "From its monochrome icon"
                    "fg" -> "From its icon's shape"
                    else -> "Cut out of its icon"
                })
            }
        }
    }

    /**
     * The icon as the app ships it. HyperOS themes icons inside PackageManager itself, so
     * getIcon() and loadIcon() hand back the theme's traced fallback for apps the theme
     * does not cover. Reading the drawable from the app's own resources skips that.
     */
    private fun rawIcon(ai: android.content.pm.ActivityInfo): android.graphics.drawable.Drawable? = runCatching {
        val res = ctx.packageManager.getResourcesForApplication(ai.applicationInfo)
        val id = ai.iconResource.takeIf { it != 0 } ?: ai.applicationInfo.icon
        if (id == 0) null else res.getDrawableForDensity(id, android.util.DisplayMetrics.DENSITY_XXXHIGH, null)
    }.getOrNull()

    fun label(pkg: String): String = runCatching {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    /** The app's own icon, for the sheet. */
    fun appIcon(pkg: String): Bitmap? = runCatching {
        val ai = ctx.getSystemService(LauncherApps::class.java)
            .getActivityList(pkg, Process.myUserHandle()).firstOrNull()?.activityInfo
        val d = ai?.let { rawIcon(it) } ?: ctx.packageManager.getApplicationIcon(pkg)
        Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888).also { b ->
            d.setBounds(0, 0, 192, 192); d.draw(android.graphics.Canvas(b))
        }
    }.getOrNull()

    fun launchablePackages(): List<String> =
        ctx.getSystemService(LauncherApps::class.java)
            .getActivityList(null, Process.myUserHandle())
            .map { it.applicationInfo.packageName }
            .filter { it != ctx.packageName }
            .distinct().sorted()

    fun build(progress: (String) -> Unit): Report {
        if (!Shell.available()) return Report(0, emptyList(), "Shizuku is not ready")
        val own = Prefs.ownIconsId(ctx)
        progress("Finding your theme")
        val t = target()
        val theme = t.theme ?: return Report(0, emptyList(),
            if (t.all.isEmpty()) "No Theme backup found. In Themes, open Customize theme and save once."
            else "Choose which Theme backup to change.")
        val base = loadBase(theme) ?: return Report(0, emptyList(), "Could not read the theme's icons")

        val made = LinkedHashMap<String, ByteArray>(); val needs = ArrayList<String>()
        val pkgs = launchablePackages()
        pkgs.forEachIndexed { i, pkg ->
            progress("Drawing ${i + 1} of ${pkgs.size}")
            val r = runCatching { resultFor(pkg, base) }.getOrNull()
            when (r?.kind) {
                Kind.DRAWN, Kind.CUSTOM -> made[pkg] = png(r.bitmap!!)
                Kind.MISSING -> needs += pkg
                else -> {}
            }
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
        ThemeStore.removeStale(own)
        ThemeStore.restartThemes()
        return Report(made.size, needs)
    }

    /** Puts "Theme backup" back on the theme's own icons. */
    fun restore(): String? {
        if (!Shell.available()) return "Shizuku is not ready"
        val theme = target().theme ?: return "Choose which Theme backup to change first"
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
            for ((name, data) in base.entries) if (name !in replaced) put(name, data)
            for ((pkg, data) in made) put(base.dir + pkg + ".png", data)
            put("sondericons.json", "{\"app\":\"SonderIcons\",\"icons\":${made.size}}".toByteArray())
        }
        return out.toByteArray()
    }
}
