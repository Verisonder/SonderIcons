@file:OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.verisonder.sondericons.ui

import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.verisonder.sondericons.Builder
import com.verisonder.sondericons.Builder.Kind
import com.verisonder.sondericons.Prefs
import com.verisonder.sondericons.Shell
import com.verisonder.sondericons.ThemeStore
import com.verisonder.sondericons.Style
import com.verisonder.sondericons.StyleKind
import com.verisonder.sondericons.Shape
import com.verisonder.sondericons.IconPack
import com.verisonder.sondericons.GlyphEngine
import com.verisonder.sondericons.PinnedShortcuts
import com.verisonder.sondericons.Backup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private var resumes by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        Palette.apply(Prefs.appTheme(this))
        bars()
        super.onCreate(savedInstanceState)
        setContent { SonderTheme { Surface(color = Palette.Black) { Home(resumes) } } }
    }

    override fun onResume() { super.onResume(); resumes++ }

    /** Status and navigation bar icons dark on the light theme, light on the others. */
    fun bars() {
        val style = if (Palette.light) androidx.activity.SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                    else androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }
}

private class App(val pkg: String, val label: String)

private const val SHIZUKU = "moe.shizuku.privileged.api"

private enum class Filter(val title: String) { ALL("All"), DRAWN("Drawn"), YOURS("Yours"), THEME("Designed"), MISSING("Missing"), EXTRAS("Extras") }

private fun Filter.matches(k: Kind?) = when (this) {
    Filter.ALL -> true
    Filter.DRAWN -> k == Kind.DRAWN
    Filter.YOURS -> k == Kind.CUSTOM
    Filter.THEME -> k == Kind.THEME
    Filter.MISSING -> k == Kind.MISSING
    Filter.EXTRAS -> false   // its own list, see [extras]
}

@Composable
private fun Home(resumes: Int) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val builder = remember { Builder(ctx) }

    // read at once so the setup screen doesn't flash past on every launch
    var shizuku by remember { mutableStateOf(when { Shell.available() -> true; Shell.running() -> false; else -> null }) }
    var base by remember { mutableStateOf<Builder.Base?>(null) }
    var target by remember { mutableStateOf<Builder.Target?>(null) }
    var picking by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var apps by remember { mutableStateOf<List<App>>(emptyList()) }
    var extras by remember { mutableStateOf<List<App>>(emptyList()) }   // second icons and aliases
    val results = remember { mutableStateMapOf<String, Builder.Result>() }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(Filter.ALL) }
    var open by remember { mutableStateOf<App?>(null) }
    var settings by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    val selected = remember { mutableStateListOf<String>() }
    var previewing by remember { mutableStateOf<App?>(null) }
    var style by remember { mutableStateOf(Style.byId(ctx, Prefs.styleId(ctx))) }
    var styles by remember { mutableStateOf(Style.all(ctx)) }
    var styling by remember { mutableStateOf(false) }
    var built by remember { mutableStateOf(false) }
    LaunchedEffect(resumes) { styles = withContext(Dispatchers.Default) { Style.all(ctx) } }
    var styleError by remember { mutableStateOf<String?>(null) }
    fun load(t: Builder.Target): Builder.Base? = t.theme?.let {
        try { builder.loadBase(it).also { styleError = null } } catch (e: Builder.Unsupported) { styleError = e.message; null }
    }
    var message by remember { mutableStateOf<String?>(null) }

    fun refresh(pkg: String) = scope.launch {
        val b = base ?: return@launch
        withContext(Dispatchers.Default) { runCatching { builder.resultFor(pkg, b) }.getOrNull() }?.let { results[pkg] = it }
    }
    fun refreshAll() = scope.launch {
        val b = base ?: return@launch
        withContext(Dispatchers.Default) {
            for (a in apps + extras) runCatching { builder.resultFor(a.pkg, b) }.getOrNull()?.let { results[a.pkg] = it }
        }
    }

    LaunchedEffect(resumes) {
        shizuku = when { Shell.available() -> true; Shell.running() -> false; else -> null }
        withContext(Dispatchers.Default) {
            val pm = ctx.packageManager
            val all = builder.entries()
            apps = all.filter { it.primary }.map { e ->
                App(e.pkg, runCatching { pm.getApplicationLabel(pm.getApplicationInfo(e.pkg, 0)).toString() }.getOrDefault(e.label))
            }.sortedBy { it.label.lowercase() }
            extras = all.filter { !it.primary }.map { e ->
                val app = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(e.pkg, 0)).toString() }.getOrDefault(e.label)
                App(e.key, if (builder.isAlternate(e.key)) "$app, alternate icon" else e.label)
            }
            if (shizuku == true) {
                val t = builder.target()
                // reread the theme only when the target changed; a resume alone shouldn't redraw everything
                if (t.theme?.id != target?.theme?.id || base == null) base = load(t)
                target = t
            }
        }
        if (base != null && results.isEmpty()) refreshAll()
    }

    LaunchedEffect(reload) {
        if (reload == 0) return@LaunchedEffect
        withContext(Dispatchers.Default) {
            val t = builder.target()
            target = t; base = load(t)
        }
        refreshAll()   // cached icons come back at once; old ones stay on screen meanwhile
    }

    val counts = remember(results.toMap(), extras) {
        Filter.entries.associateWith { f -> if (f == Filter.EXTRAS) extras.size else apps.count { f.matches(results[it.pkg]?.kind) } }
    }
    val shown = (if (filter == Filter.EXTRAS) extras else apps).filter { a ->
        (query.isBlank() || a.label.contains(query, true) || a.pkg.contains(query, true)) &&
            (filter == Filter.ALL || filter == Filter.EXTRAS || filter.matches(results[a.pkg]?.kind))
    }

    val hasShizuku = remember(resumes) { ctx.packageManager.getLaunchIntentForPackage(SHIZUKU) != null }
    if (shizuku != true || target?.all?.isEmpty() == true) {
        Setup(
            installed = hasShizuku, shizuku = shizuku, hasBackup = target?.all?.isNotEmpty(),
            onInstall = { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                Uri.parse("https://play.google.com/store/apps/details?id=$SHIZUKU"))) },
            onOpenShizuku = { ctx.packageManager.getLaunchIntentForPackage(SHIZUKU)?.let { ctx.startActivity(it) } },
            onAllow = { Shell.requestPermission() },
            onOpenThemes = { ctx.packageManager.getLaunchIntentForPackage(ThemeStore.THEMES_PKG)?.let { ctx.startActivity(it) } },
        )
        return
    }

    Scaffold(
        containerColor = Palette.Black,
        bottomBar = {
            if (selected.isNotEmpty()) SelectionBar(
                count = selected.size,
                onAll = { shown.forEach { if (it.pkg !in selected) selected += it.pkg } },
                onClear = { selected.clear() },
                onApply = { mode ->
                    val pkgs = selected.toList(); selected.clear(); built = false; message = null
                    pkgs.forEach { Prefs.setMode(ctx, it, mode) }
                    scope.launch {
                        val b = base ?: return@launch
                        withContext(Dispatchers.Default) {
                            for (p in pkgs) runCatching { builder.resultFor(p, b) }.getOrNull()?.let { results[p] = it }
                        }
                    }
                },
            ) else BuildBar(
                enabled = shizuku == true && base != null && busy == null,
                busy = busy, message = message, built = built,
                onBuild = {
                    busy = "Starting"; message = null
                    scope.launch {
                        val r = withContext(Dispatchers.Default) { builder.build { busy = it } }
                        busy = null
                        built = r.error == null
                        message = r.error ?: "${r.made} icons ready."
                    }
                },
                onThemes = { ctx.packageManager.getLaunchIntentForPackage(ThemeStore.THEMES_PKG)?.let { ctx.startActivity(it) } },
            )
        },
    ) { pad ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = pad.calculateTopPadding() + 12.dp, bottom = pad.calculateBottomPadding() + 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(4) }) {
                Header(
                    shizuku = shizuku, target = target, ready = base != null, onPick = { picking = true },
                    style = style, styles = styles, styleError = styleError,
                    onStyle = { st -> style = st; Prefs.setStyleId(ctx, st.id); reload++ },
                    onCustomize = { styling = true }, query = query, filter = filter, counts = counts,
                    onQuery = { query = it }, onFilter = { filter = it }, onSettings = { settings = true },
                    onAllow = { Shell.requestPermission() },
                    onOpenShizuku = { ctx.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")?.let { ctx.startActivity(it) } },
                )
            }
            if (filter == Filter.EXTRAS) item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(4) }) {
                Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    ShortcutsCard(base, onChanged = { built = false; message = null })
                    PinnedList(base, shizuku == true, resumes)
                    if (extras.isNotEmpty()) Text("Second and alternative icons", style = MaterialTheme.typography.titleMedium)
                }
            }
            items(shown, key = { it.pkg }) { a ->
                Tile(
                    a, results[a.pkg], builder, selected = a.pkg in selected,
                    onClick = { if (selected.isEmpty()) open = a else if (a.pkg in selected) selected -= a.pkg else selected += a.pkg },
                    onLongClick = { if (a.pkg in selected) selected -= a.pkg else selected += a.pkg },
                )
            }
            if (base != null && shown.isEmpty()) item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(4) }) {
                Text(
                    when (filter) {
                        Filter.MISSING -> "Every app has an icon."
                        Filter.EXTRAS -> "No app has a second icon."
                        else -> "No apps match."
                    },
                    color = Palette.Muted, modifier = Modifier.padding(top = 24.dp),
                )
            }
        }
    }

    val sheetApp = open
    if (sheetApp != null && base != null) {
        AppSheet(sheetApp, results[sheetApp.pkg], builder, onDismiss = { open = null }, onChanged = { built = false; message = null; refresh(sheetApp.pkg) },
            onPreview = { previewing = sheetApp })
    }
    previewing?.let { a ->
        // the app among its neighbours in name order, as it would sit on a home screen
        val i = apps.indexOfFirst { it.pkg == a.pkg }.coerceAtLeast(0)
        val from = (i - 5).coerceIn(0, maxOf(0, apps.size - 8))
        val around = apps.subList(from, minOf(apps.size, from + 8))
        PreviewScreen(
            themeId = target?.theme?.id, focus = a.pkg,
            icons = around.map { Triple(it.pkg, it.label, results[it.pkg]) }, builder = builder,
            onDismiss = { previewing = null },
        )
    }
    if (styling) StyleScreen(styles, style) { st, changed ->
        styling = false; style = st
        if (changed) { styles = Style.all(ctx); built = false; message = null; reload++ }
    }
    if (picking) target?.let { t ->
        ThemePicker(t, onDismiss = { picking = false }, onPick = { id ->
            Prefs.setChosenThemeId(ctx, id); picking = false; reload++
        })
    }
    if (settings) SettingsSheet(
        ready = shizuku == true && base != null,
        onDismiss = { settings = false },
        onScale = { refreshAll() },
        onTuning = { refreshAll() },
        onRestore = {
            scope.launch {
                message = withContext(Dispatchers.Default) { builder.restore() } ?: "Original icons linked. Apply Theme backup in Themes."
                settings = false
            }
        },
    )
}

