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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.font.FontWeight
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
 * A dual-pane file manager.
 *
 * Two panes, each an independent location with its own history. The active
 * pane is the one a tap acts on; the other is the destination for a copy or a
 * move, which is the whole reason to have two — dragging a file from a distro
 * rootfs into internal storage is one action instead of three.
 *
 * It browses three kinds of place, and the distinction matters:
 *
 *  - **host** — internal storage, Downloads, the app's own data, and (with
 *    root) /data, /system, /vendor and the filesystem root;
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

    // One pane model, two instances. Everything per-pane lives here so the two
    // panes cannot drift in behaviour.
    var panes by remember { mutableStateOf(listOf(PaneState(), PaneState())) }
    var active by remember { mutableStateOf(0) }
    var dual by remember { mutableStateOf(true) }
    var places by remember { mutableStateOf<List<FileBrowser.Place>>(emptyList()) }
    var notice by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(FileBrowser.Sort.NAME) }
    var descending by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<Pair<Int, FileBrowser.Entry>?>(null) }
    var pendingDelete by remember { mutableStateOf<Pair<Int, FileBrowser.Entry>?>(null) }
    var editing by remember { mutableStateOf<Pair<String, String>?>(null) }
    var creating by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<Pair<Int, FileBrowser.Entry>?>(null) }

    suspend fun load(index: Int, target: String) {
        panes = panes.toMutableList().also { it[index] = it[index].copy(loading = true) }
        val result = withContext(Dispatchers.IO) {
            FileBrowser.list(context, target, panes[index].showHidden)
        }
        panes = panes.toMutableList().also {
            it[index] = it[index].copy(
                path = target,
                listing = result,
                loading = false,
            )
        }
    }

    LaunchedEffect(Unit) {
        places = withContext(Dispatchers.IO) { FileBrowser.places(context) }
        val start = FileBrowser.defaultPath(context)
        load(0, start)
        // The second pane opens on the same place: the first action is usually
        // "copy something out of here", and starting at the top-level list
        // would be a second navigation.
        load(1, start)
    }

    fun refreshBoth() {
        scope.launch {
            load(0, panes[0].path)
            load(1, panes[1].path)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = if (embeddedInBottomNav) Prism.Gutter else 0.dp)
            .padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // ---------------------------------------------------------- controls
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PrismChip(
                if (dual) "Two panes" else "One pane",
                accent,
                selected = dual,
                onClick = {
                    dual = !dual
                    if (!dual) active = 0
                },
            )
            PrismChip("New folder", accent) { creating = "dir" }
            PrismChip("New file", accent) { creating = "file" }
            PrismChip(
                if (panes[active].showHidden) "Hidden on" else "Hidden off",
                accent,
                selected = panes[active].showHidden,
                onClick = {
                    val next = !panes[active].showHidden
                    panes = panes.toMutableList().also {
                        it[active] = it[active].copy(showHidden = next)
                    }
                    scope.launch { load(active, panes[active].path) }
                },
            )
            PrismChip("Refresh", accent) { refreshBoth() }
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

        // Filter applies to the active pane only: it is a lens, not a mode.
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 10.sp),
            placeholder = {
                Text(
                    "Filter " + panes[active].path.substringAfterLast('/'),
                    fontSize = 10.sp,
                    color = altarisMutedText(),
                )
            },
        )

        // The two panes. In dual mode they split the height; in single mode the
        // active one takes everything.
        if (dual) {
            Pane(
                state = panes[0],
                active = active == 0,
                accent = accent,
                query = if (active == 0) query else "",
                sort = sort,
                descending = descending,
                places = places,
                modifier = Modifier.weight(1f),
                onActivate = { active = 0 },
                onNavigate = { scope.launch { load(0, it) } },
                onMore = { picked = 0 to it },
                onTransfer = { entry, move ->
                    scope.launch {
                        notice = transfer(context, entry, move, panes[1].path) { busy = it }
                        busy = ""
                        load(1, panes[1].path)
                    }
                },
            )
            Pane(
                state = panes[1],
                active = active == 1,
                accent = accent,
                query = if (active == 1) query else "",
                sort = sort,
                descending = descending,
                places = places,
                modifier = Modifier.weight(1f),
                onActivate = { active = 1 },
                onNavigate = { scope.launch { load(1, it) } },
                onMore = { picked = 1 to it },
                onTransfer = { entry, move ->
                    scope.launch {
                        notice = transfer(context, entry, move, panes[0].path) { busy = it }
                        busy = ""
                        load(0, panes[0].path)
                    }
                },
            )
        } else {
            Pane(
                state = panes[0],
                active = true,
                accent = accent,
                query = query,
                sort = sort,
                descending = descending,
                places = places,
                modifier = Modifier.weight(1f),
                onActivate = { active = 0 },
                onNavigate = { scope.launch { load(0, it) } },
                onMore = { picked = 0 to it },
                onTransfer = { _, _ -> },
            )
        }

        if (busy.isNotBlank()) {
            PrismNote(busy, fontSize = 9.sp)
        }
        if (notice.isNotBlank()) {
            PrismNote(notice, fontSize = 9.sp)
        }
    }

    // ------------------------------------------------------------- dialogs

    picked?.let { (index, entry) ->
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
                        renaming = index to entry
                    }) { Text("Rename", color = accent) }
                    TextButton(onClick = {
                        picked = null
                        pendingDelete = index to entry
                    }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                }
            },
            title = { Text(entry.name, fontSize = 12.sp, fontFamily = FontFamily.Monospace) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Detail(entry.path, accent)
                    if (!entry.dir) {
                        Detail(com.altaris.linux.core.rea.ReaDistro.formatBytes(entry.size), accent)
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

    pendingDelete?.let { (index, entry) ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    scope.launch {
                        withContext(Dispatchers.IO) { FileBrowser.delete(context, entry.path) }
                            .onFailure { notice = it.message ?: "delete failed" }
                            .onSuccess { notice = "Deleted " + entry.name }
                        load(index, panes[index].path)
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
                        refreshBoth()
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
                    val target = panes[active].path.trimEnd('/') + "/" + name.trim()
                    if (name.isBlank()) return@TextButton
                    creating = null
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            if (kind == "dir") FileBrowser.createDirectory(context, target)
                            else FileBrowser.createFile(context, target)
                        }.onFailure { notice = it.message ?: "failed" }
                        load(active, panes[active].path)
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

    renaming?.let { (index, entry) ->
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
                        load(index, panes[index].path)
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

/** Everything one pane remembers. */
private data class PaneState(
    val path: String = "",
    val listing: FileBrowser.Listing = FileBrowser.Listing(""),
    val loading: Boolean = false,
    val showHidden: Boolean = false,
)

/**
 * Copy or move [entry] into [destination].
 *
 * A directory can be large, so this is a suspend call the caller launches and
 * reports through [status] — never a fire-and-forget on a global scope, which
 * would outlive the screen and update state after it is gone.
 */
private suspend fun transfer(
    context: android.content.Context,
    entry: FileBrowser.Entry,
    move: Boolean,
    destination: String,
    status: (String) -> Unit,
): String {
    val target = destination.trimEnd('/') + "/" + entry.name
    if (target == entry.path) return "That is already the destination."
    status((if (move) "Moving " else "Copying ") + entry.name)
    return withContext(Dispatchers.IO) {
        val result = FileBrowser.copy(context, entry.path, target)
        if (result.isSuccess && move) FileBrowser.delete(context, entry.path)
        status("")
        if (result.isFailure) {
            (if (move) "Move" else "Copy") + " failed: " +
                (result.exceptionOrNull()?.message ?: "unknown error")
        } else {
            (if (move) "Moved " else "Copied ") + entry.name
        }
    }
}

/** One pane: location chips, breadcrumbs and the listing. */
@Composable
private fun Pane(
    state: PaneState,
    active: Boolean,
    accent: androidx.compose.ui.graphics.Color,
    query: String,
    sort: FileBrowser.Sort,
    descending: Boolean,
    places: List<FileBrowser.Place>,
    modifier: Modifier = Modifier,
    onActivate: () -> Unit,
    onNavigate: (String) -> Unit,
    onMore: (FileBrowser.Entry) -> Unit,
    onTransfer: (FileBrowser.Entry, Boolean) -> Unit,
) {
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val border = if (active) accent.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outline
    val visible = remember(state.listing, query, sort, descending) {
        FileBrowser.sorted(FileBrowser.filtered(state.listing.entries, query), sort, descending)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Prism.PanelRadius))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
            .border(1.dp, border, RoundedCornerShape(Prism.PanelRadius))
            .clickable(onClick = onActivate)
            .padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        // Where this pane is.
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (active) "●" else "○",
                fontSize = 7.sp,
                color = if (active) accent else altarisMutedText(),
            )
            Spacer(Modifier.width(4.dp))
            FileBrowser.crumbs(context = context, path = state.path)
                .forEachIndexed { index, (label, target) ->
                    if (index > 0) Text("/", fontSize = 8.sp, color = altarisMutedText())
                    Text(
                        label,
                        fontSize = 8.5.sp,
                        fontFamily = FontFamily.Monospace,
                        color = if (index == 0) accent else altarisMutedText(),
                        maxLines = 1,
                        modifier = Modifier
                            .clip(RoundedCornerShape(3.dp))
                            .clickable { onNavigate(target) }
                            .padding(horizontal = 2.dp, vertical = 1.dp),
                    )
                }
        }

        // Places, so a pane can jump to a distro or the filesystem root.
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            places.forEach { place ->
                PrismChip(
                    place.label,
                    accent,
                    selected = state.path == place.path || state.path.startsWith(place.path + "/"),
                    onClick = { onNavigate(place.path) },
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    state.loading -> "…"
                    query.isNotBlank() -> visible.size.toString() + " of " +
                        state.listing.entries.size
                    else -> state.listing.entries.size.toString() + " items"
                },
                fontSize = 8.sp,
                color = altarisMutedText(),
            )
            if (state.listing.usedRoot) {
                Spacer(Modifier.width(4.dp))
                Text("· root", fontSize = 7.5.sp, color = accent)
            }
            Spacer(Modifier.weight(1f))
            PrismChip("Up", accent) {
                FileBrowser.parent(context, state.path)
                    ?.let(onNavigate)
            }
        }

        if (state.listing.error.isNotBlank()) {
            PrismNote(state.listing.error, color = MaterialTheme.colorScheme.error, fontSize = 9.sp)
        }

        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f)) {
            items(visible, key = { it.path }) { entry ->
                FileRow(
                    entry = entry,
                    accent = accent,
                    onOpen = {
                        onActivate()
                        if (entry.dir) onNavigate(entry.path) else onMore(entry)
                    },
                    onMore = {
                        onActivate()
                        onMore(entry)
                    },
                    onCopy = { onTransfer(entry, false) },
                    onMove = { onTransfer(entry, true) },
                )
            }
        }
    }
}

