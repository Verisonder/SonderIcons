package com.verisonder.sondericons

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import java.io.ByteArrayOutputStream

/**
 * Pinned shortcuts and icons the look has nothing for.
 *
 * A shortcut's picture comes from its app while the phone runs, so a theme can't give each
 * one its own icon. What the theme does set: the background shortcuts sit on
 * (`icon_shortcut.png`), the small arrow badge (`icon_shortcut_arrow.png`), and the filter
 * that turns any unknown icon into the theme's style (`transform_config.xml`).
 */
object Shortcuts {

    fun entries(ctx: Context, base: Builder.Base): Map<String, ByteArray> {
        val s = Prefs.shortcutStyle(ctx)
        val out = LinkedHashMap<String, ByteArray>()
        val size = base.pattern.width

        val back = if (s.background == "none") Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888) else base.pattern
        out[base.dir + "icon_shortcut.png"] = png(back)
        out[base.dir + "icon_shortcut_arrow.png"] = png(if (s.hideArrow) Bitmap.createBitmap(size / 3, size / 3, Bitmap.Config.ARGB_8888) else arrow(base, size / 3))

        val hasConfig = base.entries.keys.any { it.endsWith("transform_config.xml") }
        when {
            s.fallback == "solid" -> out["transform_config.xml"] = solidConfig(base.glyph).toByteArray()
            !hasConfig -> out["transform_config.xml"] = tracedConfig(base.glyph).toByteArray()   // drawn looks had none
        }
        return out
    }

    /** The badge: a dot in the glyph colour with an arrow in the background colour. */
    fun arrow(base: Builder.Base, size: Int): Bitmap {
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val center = base.pattern.getPixel(base.pattern.width / 2, base.pattern.height / 2)
        val bg = if (Color.alpha(center) > 128) center or 0xFF000000.toInt() else 0xFF1C1C1C.toInt()
        c.drawCircle(size / 2f, size / 2f, size / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = base.glyph })
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = bg; style = Paint.Style.STROKE; strokeWidth = size * 0.12f
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        val path = Path().apply {
            moveTo(size * 0.32f, size * 0.68f); lineTo(size * 0.66f, size * 0.34f)
            moveTo(size * 0.40f, size * 0.32f); lineTo(size * 0.68f, size * 0.32f); lineTo(size * 0.68f, size * 0.60f)
        }
        c.drawPath(path, p)
        return b
    }

    private fun hex(c: Int) = "#%08x".format(c)

    // Where the icon lands inside the theme's 90-unit box: the same margins the designed set uses.
    private const val POINTS = """
    <PointsMapping>
        <Point fromX="0.0" fromY="0.0" toX="15.0" toY="15.0"/>
        <Point fromX="0.0" fromY="90.0" toX="15.0" toY="75.0"/>
        <Point fromX="90.0" fromY="90.0" toX="75.0" toY="75.0"/>
        <Point fromX="90.0" fromY="0.0" toX="75.0" toY="15.0"/>
    </PointsMapping>"""

    /** HyperOS's own: edges traced, then dark becomes the glyph colour and light disappears. */
    fun tracedConfig(glyph: Int) = """<?xml version="1.0" encoding="UTF-8"?>
<IconTransform>$POINTS
    <IconFilters>
        <Filter name="Edges"/>
        <Filter name="GrayScale">
            <Param name="BlackColor" value="${hex(glyph)}"/>
            <Param name="WhiteColor" value="#00000000"/>
        </Filter>
    </IconFilters>
</IconTransform>
"""

    /**
     * A filled silhouette instead of an outline: each icon is split at its own median
     * brightness (Threshold, Uniform), and the darker half becomes the glyph.
     */
    fun solidConfig(glyph: Int) = """<?xml version="1.0" encoding="UTF-8"?>
<IconTransform>$POINTS
    <IconFilters>
        <Filter name="Threshold">
            <Param name="ThresholdLevel" value="128"/>
            <Param name="Uniform" value="true"/>
        </Filter>
        <Filter name="GrayScale">
            <Param name="BlackColor" value="${hex(glyph)}"/>
            <Param name="WhiteColor" value="#00000000"/>
        </Filter>
    </IconFilters>
</IconTransform>
"""

    private fun png(b: Bitmap) = ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
}
