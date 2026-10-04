@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.verisonder.sondericons.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.verisonder.sondericons.GlyphEngine
import com.verisonder.sondericons.IconPack
import com.verisonder.sondericons.Prefs
import com.verisonder.sondericons.QuickToggles
import com.verisonder.sondericons.Shape
import com.verisonder.sondericons.Style
import com.verisonder.sondericons.StyleKind
import kotlin.math.roundToInt

/**
 * Everything about how icons look, on one screen, in the order a person decides it:
 * the look, its shape, what a pack's missing apps sit on, the quick toggles, the glyphs.
 * Sections that don't apply to the chosen look aren't shown. Changes save as they're made;
 * Done redraws the apps.
 */
@Composable
fun StyleScreen(styles: List<Style>, current: Style, onDone: (Style) -> Unit) {
    val ctx = LocalContext.current
    var style by remember { mutableStateOf(current) }
    var shape by remember { mutableStateOf(Prefs.customShape(ctx)) }
    var packBack by remember { mutableStateOf(Prefs.packBack(ctx)) }
    var toggles by remember { mutableStateOf(Prefs.toggles(ctx)) }
    var size by remember { mutableFloatStateOf(Prefs.globalScale(ctx)) }
    var tuning by remember { mutableStateOf(Prefs.globalTuning(ctx)) }

    fun done() {
        Prefs.setStyleId(ctx, style.id); Prefs.setCustomShape(ctx, shape); Prefs.setPackBack(ctx, packBack)
        Prefs.setToggles(ctx, toggles); Prefs.setGlobalScale(ctx, size); Prefs.setGlobalTuning(ctx, tuning)
        onDone(Style.byId(ctx, style.id))
    }

    Dialog(onDismissRequest = ::done, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(Palette.Black).systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Style", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = ::done) { Text("Done") }
            }
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                Section("Look", "Where every icon comes from.") {
                    LookGrid(styles, style, shape) { style = it }
                }

                val usesShape = style.id == "custom" || (style.kind == StyleKind.PACK && packBack == "shape")
                if (style.kind == StyleKind.PACK) Section("Apps the pack doesn't cover", "What their drawn icons sit on.") {
                    Segments(listOf("pack" to "Pack's", "none" to "None", "shape" to "My shape"), packBack) { packBack = it }
                    Text(
                        when (packBack) {
                            "none" -> "A bare glyph, like most packs' own icons."
                            "shape" -> "Your shape, set below."
                            else -> "The background the pack ships, if it has one."
                        },
                        style = MaterialTheme.typography.labelSmall, color = Palette.Muted,
                    )
                }
                if (usesShape) Section("Shape", null) { ShapeControls(shape) { shape = it } }

                Section("Quick toggles", "The big Wi-Fi, data and torch buttons.") {
                    ToggleControls(toggles) { toggles = it }
                }

                Section("Glyphs", "How drawn icons are made, for every app.") {
                    Column {
                        Row {
                            Text("Size", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Text("${(size * 100).roundToInt()}%", style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
                        }
                        Slider(value = size, onValueChange = { size = (it * 20).roundToInt() / 20f },
                            valueRange = 0.8f..1.2f, steps = 7, colors = sliderColors())
                    }
                    Advanced(subtitle = if (tuning == Prefs.Tuning()) "Using the defaults" else "Changed") {
                        TuningEditor(tuning, showSource = true) { tuning = it }
                        if (tuning != Prefs.Tuning()) TextButton(onClick = { tuning = Prefs.Tuning() },
                            contentPadding = PaddingValues(0.dp)) { Text("Reset") }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun Section(title: String, note: String?, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            note?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Palette.Muted) }
        }
        content()
    }
}

/** Every look as a card with a sample, three across. */
@Composable
private fun LookGrid(styles: List<Style>, current: Style, shape: Shape, onPick: (Style) -> Unit) {
    val ctx = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        styles.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { st ->
                    val sample = remember(st.id, shape) {
                        when (st.kind) {
                            StyleKind.DRAWN -> {
                                val sh = if (st.id == "custom") shape else st.shape
                                GlyphEngine.letter("A", sh.draw(180), 58, color = sh.glyph)
                            }
                            StyleKind.SET -> sampleIcon(ctx, st.asset!!)
                            StyleKind.PACK -> IconPack(ctx, st.pack!!, st.label).let { p -> p.covers("com.whatsapp", null)?.let { p.bitmap(it, 180) } }
                            StyleKind.THEME -> null
                        }
                    }
                    val selected = st.id == current.id
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(18.dp))
                            .background(if (selected) Palette.Circle else Palette.Black)
                            .border(1.dp, if (selected) Palette.White else Palette.Line, RoundedCornerShape(18.dp))
                            .clickable { onPick(st) }.padding(vertical = 14.dp, horizontal = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                            if (sample != null) Image(sample.asImageBitmap(), null, Modifier.fillMaxSize())
                            else Box(Modifier.fillMaxSize().clip(CircleShape).background(Palette.Line), contentAlignment = Alignment.Center) {
                                Text("Aa", style = MaterialTheme.typography.labelSmall, color = Palette.Muted)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(st.label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = if (selected) Palette.White else Palette.Muted)
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private val FORMS = listOf("circle" to "Circle", "squircle" to "Squircle", "square" to "Square",
    "teardrop" to "Teardrop", "hexagon" to "Hexagon", "none" to "None")

@Composable
private fun FormChips(forms: List<Pair<String, String>>, value: String, onPick: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
        forms.forEach { (f, label) ->
            FilterChip(
                selected = value == f, onClick = { onPick(f) }, label = { Text(label) }, shape = RoundedCornerShape(20.dp),
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Palette.White, selectedLabelColor = Palette.Black,
                    containerColor = Palette.Black, labelColor = Palette.White),
                border = FilterChipDefaults.filterChipBorder(enabled = true, selected = value == f, borderColor = Palette.Line),
            )
        }
    }
}

private fun cornerFor(form: String, current: Float) = when (form) { "square" -> 0.12f; "squircle" -> 0.3f; "teardrop" -> 0.5f; else -> current }

/** The custom background, with a live sample of three icons on it. */
@Composable
fun ShapeControls(sh: Shape, onChange: (Shape) -> Unit) {
    val samples = remember(sh) { val pat = sh.draw(180); listOf("S", "I", "C").map { GlyphEngine.letter(it, pat, 58, color = sh.glyph) } }
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Palette.Line).padding(vertical = 18.dp),
            horizontalArrangement = Arrangement.SpaceEvenly) { samples.forEach { Image(it.asImageBitmap(), null, Modifier.size(64.dp)) } }
        FormChips(FORMS, sh.form) { sh.copy(form = it, corner = cornerFor(it, sh.corner)).let(onChange) }
        if (sh.form in setOf("squircle", "square", "teardrop")) Column {
            Text("Corners", style = MaterialTheme.typography.bodyMedium)
            Slider(value = sh.corner, onValueChange = { onChange(sh.copy(corner = it)) }, valueRange = 0f..0.5f, colors = sliderColors())
        }
        if (sh.form != "none") ColorRow("Background", sh.background) { onChange(sh.copy(background = it)) }
        ColorRow("Glyph", sh.glyph) { onChange(sh.copy(glyph = it)) }
        if (sh.form != "none") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Outline", style = MaterialTheme.typography.bodyMedium)
                    Text("A ring around the background.", style = MaterialTheme.typography.labelSmall, color = Palette.Muted)
                }
                Switch(checked = sh.outline, onCheckedChange = { onChange(sh.copy(outline = it)) }, colors = switchColors())
            }
            if (sh.outline) ColorRow("Outline colour", sh.outlineColor) { onChange(sh.copy(outlineColor = it)) }
        }
    }
}

