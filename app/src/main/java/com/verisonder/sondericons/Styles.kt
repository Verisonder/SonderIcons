package com.verisonder.sondericons

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.sin

/**
 * Where a build's icons come from.
 *
 *  - THEME: the applied theme's icons; only apps it doesn't cover are drawn, in colours read
 *    from its icons ([StyleDetect]).
 *  - SET: a designed icon set shipped in the app; uncovered apps are drawn to match.
 *  - DRAWN: every app drawn on a background made here, from a [Shape].
 *  - PACK: an installed icon pack; uncovered apps are drawn on its iconback, or on a shape.
 */
enum class StyleKind { THEME, SET, DRAWN, PACK }

/** A background a person can design. Colours are ARGB ints. */
data class Shape(
    val form: String = "circle",            // circle | squircle | square | teardrop | hexagon | none
    val corner: Float = 0.3f,               // squircle, square, teardrop: radius as a share of the size
    val background: Int = 0xFF1C1C1C.toInt(),
    val glyph: Int = Color.WHITE,
    val outline: Boolean = false,
    val outlineColor: Int = Color.WHITE,
) {
    fun toJson() = JSONObject().put("form", form).put("corner", corner.toDouble()).put("bg", background)
        .put("glyph", glyph).put("outline", outline).put("outlineColor", outlineColor).toString()

    fun draw(size: Int = 180): Bitmap {
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        if (form == "none") return b
        val c = Canvas(b)
        val inset = size * 0.04f
        val path = path(size.toFloat(), inset)
        c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = background })
        if (outline) c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = size * 0.025f; color = outlineColor
        })
        return b
    }

    private fun path(s: Float, i: Float): Path = Path().apply {
        val r = (s - 2 * i) * corner
        when (form) {
            "squircle", "square" -> addRoundRect(i, i, s - i, s - i, r, r, Path.Direction.CW)
            "teardrop" -> addRoundRect(i, i, s - i, s - i,
                floatArrayOf(s, s, s, s, (s - 2 * i) * corner * 0.4f, (s - 2 * i) * corner * 0.4f, s, s), Path.Direction.CW)
            "hexagon" -> {
                val cx = s / 2; val rad = s / 2 - i
                for (k in 0 until 6) {
                    val a = Math.toRadians(60.0 * k - 90)
                    val x = cx + rad * cos(a).toFloat(); val y = cx + rad * sin(a).toFloat()
                    if (k == 0) moveTo(x, y) else lineTo(x, y)
                }
                close()
            }
            else -> addCircle(s / 2, s / 2, s / 2 - i, Path.Direction.CW)
        }
    }

    companion object {
        fun fromJson(s: String?): Shape = runCatching {
            val j = JSONObject(s!!)
            Shape(j.optString("form", "circle"), j.optDouble("corner", 0.3).toFloat(), j.optInt("bg", 0xFF1C1C1C.toInt()),
                j.optInt("glyph", Color.WHITE), j.optBoolean("outline", false), j.optInt("outlineColor", Color.WHITE))
        }.getOrDefault(Shape())
    }
}

class Style(
    val id: String,
    val label: String,
    val kind: StyleKind,
    val asset: String? = null,          // SET: zip in assets
    val shape: Shape = Shape(),         // DRAWN (and PACK without an iconback)
    val pack: String? = null,           // PACK: package name
) {
    val glyph: Int get() = shape.glyph
    fun pattern(size: Int = 180): Bitmap = shape.draw(size)

    companion object {
        private val BUILT_IN = listOf(
            Style("set-dark", "Dark Nothing", StyleKind.SET, asset = "styles/dark-circles.zip"),
            Style("theme", "My theme", StyleKind.THEME),
            Style("light", "Light circles", StyleKind.DRAWN, shape = Shape("circle", background = 0xFFF2F2F2.toInt(), glyph = 0xFF1C1C1C.toInt())),
            Style("squircle-dark", "Dark squircles", StyleKind.DRAWN, shape = Shape("squircle")),
            Style("squircle-light", "Light squircles", StyleKind.DRAWN, shape = Shape("squircle", background = 0xFFF2F2F2.toInt(), glyph = 0xFF1C1C1C.toInt())),
        )

        /** Built-ins, the person's own shape, then every installed icon pack. */
        fun all(ctx: Context): List<Style> =
            BUILT_IN + Style("custom", "Custom", StyleKind.DRAWN, shape = Prefs.customShape(ctx)) +
                IconPack.installed(ctx).map { Style("pack:${it.pkg}", it.label, StyleKind.PACK, pack = it.pkg, shape = Prefs.customShape(ctx)) }

        fun byId(ctx: Context, id: String?): Style = all(ctx).firstOrNull { it.id == id } ?: BUILT_IN.first()
    }
}

/** Reads a theme's look from its own icons, so the glyphs drawn for it match. */
object StyleDetect {
    /**
     * The glyph colour: across a sample of the theme's icons, the pixels that differ clearly
     * from the background pattern underneath them. Median of those, so a few coloured
     * accents don't decide it. White if nothing stands out.
     */
    fun glyphColor(icons: List<Bitmap>, pattern: Bitmap): Int {
        val rs = ArrayList<Int>(); val gs = ArrayList<Int>(); val bs = ArrayList<Int>()
        val pw = pattern.width; val ph = pattern.height
        for (icon in icons.take(60)) {
            val ic = if (icon.width == pw && icon.height == ph) icon else Bitmap.createScaledBitmap(icon, pw, ph, true)
            for (y in 0 until ph step 3) for (x in 0 until pw step 3) {
                val a = ic.getPixel(x, y); val b = pattern.getPixel(x, y)
                if (Color.alpha(a) < 200 || Color.alpha(b) < 200) continue
                val d = Math.abs(Color.red(a) - Color.red(b)) + Math.abs(Color.green(a) - Color.green(b)) + Math.abs(Color.blue(a) - Color.blue(b))
                if (d > 150) { rs += Color.red(a); gs += Color.green(a); bs += Color.blue(a) }
            }
        }
        if (rs.size < 50) return Color.WHITE
        rs.sort(); gs.sort(); bs.sort()
        return Color.rgb(rs[rs.size / 2], gs[gs.size / 2], bs[bs.size / 2])
    }
}
