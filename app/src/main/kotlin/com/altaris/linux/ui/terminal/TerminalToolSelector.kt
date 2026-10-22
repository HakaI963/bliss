package com.altaris.linux.ui.terminal

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.altaris.linux.core.root.RootShell
import com.altaris.linux.core.terminal.TerminalShellAvailability
import com.altaris.linux.core.terminal.TerminalShellCardUi
import com.altaris.linux.core.terminal.TerminalShellCatalog
import com.altaris.linux.ui.components.MethodChip
import com.altaris.linux.ui.components.MethodTab
import com.altaris.linux.ui.components.MethodTabs
import com.altaris.linux.ui.theme.AltarisBackground
import com.altaris.linux.ui.theme.AltarisSwitchUncheckedTrack
import com.altaris.linux.ui.theme.FrostHairline
import com.altaris.linux.ui.theme.FrostSurface
import com.altaris.linux.ui.theme.TextMuted
import com.altaris.linux.ui.theme.TextPrimary
import com.altaris.linux.ui.theme.TextSecondary

// Monochrome frost method colors — no cyan/magenta/beige neon.
private val MethodProot = TextSecondary
private val MethodChroot = TextSecondary
private val MethodHost = TextPrimary
private val RowShape = RoundedCornerShape(14.dp)
private val TileShape = RoundedCornerShape(12.dp)

private data class TerminalMethodGroup(
    val title: String,
    val subtitle: String,
    val method: String,
    val rows: List<TerminalDistroRow>
)

private data class TerminalDistroRow(
    val method: String,
    val distroId: String?,
    val iconRes: Int,
    val name: String,
    val subtitle: String,
    /** Distro/guest present on disk — unrelated to root permission for chroot. */
    val installed: Boolean,
    val user: TerminalShellCardUi?,
    val root: TerminalShellCardUi?
)

/**
 * Terminal empty-state selector.
 *
 * Catalog cards stay 1:1 with [TerminalShellCatalog] (proot / chroot × user / root),
 * grouped into method sections so User and Root stay on the same row. Only installed
 * guests are shown by default ("Show all" reveals the rest with their install hint);
 * method tabs only appear when both methods actually have visible rows.
 *
 * @param onOpen (type, title, method, distroId) — opens the session (host prepare by caller)
 * @param bottomPadding extra scroll padding (the bottom dock floats over the empty state)
 */
