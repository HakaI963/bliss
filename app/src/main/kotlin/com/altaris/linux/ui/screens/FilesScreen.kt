package com.altaris.linux.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.altaris.linux.core.files.FileBrowser
import com.altaris.linux.ui.components.PrismButton
import com.altaris.linux.ui.components.PrismCard
import com.altaris.linux.ui.components.PrismChip
import com.altaris.linux.ui.components.PrismNote
import com.altaris.linux.ui.components.PrismOrb
import com.altaris.linux.ui.components.PrismRow
import com.altaris.linux.ui.components.PrismSecondaryButton
import com.altaris.linux.ui.theme.Prism
import com.altaris.linux.ui.theme.altarisMutedText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A real file manager.
 *
 * It browses three kinds of place, and the distinction matters:
 *
 *  - **host** — internal storage, Downloads, the app's own data, and (with
 *    root) /data and /system;
 *  - **proot guest** — a distro rootfs, which is ordinary files to this
 *    process;
 *  - **chroot guest** — a tree under /data/local, which SELinux hides from the
 *    app uid, so every operation on it runs through root.
 *
 * That is why this is not a plain directory list: a chroot that is really there
 * reads as an empty folder unless the read goes through `su`.
 */
@Composable
fun FilesScreen(
    modifier: Modifier = Modifier,
    embeddedInBottomNav: Boolean = false,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accent = MaterialTheme.colorScheme.primary

    var path by remember { mutableStateOf("") }
    var listing by remember { mutableStateOf(FileBrowser.Listing("")) }
    var loading by remember { mutableStateOf(false) }
    var showHidden by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<FileBrowser.Entry?>(null) }
    var pendingDelete by remember { mutableStateOf<FileBrowser.Entry?>(null) }
    var editing by remember { mutableStateOf<Pair<String, String>?>(null) }
    var creating by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<FileBrowser.Entry?>(null) }
    var places by remember { mutableStateOf<List<FileBrowser.Place>>(emptyList()) }
    var notice by remember { mutableStateOf("") }

    suspend fun load(target: String) {
        loading = true
        val result = withContext(Dispatchers.IO) {
            FileBrowser.list(context, target, showHidden)
        }
        listing = result
        path = target
        loading = false
    }

    LaunchedEffect(Unit) {
        places = withContext(Dispatchers.IO) { FileBrowser.places(context) }
        load(FileBrowser.defaultPath(context))
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = if (embeddedInBottomNav) Prism.Gutter else 0.dp)
            .padding(top = 6.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        // Where you are.
        PrismCard(accent = accent) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Files", fontSize = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                PrismChip(
                    if (showHidden) "Hidden on" else "Hidden off",
                    accent,
                    selected = showHidden,
                    onClick = {
                        showHidden = !showHidden
                        scope.launch { load(path) }
                    },
                )
            }
            Spacer(Modifier.height(3.dp))
            // Breadcrumbs scroll horizontally so a deep path never pushes the
            // controls off screen.
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val crumbs = remember(path, places) { FileBrowser.crumbs(context, path) }
                crumbs.forEachIndexed { index, (label, target) ->
                    if (index > 0) {
                        Text(" / ", fontSize = 8.sp, color = altarisMutedText())
                    }
                    Text(
                        label,
                        fontSize = 8.5.sp,
                        fontFamily = FontFamily.Monospace,
                        color = if (index == crumbs.lastIndex) accent else altarisMutedText(),
                        maxLines = 1,
                        modifier = Modifier
                            .clip(RoundedCornerShape(3.dp))
                            .clickable { scope.launch { load(target) } }
                            .padding(horizontal = 2.dp, vertical = 1.dp),
                    )
                }
            }
            Spacer(Modifier.height(3.dp))
            // Places, in one scrollable row.
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                places.forEach { place ->
                    PrismChip(
                        place.label,
                        accent,
                        selected = path.startsWith(place.path),
                        onClick = { scope.launch { load(place.path) } },
                    )
                }
            }
            if (notice.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                PrismNote(notice, fontSize = 9.sp)
            }
        }

        // Actions for the current directory.
        PrismCard(accent = accent) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PrismChip("Up", accent) {
                    val parent = FileBrowser.parent(context, path)
                    if (parent != null) scope.launch { load(parent) }
                }
                PrismChip("New file", accent) { creating = "file" }
                PrismChip("New folder", accent) { creating = "dir" }
                PrismChip("Refresh", accent) { scope.launch { load(path) } }
            }
        }

        // The listing.
        PrismCard(accent = accent, modifier = Modifier.fillMaxWidth().weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    listing.entries.size.toString() + " items",
                    fontSize = 8.5.sp,
                    color = altarisMutedText(),
                )
                if (listing.usedRoot) {
                    Spacer(Modifier.width(4.dp))
                    Text("· via root", fontSize = 8.sp, color = accent)
                }
                Spacer(Modifier.weight(1f))
                if (loading) Text("…", fontSize = 9.sp, color = accent)
            }
            if (listing.error.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                PrismNote(listing.error, color = MaterialTheme.colorScheme.error, fontSize = 9.sp)
                return@PrismCard
            }
            Spacer(Modifier.height(2.dp))
            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                items(listing.entries, key = { it.path }) { entry ->
                    FileRow(
                        entry = entry,
                        accent = accent,
                        onOpen = {
                            if (entry.dir) {
                                scope.launch { load(entry.path) }
                            } else if (FileBrowser.isViewable(entry)) {
                                scope.launch {
                                    val text = withContext(Dispatchers.IO) {
                                        FileBrowser.read(context, entry.path)
                                    }
                                    text.onSuccess { editing = entry.path to it }
                                        .onFailure { notice = it.message ?: "cannot open" }
                                }
                            } else {
                                picked = entry
                            }
                        },
                        onMore = { picked = entry },
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------- dialogs

    picked?.let { entry ->
        val isDir = entry.dir
        AlertDialog(
            onDismissRequest = { picked = null },
            confirmButton = {
                TextButton(onClick = { picked = null }) { Text("Close", color = accent) }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (!isDir && FileBrowser.isViewable(entry)) {
                        TextButton(onClick = {
                            picked = null
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    FileBrowser.read(context, entry.path)
                                }.onSuccess { editing = entry.path to it }
                                    .onFailure { notice = it.message ?: "cannot open" }
                            }
                        }) { Text("Edit", color = accent) }
                    }
                    TextButton(onClick = {
                        picked = null
                        renaming = entry
                    }) { Text("Rename", color = accent) }
                    TextButton(onClick = {
                        picked = null
                        pendingDelete = entry
                    }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                }
            },
            title = { Text(entry.name, fontSize = 12.sp, fontFamily = FontFamily.Monospace) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Detail(entry.path, accent)
                    if (!isDir) {
                        Detail(com.altaris.linux.core.rea.ReaDistro.formatBytes(entry.size), accent)
                    }
                    if (entry.modified > 0) {
                        Detail(formatTime(entry.modified), accent)
                    }
                    if (entry.mode.isNotBlank()) Detail(entry.mode, accent)
                    if (entry.needsRoot) {
                        Text(
                            "Needs root to read.",
                            fontSize = 9.sp,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
        )
    }

    pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    scope.launch {
                        withContext(Dispatchers.IO) { FileBrowser.delete(context, entry.path) }
                            .onFailure { notice = it.message ?: "delete failed" }
                            .onSuccess { notice = "Deleted " + entry.name }
                        load(path)
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Keep") }
            },
            title = { Text("Delete " + entry.name + "?", fontSize = 13.sp) },
            text = {
                Text(
                    if (entry.dir) {
                        "This deletes the folder and everything in it. It cannot be undone."
                    } else {
                        "This deletes the file. It cannot be undone."
                    },
                    fontSize = 11.sp,
                )
            },
        )
    }

    editing?.let { (file, body) ->
        var draft by remember(file) { mutableStateOf(body) }
        AlertDialog(
            onDismissRequest = { editing = null },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { FileBrowser.write(context, file, draft) }
                            .onFailure { notice = it.message ?: "write failed" }
                            .onSuccess { notice = "Saved" }
                        editing = null
                        load(path)
                    }
                }) { Text("Save", color = accent) }
            },
            dismissButton = {
                TextButton(onClick = { editing = null }) { Text("Cancel") }
            },
            title = { Text(file.substringAfterLast('/'), fontSize = 12.sp) },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth().height(300.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                    ),
                )
            },
        )
    }

    creating?.let { kind ->
        var name by remember(kind) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { creating = null },
            confirmButton = {
                TextButton(onClick = {
                    val target = path.trimEnd('/') + "/" + name.trim()
                    if (name.isBlank()) return@TextButton
                    creating = null
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            if (kind == "dir") FileBrowser.createDirectory(context, target)
                            else FileBrowser.createFile(context, target)
                        }.onFailure { notice = it.message ?: "failed" }
                        load(path)
                    }
                }) { Text("Create", color = accent) }
            },
            dismissButton = {
                TextButton(onClick = { creating = null }) { Text("Cancel") }
            },
            title = {
                Text(if (kind == "dir") "New folder" else "New file", fontSize = 13.sp)
            },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("name", fontSize = 10.sp) },
                    singleLine = true,
                )
            },
        )
    }

    renaming?.let { entry ->
        var name by remember(entry.path) { mutableStateOf(entry.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            confirmButton = {
                TextButton(onClick = {
                    val parent = entry.path.substringBeforeLast('/')
                    val target = parent + "/" + name.trim()
                    renaming = null
                    if (name.isBlank() || target == entry.path) return@TextButton
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            FileBrowser.rename(context, entry.path, target)
                        }.onFailure { notice = it.message ?: "rename failed" }
                        load(path)
                    }
                }) { Text("Rename", color = accent) }
            },
            dismissButton = {
                TextButton(onClick = { renaming = null }) { Text("Cancel") }
            },
            title = { Text("Rename", fontSize = 13.sp) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            },
        )
    }
}

