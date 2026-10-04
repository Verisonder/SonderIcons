package com.verisonder.sondericons

import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/**
 * Icon packs in the standard launcher format, as Icon Pack Studio and most packs ship:
 * an `appfilter.xml` mapping `ComponentInfo{package/activity}` to a drawable name, and
 * optionally `iconback` images to put behind apps the pack doesn't cover.
 */
class IconPack(private val ctx: Context, val pkg: String, val label: String) {
    private val res: Resources by lazy { ctx.packageManager.getResourcesForApplication(pkg) }

    /** component or package -> drawable name */
    private val filter: Map<String, String> by lazy { parseFilter() }
    private val backNames = mutableListOf<String>()
    /** iconback images, filled while appfilter is read. */
    val backs: List<String> get() { filter.size; return backNames }

    private fun parser(name: String): XmlPullParser? {
        val id = res.getIdentifier(name, "xml", pkg)
        if (id != 0) return res.getXml(id)
        return runCatching {
            val pc = ctx.createPackageContext(pkg, 0)
            XmlPullParserFactory.newInstance().newPullParser().apply { setInput(pc.assets.open("$name.xml"), "UTF-8") }
        }.getOrNull()
    }

    private fun parseFilter(): Map<String, String> {
        val out = HashMap<String, String>()
        val p = parser("appfilter") ?: return out
        runCatching {
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType != XmlPullParser.START_TAG) continue
                when (p.name) {
                    "item" -> {
                        val comp = p.getAttributeValue(null, "component") ?: continue
                        val draw = p.getAttributeValue(null, "drawable") ?: continue
                        val inner = comp.substringAfter("ComponentInfo{", "").substringBefore("}")
                        if (inner.isEmpty()) continue
                        out.putIfAbsent(inner, draw)
                        out.putIfAbsent(inner.substringBefore('/'), draw)   // package-level fallback
                    }
                    "iconback" -> for (i in 0 until p.attributeCount) backNames += p.getAttributeValue(i)
                }
            }
        }
        return out
    }

    fun covers(pkg: String, activity: String?): String? =
        activity?.let { filter["$pkg/$it"] ?: filter["$pkg/${it.removePrefix(pkg)}"] } ?: filter[pkg]

    fun drawable(name: String): Drawable? = runCatching {
        val id = res.getIdentifier(name, "drawable", pkg)
        if (id == 0) null else res.getDrawableForDensity(id, android.util.DisplayMetrics.DENSITY_XXXHIGH, null)
    }.getOrNull()

    fun bitmap(name: String, size: Int = 180): Bitmap? = drawable(name)?.let { d ->
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { b -> d.setBounds(0, 0, size, size); d.draw(Canvas(b)) }
    }

    /** Every icon the pack offers, for picking one by hand. Uses drawable.xml when present. */
    fun allNames(): List<String> {
        val names = LinkedHashSet<String>()
        parser("drawable")?.let { p ->
            runCatching {
                while (p.next() != XmlPullParser.END_DOCUMENT) {
                    if (p.eventType == XmlPullParser.START_TAG && p.name == "item")
                        p.getAttributeValue(null, "drawable")?.let { names += it }
                }
            }
        }
        if (names.isEmpty()) names += filter.values
        return names.toList()
    }

    companion object {
        private val ACTIONS = listOf("org.adw.launcher.THEMES", "com.novalauncher.THEME", "com.gau.go.launcherex.theme")

        fun installed(ctx: Context): List<IconPack> {
            val pm = ctx.packageManager
            return ACTIONS.flatMap { a -> pm.queryIntentActivities(Intent(a), 0).map { it.activityInfo.packageName } }
                .distinct().map { p ->
                    IconPack(ctx, p, runCatching { pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString() }.getOrDefault(p))
                }.sortedBy { it.label.lowercase() }
        }
    }
}