@Composable
fun TerminalToolSelector(
    onOpen: (type: String, title: String, method: String, distroId: String?) -> Unit,
    modifier: Modifier = Modifier,
    bottomPadding: Dp = 110.dp
) {
    val context = LocalContext.current
    var rootAvailable by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        RootShell.probeRootAvailable { ok -> rootAvailable = ok }
    }

    val availability = remember(context, rootAvailable) {
        TerminalShellCatalog.availability(context, rootAvailable)
    }
    val sections = remember(context, availability) {
        TerminalShellCatalog.sections(context, availability)
    }
    val groups = remember(sections, availability) {
        groupCatalogByMethod(sections.flatMap { it.cards }, availability)
    }
    var showAll by remember { mutableStateOf(false) }
    var methodTab by remember { mutableStateOf(MethodTab.PROOT) }

    val prootGroup = groups.firstOrNull { it.method == "proot" }
    val chrootGroup = groups.firstOrNull { it.method == "chroot" }
    val hostGroup = groups.firstOrNull { it.method == "host" }
    val prootAll = prootGroup?.rows.orEmpty()
    val chrootAll = chrootGroup?.rows.orEmpty()
    val prootRows = prootAll.filter { it.installed || showAll }
    val chrootRows = chrootAll.filter { it.installed || showAll }
    val anyInstalled = prootAll.any { it.installed } || chrootAll.any { it.installed }

    // Root granted (probe above) + chroot installed: warm helper + BusyBox off main
    // so the first chroot open skips su staging/probes. Never prompts non-root users.
    val warmChroot = rootAvailable && chrootAll.any { it.installed }
    LaunchedEffect(warmChroot) {
        if (warmChroot) {
            val app = context.applicationContext
            Thread { RootShell.ensureChrootHelper(app); RootShell.resolveBusyBox() }.start()
        }
    }

    // Method tabs only when both methods have rows to show; a lone method renders flat.
    val showMethodTabs = prootRows.isNotEmpty() && chrootRows.isNotEmpty()
    val activeMethod = when {
        methodTab == MethodTab.CHROOT && chrootRows.isNotEmpty() -> "chroot"
        prootRows.isNotEmpty() -> "proot"
        chrootRows.isNotEmpty() -> "chroot"
        else -> "proot"
    }
    val activeRows = if (activeMethod == "chroot") chrootRows else prootRows
    val scrollState = rememberScrollState()

    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (showAll) "All distros" else "Installed distros only",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = TextMuted,
                letterSpacing = 0.8.sp
            )
            Spacer(Modifier.weight(1f))
            ShowAllToggle(showAll = showAll) { showAll = !showAll }
        }

        if (showMethodTabs) {
            MethodTabs(
                selected = if (activeMethod == "chroot") MethodTab.CHROOT else MethodTab.PROOT,
                onSelected = { methodTab = it },
                prootCount = prootRows.size,
                chrootCount = chrootRows.size,
                horizontalPadding = 12.dp
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                // wraps content when short, scrolls when tall — works both as the
                // full-screen empty state and inside the anchored new-session panel
                .weight(1f, fill = false)
                .verticalScroll(scrollState)
                .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = bottomPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (activeRows.isEmpty() && !anyInstalled) {
                NoDistrosHint()
            }
            activeRows.forEach { row ->
                TerminalDistroRowCard(
                    row = row,
                    onOpen = onOpen,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (hostGroup != null) {
                TerminalSectionHeader(hostGroup)
                hostGroup.rows.forEach { row ->
                    TerminalDistroRowCard(
                        row = row,
                        onOpen = onOpen,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

/**
 * Anchored new-session panel ("+"): in-place dropdown under the top bar instead of a
 * modal sheet — one tap always surfaces it, no dismissal/scrim tap races.
 */
@Composable
fun TerminalNewSessionPanel(
    onOpen: (type: String, title: String, method: String, distroId: String?) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.98f))
            .border(BorderStroke(0.75.dp, FrostHairline), shape)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 4.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "New session",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close",
                    tint = TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        TerminalToolSelector(
            onOpen = onOpen,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 400.dp),
            bottomPadding = 14.dp
        )
    }
}

private fun groupCatalogByMethod(
    cards: List<TerminalShellCardUi>,
    availability: TerminalShellAvailability
): List<TerminalMethodGroup> {
    fun isInstalled(method: String, distroId: String?): Boolean = when (method) {
        "proot" -> availability.prootInstalled(distroId ?: "debian")
        "chroot" -> availability.chrootInstalled(distroId ?: "debian13_chroot")
        else -> true // host prefix is always present
    }

    fun rowsFor(method: String): List<TerminalDistroRow> {
        return cards
            .filter { it.def.method == method }
            .groupBy { it.def.distroId ?: it.def.label }
            .map { (_, group) ->
                val sample = group.first()
                TerminalDistroRow(
                    method = sample.def.method,
                    distroId = sample.def.distroId,
                    iconRes = sample.def.iconRes,
                    name = displayName(sample),
                    subtitle = sample.def.desc,
                    installed = isInstalled(sample.def.method, sample.def.distroId),
                    user = group.find { it.def.type == "shell" || it.def.type == "host" },
                    root = group.find { it.def.type == "shell-root" }
                )
            }
    }

    return listOf(
        TerminalMethodGroup("PROOT", "User-space guests", "proot", rowsFor("proot")),
        TerminalMethodGroup("CHROOT", "Requires device root", "chroot", rowsFor("chroot")),
        TerminalMethodGroup("HOST", "Embedded Termux", "host", rowsFor("host"))
    ).filter { it.rows.isNotEmpty() }
}

private fun displayName(card: TerminalShellCardUi): String {
    return card.def.label
        .replace(" Shell Rooted", "")
        .replace(" Rooted", "")
        .replace(" Chroot Shell", "")
        .replace(" Chroot", "")
        .replace(" Shell", "")
        .trim()
        .ifBlank { card.def.label }
}

@Composable
private fun ShowAllToggle(showAll: Boolean, onToggle: () -> Unit) {
    val shape = RoundedCornerShape(percent = 50)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(if (showAll) TextPrimary else FrostSurface)
            .border(0.75.dp, if (showAll) TextPrimary else FrostHairline, shape)
            .clickable(onClick = onToggle)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (showAll) "Show installed" else "Show all",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (showAll) AltarisBackground else TextSecondary
        )
    }
}

@Composable
private fun NoDistrosHint() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RowShape)
            .background(FrostSurface)
            .border(0.75.dp, FrostHairline, RowShape)
            .padding(14.dp)
    ) {
        Text(
            text = "No distros installed yet",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Install one from the Distros tab.",
            fontSize = 11.sp,
            lineHeight = 16.sp,
            color = TextSecondary
        )
    }
}