@Composable
private fun Header(
    shizuku: Boolean?, target: Builder.Target?, ready: Boolean, onPick: () -> Unit,
    style: Style, styles: List<Style>, styleError: String?, onStyle: (Style) -> Unit, onCustomize: () -> Unit, query: String, filter: Filter, counts: Map<Filter, Int>,
    onQuery: (String) -> Unit, onFilter: (Filter) -> Unit, onSettings: () -> Unit, onAllow: () -> Unit, onOpenShizuku: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("SonderIcons", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "Settings", tint = Palette.Muted) }
        }
        // one status line: says what is wrong and how to fix it, or nothing at all
        when {
            shizuku == null -> Status("Shizuku isn't running.", "Open Shizuku", onOpenShizuku)
            shizuku == false -> Status("SonderIcons needs Shizuku access.", "Allow", onAllow)
            target == null -> Text("Reading your theme…", color = Palette.Muted)
            target.all.isEmpty() -> Status("No Theme backup found. In Themes, open Customize theme and save once.", null, null)
            target.theme == null -> Status("Your current theme isn't a Theme backup.", "Choose", onPick)
            else -> ThemeLine(target, onPick)
        }
        if (target?.theme != null) StyleCard(style, onCustomize)
        styleError?.let { Status(it, null, null) }
        TextField(
            value = query, onValueChange = onQuery, singleLine = true,
            placeholder = { Text("Search apps") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Filled.Clear, "Clear") } },
            shape = RoundedCornerShape(28.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Palette.Circle, unfocusedContainerColor = Palette.Circle,
                focusedIndicatorColor = Palette.Circle, unfocusedIndicatorColor = Palette.Circle,
                cursorColor = Palette.White,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        if (ready) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScrollable()) {
            Filter.entries.forEach { f ->
                val n = counts[f] ?: 0
                if (f != Filter.ALL && f != Filter.EXTRAS && n == 0) return@forEach
                FilterChip(
                    selected = filter == f, onClick = { onFilter(f) },
                    label = { Text("${f.title}  $n", color = if (f == Filter.MISSING && filter != f) Palette.Red else Color.Unspecified) },
                    shape = RoundedCornerShape(20.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Palette.White, selectedLabelColor = Palette.Black,
                        containerColor = Palette.Black, labelColor = Palette.White,
                    ),
                    border = FilterChipDefaults.filterChipBorder(enabled = true, selected = filter == f, borderColor = Palette.Line),
                )
            }
        }
    }
}