/** The quick toggles: follow the look, or own colours, with a live preview of on and off. */
@Composable
private fun ToggleControls(t: Prefs.Toggles, onChange: (Prefs.Toggles) -> Unit) {
    val ctx = LocalContext.current
    Segments(listOf(false to "Follow style", true to "Custom"), t.custom) { onChange(t.copy(custom = it)) }
    if (!t.custom) {
        Text("Designed sets keep their own. Other looks draw them in the look's colours.",
            style = MaterialTheme.typography.labelSmall, color = Palette.Muted)
        return
    }
    val preview = remember(t) {
        listOf("wifi" to true, "data" to true, "torch" to false, "flight_mode" to false).mapNotNull { (n, on) ->
            QuickToggles.draw(ctx, n, t.form, t.corner, if (on) t.onBg else t.offBg, if (on) t.onGlyph else t.offGlyph, 144)
        }
    }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Palette.Line).padding(vertical = 18.dp),
        horizontalArrangement = Arrangement.SpaceEvenly) { preview.forEach { Image(it.asImageBitmap(), null, Modifier.size(60.dp)) } }
    FormChips(FORMS.filter { it.first in setOf("circle", "squircle", "square", "hexagon") }, t.form) {
        onChange(t.copy(form = it, corner = cornerFor(it, t.corner)))
    }
    ColorRow("On", t.onBg) { onChange(t.copy(onBg = it)) }
    ColorRow("On, glyph", t.onGlyph) { onChange(t.copy(onGlyph = it)) }
    ColorRow("Off", t.offBg) { onChange(t.copy(offBg = it)) }
    ColorRow("Off, glyph", t.offGlyph) { onChange(t.copy(offGlyph = it)) }
}
