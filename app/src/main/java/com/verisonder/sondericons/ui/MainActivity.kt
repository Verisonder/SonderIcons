@file:OptIn(ExperimentalMaterial3Api::class)

package com.verisonder.sondericons.ui

import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private var resumes by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { SonderTheme { Surface(color = Palette.Black) { Home(resumes) } } }
    }

    override fun onResume() { super.onResume(); resumes++ }
}

private class App(val pkg: String, val label: String)

private enum class Filter(val title: String) { ALL("All"), DRAWN("Drawn"), YOURS("Yours"), THEME("Theme"), MISSING("Missing") }

private fun Filter.matches(k: Kind?) = when (this) {
    Filter.ALL -> true
    Filter.DRAWN -> k == Kind.DRAWN
    Filter.YOURS -> k == Kind.CUSTOM
    Filter.THEME -> k == Kind.THEME
    Filter.MISSING -> k == Kind.MISSING
}

@Composable
private fun Home(resumes: Int) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val builder = remember { Builder(ctx) }

    var shizuku by remember { mutableStateOf<Boolean?>(null) }       // null = not running
    var base by remember { mutableStateOf<Builder.Base?>(null) }
    var noTheme by remember { mutableStateOf(false) }
    var apps by remember { mutableStateOf<List<App>>(emptyList()) }
    val results = remember { mutableStateMapOf<String, Builder.Result>() }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(Filter.ALL) }
    var open by remember { mutableStateOf<App?>(null) }
    var settings by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    fun refresh(pkg: String) = scope.launch {
        val b = base ?: return@launch
        withContext(Dispatchers.Default) { runCatching { builder.resultFor(pkg, b) }.getOrNull() }?.let { results[pkg] = it }
    }
    fun refreshAll() = scope.launch {
        val b = base ?: return@launch
        withContext(Dispatchers.Default) {
            for (a in apps) runCatching { builder.resultFor(a.pkg, b) }.getOrNull()?.let { results[a.pkg] = it }
        }
    }

    LaunchedEffect(resumes) {
        shizuku = when { Shell.available() -> true; Shell.running() -> false; else -> null }
        withContext(Dispatchers.Default) {
            val pm = ctx.packageManager
            apps = builder.launchablePackages().map { p ->
                App(p, runCatching { pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString() }.getOrDefault(p))
            }.sortedBy { it.label.lowercase() }
            if (shizuku == true && base == null) {
                val t = ThemeStore.findBackupTheme(Prefs.themeId(ctx), Prefs.ownIconsId(ctx))
                base = t?.let { builder.loadBase(it) }
                noTheme = t == null
            }
        }
        if (base != null && results.isEmpty()) refreshAll()
    }

    val counts = remember(results.toMap()) { Filter.entries.associateWith { f -> apps.count { f.matches(results[it.pkg]?.kind) } } }
    val shown = apps.filter { a ->
        (query.isBlank() || a.label.contains(query, true) || a.pkg.contains(query, true)) &&
            (filter == Filter.ALL || filter.matches(results[a.pkg]?.kind))
    }

    Scaffold(
        containerColor = Palette.Black,
        bottomBar = {
            BuildBar(
                enabled = shizuku == true && base != null && busy == null,
                busy = busy, message = message,
                onBuild = {
                    busy = "Starting"; message = null
                    scope.launch {
                        val r = withContext(Dispatchers.Default) { builder.build { busy = it } }
                        busy = null
                        message = r.error ?: "${r.made} icons ready. Apply Theme backup in Themes."
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
                    shizuku = shizuku, noTheme = noTheme, ready = base != null, query = query, filter = filter, counts = counts,
                    onQuery = { query = it }, onFilter = { filter = it }, onSettings = { settings = true },
                    onAllow = { Shell.requestPermission() },
                    onOpenShizuku = { ctx.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")?.let { ctx.startActivity(it) } },
                )
            }
            items(shown, key = { it.pkg }) { a ->
                Tile(a, results[a.pkg], builder, onClick = { open = a })
            }
            if (base != null && shown.isEmpty()) item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(4) }) {
                Text(
                    if (filter == Filter.MISSING) "Every app has an icon." else "No apps match.",
                    color = Palette.Muted, modifier = Modifier.padding(top = 24.dp),
                )
            }
        }
    }

    val sheetApp = open
    if (sheetApp != null && base != null) {
        AppSheet(sheetApp, results[sheetApp.pkg], builder, onDismiss = { open = null }, onChanged = { refresh(sheetApp.pkg) })
    }
    if (settings) SettingsSheet(
        ready = shizuku == true && base != null,
        onDismiss = { settings = false },
        onScale = { refreshAll() },
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
    shizuku: Boolean?, noTheme: Boolean, ready: Boolean, query: String, filter: Filter, counts: Map<Filter, Int>,
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
            noTheme -> Status("No Theme backup found. Make one in Themes, Customize theme.", null, null)
            !ready -> Text("Reading your theme…", color = Palette.Muted)
        }
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
                if (f != Filter.ALL && n == 0) return@forEach
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
private fun Tile(a: App, r: Builder.Result?, builder: Builder, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick)) {
        Box(contentAlignment = Alignment.TopEnd) {
            IconCircle(r, a.pkg, builder, 62.dp)
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
private fun BuildBar(enabled: Boolean, busy: String?, message: String?, onBuild: () -> Unit, onThemes: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Palette.Black).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        HorizontalDivider(color = Palette.Line, modifier = Modifier.padding(bottom = 2.dp))
        (busy ?: message)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = if (busy != null) Palette.Muted else Palette.White) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onBuild, enabled = enabled, modifier = Modifier.weight(1f).height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Palette.White, contentColor = Palette.Black,
                    disabledContainerColor = Palette.Circle, disabledContentColor = Palette.Muted),
            ) {
                if (busy != null) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Palette.Muted)
                else Text("Build icons")
            }
            OutlinedButton(
                onClick = onThemes, modifier = Modifier.height(52.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Palette.Line),
            ) { Text("Open Themes", color = Palette.White) }
        }
    }
}