@Composable
private fun Modifier.horizontalScrollable(): Modifier =
    this.then(Modifier.horizontalScroll(rememberScrollState()))

private fun savedOn(ms: Long): String =
    if (ms <= 0) "" else java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(ms))

/**
 * Pinned shortcuts and unknown icons. A shortcut's picture comes from its app at run time,
 * so what can be set is what it sits on, its arrow badge, and the filter for unknown icons.
 */
@Composable
private fun ShortcutsCard(base: Builder.Base?, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    var sc by remember { mutableStateOf(Prefs.shortcutStyle(ctx)) }
    fun set(v: Prefs.ShortcutStyle) { sc = v; Prefs.setShortcutStyle(ctx, v); onChanged() }
    val sample = remember(base, sc) {
        base?.let { b ->
            val size = 180
            val out = if (sc.background == "none") Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                      else b.pattern.copy(Bitmap.Config.ARGB_8888, true)
            val glyph = GlyphEngine.letter("S", Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888), b.target, color = b.glyph)
            val c = android.graphics.Canvas(out)
            c.drawBitmap(glyph, 0f, 0f, null)
            if (!sc.hideArrow) c.drawBitmap(com.verisonder.sondericons.Shortcuts.arrow(b, size / 3), size * 2f / 3, size * 2f / 3, null)
            out
        }
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Palette.Circle).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Shortcuts and unknown icons", style = MaterialTheme.typography.bodyMedium)
                Text("Shortcuts an app pins to your home screen, and apps the look knows nothing about.",
                    style = MaterialTheme.typography.labelSmall, color = Palette.Muted)
            }
            sample?.let { Image(it.asImageBitmap(), null, Modifier.size(56.dp)) }
        }
        Text("Shortcuts sit on", style = MaterialTheme.typography.bodyMedium)
        Segments(listOf("style" to "The style's shape", "none" to "Nothing"), sc.background) { set(sc.copy(background = it)) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Hide the shortcut arrow", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Switch(checked = sc.hideArrow, onCheckedChange = { set(sc.copy(hideArrow = it)) }, colors = switchColors())
        }
        Text("Unknown icons become", style = MaterialTheme.typography.bodyMedium)
        Segments(listOf("traced" to "Outline", "solid" to "Silhouette", "none" to "As they are"), sc.fallback) { set(sc.copy(fallback = it)) }
        Text(
            when (sc.fallback) {
                "solid" -> "New: check a few unknown icons after applying."
                "none" -> "Shown untouched. Best with themed shortcut copies."
                else -> "HyperOS's own traced look."
            },
            style = MaterialTheme.typography.labelSmall, color = Palette.Muted,
        )
    }
}

/** Shortcuts pinned to the home screen, each of which can be replaced by a themed copy. */
@Composable
private fun PinnedList(base: Builder.Base?, ready: Boolean, resumes: Int) {
    val ctx = LocalContext.current
    var pinned by remember { mutableStateOf<List<PinnedShortcuts.Pinned>?>(null) }
    var open by remember { mutableStateOf<PinnedShortcuts.Pinned?>(null) }
    LaunchedEffect(ready, resumes) {
        if (ready) pinned = withContext(Dispatchers.Default) { runCatching { PinnedShortcuts.read(ctx.packageName) }.getOrDefault(emptyList()) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Pinned shortcuts", style = MaterialTheme.typography.titleMedium)
        Text("A theme can't change these one by one. Pin a themed copy, then remove the original.",
            style = MaterialTheme.typography.labelSmall, color = Palette.Muted)
        when {
            !ready -> Text("Needs Shizuku.", style = MaterialTheme.typography.labelSmall, color = Palette.Muted)
            pinned == null -> Text("Reading…", style = MaterialTheme.typography.labelSmall, color = Palette.Muted)
            pinned!!.isEmpty() -> Text("No shortcuts are pinned.", style = MaterialTheme.typography.labelSmall, color = Palette.Muted)
            else -> pinned!!.forEach { p ->
                val icon by produceState<Bitmap?>(null, p.key) { value = withContext(Dispatchers.Default) { PinnedShortcuts.sourceIcon(ctx, p) } }
                val app = remember(p.pkg) {
                    runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(p.pkg, 0)).toString() }.getOrDefault(p.pkg)
                }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.Circle).clickable { open = p }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(44.dp).clip(CircleShape).background(Palette.Black), contentAlignment = Alignment.Center) {
                        icon?.let { Image(it.asImageBitmap(), null, Modifier.size(36.dp)) }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("From $app", style = MaterialTheme.typography.labelSmall, color = Palette.Muted)
                    }
                    Text("Copy", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
    open?.let { p -> if (base != null) ShortcutSheet(p, base, onDismiss = { open = null }) else open = null }
}

/** A themed copy of one pinned shortcut: where its icon comes from, a preview, and pinning. */
@Composable
private fun ShortcutSheet(p: PinnedShortcuts.Pinned, base: Builder.Base, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var source by remember { mutableStateOf("auto") }          // auto | letter | picture
    var picture by remember { mutableStateOf<Bitmap?>(null) }
    var pinned by remember { mutableStateOf(false) }
    val packs = remember { IconPack.installed(ctx) }
    var packFor by remember { mutableStateOf<IconPack?>(null) }
    val picker = rememberLauncherForActivityResult(PickVisualMedia()) { uri: Uri? ->
        uri?.let { u -> ctx.contentResolver.openInputStream(u)?.use { android.graphics.BitmapFactory.decodeStream(it) } }?.let {
            picture = it; source = "picture"
        }
    }
    val preview by produceState<Bitmap?>(null, source, picture) {
        value = withContext(Dispatchers.Default) {
            when (source) {
                "letter" -> GlyphEngine.letter(p.label, base.pattern, base.target, color = base.glyph)
                "picture" -> picture?.let { GlyphEngine.fromImage(it, base.pattern, base.target, color = base.glyph) }
                else -> PinnedShortcuts.sourceIcon(ctx, p)?.let { src ->
                    val (m, _) = GlyphEngine.pick(GlyphEngine.Layers(null, null, null, Bitmap.createScaledBitmap(src, GlyphEngine.N, GlyphEngine.N, true)))
                    m?.let { GlyphEngine.render(it, base.pattern, base.target, color = base.glyph) }
                }
            }
        }
    }
    packFor?.let { pk ->
        PackIconPicker(pk, packs, initialQuery = p.label.split(' ').first(), onSwitch = { packFor = it }, onDismiss = { packFor = null },
            onPick = { picture = it; source = "picture"; packFor = null })
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Palette.Circle) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(104.dp), contentAlignment = Alignment.Center) {
                    preview?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize()) }
                        ?: Text("No clear shape", style = MaterialTheme.typography.labelSmall, color = Palette.Red)
                }
                Spacer(Modifier.width(18.dp))
                Column {
                    Text(p.label, style = MaterialTheme.typography.titleMedium)
                    Text("Opens the same thing as the original.", style = MaterialTheme.typography.labelSmall, color = Palette.Muted)
                }
            }
            Segments(listOf("auto" to "Auto", "letter" to "Letter", "picture" to "Picture"), source) {
                if (it == "picture" && picture == null) picker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) else source = it
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (packs.isNotEmpty()) OutlinedButton(onClick = { packFor = packs.first() }, modifier = Modifier.weight(1f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Palette.Line)) { Text("Browse a pack", color = Palette.White) }
                OutlinedButton(onClick = { picker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) }, modifier = Modifier.weight(1f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Palette.Line)) { Text("From gallery", color = Palette.White) }
            }
            Button(
                onClick = { preview?.let { pinned = PinnedShortcuts.pinCopy(ctx, p, it) } },
                enabled = preview != null, modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Palette.White, contentColor = Palette.Black),
            ) { Text("Pin themed copy") }
            if (pinned) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Copy pinned.", style = MaterialTheme.typography.bodyMedium)
                Text("Remove the original: long-press it, Remove.", style = MaterialTheme.typography.labelSmall, color = Palette.Muted)
                if (Prefs.shortcutStyle(ctx).fallback != "none") Text(
                    "Set Unknown icons to As they are, then build and apply, or HyperOS will trace the copy too.",
                    style = MaterialTheme.typography.labelSmall, color = Palette.Red)
            }
        }
    }
}

