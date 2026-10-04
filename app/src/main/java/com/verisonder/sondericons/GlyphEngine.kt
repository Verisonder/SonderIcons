package com.verisonder.sondericons

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Turns an app icon into a theme icon: a white glyph centred on the theme's own background.
 *
 * Candidates are tried best first and the first that passes [judge] wins:
 *   1. the app's monochrome layer (its designers' own glyph)
 *   2. the foreground layer's silhouette
 *   3. the logo separated by colour from the tile it is painted on (many apps put their whole
 *      old square icon inside a layer, which as a silhouette is just a square)
 *   4. the same on the full icon, then on a non-adaptive icon
 * Nothing passing means the theme's own fallback is left alone. A white square is worse.
 */
object GlyphEngine {
    const val N = 432          // working canvas: 108dp at 4x

    class Layers(val mono: Bitmap?, val fg: Bitmap?, val full: Bitmap?, val legacy: Bitmap?)

    fun layersOf(d: Drawable): Layers {
        if (d is AdaptiveIconDrawable) {
            val mono = d.monochrome?.let { draw(it) }
            val fg = d.foreground?.let { draw(it) }
            val full = Bitmap.createBitmap(N, N, Bitmap.Config.ARGB_8888).also { b ->
                val c = Canvas(b)
                d.background?.let { it.setBounds(0, 0, N, N); it.draw(c) }
                d.foreground?.let { it.setBounds(0, 0, N, N); it.draw(c) }
            }
            return Layers(mono, fg, full, null)
        }
        return Layers(null, null, null, draw(d))
    }

    private fun draw(d: Drawable): Bitmap {
        val b = Bitmap.createBitmap(N, N, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, N, N); d.draw(Canvas(b))
        return b
    }

    // ---------------- masks: FloatArray(N*N), 0..1 ----------------

    fun alphaMask(b: Bitmap): FloatArray {
        val px = pixels(b)
        return FloatArray(px.size) { min(1f, (px[it] ushr 24) / 255f / 0.6f) }
    }

    fun colorMask(b: Bitmap): FloatArray? = colorMask(b, 1f)

    fun colorMask(b: Bitmap, sensitivity: Float): FloatArray? {
        val px = pixels(b)
        val region = BooleanArray(px.size) { (px[it] ushr 24) > 127 }
        if (region.count { it } < 500) return null
        val inner = erode(region, 4)
        val outer = erode(region, 20)
        val band = BooleanArray(px.size) { region[it] && !outer[it] }
        if (band.count { it } < 50) return null

        // three colour clusters over the shape; the ones that own the outer band are the tile
        val idx = (px.indices).filter { region[it] }
        val step = max(1, idx.size / 5000)
        val sample = idx.filterIndexed { i, _ -> i % step == 0 }.map { rgb(px[it]) }
        val centers = kmeans(sample, 3)
        val share = IntArray(centers.size)
        var bandCount = 0
        for (i in px.indices) if (band[i]) { share[nearest(rgb(px[i]), centers)]++; bandCount++ }
        val bg = centers.filterIndexed { k, _ -> share[k] > bandCount * 0.25f }
            .ifEmpty { listOf(centers[share.indices.maxBy { share[it] }]) }

        return FloatArray(px.size) { i ->
            if (!inner[i]) 0f else {
                val c = rgb(px[i])
                val d = bg.minOf { dist(c, it) }
                ((d - 28f / sensitivity) / 40f).coerceIn(0f, 1f)
            }
        }
    }

    // ---------------- quality gate ----------------

    fun judge(m: FloatArray?, trusted: Boolean): String? {
        if (m == null) return "empty"
        var x0 = N; var y0 = N; var x1 = -1; var y1 = -1; var count = 0
        for (y in 0 until N) for (x in 0 until N) if (m[y * N + x] > 0.5f) {
            count++; if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
        }
        if (count < 300) return "empty"
        val w = x1 - x0 + 1; val h = y1 - y0 + 1
        val fill = count.toFloat() / (w * h)
        val s = 72
        val small = BooleanArray(s * s) { i ->
            val sx = x0 + (i % s) * w / s; val sy = y0 + (i / s) * h / s
            m[sy * N + sx] > 0.5f
        }
        val conv = small.count { it }.toFloat() / max(1f, hullArea(small, s))
        val (blobs, specks) = components(small, s)
        if (fill > 0.72f && conv > 0.93f) return "solid blob"
        if (trusted) return null
        if (blobs > 40 || specks > 25) return "noisy"
        var edges = 0
        for (y in 0 until s) for (x in 0 until s) {
            val v = small[y * s + x]
            if (x + 1 < s && v != small[y * s + x + 1]) edges++
            if (y + 1 < s && v != small[(y + 1) * s + x]) edges++
        }
        if (edges.toFloat() / max(1, small.count { it }) > 1.4f) return "noisy"
        return null
    }

