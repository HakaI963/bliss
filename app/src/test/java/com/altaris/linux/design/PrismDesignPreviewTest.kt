package com.altaris.linux.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.altaris.linux.core.utils.ThemeMode
import com.altaris.linux.ui.components.AltarisCommandBar
import com.altaris.linux.ui.components.AltarisDock
import com.altaris.linux.ui.components.AltarisTitle
import com.altaris.linux.ui.components.DockDestination
import com.altaris.linux.ui.components.HomeHero
import com.altaris.linux.ui.components.PrismBackdrop
import com.altaris.linux.ui.components.PrismButton
import com.altaris.linux.ui.components.PrismCard
import com.altaris.linux.ui.components.PrismChip
import com.altaris.linux.ui.components.PrismRow
import com.altaris.linux.ui.components.PrismSectionLabel
import com.altaris.linux.ui.theme.AltarisScheme
import com.altaris.linux.ui.theme.AltarisTheme
import com.altaris.linux.ui.theme.Prism
import org.junit.Rule
import org.junit.Test

/**
 * The three Altaris identities, rendered on the JVM by Paparazzi.
 *
 * Each board shows the real shell: the command bar, the home banner with
 * the anime artwork, a panel with rows, a primary action and the dock.
 */
class PrismDesignPreviewTest {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5,
        theme = "android:Theme.Material.NoActionBar",
        showSystemUi = false,
    )

    @Test
    fun identity_graphite() = board(AltarisScheme.GRAPHITE, "identity_graphite")

    @Test
    fun identity_midnight_slate() = board(AltarisScheme.MIDNIGHT_SLATE, "identity_midnight_slate")

    @Test
    fun identity_warm_neutral() = board(AltarisScheme.WARM_NEUTRAL, "identity_warm_neutral")

    private fun board(scheme: AltarisScheme, name: String) {
        paparazzi.snapshot(name = name) {
            AltarisTheme(themeMode = ThemeMode.DARK, scheme = scheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    PrismBackdrop()
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(Prism.Gutter),
                        verticalArrangement = Arrangement.spacedBy(Prism.Stack),
                    ) {
                        AltarisCommandBar(
                            accent = MaterialTheme.colorScheme.primary,
                            statusLabel = "READY",
                            live = false,
                            onOpenPalette = {},
                            onOpenSettings = {},
                        )
                        HomeHero(
                            artRes = com.altaris.linux.R.drawable.splash_art_catgirl,
                            title = "Altaris",
                            subtitle = "Your Linux environments",
                            trailing = { PrismChip("Distros", MaterialTheme.colorScheme.primary) {} },
                        )
                        AltarisTitle(
                            "Home",
                            "Your Linux environments",
                            MaterialTheme.colorScheme.primary,
                        )
                        PrismCard(accent = MaterialTheme.colorScheme.primary) {
                            PrismSectionLabel("Containers", MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.height(10.dp))
                            PrismRow(
                                title = "Debian",
                                subtitle = "PRoot · shell ready",
                                accent = MaterialTheme.colorScheme.primary,
                                divider = true,
                            )
                            PrismRow(
                                title = "Alpine",
                                subtitle = "PRoot · shell ready",
                                accent = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.height(12.dp))
                            PrismButton(
                                text = "Add a distribution",
                                accent = MaterialTheme.colorScheme.primary,
                            ) {}
                        }
                        Box(modifier = Modifier.weight(1f))
                        AltarisDock(
                            selected = DockDestination.HOME,
                            onSelect = {},
                        )
                    }
                }
            }
        }
    }
}