/** The current look and the way into changing it. */
@Composable
private fun StyleCard(style: Style, onCustomize: () -> Unit) {
    val ctx = LocalContext.current
    val sample = remember(style.id) {
        when (style.kind) {
            StyleKind.DRAWN -> style.pattern(96).let { GlyphEngine.letter("A", style.pattern(180), 58, color = style.glyph) }
            StyleKind.SET -> sampleIcon(ctx, style.asset!!)
            StyleKind.PACK -> IconPack(ctx, style.pack!!, style.label).let { p -> p.covers("com.whatsapp", null)?.let { p.bitmap(it, 180) } }
            StyleKind.THEME -> null
        }
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Palette.Circle).clickable(onClick = onCustomize).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            if (sample != null) Image(sample.asImageBitmap(), null, Modifier.fillMaxSize())
            else Box(Modifier.fillMaxSize().clip(CircleShape).background(Palette.Line))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("Style", style = MaterialTheme.typography.labelSmall, color = Palette.Muted)
            Text(style.label, style = MaterialTheme.typography.bodyMedium)
        }
        Text("Customize", style = MaterialTheme.typography.bodyMedium)
    }
}

/** One designed icon from a bundled set, to show what the set looks like. */
internal fun sampleIcon(ctx: android.content.Context, asset: String): Bitmap? = runCatching {
    java.util.zip.ZipInputStream(ctx.assets.open(asset)).use { z ->
        while (true) {
            val e = z.nextEntry ?: return@use null
            if (e.name.endsWith("/com.whatsapp.png")) {
                val bytes = z.readBytes()
                return@use android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        }
        null
    }
}.getOrNull()


/**
 * First run, and whenever something is missing: the steps in order, each with what to do
 * and a tick once it's done. Replaces the app until everything is in place.
 */
@Composable
private fun Setup(
    installed: Boolean, shizuku: Boolean?, hasBackup: Boolean?,
    onInstall: () -> Unit, onOpenShizuku: () -> Unit, onAllow: () -> Unit, onOpenThemes: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().background(Palette.Black).systemBarsPadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        Text("SonderIcons", style = MaterialTheme.typography.headlineMedium)
        Text("Two things to set up once. After that it's one button.", style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)

        Step(
            done = shizuku == true,
            title = "Shizuku",
            body = when {
                !installed -> "SonderIcons changes your theme through Shizuku. Install it, then start it with Wireless debugging."
                shizuku == null -> "Open Shizuku and start it with Wireless debugging. After a restart, start it again."
                shizuku == false -> "Shizuku is running. Allow SonderIcons to use it."
                else -> "Ready."
            },
            action = when {
                !installed -> "Install Shizuku" to onInstall
                shizuku == null -> "Open Shizuku" to onOpenShizuku
                shizuku == false -> "Allow" to onAllow
                else -> null
            },
        )
        Step(
            done = hasBackup == true,
            title = "Theme backup",
            body = when (hasBackup) {
                null -> "Checked once Shizuku is ready."
                true -> "Ready."
                false -> "SonderIcons puts its icons into the theme HyperOS calls Theme backup. " +
                    "To make one: in Themes, open Customize theme, change any part, and apply."
            },
            action = if (hasBackup == false) "Open Themes" to onOpenThemes else null,
        )
    }
}

@Composable
private fun Step(done: Boolean, title: String, body: String, action: Pair<String, () -> Unit>?) {
    Row {
        Box(
            Modifier.size(26.dp).clip(CircleShape).background(if (done) Palette.White else Palette.Circle),
            contentAlignment = Alignment.Center,
        ) { if (done) Text("✓", color = Palette.Black, style = MaterialTheme.typography.bodyMedium) }
        Spacer(Modifier.width(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = if (done) Palette.Muted else Palette.White)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
            action?.let { (label, go) ->
                Button(
                    onClick = go, modifier = Modifier.padding(top = 4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.White, contentColor = Palette.Black),
                ) { Text(label) }
            }
        }
    }
}

/** Which theme a build changes. Tappable only when there is something else to choose. */
@Composable
private fun ThemeLine(t: Builder.Target, onPick: () -> Unit) {
    val theme = t.theme ?: return
    val choosable = t.all.size > 1 || !t.onScreen
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text("Theme backup, saved ${savedOn(theme.savedAt)}", style = MaterialTheme.typography.bodyMedium)
            Text(
                if (t.onScreen) "On screen now" else "Not on screen. Apply it in Themes after building.",
                style = MaterialTheme.typography.labelSmall, color = if (t.onScreen) Palette.Muted else Palette.Red,
            )
        }
        if (choosable) TextButton(onClick = onPick) { Text("Change") }
    }
}

