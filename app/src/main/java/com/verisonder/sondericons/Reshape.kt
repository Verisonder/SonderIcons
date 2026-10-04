package com.verisonder.sondericons

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import kotlin.math.abs

/**
 * Gives a designed icon a new background without touching its design: the glyph is lifted
 * off the old background and laid, same place and size, on the new one.
 */
object Reshape {

    /**
     * The glyph of [icon], as its own pixels with everything else transparent.
     * With [pattern] (a theme's blank background) the glyph is whatever differs from it.
     * Without one (icon packs), a mostly transparent icon is its own glyph; one with its own
     * tile has the tile cut away by colour.
     */
    fun glyph(icon: Bitmap, pattern: Bitmap?): Bitmap? {
        val w = icon.width; val h = icon.height
        val px = IntArray(w * h).also { icon.getPixels(it, 0, w, 0, 0, w, h) }
        val out = IntArray(px.size)
        if (pattern != null) {
            val pt = if (pattern.width == w && pattern.height == h) pattern else Bitmap.createScaledBitmap(pattern, w, h, true)
            val pp = IntArray(w * h).also { pt.getPixels(it, 0, w, 0, 0, w, h) }
            for (i in px.indices) {
                val a = px[i]; val b = pp[i]
                val m = if (Color.alpha(b) > 200) {
                    val d = abs(Color.red(a) - Color.red(b)) + abs(Color.green(a) - Color.green(b)) + abs(Color.blue(a) - Color.blue(b))
                    ((d - 40) / 60f).coerceIn(0f, 1f)
                } else if (Color.alpha(b) < 30) Color.alpha(a) / 255f else 0f   // glyph parts outside the old shape
                out[i] = Color.argb((m * Color.alpha(a)).toInt().coerceIn(0, 255), Color.red(a), Color.green(a), Color.blue(a))
            }
        } else {
            val transparent = px.count { Color.alpha(it) < 128 }
            if (transparent > px.size * 0.3f) return icon            // already a bare glyph
            val big = Bitmap.createScaledBitmap(icon, GlyphEngine.N, GlyphEngine.N, true)
            val mask = GlyphEngine.colorMask(big) ?: return null
            for (i in px.indices) {
                val x = i % w * GlyphEngine.N / w; val y = i / w * GlyphEngine.N / h
                val m = mask[y * GlyphEngine.N + x]
                out[i] = Color.argb((m * Color.alpha(px[i])).toInt(), Color.red(px[i]), Color.green(px[i]), Color.blue(px[i]))
            }
        }
        if (out.count { Color.alpha(it) > 128 } < 30) return null
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    /** [glyph] on [shape]'s background, recoloured to the shape's glyph colour if [recolor]. */
    fun onto(glyph: Bitmap, shape: Shape, recolor: Boolean): Bitmap {
        val out = shape.draw(glyph.width)
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        if (recolor) p.colorFilter = PorterDuffColorFilter(shape.glyph, PorterDuff.Mode.SRC_IN)
        Canvas(out).drawBitmap(glyph, 0f, 0f, p)
        return out
    }
}