@Composable
private fun TerminalSectionHeader(group: TerminalMethodGroup, modifier: Modifier = Modifier) {
    val accent = when (group.method) {
        "chroot" -> MethodChroot
        "host" -> MethodHost
        else -> MethodProot
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(14.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(accent)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = group.title,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = accent,
            letterSpacing = 1.2.sp
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = group.subtitle,
            fontSize = 11.sp,
            color = TextMuted
        )
    }
}

@Composable
private fun TerminalDistroRowCard(
    row: TerminalDistroRow,
    onOpen: (type: String, title: String, method: String, distroId: String?) -> Unit,
    modifier: Modifier = Modifier
) {
    val methodColor = when (row.method) {
        "chroot" -> MethodChroot
        "host" -> MethodHost
        else -> MethodProot
    }
    val userCard = row.user
    val rootCard = row.root
    val reason = if (row.installed && (userCard?.enabled == true || rootCard?.enabled == true)) {
        null
    } else {
        userCard?.disabledReason ?: rootCard?.disabledReason
    }

    Box(
        modifier = modifier
            .clip(RowShape)
            .background(if (row.installed) FrostSurface else FrostSurface.copy(alpha = 0.45f))
            .border(BorderStroke(0.75.dp, FrostHairline), RowShape)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Frosted icon tile (distro art stays as-is inside).
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(TileShape)
                    .background(FrostSurface)
                    .border(0.75.dp, FrostHairline, TileShape),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = row.iconRes),
                    contentDescription = row.name,
                    modifier = Modifier
                        .size(26.dp)
                        .alpha(if (row.installed) 1f else 0.45f)
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = row.name,
                        color = if (row.installed) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(Modifier.width(6.dp))
                    MethodChip(
                        label = row.method.uppercase(),
                        color = if (row.installed) methodColor else TextMuted
                    )
                }
                Text(
                    text = reason ?: row.subtitle,
                    color = if (reason != null) MaterialTheme.colorScheme.error else TextSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            when {
                rootCard != null -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (userCard != null) {
                        SessionPill(
                            label = "User",
                            icon = Icons.Default.Person,
                            enabled = userCard.enabled,
                            primary = true,
                            onClick = { openCard(userCard, onOpen) }
                        )
                    }
                    SessionPill(
                        label = "Root",
                        icon = Icons.Default.AdminPanelSettings,
                        enabled = rootCard.enabled,
                        primary = false,
                        onClick = { openCard(rootCard, onOpen) }
                    )
                }
                userCard != null -> SessionPill(
                    label = if (row.method == "host") "Shell" else "Open",
                    icon = Icons.Default.Terminal,
                    enabled = userCard.enabled,
                    primary = true,
                    onClick = { openCard(userCard, onOpen) }
                )
            }
        }
    }
}

private fun openCard(
    card: TerminalShellCardUi,
    onOpen: (type: String, title: String, method: String, distroId: String?) -> Unit
) {
    if (!card.enabled) return
    onOpen(card.def.type, card.def.label, card.def.method, card.def.distroId)
}

/** Compact frost action pill — inverted white for the primary action. */
@Composable
private fun SessionPill(
    label: String,
    icon: ImageVector,
    enabled: Boolean,
    primary: Boolean,
    onClick: () -> Unit
) {
    val container = if (primary) TextPrimary else AltarisSwitchUncheckedTrack
    val content = if (primary) AltarisBackground else TextPrimary
    Row(
        modifier = Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(container.copy(alpha = if (enabled) 1f else 0.3f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = content.copy(alpha = if (enabled) 1f else 0.45f),
            modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = content.copy(alpha = if (enabled) 1f else 0.45f),
            maxLines = 1
        )
    }
}