    /** Returns the winning mask and its source name, or null with every reason it failed. */
    fun pick(l: Layers, t: Prefs.Tuning = Prefs.Tuning()): Pair<FloatArray?, String> {
        val cut: (Bitmap) -> FloatArray? = { colorMask(it, t.sensitivity) }
        val all = listOf<Triple<String, Bitmap?, (Bitmap) -> FloatArray?>>(
            Triple("mono", l.mono, ::alphaMask),
            Triple("mono-color", l.mono, cut),
            Triple("fg", l.fg, ::alphaMask),
            Triple("fg-color", l.fg, cut),
            Triple("icon-color", l.full, cut),
            Triple("legacy-color", l.legacy, cut),
            Triple("legacy", l.legacy, ::alphaMask),
        )
        val tries = when (t.source) {
            "glyph" -> all.filter { it.first.startsWith("mono") }
            "shape" -> all.filter { it.first == "fg" || it.first == "legacy" }
            "cutout" -> all.filter { it.first.endsWith("-color") }
            else -> all
        }
        // a forced source is the person's call: only an empty result is refused
        val forced = t.source != "auto"
        val why = mutableListOf<String>()
        for ((name, bmp, fn) in tries) {
            if (bmp == null) continue
            val m = runCatching { fn(bmp) }.getOrNull()
            val bad = judge(m, trusted = name == "mono" || forced)
            if (bad == null) return m to name
            why += "$name: $bad"
        }
        return null to why.joinToString("; ").ifEmpty { "no layers" }
    }

    // ---------------- rendering ----------------