/** Everything about one app: what it will look like, where that comes from, and how to change it. */
@Composable
private fun AppSheet(a: App, r: Builder.Result?, builder: Builder, onDismiss: () -> Unit, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    var mode by remember { mutableStateOf(Prefs.mode(ctx, a.pkg)) }
    var size by remember { mutableFloatStateOf(Prefs.scale(ctx, a.pkg)) }
    val picker = rememberLauncherForActivityResult(PickVisualMedia()) { uri: Uri? ->
        if (uri != null) {
            ctx.contentResolver.openInputStream(uri)?.use { input -> Prefs.customFile(ctx, a.pkg).outputStream().use { input.copyTo(it) } }
            Prefs.setMode(ctx, a.pkg, Prefs.Mode.CUSTOM); mode = Prefs.Mode.CUSTOM; onChanged()
        }
    }
    // a size change redraws the preview once the slider settles
    LaunchedEffect(size) { delay(150); if (size != Prefs.scale(ctx, a.pkg)) { Prefs.setScale(ctx, a.pkg, size); onChanged() } }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Palette.Circle) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
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

            val options = listOf(Prefs.Mode.AUTO to "Auto", Prefs.Mode.CUSTOM to "Image", Prefs.Mode.THEME to "Theme")
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
                    Prefs.Mode.THEME -> "Left to the theme."
                },
                style = MaterialTheme.typography.bodyMedium, color = Palette.Muted,
            )
            if (mode == Prefs.Mode.CUSTOM) OutlinedButton(
                onClick = { picker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) },
                border = androidx.compose.foundation.BorderStroke(1.dp, Palette.Line),
            ) { Text("Change image", color = Palette.White) }

            if (mode != Prefs.Mode.THEME) Column {
                Row {
                    Text("Size", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text("${(size * 100).roundToInt()}%", style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
                }
                Slider(
                    value = size, onValueChange = { size = (it * 20).roundToInt() / 20f }, valueRange = 0.6f..1.4f, steps = 15,
                    colors = SliderDefaults.colors(thumbColor = Palette.White, activeTrackColor = Palette.White, inactiveTrackColor = Palette.Line,
                        activeTickColor = Palette.Black, inactiveTickColor = Palette.Muted),
                )
            }
        }
    }
}

@Composable
private fun SettingsSheet(ready: Boolean, onDismiss: () -> Unit, onScale: () -> Unit, onRestore: () -> Unit) {
    val ctx = LocalContext.current
    var size by remember { mutableFloatStateOf(Prefs.globalScale(ctx)) }
    LaunchedEffect(size) { delay(250); if (size != Prefs.globalScale(ctx)) { Prefs.setGlobalScale(ctx, size); onScale() } }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Palette.Circle) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text("Settings", style = MaterialTheme.typography.titleMedium)
            Column {
                Row {
                    Text("Glyph size for all apps", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text("${(size * 100).roundToInt()}%", style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
                }
                Slider(
                    value = size, onValueChange = { size = (it * 20).roundToInt() / 20f }, valueRange = 0.8f..1.2f, steps = 7,
                    colors = SliderDefaults.colors(thumbColor = Palette.White, activeTrackColor = Palette.White, inactiveTrackColor = Palette.Line,
                        activeTickColor = Palette.Black, inactiveTickColor = Palette.Muted),
                )
                Text("100% matches the theme's own icons.", style = MaterialTheme.typography.labelSmall, color = Palette.Muted)
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
