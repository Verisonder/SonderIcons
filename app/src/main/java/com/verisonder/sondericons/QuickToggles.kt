package com.verisonder.sondericons

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import java.util.zip.ZipInputStream

/**
 * The big Quick settings buttons (Wi-Fi, data, torch, Bluetooth, aeroplane, hotspot, lock).
 * HyperOS takes them from the icons file as `status_bar_toggle_<name>_on/_off.png`; a file
 * without them falls back to the system's blue and grey. The glyphs come from the bundled
 * set; backgrounds and colours are drawn here.
 */
object QuickToggles {
    val NAMES = listOf("bluetooth", "data", "flight_mode", "torch", "wifi_ap", "wifi")

    private var masks: Map<String, Bitmap>? = null

    /** White-glyph masks cut from the bundled set's own toggles, once. */
    fun masks(ctx: Context): Map<String, Bitmap> = masks ?: run {
        val out = HashMap<String, Bitmap>()
        ZipInputStream(ctx.assets.open("styles/dark-circles.zip")).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val n = e.name.substringAfterLast('/')
                if (!n.startsWith("status_bar_toggle_") || !n.endsWith("_off.png") && n != "status_bar_toggle_lock.png") continue
                val b = z.readBytes().let { BitmapFactory.decodeByteArray(it, 0, it.size) } ?: continue
                val px = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
                for (i in px.indices) {
                    val c = px[i]
                    val m = minOf(Color.red(c), Color.green(c), Color.blue(c))
                    val a = ((m - 120) * 255 / 100).coerceIn(0, 255) * Color.alpha(c) / 255
                    px[i] = Color.argb(a, 255, 255, 255)
                }
                val key = n.removePrefix("status_bar_toggle_").removeSuffix("_off.png").removeSuffix(".png")
                out[key] = Bitmap.createBitmap(px, b.width, b.height, Bitmap.Config.ARGB_8888)
            }
        }
        out.also { masks = it }
    }

    /** One toggle image: [bg] shape with the glyph for [name] in [glyph]. */
    fun draw(ctx: Context, name: String, form: String, corner: Float, bg: Int, glyph: Int, size: Int = 180): Bitmap? {
        val mask = masks(ctx)[name] ?: return null
        val out = Shape(form, corner, background = bg).draw(size)
        val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply { colorFilter = PorterDuffColorFilter(glyph, PorterDuff.Mode.SRC_IN) }
        Canvas(out).drawBitmap(mask, null, android.graphics.Rect(0, 0, size, size), p)
        return out
    }

    /** The colours and shape toggles take when they follow the style. */
    fun followed(base: Builder.Base): Prefs.Toggles {
        val center = base.pattern.getPixel(base.pattern.width / 2, base.pattern.height / 2)
        val bg = if (Color.alpha(center) > 128) center or 0xFF000000.toInt() else 0xFF1C1C1C.toInt()
        val form = if (base.style.kind == StyleKind.DRAWN) base.style.shape.form.takeIf { it != "none" } ?: "circle" else "circle"
        return Prefs.Toggles(true, form, base.style.shape.corner, onBg = base.glyph, onGlyph = bg, offBg = bg, offGlyph = base.glyph)
    }

    /**
     * Toggle images for a build, by full entry name. Empty when the style's own toggles
     * should stand: a designed set or theme that ships them, and the person hasn't chosen
     * their own.
     */
    fun entries(ctx: Context, base: Builder.Base): Map<String, Bitmap> {
        val mine = Prefs.toggles(ctx)
        val shipped = base.entries.keys.any { it.contains("status_bar_toggle_") }
        if (!mine.custom && shipped && base.style.kind != StyleKind.DRAWN) return emptyMap()
        val t = if (mine.custom) mine else followed(base)
        val out = LinkedHashMap<String, Bitmap>()
        for (n in NAMES) {
            draw(ctx, n, t.form, t.corner, t.onBg, t.onGlyph)?.let { out[base.dir + "status_bar_toggle_${n}_on.png"] = it }
            draw(ctx, n, t.form, t.corner, t.offBg, t.offGlyph)?.let { out[base.dir + "status_bar_toggle_${n}_off.png"] = it }
        }
        draw(ctx, "lock", t.form, t.corner, t.offBg, t.offGlyph)?.let { out[base.dir + "status_bar_toggle_lock.png"] = it }
        return out
    }
}