@Composable
private fun FileRow(
    entry: FileBrowser.Entry,
    accent: androidx.compose.ui.graphics.Color,
    onOpen: () -> Unit,
    onMore: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(3.dp))
            .clickable(onClick = onOpen)
            .padding(horizontal = 3.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (entry.dir) accent else MaterialTheme.colorScheme.outline),
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
            if (!entry.dir) {
                Text(
                    com.altaris.linux.core.rea.ReaDistro.formatBytes(entry.size),
                    fontSize = 7.5.sp,
                    color = altarisMutedText(),
                )
            }
        }
        Text(
            "⇄",
            fontSize = 11.sp,
            color = accent,
            modifier = Modifier
                .clip(RoundedCornerShape(3.dp))
                .clickable(onClick = onCopy)
                .padding(horizontal = 5.dp, vertical = 2.dp),
        )
        Text(
            "→",
            fontSize = 11.sp,
            color = accent,
            modifier = Modifier
                .clip(RoundedCornerShape(3.dp))
                .clickable(onClick = onMove)
                .padding(horizontal = 5.dp, vertical = 2.dp),
        )
        Text(
            "⋯",
            fontSize = 11.sp,
            color = altarisMutedText(),
            modifier = Modifier
                .clip(RoundedCornerShape(3.dp))
                .clickable(onClick = onMore)
                .padding(horizontal = 5.dp, vertical = 2.dp),
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
