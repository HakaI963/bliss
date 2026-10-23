package com.altaris.linux.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.altaris.linux.ui.components.PrismCard
import com.altaris.linux.ui.components.PrismRow
import com.altaris.linux.ui.components.PrismSectionLabel
import com.altaris.linux.ui.theme.AltarisSection
import com.altaris.linux.ui.theme.AltarisTimings
import com.altaris.linux.ui.theme.Prism
import com.altaris.linux.ui.theme.rememberStaggerEntrance
import com.altaris.linux.ui.theme.sectionAccent
import com.altaris.linux.ui.theme.staggerIn

data class TroubleshootingItem(
    val title: String,
    val content: String
)

/**
 * Troubleshooting — a flat, collapsible knowledge base plus the three
 * destinations people actually need when something is wrong (logs, root,
 * settings). Rebuilt in the Prism language: no haze, no floating cards, no
 * centre-aligned app bar.
 */
@Composable
fun TroubleshootingScreen(
    onBack: () -> Unit,
    onOpenLogs: () -> Unit = {},
    onOpenRoot: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    val accent = sectionAccent(AltarisSection.TOOLS)
    val entrance = rememberStaggerEntrance()

    val issues = remember {
        listOf(
            TroubleshootingItem(
                "A distro download stopped half-way",
                "Open the distro again: the install resumes where it stopped. The download task button next to Settings shows live progress while anything is running."
            ),
            TroubleshootingItem(
                "The desktop does not start",
                "The display packages install after the terminal base. A terminal-only install has no XFCE, and Altaris says so instead of starting a dead session. Install the desktop from the distro's card."
            ),
            TroubleshootingItem(
                "Clones or the Elements panel do nothing",
                "The clone engine runs through Shizuku. Apps → Preference shows whether the shell engine is connected; authorise Altaris in Shizuku once and the engine starts by itself from then on. The accessibility service is enabled automatically over Shizuku."
            ),
            TroubleshootingItem(
                "A clone looks slow or blurry",
                "Lower the frame rate in Apps → Preference and keep the quality at Medium. The virtual display runs at the device refresh rate by default, which is more than a preview needs."
            ),
            TroubleshootingItem(
                "Root features are unavailable",
                "Chroot distros and some repairs need root. Grant Altaris superuser access in KernelSU or Magisk; the Settings status strip shows whether root is visible."
            ),
            TroubleshootingItem(
                "Where are the logs?",
                "Crash traces live in Settings → Crash logs. A running clone streams its output in the viewer's Live logs panel, and the control server keeps its own log under Tools → Clone."
            ),
            TroubleshootingItem(
                "A running clone does not stop",
                "Press Stop in the viewer, or the stop action on the app's row in Apps → Running. If the shell engine was disconnected, reconnect it in Apps → Preference and stop again."
            ),
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(56.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onBack() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(19.dp),
                )
            }
            Column(modifier = Modifier.padding(start = 8.dp)) {
                Text(
                    "Troubleshooting",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "What to check when something stalls",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Prism.Gutter)
                .padding(top = 6.dp, bottom = 110.dp),
            verticalArrangement = Arrangement.spacedBy(Prism.Stack),
        ) {
            PrismSectionLabel("Quick actions", accent, Modifier.staggerIn(1, entrance))
            PrismCard(accent = accent, modifier = Modifier.staggerIn(2, entrance)) {
                PrismRow(
                    title = "Crash logs",
                    subtitle = "Read or share the last stack trace",
                    accent = accent,
                    leading = { QuickIcon(Icons.Filled.BugReport) },
                    onClick = onOpenLogs,
                    divider = true,
                )
                PrismRow(
                    title = "Root access",
                    subtitle = "Check superuser state and repair the host",
                    accent = accent,
                    leading = { QuickIcon(Icons.Filled.Security) },
                    onClick = onOpenRoot,
                    divider = true,
                )
                PrismRow(
                    title = "Settings",
                    subtitle = "Terminal, displays, audio and storage",
                    accent = accent,
                    leading = { QuickIcon(Icons.Filled.Settings) },
                    onClick = onOpenSettings,
                )
            }

            PrismSectionLabel("Common issues", accent, Modifier.staggerIn(3, entrance))
            PrismCard(accent = accent, modifier = Modifier.staggerIn(4, entrance)) {
                issues.forEachIndexed { index, issue ->
                    IssueRow(
                        issue = issue,
                        accent = accent,
                        divider = index < issues.lastIndex,
                    )
                }
            }
        }
    }
}

/** A small hairline icon tile used by the quick actions. */
@Composable
private fun QuickIcon(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(scheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = scheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
    }
}

/** Title + rotating chevron; the answer opens in place. */
@Composable
private fun IssueRow(
    issue: TroubleshootingItem,
    accent: androidx.compose.ui.graphics.Color,
    divider: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(AltarisTimings.ScreenMs, easing = FastOutSlowInEasing),
        label = "issueChevron",
    )
    val scheme = MaterialTheme.colorScheme

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { expanded = !expanded }
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                issue.title,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Medium,
                color = scheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = scheme.onSurfaceVariant,
                modifier = Modifier
                    .size(18.dp)
                    .graphicsLayer { rotationZ = rotation },
            )
        }
        AnimatedVisibility(visible = expanded) {
            Text(
                issue.content,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        if (divider) {
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(0.75.dp)
                    .background(scheme.outlineVariant)
            )
        }
    }
}