/** Every Theme backup, newest first, with the launcher preview the Themes app saved for it. */
@Composable
private fun ThemePicker(t: Builder.Target, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Palette.Circle) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Which theme should get the icons?", style = MaterialTheme.typography.titleMedium)
            t.all.forEach { theme ->
                val preview by produceState<Bitmap?>(null, theme.id) {
                    value = withContext(Dispatchers.Default) {
                        ThemeStore.preview(theme.id)?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }
                    }
                }
                val selected = theme.id == t.theme?.id
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                        .background(if (selected) Palette.Black else Palette.Circle)
                        .clickable { onPick(theme.id) }.padding(10.dp),
                ) {
                    Box(Modifier.size(width = 54.dp, height = 96.dp).clip(RoundedCornerShape(10.dp)).background(Palette.Black)) {
                        preview?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Saved ${savedOn(theme.savedAt)}", style = MaterialTheme.typography.bodyMedium)
                        if (theme.id == t.theme?.id && t.onScreen) Text("On screen now", style = MaterialTheme.typography.labelSmall, color = Palette.Muted)
                    }
                    if (selected) Box(Modifier.size(10.dp).clip(CircleShape).background(Palette.White))
                }
            }
            Text("Only Theme backups can be changed. Make one in Themes, Customize theme.",
                style = MaterialTheme.typography.labelSmall, color = Palette.Muted)
        }
    }
}

@Composable
private fun Status(text: String, action: String?, onAction: (() -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(Palette.Red))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) TextButton(onClick = onAction) { Text(action) }
    }
}

/** An app as it will look: the theme's icon, a drawn one, or the app's own icon dimmed. */
@Composable
private fun Tile(a: App, r: Builder.Result?, builder: Builder, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Box(contentAlignment = Alignment.TopEnd) {
            Box(
                Modifier.size(70.dp).clip(CircleShape)
                    .border(2.dp, if (selected) Palette.White else Palette.Black, CircleShape),
                contentAlignment = Alignment.Center,
            ) { IconCircle(r, a.pkg, builder, 62.dp) }
            when (r?.kind) {
                Kind.MISSING -> Dot(Palette.Red)
                Kind.CUSTOM -> Dot(Palette.White)
                else -> {}
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(a.label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Dot(c: Color) = Box(Modifier.size(10.dp).clip(CircleShape).background(Palette.Black).padding(2.dp).clip(CircleShape).background(c))

@Composable
private fun IconCircle(r: Builder.Result?, pkg: String, builder: Builder, size: Dp) {
    val bmp = r?.bitmap
    if (bmp != null) {
        Image(bmp.asImageBitmap(), null, Modifier.size(size))
    } else {
        // no icon of ours: show the app's own, greyed, on the theme's circle
        val own by produceState<Bitmap?>(null, pkg) { value = withContext(Dispatchers.Default) { builder.appIcon(pkg) } }
        Box(Modifier.size(size).clip(CircleShape).background(Palette.Circle), contentAlignment = Alignment.Center) {
            own?.let {
                Image(
                    it.asImageBitmap(), null, Modifier.size(size * 0.55f).alpha(0.45f),
                    colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }),
                )
            }
        }
    }
}

@Composable
private fun BuildBar(enabled: Boolean, busy: String?, message: String?, built: Boolean, onBuild: () -> Unit, onThemes: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Palette.Black).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        HorizontalDivider(color = Palette.Line, modifier = Modifier.padding(bottom = 2.dp))
        (busy ?: message)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = if (busy != null) Palette.Muted else Palette.White) }
        // the one step only the person can do, said where they'll need it
        if (built && busy == null) Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Palette.Circle).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Now apply it in Themes", style = MaterialTheme.typography.bodyMedium)
            Text("My account, Themes, then Theme backup, Apply.", style = MaterialTheme.typography.labelSmall, color = Palette.Muted)
            Text("Choose Theme backup, not SonderIcons.", style = MaterialTheme.typography.labelSmall, color = Palette.Red)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // once built, Build has nothing to do until something changes, so only Themes is left
            if (!(built && busy == null)) Button(
                onClick = onBuild, enabled = enabled, modifier = Modifier.weight(1f).height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Palette.White, contentColor = Palette.Black,
                    disabledContainerColor = Palette.Circle, disabledContentColor = Palette.Muted),
            ) {
                if (busy != null) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Palette.Muted)
                else Text("Build icons")
            }
            if (built && busy == null) Button(
                onClick = onThemes, modifier = Modifier.weight(1f).height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Palette.White, contentColor = Palette.Black),
            ) { Text("Open Themes") }
            else OutlinedButton(
                onClick = onThemes, modifier = Modifier.height(52.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Palette.Line),
            ) { Text("Open Themes", color = Palette.White) }
        }
    }
}

/** Replaces the build bar while apps are selected: one choice applied to all of them. */
@Composable
private fun SelectionBar(count: Int, onAll: () -> Unit, onClear: () -> Unit, onApply: (Prefs.Mode) -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Palette.Black).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        HorizontalDivider(color = Palette.Line, modifier = Modifier.padding(bottom = 2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$count selected", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onAll) { Text("Select all") }
            TextButton(onClick = onClear) { Text("Cancel", color = Palette.Muted) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Prefs.Mode.AUTO to "Auto", Prefs.Mode.LETTER to "Letter", Prefs.Mode.THEME to "Theme").forEach { (m, label) ->
                OutlinedButton(
                    onClick = { onApply(m) }, modifier = Modifier.weight(1f).height(48.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Palette.Line),
                ) { Text(label, color = Palette.White) }
            }
        }
    }
}

