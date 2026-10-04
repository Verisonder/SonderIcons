package com.verisonder.sondericons

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

/**
 * Where a build's icons come from.
 *
 *  - THEME: the applied theme's own icons; only apps it doesn't cover are drawn, in the
 *    colours read from its icons ([StyleDetect]).
 *  - SET: a designed icon set shipped in the app; apps it doesn't cover are drawn to match.
 *  - DRAWN: no designed icons at all; every app is drawn on a background made here.
 */
enum class StyleKind { THEME, SET, DRAWN }

class Style(
    val id: String,
    val label: String,
    val kind: StyleKind,
    val asset: String? = null,          // SET: zip in assets
    val background: Int = 0,            // DRAWN: background colour
    val glyph: Int = Color.WHITE,       // DRAWN/SET: glyph colour (THEME detects its own)
    val squircle: Boolean = false,      // DRAWN: shape
) {
    /** A 180px background for DRAWN styles, the size HyperOS theme icons are drawn at. */
    fun pattern(size: Int = 180): Bitmap {
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = background }
        val c = Canvas(b)
        val inset = size * 0.04f
        if (squircle) c.drawRoundRect(inset, inset, size - inset, size - inset, size * 0.3f, size * 0.3f, p)
        else c.drawCircle(size / 2f, size / 2f, size / 2f - inset, p)
        return b
    }

    companion object {
        val ALL = listOf(
            Style("set-dark", "Dark circles", StyleKind.SET, asset = "styles/dark-circles.zip"),
            Style("theme", "My theme", StyleKind.THEME),
            Style("light", "Light circles", StyleKind.DRAWN, background = 0xFFF2F2F2.toInt(), glyph = 0xFF1C1C1C.toInt()),
            Style("squircle-dark", "Dark squircles", StyleKind.DRAWN, background = 0xFF1C1C1C.toInt(), squircle = true),
            Style("squircle-light", "Light squircles", StyleKind.DRAWN, background = 0xFFF2F2F2.toInt(), glyph = 0xFF1C1C1C.toInt(), squircle = true),
        )
        fun byId(id: String?) = ALL.firstOrNull { it.id == id } ?: ALL.first()
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