@Composable
private fun FileRow(
    entry: FileBrowser.Entry,
    accent: androidx.compose.ui.graphics.Color,
    onOpen: () -> Unit,
    onMore: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(3.dp))
            .clickable(onClick = onOpen)
            .padding(horizontal = 3.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(
                    if (entry.dir) accent else MaterialTheme.colorScheme.outline
                ),
        )
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.name,
                fontSize = 9.5.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (entry.needsRoot) altarisMutedText() else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            val meta = buildList {
                if (!entry.dir) add(com.altaris.linux.core.rea.ReaDistro.formatBytes(entry.size))
                if (entry.modified > 0) add(formatTime(entry.modified))
                if (entry.needsRoot) add("needs root")
            }.joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(meta, fontSize = 7.5.sp, color = altarisMutedText(), maxLines = 1)
            }
        }
        Text(
            if (entry.dir) "dir" else entry.ext.ifBlank { "file" },
            fontSize = 7.5.sp,
            color = altarisMutedText(),
            modifier = Modifier
                .clip(RoundedCornerShape(3.dp))
                .clickable(onClick = onMore)
                .padding(horizontal = 4.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun Detail(text: String, accent: androidx.compose.ui.graphics.Color) {
    Text(
        text,
        fontSize = 9.sp,
        fontFamily = FontFamily.Monospace,
        color = altarisMutedText(),
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
    )
}

private fun formatTime(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(millis))