/** Everything about one app: what it will look like, where that comes from, and how to change it. */
@Composable
private fun AppSheet(a: App, r: Builder.Result?, builder: Builder, onDismiss: () -> Unit, onChanged: () -> Unit, onPreview: () -> Unit) {
    val ctx = LocalContext.current
    var mode by remember { mutableStateOf(Prefs.mode(ctx, a.pkg)) }
    var size by remember { mutableFloatStateOf(Prefs.scale(ctx, a.pkg)) }
    val picker = rememberLauncherForActivityResult(PickVisualMedia()) { uri: Uri? ->
        if (uri != null) {
            ctx.contentResolver.openInputStream(uri)?.use { input -> Prefs.customFile(ctx, a.pkg).outputStream().use { input.copyTo(it) } }
            Prefs.setMode(ctx, a.pkg, Prefs.Mode.CUSTOM); mode = Prefs.Mode.CUSTOM; onChanged()
        }
    }
    val packs = remember { IconPack.installed(ctx) }
    var packFor by remember { mutableStateOf<IconPack?>(null) }
    var asIs by remember { mutableStateOf(Prefs.asIs(ctx, a.pkg)) }
    packFor?.let { pk ->
        PackIconPicker(pk, packs, initialQuery = a.label.substringBefore(',').split(' ').first(),
            onSwitch = { packFor = it }, onDismiss = { packFor = null }, onPick = { bmp ->
            Prefs.customFile(ctx, a.pkg).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            // a pack icon is a finished design: used whole unless the person turns that off
            Prefs.setMode(ctx, a.pkg, Prefs.Mode.CUSTOM); mode = Prefs.Mode.CUSTOM
            Prefs.setAsIs(ctx, a.pkg, true); asIs = true
            packFor = null; onChanged()
        })
    }
    // a size change redraws the preview once the slider settles
    LaunchedEffect(size) { delay(150); if (size != Prefs.scale(ctx, a.pkg)) { Prefs.setScale(ctx, a.pkg, size); onChanged() } }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Palette.Circle) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(112.dp).clip(CircleShape).background(Palette.Black), contentAlignment = Alignment.Center) {
                    IconCircle(r, a.pkg, builder, 104.dp)
                }
                Spacer(Modifier.width(18.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(a.label, style = MaterialTheme.typography.titleMedium)
                    Text(r?.note ?: "Drawing…", style = MaterialTheme.typography.bodyMedium,
                        color = if (r?.kind == Kind.MISSING) Palette.Red else Palette.Muted)
                }
            }

            val options = listOf(Prefs.Mode.AUTO to "Auto", Prefs.Mode.CUSTOM to "Image", Prefs.Mode.LETTER to "Letter", Prefs.Mode.THEME to "Theme")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                options.forEachIndexed { i, (m, label) ->
                    SegmentedButton(
                        selected = mode == m,
                        onClick = {
                            if (m == Prefs.Mode.CUSTOM && !Prefs.customFile(ctx, a.pkg).exists()) {
                                picker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly))
                            } else { Prefs.setMode(ctx, a.pkg, m); mode = m; onChanged() }
                        },
                        shape = SegmentedButtonDefaults.itemShape(i, options.size),
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = Palette.White, activeContentColor = Palette.Black,
                            inactiveContainerColor = Palette.Circle, inactiveContentColor = Palette.White,
                            activeBorderColor = Palette.White, inactiveBorderColor = Palette.Line,
                        ),
                        icon = {},
                    ) { Text(label) }
                }
            }
            Text(
                when (mode) {
                    Prefs.Mode.AUTO -> "Drawn from the app's own icon."
                    Prefs.Mode.CUSTOM -> "Drawn from an image you choose."
                    Prefs.Mode.LETTER -> "The first letter of its name."
                    Prefs.Mode.THEME -> "Left to the theme."
                },
                style = MaterialTheme.typography.bodyMedium, color = Palette.Muted,
            )
            if (mode == Prefs.Mode.CUSTOM || r?.kind == Kind.MISSING) {
                PickAnIcon(a, packs, onPack = { pk -> packFor = pk },
                    onGallery = { picker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) },
                    onUse = { bmp, whole ->
                        Prefs.customFile(ctx, a.pkg).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        Prefs.setMode(ctx, a.pkg, Prefs.Mode.CUSTOM); mode = Prefs.Mode.CUSTOM
                        Prefs.setAsIs(ctx, a.pkg, whole); asIs = whole; onChanged()
                    },
                    onLetter = { Prefs.setMode(ctx, a.pkg, Prefs.Mode.LETTER); mode = Prefs.Mode.LETTER; onChanged() })
            }
            if (mode == Prefs.Mode.CUSTOM) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Use as it is", style = MaterialTheme.typography.bodyMedium)
                        Text("The picture whole, not turned into a glyph.", style = MaterialTheme.typography.labelSmall, color = Palette.Muted)
                    }
                    Switch(checked = asIs, onCheckedChange = { asIs = it; Prefs.setAsIs(ctx, a.pkg, it); onChanged() }, colors = switchColors())
                }
                if (asIs) {
                    var back by remember { mutableStateOf(Prefs.pictureBack(ctx, a.pkg)) }
                    var backColor by remember { mutableStateOf(Prefs.pictureColor(ctx, a.pkg)) }
                    Text("Background", style = MaterialTheme.typography.bodyMedium)
                    Segments(listOf("none" to "None", "style" to "Style's", "color" to "Colour"), back) {
                        back = it; Prefs.setPictureBack(ctx, a.pkg, it); onChanged()
                    }
                    Text(
                        when (back) {
                            "style" -> "On the look's own background, like the icons around it."
                            "color" -> "On the look's shape, in a colour you choose."
                            else -> "The picture alone."
                        },
                        style = MaterialTheme.typography.labelSmall, color = Palette.Muted,
                    )
                    if (back == "color") ColorRow("Colour", backColor) {
                        backColor = it; Prefs.setPictureColor(ctx, a.pkg, it); onChanged()
                    }
                }
            }

            if (mode != Prefs.Mode.THEME) Column {
                Row {
                    Text("Size", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text("${(size * 100).roundToInt()}%", style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
                }
                Slider(
                    value = size, onValueChange = { size = (it * 20).roundToInt() / 20f }, valueRange = 0.6f..1.4f, steps = 15,
                    colors = sliderColors(),
                )
            }

            OutlinedButton(
                onClick = onPreview, enabled = r?.bitmap != null, modifier = Modifier.fillMaxWidth().height(48.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Palette.Line),
            ) { Text("Preview on home screen", color = Palette.White) }

            if (mode != Prefs.Mode.THEME) {
                var tuning by remember { mutableStateOf(Prefs.tuning(ctx, a.pkg)) }
                var custom by remember { mutableStateOf(Prefs.hasTuning(ctx, a.pkg)) }
                Advanced(subtitle = if (custom) "Changed for this app" else "Using the defaults") {
                    TuningEditor(tuning, showSource = mode == Prefs.Mode.AUTO) {
                        tuning = it; custom = true; Prefs.setTuning(ctx, a.pkg, it); onChanged()
                    }
                    if (custom) TextButton(onClick = {
                        Prefs.clearTuning(ctx, a.pkg); custom = false; tuning = Prefs.tuning(ctx, a.pkg); onChanged()
                    }, contentPadding = PaddingValues(0.dp)) { Text("Use the defaults") }
                }
            }
        }
    }
}

@Composable
internal fun switchColors() = SwitchDefaults.colors(
    checkedThumbColor = Palette.Black, checkedTrackColor = Palette.White,
    uncheckedThumbColor = Palette.Muted, uncheckedTrackColor = Palette.Black, uncheckedBorderColor = Palette.Line,
)

