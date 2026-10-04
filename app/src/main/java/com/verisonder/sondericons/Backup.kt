package com.verisonder.sondericons

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The whole setup in one zip: every choice (setup.json) and every picture chosen for an app
 * (custom/). Ids that only mean something on this phone (which theme, which icons file)
 * are left out; they're found again after a restore.
 */
object Backup {
    private const val PREFS = "sondericons"
    private val DEVICE_ONLY = setOf("ownIconsId", "origIconsId", "chosenThemeId")

    fun save(ctx: Context, uri: Uri): Boolean = runCatching {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all
        val json = JSONObject().put("format", 1).put("app", "SonderIcons")
        val values = JSONObject()
        for ((k, v) in prefs) {
            if (k in DEVICE_ONLY || v == null) continue
            val type = when (v) { is Boolean -> "b"; is Int -> "i"; is Long -> "l"; is Float -> "f"; else -> "s" }
            values.put(k, JSONObject().put("t", type).put("v", if (v is Float) v.toDouble() else v))
        }
        json.put("values", values)
        ctx.contentResolver.openOutputStream(uri)!!.use { os ->
            ZipOutputStream(os).use { z ->
                z.putNextEntry(ZipEntry("setup.json")); z.write(json.toString(2).toByteArray()); z.closeEntry()
                File(ctx.filesDir, "custom").listFiles()?.forEach { f ->
                    z.putNextEntry(ZipEntry("custom/${f.name}")); f.inputStream().use { it.copyTo(z) }; z.closeEntry()
                }
            }
        }
        true
    }.getOrDefault(false)

    /** Replaces the current setup with the backup's. Returns null on success, else why not. */
    fun restore(ctx: Context, uri: Uri): String? = runCatching {
        var setup: JSONObject? = null
        val images = HashMap<String, ByteArray>()
        ctx.contentResolver.openInputStream(uri)!!.use { ins ->
            ZipInputStream(ins).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    when {
                        e.name == "setup.json" -> setup = JSONObject(String(z.readBytes()))
                        e.name.startsWith("custom/") && !e.name.contains("..") -> images[e.name.removePrefix("custom/")] = z.readBytes()
                    }
                }
            }
        }
        val s = setup ?: return "That file isn't a SonderIcons backup."
        if (s.optString("app") != "SonderIcons") return "That file isn't a SonderIcons backup."
        val values = s.getJSONObject("values")
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val keep = sp.all.filterKeys { it in DEVICE_ONLY }
        val ed = sp.edit().clear()
        keep.forEach { (k, v) -> if (v is String) ed.putString(k, v) }
        for (k in values.keys()) {
            val o = values.getJSONObject(k)
            when (o.getString("t")) {
                "b" -> ed.putBoolean(k, o.getBoolean("v"))
                "i" -> ed.putInt(k, o.getInt("v"))
                "l" -> ed.putLong(k, o.getLong("v"))
                "f" -> ed.putFloat(k, o.getDouble("v").toFloat())
                else -> ed.putString(k, o.getString("v"))
            }
        }
        ed.apply()
        val dir = File(ctx.filesDir, "custom").apply { deleteRecursively(); mkdirs() }
        images.forEach { (name, bytes) -> File(dir, File(name).name).writeBytes(bytes) }
        null
    }.getOrElse { "Couldn't read that backup." }
}
