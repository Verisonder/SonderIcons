package com.verisonder.sondericons.ui

import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.verisonder.sondericons.Builder
import com.verisonder.sondericons.Prefs
import com.verisonder.sondericons.Shell
import com.verisonder.sondericons.ThemeStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private var resumes by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface { Home(resumes) } } }
    }

    override fun onResume() { super.onResume(); resumes++ }
}

private class AppItem(val pkg: String, val label: String)

@Composable
private fun Home(resumes: Int) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val builder = remember { Builder(ctx) }
    var shizuku by remember { mutableStateOf("…") }
    var base by remember { mutableStateOf<Builder.Base?>(null) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var apps by remember { mutableStateOf<List<AppItem>>(emptyList()) }
    val previews = remember { mutableStateMapOf<String, Bitmap?>() }
    var editing by remember { mutableStateOf<AppItem?>(null) }
    var version by remember { mutableIntStateOf(0) }   // bumps to redraw previews

    LaunchedEffect(resumes) {
        shizuku = when { Shell.available() -> "ready"; Shell.running() -> "permission"; else -> "off" }
        withContext(Dispatchers.Default) {
            val pm = ctx.packageManager
            apps = builder.launchablePackages().map { p ->
                AppItem(p, runCatching { pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString() }.getOrDefault(p))
            }.sortedBy { it.label.lowercase() }
            if (shizuku == "ready" && base == null) {
                val t = ThemeStore.findBackupTheme(Prefs.themeId(ctx), Prefs.ownIconsId(ctx))
                base = t?.let { builder.loadBase(it) }
                if (base == null) status = "No Theme backup found"
            }
        }
    }

    // previews are drawn lazily, off the main thread, once the theme is known
    LaunchedEffect(base, version) {
        val b = base ?: return@LaunchedEffect
        withContext(Dispatchers.Default) {
            for (a in apps) previews[a.pkg] = runCatching { builder.iconFor(a.pkg, b).first }.getOrNull()
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        val a = editing ?: return@rememberLauncherForActivityResult
        if (uri != null) {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                Prefs.customFile(ctx, a.pkg).outputStream().use { input.copyTo(it) }
            }
            Prefs.setMode(ctx, a.pkg, Prefs.Mode.CUSTOM)
            version++
        }
        editing = null
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize().systemBarsPadding(),
    ) {
        item(span = { GridItemSpan(4) }) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("SonderIcons", fontSize = 28.sp)
                when (shizuku) {
                    "ready" -> Text("Shizuku ready")
                    "permission" -> Button(onClick = { Shell.requestPermission() }) { Text("Allow Shizuku") }
                    else -> Text("Start Shizuku first")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = shizuku == "ready" && !busy, onClick = {
                        busy = true
                        scope.launch {
                            val r = withContext(Dispatchers.Default) { builder.build { status = it } }
                            status = r.error ?: "Built ${r.made} icons. Apply Theme backup in Themes."
                            busy = false
                        }
                    }) { Text("Build icons") }
                    OutlinedButton(enabled = !busy, onClick = {
                        ctx.packageManager.getLaunchIntentForPackage(ThemeStore.THEMES_PKG)?.let { ctx.startActivity(it) }
                    }) { Text("Open Themes") }
                }
                if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodyMedium)
                TextButton(enabled = shizuku == "ready" && !busy, onClick = {
                    scope.launch { status = withContext(Dispatchers.Default) { builder.restore() } ?: "Original icons linked. Apply Theme backup." }
                }) { Text("Restore original icons") }
                HorizontalDivider()
            }
        }
        items(apps, key = { it.pkg }) { a ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable { editing = a },
            ) {
                val bmp = previews[a.pkg]
                Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                    if (bmp != null) Image(bmp.asImageBitmap(), a.label, Modifier.fillMaxSize())
                    else ThemeIconPlaceholder()
                }
                Text(a.label, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }

    editing?.let { a ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(a.label) },
            text = {
                Column {
                    Choice("Monochrome") { Prefs.setMode(ctx, a.pkg, Prefs.Mode.AUTO); version++; editing = null }
                    Choice("Pick an image") {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                    Choice("Keep theme icon") { Prefs.setMode(ctx, a.pkg, Prefs.Mode.THEME); version++; editing = null }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Close") } },
        )
    }
}

@Composable
private fun Choice(label: String, onClick: () -> Unit) {
    Text(label, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp))
}

/** Shown where the theme's own icon (or its fallback) will be used. */
@Composable
private fun ThemeIconPlaceholder() {
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxSize()) {
        Box(contentAlignment = Alignment.Center) { Text("theme", fontSize = 10.sp) }
    }
}
