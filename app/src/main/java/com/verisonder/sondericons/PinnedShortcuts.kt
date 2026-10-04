package com.verisonder.sondericons

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.PersistableBundle
import android.util.Base64

/**
 * Shortcuts apps have pinned to the home screen, and themed copies of them.
 *
 * A theme can't give one shortcut its own icon: HyperOS has no file name for it. What can be
 * done is to pin a copy that opens exactly the same thing with an icon drawn here, and
 * remove the original. `dumpsys shortcut` (through Shizuku) reports each shortcut's full
 * intent, extras included, so the copy behaves like the original: a Brave web app copy
 * still opens standalone, because its extras (and the code Brave checks them with) are
 * carried over unchanged.
 */
object PinnedShortcuts {

    class Extra(val key: String, val raw: String)

    /** One intent of a shortcut. Some shortcuts carry several: a back stack, the last on top. */
    class Raw(
        val action: String?, val component: String?, val target: String?, val data: String?,
        val categories: List<String>, val flags: Int, val extras: List<Extra>,
    )

    class Pinned(val pkg: String, val id: String, val label: String, val intents: List<Raw>, val iconPng: ByteArray?, val iconRes: Int) {
        val key: String get() = "shortcut:$pkg:$id"
    }

    fun read(ownPackage: String): List<Pinned> {
        val text = Shell.run("dumpsys shortcut").text.replace("\r", "")
        val out = ArrayList<Pinned>()
        var i = text.indexOf("ShortcutInfo {id=")
        while (i >= 0) {
            val next = text.indexOf("ShortcutInfo {id=", i + 1)
            val block = text.substring(i, if (next < 0) text.length else next)
            parse(block)?.takeIf { it.pkg != ownPackage }?.let { p -> if (out.none { it.key == p.key }) out += p }
            i = next
        }
        return out.sortedBy { it.label.lowercase() }
    }

    private fun field(block: String, name: String): String? =
        Regex("""\n\s*$name=([^\n]*)""").find(block)?.groupValues?.get(1)

    private fun parse(block: String): Pinned? {
        val head = block.substringBefore('\n')
        val flags = Regex("""\[([^\]]*)\]""").find(head)?.groupValues?.get(1) ?: return null
        if (!flags.startsWith("Pin") && !flags.contains("-Pin")) return null
        val id = head.substringAfter("id=").substringBefore(", flags")
        val pkg = field(block, "packageName") ?: return null
        val label = field(block, "shortLabel")?.substringBefore(", resId=") ?: id
        // the intents field may wrap (a base64 icon spans many lines); it ends where iconRes starts
        val intentsRaw = block.substringAfter("intents=", "").substringBefore("\n            iconRes=")
            .replace(Regex("""\n\s*"""), "")
        val intents = intentsRaw.split("Intent { ").drop(1).map { part ->
            val first = part.substringBefore(" }")
            fun tok(name: String) = Regex("""(?:^| )$name=([^ ]+)""").find(first)?.groupValues?.get(1)
            val cats = Regex("""cat=\[([^\]]*)\]""").find(first)?.groupValues?.get(1)?.split(',')?.map { it.trim() } ?: emptyList()
            val flg = tok("flg")?.removePrefix("0x")?.toLongOrNull(16)?.toInt() ?: 0
            val bundle = part.substringAfter(" }/PersistableBundle[{", "").substringBefore("}]")
            Raw(tok("act"), tok("cmp"), tok("pkg"), tok("dat"), cats, flg, parseBundle(bundle))
        }
        if (intents.isEmpty()) return null
        val icon = intents.flatMap { it.extras }.firstOrNull { it.key.endsWith("webapp_icon") }?.let {
            runCatching { Base64.decode(it.raw, Base64.DEFAULT) }.getOrNull()
        }
        val iconRes = field(block, "iconRes")?.substringBefore('[')?.toIntOrNull() ?: 0
        return Pinned(pkg, id, label, intents, icon, iconRes)
    }

    /** "k=v, k2=v2" with values that may themselves contain commas: split at each "key=" start. */
    private fun parseBundle(s: String): List<Extra> {
        if (s.isBlank()) return emptyList()
        val starts = Regex("""(?:^|, )([A-Za-z_][\w.]*)=""").findAll(s).toList()
        return starts.mapIndexed { n, m ->
            val vStart = m.range.last + 1
            val vEnd = if (n + 1 < starts.size) starts[n + 1].range.first else s.length
            Extra(m.groupValues[1], s.substring(vStart, vEnd))
        }
    }

    /** The original's intents, each rebuilt with every extra in the type the app reads it as. */
    fun intentsOf(p: Pinned): Array<Intent> = p.intents.map { intentOf(it, p.pkg) }.toTypedArray()

    private fun intentOf(p: Raw, owner: String): Intent {
        val i = Intent(p.action ?: Intent.ACTION_VIEW)
        p.component?.let { c ->
            val pkg = c.substringBefore('/'); var cls = c.substringAfter('/')
            if (cls.startsWith(".")) cls = pkg + cls
            i.component = ComponentName(pkg, cls)
        }
        if (p.component == null) i.setPackage(p.target ?: owner)
        p.data?.let { i.data = Uri.parse(it) }
        p.categories.forEach { i.addCategory(it) }
        i.addFlags(p.flags or Intent.FLAG_ACTIVITY_NEW_TASK)
        for (e in p.extras) {
            val v = e.raw
            when {
                v == "true" || v == "false" -> i.putExtra(e.key, v == "true")
                // colours are read as longs by Chromium-based browsers; everything else that fits is an int
                v.matches(Regex("-?\\d+")) && (e.key.endsWith("_color") || v.toLongOrNull()?.let { it !in Int.MIN_VALUE..Int.MAX_VALUE } == true) ->
                    i.putExtra(e.key, v.toLong())
                v.matches(Regex("-?\\d+")) -> i.putExtra(e.key, v.toInt())
                else -> i.putExtra(e.key, v)
            }
        }
        return i
    }

    /** The shortcut's own picture: a web app's embedded icon, its icon resource, or its app's icon. */
    fun sourceIcon(ctx: Context, p: Pinned): Bitmap? {
        p.iconPng?.let { b -> BitmapFactory.decodeByteArray(b, 0, b.size)?.let { return it } }
        val d = runCatching {
            if (p.iconRes != 0) ctx.packageManager.getResourcesForApplication(p.pkg).getDrawable(p.iconRes, null)
            else ctx.packageManager.getApplicationIcon(p.pkg)
        }.getOrNull() ?: return null
        return Bitmap.createBitmap(GlyphEngine.N, GlyphEngine.N, Bitmap.Config.ARGB_8888).also { b ->
            d.setBounds(0, 0, GlyphEngine.N, GlyphEngine.N); d.draw(Canvas(b))
        }
    }

    /** Asks the launcher to pin a copy with [icon]. HyperOS shows its own confirmation. */
    fun pinCopy(ctx: Context, p: Pinned, icon: Bitmap): Boolean = runCatching {
        val sm = ctx.getSystemService(ShortcutManager::class.java)
        if (!sm.isRequestPinShortcutSupported) return false
        val info = ShortcutInfo.Builder(ctx, "copy-${(p.pkg + p.id).hashCode()}")
            .setShortLabel(p.label).setIcon(Icon.createWithBitmap(icon)).setIntents(intentsOf(p)).build()
        sm.requestPinShortcut(info, null)
    }.getOrDefault(false)
}
