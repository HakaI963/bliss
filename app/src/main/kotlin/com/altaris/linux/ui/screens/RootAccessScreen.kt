package com.altaris.linux.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.altaris.linux.core.root.RootShell
import com.altaris.linux.ui.components.AltarisSubHeader
import com.altaris.linux.ui.components.PrismButton
import com.altaris.linux.ui.components.PrismCard
import com.altaris.linux.ui.components.PrismOrb
import com.altaris.linux.ui.components.PrismRow
import com.altaris.linux.ui.components.PrismSecondaryButton
import com.altaris.linux.ui.components.PrismSectionLabel
import com.altaris.linux.ui.theme.AltarisSection
import com.altaris.linux.ui.theme.Prism
import com.altaris.linux.ui.theme.rememberStaggerEntrance
import com.altaris.linux.ui.theme.sectionAccent
import com.altaris.linux.ui.theme.staggerIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Root access — one honest status plate and the two container modes.
 *
 * The old screen was a giant red warning triangle with a pill button. Root is
 * optional: PRoot works everywhere, chroot is the fast path when `su` answers.
 */
@Composable
fun RootAccessScreen(
    onBack: () -> Unit,
    onEnableChroot: () -> Unit,
) {
    val accent = sectionAccent(AltarisSection.TOOLS)
    val entrance = rememberStaggerEntrance()
    var isRooted by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(true) }
    var recheck by remember { androidx.compose.runtime.mutableIntStateOf(0) }

    LaunchedEffect(recheck) {
        checking = true
        isRooted = withContext(Dispatchers.IO) {
            runCatching {
                if (recheck > 0) RootShell.clearSuCache()
                RootShell.isRootAvailable()
            }.getOrDefault(false)
        }
        checking = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        AltarisSubHeader(title = "Root Access", onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Prism.Gutter)
                .padding(top = 4.dp, bottom = 110.dp),
            verticalArrangement = Arrangement.spacedBy(Prism.Stack),
        ) {
            PrismSectionLabel("Status", accent, Modifier.staggerIn(1, entrance))
            PrismCard(accent = accent, modifier = Modifier.staggerIn(2, entrance)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PrismOrb(active = isRooted, accent = accent)
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 12.dp),
                    ) {
                        Text(
                            "Root access",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            when {
                                checking -> "Checking superuser…"
                                isRooted -> "Root available · chroot enabled"
                                else -> "No root · PRoot mode"
                            },
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    if (isRooted) {
                        "The superuser daemon answered. Chroot containers run without PRoot and see the device hardware directly."
                    } else {
                        "Altaris did not get an answer from a superuser daemon. PRoot is the default and works on every device; grant root in KernelSU or Magisk to unlock chroot."
                    },
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                )
            }

            PrismSectionLabel("Container modes", accent, Modifier.staggerIn(3, entrance))
            PrismCard(accent = accent, modifier = Modifier.staggerIn(4, entrance)) {
                PrismRow(
                    title = "PRoot",
                    subtitle = "Userspace Linux inside app storage. No root needed.",
                    accent = accent,
                    leading = { PrismOrb(active = true, accent = accent) },
                    divider = true,
                )
                PrismRow(
                    title = "Chroot",
                    subtitle = if (isRooted) {
                        "Root available. Faster startup and full hardware access."
                    } else {
                        "Needs root. Faster startup and full hardware access."
                    },
                    accent = accent,
                    leading = { PrismOrb(active = isRooted, accent = accent) },
                )
            }

            Spacer(Modifier.height(2.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .staggerIn(5, entrance),
                horizontalArrangement = Arrangement.spacedBy(Prism.Tight),
            ) {
                PrismSecondaryButton(
                    text = if (checking) "Checking…" else "Recheck",
                    accent = accent,
                    enabled = !checking,
                    modifier = Modifier.weight(1f),
                    onClick = { recheck += 1 },
                )
                if (isRooted) {
                    PrismButton(
                        text = "Enable chroot",
                        accent = accent,
                        modifier = Modifier.weight(1f),
                        onClick = onEnableChroot,
                    )
                }
            }
        }
    }
}