private val SWATCHES = listOf(0xFF1C1C1C, 0xFF000000, 0xFFF2F2F2, 0xFFFFFFFF, 0xFFD71921, 0xFF2D5BFF, 0xFF1E8E5A, 0xFFF2B705).map { it.toInt() }

/** Colour choice: common swatches, and a hex field for anything else. */
@Composable
internal fun ColorRow(label: String, value: Int, onChange: (Int) -> Unit) {
    var hex by remember(value) { mutableStateOf("%06X".format(value and 0xFFFFFF)) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SWATCHES.forEach { c ->
                Box(
                    Modifier.size(28.dp).clip(CircleShape).background(Color(c))
                        .border(2.dp, if (c == value) Palette.White else Palette.Line, CircleShape)
                        .clickable { onChange(c) },
                )
            }
        }
        TextField(
            value = hex, singleLine = true, prefix = { Text("#") },
            onValueChange = { v ->
                hex = v.uppercase().filter { it in "0123456789ABCDEF" }.take(6)
                if (hex.length == 6) onChange(0xFF000000.toInt() or hex.toInt(16))
            },
            colors = TextFieldDefaults.colors(focusedContainerColor = Palette.Black, unfocusedContainerColor = Palette.Black,
                focusedIndicatorColor = Palette.White, unfocusedIndicatorColor = Palette.Line, cursorColor = Palette.White),
            modifier = Modifier.width(140.dp),
        )
    }
}


/** Any icon from an installed pack, with search, for one app. */
@Composable
private fun PackIconPicker(pack: IconPack, packs: List<IconPack>, initialQuery: String = "", onSwitch: (IconPack) -> Unit, onDismiss: () -> Unit, onPick: (Bitmap) -> Unit) {
    var q by remember { mutableStateOf(initialQuery) }
    val names by produceState(emptyList<String>(), pack.pkg) { value = withContext(Dispatchers.Default) { pack.allNames() } }
    val shown = remember(names, q) { if (q.isBlank()) names else names.filter { it.contains(q.trim().replace(' ', '_'), true) } }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Palette.Circle) {
        Column(Modifier.padding(horizontal = 20.dp).fillMaxHeight(0.85f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (packs.size > 1) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                packs.forEach { p ->
                    FilterChip(selected = p.pkg == pack.pkg, onClick = { onSwitch(p) }, label = { Text(p.label) },
                        shape = RoundedCornerShape(20.dp),
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Palette.White, selectedLabelColor = Palette.Black,
                            containerColor = Palette.Circle, labelColor = Palette.White),
                        border = FilterChipDefaults.filterChipBorder(enabled = true, selected = p.pkg == pack.pkg, borderColor = Palette.Line))
                }
            } else Text(pack.label, style = MaterialTheme.typography.titleMedium)
            TextField(
                value = q, onValueChange = { q = it }, singleLine = true, placeholder = { Text("Search ${names.size} icons") },
                leadingIcon = { Icon(Icons.Filled.Search, null) }, shape = RoundedCornerShape(28.dp),
                colors = TextFieldDefaults.colors(focusedContainerColor = Palette.Black, unfocusedContainerColor = Palette.Black,
                    focusedIndicatorColor = Palette.Black, unfocusedIndicatorColor = Palette.Black, cursorColor = Palette.White),
                modifier = Modifier.fillMaxWidth(),
            )
            LazyVerticalGrid(columns = GridCells.Fixed(5), verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(shown, key = { it }) { n ->
                    val bmp by produceState<Bitmap?>(null, pack.pkg, n) { value = withContext(Dispatchers.Default) { pack.bitmap(n, 144) } }
                    Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(12.dp)).clickable {
                        pack.bitmap(n, 432)?.let(onPick)
                    }, contentAlignment = Alignment.Center) {
                        bmp?.let { Image(it.asImageBitmap(), n, Modifier.fillMaxSize()) }
                    }
                }
            }
        }
    }
}

/**
 * Where an icon can come from when the app's own doesn't work: any installed pack that has
 * one for this very app (one tap), the first letter, a pack browsed by hand, or the gallery.
 */
@Composable
private fun PickAnIcon(
    a: App, packs: List<IconPack>, onPack: (IconPack) -> Unit, onGallery: () -> Unit,
    onUse: (Bitmap, Boolean) -> Unit, onLetter: () -> Unit,
) {
    val pkg = a.pkg.substringBefore('/'); val cls = a.pkg.substringAfter('/', "").ifEmpty { null }
    val suggestions by produceState(emptyList<Pair<String, Bitmap>>(), a.pkg, packs) {
        value = withContext(Dispatchers.Default) {
            packs.mapNotNull { p -> p.covers(pkg, cls)?.let { n -> p.bitmap(n, 432)?.let { p.label to it } } }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Pick an icon", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            suggestions.forEach { (label, bmp) ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(72.dp).clickable { onUse(bmp, true) }) {
                    Image(bmp.asImageBitmap(), label, Modifier.size(56.dp))
                    Text(label, style = MaterialTheme.typography.labelSmall, color = Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(72.dp).clickable(onClick = onLetter)) {
                Box(Modifier.size(56.dp).clip(CircleShape).background(Palette.Black), contentAlignment = Alignment.Center) {
                    Text(a.label.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?", style = MaterialTheme.typography.titleMedium)
                }
                Text("Letter", style = MaterialTheme.typography.labelSmall, color = Palette.Muted)
            }
        }
        if (suggestions.isEmpty() && packs.isNotEmpty())
            Text("No installed pack has an icon made for this app.", style = MaterialTheme.typography.labelSmall, color = Palette.Muted)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (packs.isNotEmpty()) OutlinedButton(
                onClick = { onPack(packs.first()) }, modifier = Modifier.weight(1f),
                border = androidx.compose.foundation.BorderStroke(1.dp, Palette.Line),
            ) { Text("Browse a pack", color = Palette.White) }
            OutlinedButton(
                onClick = onGallery, modifier = Modifier.weight(1f),
                border = androidx.compose.foundation.BorderStroke(1.dp, Palette.Line),
            ) { Text("From gallery", color = Palette.White) }
        }
    }
}

@Composable
internal fun sliderColors() = SliderDefaults.colors(
    thumbColor = Palette.White, activeTrackColor = Palette.White, inactiveTrackColor = Palette.Line,
    activeTickColor = Palette.Black, inactiveTickColor = Palette.Muted,
)

/** A collapsed section: one line until it is opened. */
@Composable
internal fun Advanced(subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 4.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("Advanced", style = MaterialTheme.typography.bodyMedium)
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = Palette.Muted)
            }
            Text(if (open) "Hide" else "Show", color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
        }
        if (open) content()
    }
}

