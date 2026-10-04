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
    class Base(
        val entries: LinkedHashMap<String, ByteArray>, val themed: Set<String>, val pattern: Bitmap,
        val dir: String, val target: Int, val glyph: Int, val style: Style,
    ) {
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

    /**
     * The theme's own icons file id, remembered for restoring. Never one we wrote:
     * building on our own output would keep icons of apps since uninstalled.
     */
    fun originalIcons(theme: ThemeStore.Theme): String? {
        val current = theme.iconsId
        val id = if (current != Prefs.ownIconsId(ctx) && !ThemeStore.isOurs(current)) current
                 else Prefs.originalIconsId(ctx)?.takeIf { !ThemeStore.isOurs(it) } ?: ThemeStore.findOriginalIcons(theme.id)
        id?.let { Prefs.setOriginalIconsId(ctx, it) }
        return id
    }

    class Unsupported(message: String) : Exception(message)

    /** The starting icon set for [style]. Throws [Unsupported] with a reason a person can act on. */
    fun loadBase(theme: ThemeStore.Theme, style: Style = Style.byId(ctx, Prefs.styleId(ctx))): Base {
        val origId = originalIcons(theme)
        val entries = LinkedHashMap<String, ByteArray>()
        fun unzip(bytes: ByteArray) = ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) { val e = z.nextEntry ?: break; if (!e.isDirectory) entries[e.name] = z.readBytes() }
        }
        when (style.kind) {
            StyleKind.SET -> unzip(ctx.assets.open(style.asset!!).use { it.readBytes() })
            StyleKind.THEME -> unzip(origId?.let { ThemeStore.read("${ThemeStore.DATA}/content/icons/$it.mrc") }
                ?: throw Unsupported("Couldn't read your theme's icons."))
            StyleKind.DRAWN, StyleKind.PACK -> {}
        }
        // an icon pack: its icon for every app it covers, drawn into the set at theme size
        val pack = style.pack?.let { p -> IconPack.installed(ctx).firstOrNull { it.pkg == p } }
        if (style.kind == StyleKind.PACK && pack == null) throw Unsupported("That icon pack isn't installed any more.")
        val dir = entries.keys.firstOrNull { it.endsWith("/icon_pattern.png") }?.substringBeforeLast('/')?.plus("/")
            ?: "res/drawable-xxhdpi/"
        val pattern: Bitmap = when (style.kind) {
            StyleKind.DRAWN -> style.pattern()
            StyleKind.PACK -> when (Prefs.packBack(ctx)) {
                "none" -> Shape("none").draw()
                "shape" -> Prefs.customShape(ctx).draw()
                else -> pack!!.backs.firstNotNullOfOrNull { pack.bitmap(it) } ?: Prefs.customShape(ctx).draw()
            }
            else -> entries[dir + "icon_pattern.png"]?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                ?: throw Unsupported("Your theme has no plain icon background to draw on. Pick another style.")
        }
        // drawn styles and packs ship their background too, so folders and anything uncovered match
        if (style.kind == StyleKind.DRAWN || style.kind == StyleKind.PACK) entries[dir + "icon_pattern.png"] = png(pattern)
        if (pack != null) {
            val la = ctx.getSystemService(LauncherApps::class.java)
            for (info in la.getActivityList(null, Process.myUserHandle())) {
                val p = info.applicationInfo.packageName
                val name = pack.covers(p, info.componentName.className) ?: continue
                pack.bitmap(name, pattern.width)?.let { entries.putIfAbsent(dir + p + ".png", png(it)) }
            }
        }
        val themed = if (style.kind == StyleKind.DRAWN) emptySet()
            else entries.keys.filter { it.startsWith(dir) && it.endsWith(".png") && !it.substringAfterLast('/').startsWith("icon_") }
                .map { it.removePrefix(dir).removeSuffix(".png") }.toSet()
        val glyph = when (style.kind) {
            // on a pack's iconback: white or black, whichever reads on its centre
            StyleKind.PACK -> if (Prefs.packBack(ctx) != "pack" || pack!!.backs.isEmpty()) Prefs.customShape(ctx).glyph else {
                val c = pattern.getPixel(pattern.width / 2, pattern.height / 2)
                val lum = 0.299 * android.graphics.Color.red(c) + 0.587 * android.graphics.Color.green(c) + 0.114 * android.graphics.Color.blue(c)
                if (android.graphics.Color.alpha(c) > 128 && lum > 150) 0xFF1C1C1C.toInt() else android.graphics.Color.WHITE
            }
            StyleKind.THEME -> StyleDetect.glyphColor(
                themed.shuffled(java.util.Random(1)).take(60).mapNotNull { n ->
                    entries[dir + n + ".png"]?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                }, pattern)
            else -> style.glyph
        }
        // the designed glyphs measured 58px on a 180px icon; scale with the background
        val base = Base(entries, themed, pattern, dir, (pattern.width * 58f / 180f).toInt(), glyph, style)
        val rs = Prefs.reshaping(ctx)
        return if (rs.on && style.kind != StyleKind.DRAWN) reshaped(base, rs) else base
    }

    /**
     * Every designed icon of [base] moved onto the chosen shape, and the shape becomes the
     * background drawn icons sit on. An icon whose glyph can't be lifted keeps its own look.
     */
    private fun reshaped(base: Base, rs: Prefs.Reshaping): Base {
        val size = base.pattern.width
        val newPattern = rs.shape.draw(size)
        // pack icons aren't drawn on the pack's background, so there's nothing to compare against
        val old: Bitmap? = base.pattern.takeIf { base.style.kind != StyleKind.PACK }
        val entries = LinkedHashMap(base.entries)
        for (pkg in base.themed) {
            val name = base.dir + pkg + ".png"
            val icon = entries[name]?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } ?: continue
            val g = runCatching { Reshape.glyph(icon, old) }.getOrNull() ?: continue
            entries[name] = png(Reshape.onto(g, rs.shape, rs.recolor))
        }
        entries[base.dir + "icon_pattern.png"] = png(newPattern)
        val glyph = if (rs.recolor) rs.shape.glyph else base.glyph
        return Base(entries, base.themed, newPattern, base.dir, base.target, glyph, base.style)
    }

    /** What [pkg] will look like after a build, with a reason a person can read. */
    fun resultFor(pkg: String, base: Base): Result {
        val target = (base.target * Prefs.scale(ctx, pkg) * Prefs.globalScale(ctx)).toInt()
        val t = Prefs.tuning(ctx, pkg)
        return when (Prefs.mode(ctx, pkg)) {
            Prefs.Mode.THEME -> Result(base.themeIcon(pkg), Kind.THEME,
                if (pkg in base.themed) "Designed icon" else "Left to the theme")
            Prefs.Mode.LETTER -> Result(GlyphEngine.letter(label(pkg), base.pattern, target, t, base.glyph), Kind.CUSTOM, "First letter of its name")
            Prefs.Mode.CUSTOM -> {
                val f = Prefs.customFile(ctx, pkg)
                val b = if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
                if (b != null && Prefs.asIs(ctx, pkg)) return Result(whole(b, base.pattern.width), Kind.CUSTOM, "Your image, as it is")
                val out = b?.let { GlyphEngine.fromImage(it, base.pattern, target, t, base.glyph) }
                if (out != null) Result(out, Kind.CUSTOM, "Your image")
                else Result(null, Kind.MISSING, "That image has no clear shape. Try one with a transparent background.")
            }
            Prefs.Mode.AUTO -> {
                if (pkg in base.themed) return Result(base.themeIcon(pkg), Kind.THEME, "Designed icon")
                val la = ctx.getSystemService(LauncherApps::class.java)
                val info = la.getActivityList(pkg, Process.myUserHandle()).firstOrNull()
                    ?: return Result(null, Kind.MISSING, "Not on the home screen")
                val (mask, src) = GlyphEngine.pick(GlyphEngine.layersOf(rawIcon(info.activityInfo) ?: info.getIcon(0)), t)
                if (mask == null) Result(null, Kind.MISSING, "No clear shape found. Pick an image for it.")
                else Result(GlyphEngine.render(mask, base.pattern, target, t, base.glyph), Kind.DRAWN, when (src) {
                    "mono", "mono-color" -> "From its monochrome icon"
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
            .distinct().sorted()

    fun build(progress: (String) -> Unit): Report {
        if (!Shell.available()) return Report(0, emptyList(), "Shizuku is not ready")
        val own = Prefs.ownIconsId(ctx)
        progress("Finding your theme")
        val t = target()
        val theme = t.theme ?: return Report(0, emptyList(),
            if (t.all.isEmpty()) "No Theme backup found. In Themes, open Customize theme and save once."
            else "Choose which Theme backup to change.")
        val base = try { loadBase(theme) } catch (e: Unsupported) { return Report(0, emptyList(), e.message) }

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

        progress("Drawing quick toggles")
        val toggles = runCatching { QuickToggles.entries(ctx, base).mapValues { png(it.value) } }.getOrDefault(emptyMap())

        progress("Writing icons")
        val zip = rebuild(base, made, toggles)
        val stage = ThemeStore.stage(ctx)
        val mrc = File(stage, "$own.mrc").apply { writeBytes(zip) }
        val sha = ThemeStore.sha1(zip)
        val origId = Prefs.originalIconsId(ctx)
        val meta = origId?.let { ThemeStore.read("${ThemeStore.DATA}/meta/icons/$it.mrm") }
            ?.let { runCatching { JSONObject(String(it)) }.getOrNull() } ?: JSONObject().put("platform", 15).put("version", "1.0")
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

    /** A finished icon, fitted into the icon square without cropping. */
    private fun whole(b: Bitmap, size: Int): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val sc = minOf(size.toFloat() / b.width, size.toFloat() / b.height)
        val w = b.width * sc; val h = b.height * sc
        android.graphics.Canvas(out).drawBitmap(b, null,
            android.graphics.RectF((size - w) / 2, (size - h) / 2, (size + w) / 2, (size + h) / 2),
            android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        return out
    }

    private fun png(b: Bitmap) = ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

    /** Every original entry kept, ours added; stored, not deflated, like the theme's own. */
    private fun rebuild(base: Base, made: Map<String, ByteArray>, extra: Map<String, ByteArray> = emptyMap()): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zo ->
            fun put(name: String, data: ByteArray) {
                val e = ZipEntry(name).apply {
                    method = ZipEntry.STORED; size = data.size.toLong(); compressedSize = size
                    crc = CRC32().also { it.update(data) }.value
                }
                zo.putNextEntry(e); zo.write(data); zo.closeEntry()
            }
            val replaced = made.keys.map { base.dir + it + ".png" }.toSet() + extra.keys
            for ((name, data) in base.entries) if (name !in replaced) put(name, data)
            for ((pkg, data) in made) put(base.dir + pkg + ".png", data)
            for ((name, data) in extra) put(name, data)
            put("sondericons.json", "{\"app\":\"SonderIcons\",\"icons\":${made.size}}".toByteArray())
        }
        return out.toByteArray()
    }
}
