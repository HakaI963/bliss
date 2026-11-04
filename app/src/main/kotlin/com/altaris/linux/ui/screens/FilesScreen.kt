package com.altaris.linux.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.altaris.linux.ui.components.PrismChip
import com.altaris.linux.ui.components.PrismNote
import com.altaris.linux.ui.theme.Prism
import com.altaris.linux.ui.theme.altarisMutedText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One pane, two spaces.
 *
 * There were two panes side by side, a strip of eight location chips and a
 * breadcrumb bar, and opening the tab took a root shell before it could draw
 * anything. That is the freeze. A file manager needs one place at a time and
 * two destinations:
 *
 *  - **Internal** — Altaris' own root storage;
 *  - **External** — the chroot space, which is root-only.
 *
 * The space is chosen once at the top. Inside it, the breadcrumb row is the
 * only navigation, and it is drawn from the path already in hand — no call to
 * decide what the path means.
 */
@Composable
fun FilesScreen(
    modifier: Modifier = Modifier,
    embeddedInBottomNav: Boolean = false,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accent = MaterialTheme.colorScheme.primary

    var space by remember { mutableStateOf(FileBrowser.Space.INTERNAL) }
    var path by remember { mutableStateOf("") }
    var listing by remember { mutableStateOf(FileBrowser.Listing("")) }
    var loading by remember { mutableStateOf(true) }
    var notice by remember { mutableStateOf("") }
    var showHidden by remember { mutableStateOf(false) }
    var sort by remember { mutableStateOf(FileBrowser.Sort.NAME) }
    var descending by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<FileBrowser.Entry?>(null) }
    var pendingDelete by remember { mutableStateOf<FileBrowser.Entry?>(null) }
    var editing by remember { mutableStateOf<Pair<String, String>?>(null) }
    var creating by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<FileBrowser.Entry?>(null) }

    // Load a directory. The space is passed explicitly rather than derived from
    // the path: deriving it used to fork a root shell on every call.
    suspend fun load(target: String, which: FileBrowser.Space = space) {
        loading = true
        val result = withContext(Dispatchers.IO) {
            FileBrowser.list(context, which, target, showHidden)
        }
        path = target
        listing = result
        loading = false
    }

    // One initial load, in Internal. The old screen listed every place first —
    // eight `File` probes plus an `ls` under root — before it could show a row.
    LaunchedEffect(Unit) {
        val root = FileBrowser.rootOf(context, space)
        withContext(Dispatchers.IO) { FileBrowser.list(context, space, root, false) }
            .let {
                path = root
                listing = it
                loading = false
            }
    }

    fun openSpace(next: FileBrowser.Space) {
        if (next == space) return
        space = next
        query = ""
        notice = ""
        scope.launch { load(FileBrowser.rootOf(context, next), next) }
    }

    fun goUp() {
        FileBrowser.parent(context, path)?.let { parent -> scope.launch { load(parent) } }
    }

    // Android back navigates the browser before it leaves the app. This is the
    // handler the screen owns: the activity's own handler only knows about
    // routes, so back from a deep folder used to exit Altaris.
    BackHandler(enabled = FileBrowser.parent(context, path) != null) {
        goUp()
    }

    val visible = remember(listing, query, sort, descending) {
        FileBrowser.sorted(FileBrowser.filtered(listing.entries, query), sort, descending)
    }
    val listState = rememberLazyListState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = if (embeddedInBottomNav) Prism.Gutter else 0.dp)
            .padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // ---------------------------------------------------------- spaces
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FileBrowser.Space.entries.forEach { option ->
                PrismChip(
                    option.label,
                    accent,
                    selected = space == option,
                    onClick = { openSpace(option) },
                )
            }
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
            PrismChip("Refresh", accent) { scope.launch { load(path) } }
        }

        // ------------------------------------------------------------ location
        // One line, not a navigator. The old screen had a scrollable strip of
        // eight location chips plus clickable breadcrumbs; that is the "top
        // navigator" that was removed. The path is shown for orientation, Up
        // and Android back do the navigation, and nothing forks a shell to
        // decide what the path means.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (FileBrowser.parent(context, path) != null) {
                Text(
                    "↑",
                    fontSize = 14.sp,
                    color = accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { goUp() }
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                )
            }
            Text(
                path.removePrefix(FileBrowser.rootOf(context, space))
                    .ifBlank { "/" },
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                color = altarisMutedText(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }

        // ---------------------------------------------------------- filter
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().height(42.dp),
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 10.sp),
            placeholder = {
                Text(
                    "Filter " + path.substringAfterLast('/'),
                    fontSize = 10.sp,
                    color = altarisMutedText(),
                )
            },
        )

        // ----------------------------------------------------------- tools
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PrismChip("New folder", accent) { creating = "dir" }
            PrismChip("New file", accent) { creating = "file" }
            Spacer(Modifier.weight(1f))
            FileBrowser.Sort.entries.forEach { option ->
                PrismChip(
                    option.label,
                    accent,
                    selected = sort == option,
                    onClick = { sort = option },
                )
            }
            PrismChip(
                if (descending) "Z→A" else "A→Z",
                accent,
                selected = descending,
                onClick = { descending = !descending },
            )
        }

        // ---------------------------------------------------------- listing
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    loading -> "…"
                    query.isNotBlank() -> visible.size.toString() + " of " +
                        listing.entries.size
                    else -> listing.entries.size.toString() + " items"
                },
                fontSize = 8.5.sp,
                color = altarisMutedText(),
            )
            if (listing.usedRoot) {
                Spacer(Modifier.width(4.dp))
                Text("· root", fontSize = 8.sp, color = accent)
            }
        }

        if (listing.error.isNotBlank()) {
            PrismNote(
                listing.error,
                color = MaterialTheme.colorScheme.error,
                fontSize = 9.sp,
            )
        }
        if (notice.isNotBlank()) {
            PrismNote(notice, fontSize = 9.sp)
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(Prism.PanelRadius))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f))
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outline,
                    RoundedCornerShape(Prism.PanelRadius),
                ),
        ) {
            if (!loading && visible.isEmpty() && listing.error.isBlank()) {
                Text(
                    if (space == FileBrowser.Space.EXTERNAL) {
                        "No chroot trees here yet. Install a distro to fill this space."
                    } else {
                        "Empty folder."
                    },
                    fontSize = 9.sp,
                    color = altarisMutedText(),
                    modifier = Modifier.align(Alignment.Center).padding(12.dp),
                )
            }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(visible, key = { it.path }) { entry ->
                    FileRow(
                        entry = entry,
                        accent = accent,
                        onOpen = {
                            if (entry.dir) scope.launch { load(entry.path) }
                            else picked = entry
                        },
                        onMore = { picked = entry },
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------- dialogs

    picked?.let { entry ->
        AlertDialog(
            onDismissRequest = { picked = null },
            confirmButton = {
                TextButton(onClick = { picked = null }) { Text("Close", color = accent) }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (!entry.dir && FileBrowser.isViewable(entry)) {
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
                    if (!entry.dir) {
                        Detail(
                            com.altaris.linux.core.rea.ReaDistro.formatBytes(entry.size),
                            accent,
                        )
                    }
                    if (entry.modified > 0) Detail(formatTime(entry.modified), accent)
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
            title = { Text(if (kind == "dir") "New folder" else "New file", fontSize = 13.sp) },
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
            .padding(horizontal = 6.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(5.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (entry.dir) accent else MaterialTheme.colorScheme.outline),
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.name,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (entry.needsRoot) altarisMutedText() else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            if (!entry.dir) {
                Text(
                    com.altaris.linux.core.rea.ReaDistro.formatBytes(entry.size),
                    fontSize = 8.sp,
                    color = altarisMutedText(),
                )
            }
        }
        Text(
            "⋯",
            fontSize = 12.sp,
            color = altarisMutedText(),
            modifier = Modifier
                .clip(RoundedCornerShape(3.dp))
                .clickable(onClick = onMore)
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun Detail(text: String, accent: androidx.compose.ui.graphics.Color) {
    Text(
        text,
        fontSize = 8.5.sp,
        fontFamily = FontFamily.Monospace,
        color = accent,
    )
}

private fun formatTime(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(millis))