    /**
     * Draws [m] white, centred on [pattern], sized to [target] px across (the theme's own
     * glyph size). Specks outside the main shape are ignored when sizing.
     */
    fun render(m: FloatArray, pattern: Bitmap, target: Int, t: Prefs.Tuning = Prefs.Tuning(), color: Int = Color.WHITE): Bitmap {
        val xs = ArrayList<Int>(); val ys = ArrayList<Int>()
        for (i in m.indices) if (m[i] > 0.16f) { xs += i % N; ys += i / N }
        xs.sort(); ys.sort()
        val pad = 4
        val x0 = max(0, xs[xs.size / 100] - pad); val x1 = min(N, xs[xs.size * 99 / 100] + pad)
        val y0 = max(0, ys[ys.size / 100] - pad); val y1 = min(N, ys[ys.size * 99 / 100] + pad)
        val w = max(1, x1 - x0); val h = max(1, y1 - y0)
        val crop = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val out = IntArray(w * h) { i ->
            val a = (m[(y0 + i / w) * N + x0 + i % w] * 255).roundToInt().coerceIn(0, 255)
            Color.argb(a, 255, 255, 255)
        }
        crop.setPixels(out, 0, w, 0, 0, w, h)
        val s = target.toFloat() / max(w, h)
        var g = shrink(crop, max(1, (w * s).roundToInt()), max(1, (h * s).roundToInt()))

        // thin strokes vanish next to the theme's glyphs; thicken them, unless it is a
        // dotted design, which thickening would melt together
        when (val st = t.stroke) {
            null -> {
                val gp = pixels(g)
                val solid = BooleanArray(gp.size) { (gp[it] ushr 24) > 127 }
                if (solid.count { it }.toFloat() / gp.size < 0.2f && components(solid, g.width, g.height).first <= 6) g = dilate(g)
            }
            else -> {
                repeat(maxOf(0, st)) { g = dilate(g) }
                repeat(maxOf(0, -st)) { g = thin(g) }
            }
        }
        if (t.crisp) g = crisp(g)
        val result = pattern.copy(Bitmap.Config.ARGB_8888, true)
        // the glyph is built as a white mask; the style's colour is applied here, once
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = android.graphics.PorterDuffColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN)
        }
        Canvas(result).drawBitmap(g, ((result.width - g.width) / 2f), ((result.height - g.height) / 2f), paint)
        return result
    }

    /** The first letter of the app's name, for icons with no usable shape (photos, game art). */
    fun letter(label: String, pattern: Bitmap, target: Int, t: Prefs.Tuning = Prefs.Tuning(), color: Int = Color.WHITE): Bitmap {
        val ch = label.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"
        val b = Bitmap.createBitmap(N, N, Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = N * 0.7f; textAlign = Paint.Align.CENTER
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, 600, false)
        }
        val y = N / 2f - (p.descent() + p.ascent()) / 2f
        Canvas(b).drawText(ch, N / 2f, y, p)
        // letters read heavier than glyphs at the same size, so they sit a little smaller
        return render(alphaMask(b), pattern, (target * 0.9f).toInt(), t, color)
    }

    /** A picture the person chose: its shape if it has transparency, else its logo by colour. */
    fun fromImage(b: Bitmap, pattern: Bitmap, target: Int, t: Prefs.Tuning = Prefs.Tuning(), color: Int = Color.WHITE): Bitmap? {
        val big = Bitmap.createScaledBitmap(b.copy(Bitmap.Config.ARGB_8888, false), N, N, true)
        val a = alphaMask(big)
        val m = if (judge(a, trusted = true) == null) a else colorMask(big, t.sensitivity)
        if (judge(m, trusted = true) != null) return null
        return render(m!!, pattern, target, t, color)
    }

    // ---------------- helpers ----------------

    /**
     * Shrinks by halving until close, then one last step. A single bilinear resize from
     * ~400px to ~58px samples a few pixels out of each block and smears strokes thick;
     * halving averages every pixel, which keeps edges as clean as a proper area filter.
     */
    private fun shrink(src: Bitmap, w: Int, h: Int): Bitmap {
        var b = src
        while (b.width / 2 >= w && b.height / 2 >= h) {
            b = Bitmap.createScaledBitmap(b, b.width / 2, b.height / 2, true)
        }
        return if (b.width == w && b.height == h) b else Bitmap.createScaledBitmap(b, w, h, true)
    }

    private fun pixels(b: Bitmap): IntArray {
        val bb = if (b.config == Bitmap.Config.ARGB_8888) b else b.copy(Bitmap.Config.ARGB_8888, false)
        return IntArray(bb.width * bb.height).also { bb.getPixels(it, 0, bb.width, 0, 0, bb.width, bb.height) }
    }

    private fun rgb(c: Int) = floatArrayOf(((c shr 16) and 255).toFloat(), ((c shr 8) and 255).toFloat(), (c and 255).toFloat())
    private fun dist(a: FloatArray, b: FloatArray) =
        sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]))
    private fun nearest(c: FloatArray, cs: List<FloatArray>) = cs.indices.minBy { dist(c, cs[it]) }

    private fun kmeans(xs: List<FloatArray>, k: Int): List<FloatArray> {
        if (xs.isEmpty()) return listOf(floatArrayOf(0f, 0f, 0f))
        val rnd = java.util.Random(0)
        val c = MutableList(min(k, xs.size)) { xs[rnd.nextInt(xs.size)].copyOf() }
        repeat(12) {
            val sum = Array(c.size) { FloatArray(3) }; val n = IntArray(c.size)
            for (x in xs) { val j = nearest(x, c); for (d in 0..2) sum[j][d] += x[d]; n[j]++ }
            for (j in c.indices) if (n[j] > 0) c[j] = FloatArray(3) { sum[j][it] / n[j] }
        }
        return c
    }

    /** Square erosion; the canvas edge counts as outside, so full-bleed layers still shrink. */
    private fun erode(m: BooleanArray, r: Int): BooleanArray {
        val tmp = BooleanArray(m.size); val out = BooleanArray(m.size)
        for (y in 0 until N) for (x in 0 until N) {
            var ok = x - r >= 0 && x + r < N
            if (ok) for (dx in -r..r) if (!m[y * N + x + dx]) { ok = false; break }
            tmp[y * N + x] = ok
        }
        for (y in 0 until N) for (x in 0 until N) {
            var ok = y - r >= 0 && y + r < N
            if (ok) for (dy in -r..r) if (!tmp[(y + dy) * N + x]) { ok = false; break }
            out[y * N + x] = ok
        }
        return out
    }

    private fun thin(b: Bitmap): Bitmap {
        val w = b.width; val h = b.height; val p = pixels(b)
        val o = IntArray(p.size) { i ->
            val x = i % w; val y = i / w; var a = 255
            for (dy in -1..1) for (dx in -1..1) {
                val nx = x + dx; val ny = y + dy
                a = if (nx in 0 until w && ny in 0 until h) min(a, p[ny * w + nx] ushr 24) else 0
            }
            // keep half of the original edge so strokes thin without breaking apart
            Color.argb((a + (p[i] ushr 24)) / 2, 255, 255, 255)
        }
        return Bitmap.createBitmap(o, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun crisp(b: Bitmap): Bitmap {
        val p = pixels(b)
        val o = IntArray(p.size) { if ((p[it] ushr 24) > 127) Color.WHITE else Color.TRANSPARENT }
        return Bitmap.createBitmap(o, b.width, b.height, Bitmap.Config.ARGB_8888)
    }

    private fun dilate(b: Bitmap): Bitmap {
        val w = b.width; val h = b.height; val p = pixels(b)
        val o = IntArray(p.size) { i ->
            val x = i % w; val y = i / w; var a = 0
            for (dy in -1..1) for (dx in -1..1) {
                val nx = x + dx; val ny = y + dy
                if (nx in 0 until w && ny in 0 until h) a = max(a, p[ny * w + nx] ushr 24)
            }
            Color.argb(a, 255, 255, 255)
        }
        return Bitmap.createBitmap(o, w, h, Bitmap.Config.ARGB_8888)
    }

    /** (blobs of 4+ px, specks) by 4-neighbour flood fill. */
    private fun components(b: BooleanArray, w: Int, h: Int = w): Pair<Int, Int> {
        val seen = BooleanArray(b.size); var blobs = 0; var specks = 0
        val stack = IntArray(b.size)
        for (i in b.indices) if (b[i] && !seen[i]) {
            var sp = 0; stack[sp++] = i; seen[i] = true; var size = 0
            while (sp > 0) {
                val c = stack[--sp]; size++
                val x = c % w; val y = c / w
                if (x > 0 && b[c - 1] && !seen[c - 1]) { seen[c - 1] = true; stack[sp++] = c - 1 }
                if (x < w - 1 && b[c + 1] && !seen[c + 1]) { seen[c + 1] = true; stack[sp++] = c + 1 }
                if (y > 0 && b[c - w] && !seen[c - w]) { seen[c - w] = true; stack[sp++] = c - w }
                if (y < h - 1 && b[c + w] && !seen[c + w]) { seen[c + w] = true; stack[sp++] = c + w }
            }
            if (size >= 4) blobs++ else specks++
        }
        return blobs to specks
    }

    private fun hullArea(b: BooleanArray, s: Int): Float {
        val pts = ArrayList<Pair<Int, Int>>()
        for (i in b.indices) if (b[i]) pts += (i % s) to (i / s)
        if (pts.size < 3) return 0f
        pts.sortWith(compareBy({ it.first }, { it.second }))
        fun cross(o: Pair<Int, Int>, a: Pair<Int, Int>, c: Pair<Int, Int>) =
            (a.first - o.first).toLong() * (c.second - o.second) - (a.second - o.second).toLong() * (c.first - o.first)
        val lo = ArrayList<Pair<Int, Int>>(); val up = ArrayList<Pair<Int, Int>>()
        for (p in pts) { while (lo.size >= 2 && cross(lo[lo.size - 2], lo.last(), p) <= 0) lo.removeAt(lo.size - 1); lo += p }
        for (p in pts.asReversed()) { while (up.size >= 2 && cross(up[up.size - 2], up.last(), p) <= 0) up.removeAt(up.size - 1); up += p }
        val hull = lo.dropLast(1) + up.dropLast(1)
        var a = 0L
        for (i in hull.indices) { val p = hull[i]; val q = hull[(i + 1) % hull.size]; a += p.first.toLong() * q.second - q.first.toLong() * p.second }
        return abs(a) / 2f
    }
}