@Composable
internal fun <T> Segments(options: List<Pair<T, String>>, value: T, onPick: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (v, label) ->
            SegmentedButton(
                selected = v == value, onClick = { onPick(v) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = Palette.White, activeContentColor = Palette.Black,
                    inactiveContainerColor = Palette.Circle, inactiveContentColor = Palette.White,
                    activeBorderColor = Palette.White, inactiveBorderColor = Palette.Line,
                ),
                icon = {},
            ) { Text(label, maxLines = 1) }
        }
    }
}

/** How a glyph is made. The same controls set the defaults (Settings) and one app (its sheet). */
@Composable
internal fun ColumnScope.TuningEditor(t: Prefs.Tuning, showSource: Boolean, onChange: (Prefs.Tuning) -> Unit) {
    if (showSource) {
        Text("Source", style = MaterialTheme.typography.bodyMedium)
        Segments(listOf("auto" to "Auto", "glyph" to "Glyph", "shape" to "Shape", "cutout" to "Cut-out"), t.source) {
            onChange(t.copy(source = it))
        }
        Text(
            when (t.source) {
                "glyph" -> "The app's own monochrome icon."
                "shape" -> "The outline of the app's icon."
                "cutout" -> "The logo separated from its background."
                else -> "The best of the three."
            },
            style = MaterialTheme.typography.labelSmall, color = Palette.Muted,
        )
    }
    Text("Stroke", style = MaterialTheme.typography.bodyMedium)
    Segments(listOf<Pair<Int?, String>>(null to "Auto", -1 to "Thin", 0 to "Normal", 1 to "Bold"), t.stroke) {
        onChange(t.copy(stroke = it))
    }
    var sens by remember(t.sensitivity) { mutableFloatStateOf(t.sensitivity) }
    Column {
        Row {
            Text("Cut-out sensitivity", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text("${(sens * 100).roundToInt()}%", style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
        }
        Slider(
            value = sens, onValueChange = { sens = (it * 10).roundToInt() / 10f },
            onValueChangeFinished = { onChange(t.copy(sensitivity = sens)) },
            valueRange = 0.5f..2f, steps = 14, colors = sliderColors(),
        )
        Text("Higher keeps fainter parts of the logo.", style = MaterialTheme.typography.labelSmall, color = Palette.Muted)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Crisp edges", style = MaterialTheme.typography.bodyMedium)
            Text("Hard edges instead of smooth ones.", style = MaterialTheme.typography.labelSmall, color = Palette.Muted)
        }
        Switch(checked = t.crisp, onCheckedChange = { onChange(t.copy(crisp = it)) }, colors = switchColors())
    }
}

/**
 * The app on a home screen: the theme's own launcher preview behind it, its neighbours by
 * name around it, at the size icons really are. Judged in place, not on its own.
 */
@Composable
private fun PreviewScreen(
    themeId: String?, focus: String, icons: List<Triple<String, String, Builder.Result?>>,
    builder: Builder, onDismiss: () -> Unit,
) {
    val backdrop by produceState<Bitmap?>(null, themeId) {
        value = themeId?.let { id ->
            withContext(Dispatchers.Default) {
                ThemeStore.preview(id)?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }
            }
        }
    }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(Modifier.fillMaxSize().background(Palette.Black).clickable(onClick = onDismiss)) {
            backdrop?.let {
                Image(it.asImageBitmap(), null, Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop)
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(4), userScrollEnabled = false,
                verticalArrangement = Arrangement.spacedBy(22.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 20.dp),
                modifier = Modifier.align(Alignment.Center).fillMaxWidth(),
            ) {
                items(icons, key = { it.first }) { (pkg, label, r) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        IconCircle(r, pkg, builder, 62.dp)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            label, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.labelSmall.copy(
                                shadow = androidx.compose.ui.graphics.Shadow(Color.Black, blurRadius = 6f)),
                            color = Color.White,
                        )
                        if (pkg == focus) Box(Modifier.padding(top = 4.dp).size(5.dp).clip(CircleShape).background(Color.White))
                    }
                }
            }
            Text(
                "Tap anywhere to close", color = Color.White, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 24.dp),
            )
        }
    }
}

@Composable
private fun SettingsSheet(ready: Boolean, onDismiss: () -> Unit, onScale: () -> Unit, onTuning: () -> Unit, onRestore: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Palette.Circle) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text("Settings", style = MaterialTheme.typography.titleMedium)
            val ctx = LocalContext.current
            var theme by remember { mutableStateOf(Prefs.appTheme(ctx)) }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("App theme", style = MaterialTheme.typography.bodyMedium)
                Segments(Palette.THEMES, theme) {
                    theme = it; Prefs.setAppTheme(ctx, it); Palette.apply(it)
                    (ctx as? MainActivity)?.bars()
                }
            }
            HorizontalDivider(color = Palette.Line)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("How it works", style = MaterialTheme.typography.bodyMedium)
                Text("Build icons writes them into Theme backup. Applying Theme backup in Themes puts them on screen. " +
                    "Themes lists them as SonderIcons too; that entry can't be applied on its own.",
                    style = MaterialTheme.typography.labelSmall, color = Palette.Muted)
            }
            HorizontalDivider(color = Palette.Line)
            var backupNote by remember { mutableStateOf<String?>(null) }
            val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri: Uri? ->
                uri?.let { backupNote = if (Backup.save(ctx, it)) "Backup saved." else "Couldn't save the backup." }
            }
            val loader = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
                uri?.let {
                    val err = Backup.restore(ctx, it)
                    if (err == null) (ctx as? android.app.Activity)?.recreate() else backupNote = err
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Backup", style = MaterialTheme.typography.bodyMedium)
                Text("Your looks, every app's choices and your pictures, in one file. Restore replaces what's here now.",
                    style = MaterialTheme.typography.labelSmall, color = Palette.Muted)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { saver.launch("SonderIcons-backup.zip") }, modifier = Modifier.weight(1f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Palette.Line)) { Text("Save backup", color = Palette.White) }
                    OutlinedButton(onClick = { loader.launch(arrayOf("application/zip", "application/octet-stream")) }, modifier = Modifier.weight(1f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Palette.Line)) { Text("Restore", color = Palette.White) }
                }
                backupNote?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Palette.Muted) }
            }
            HorizontalDivider(color = Palette.Line)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Restore original icons", style = MaterialTheme.typography.bodyMedium)
                Text("Puts the theme's own icons back. Apply Theme backup afterwards.", style = MaterialTheme.typography.labelSmall, color = Palette.Muted)
                TextButton(onClick = onRestore, enabled = ready, contentPadding = PaddingValues(0.dp)) { Text("Restore", color = Palette.Red) }
            }
        }
    }
}
