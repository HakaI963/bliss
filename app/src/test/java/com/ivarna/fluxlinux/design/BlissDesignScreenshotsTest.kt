package com.ivarna.fluxlinux.design

import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.ivarna.fluxlinux.core.utils.ThemeMode
import com.ivarna.fluxlinux.ui.components.QCard
import com.ivarna.fluxlinux.ui.components.QLogPane
import com.ivarna.fluxlinux.ui.components.QPill
import com.ivarna.fluxlinux.ui.components.QPrimaryButton
import com.ivarna.fluxlinux.ui.components.QRow
import com.ivarna.fluxlinux.ui.components.QScreenTitle
import com.ivarna.fluxlinux.ui.components.QSectionHeader
import com.ivarna.fluxlinux.ui.components.QStatusRow
import com.ivarna.fluxlinux.ui.components.QToggleRow
import com.ivarna.fluxlinux.ui.components.Quartz
import com.ivarna.fluxlinux.ui.screens.ClonedAppsScreen
import com.ivarna.fluxlinux.ui.screens.ToolsScreen
import com.ivarna.fluxlinux.ui.theme.FluxLinuxTheme
import org.junit.Rule
import org.junit.Test

/**
 * Screenshot verification of the Quartz UI, rendered on the JVM by Paparazzi —
 * no device needed. CI runs `recordPaparazziBlissDebug` and uploads the PNGs.
 */
class BlissDesignScreenshotsTest {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5,
        theme = "android:Theme.Material.NoActionBar",
        showSystemUi = false,
    )

    @Test
    fun quartz_components() {
        paparazzi.snapshot {
            FluxLinuxTheme(themeMode = ThemeMode.DARK) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(Quartz.ScreenPad),
                        verticalArrangement = Arrangement.spacedBy(Quartz.Gap),
                    ) {
                        QScreenTitle("Design board", "Quartz components")
                        QSectionHeader("Rows")
                        QCard {
                            QRow("Clone", subtitle = "Clone an installed app, pick an APK", onClick = {}, divider = true)
                            QRow("Display", subtitle = "FPS, resolution and DPI", onClick = {})
                        }
                        QSectionHeader("Controls")
                        QCard {
                            Column(
                                modifier = Modifier.padding(Quartz.CardPad),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    QPill("Selected", true) {}
                                    QPill("Option", false) {}
                                }
                                QPrimaryButton("Primary action") {}
                                QToggleRow(
                                    "Fallback",
                                    checked = false,
                                    onCheckedChange = {},
                                    subtitle = "Only when needed",
                                )
                                QStatusRow("Shell", "Shizuku (shell)")
                                QLogPane(
                                    listOf(
                                        "12:00  Display created: id=42 720x1560",
                                        "12:00  Launch: com.foo (display 42, hidden clone)",
                                    ),
                                    maxHeight = 56.dp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun apps_tab() {
        paparazzi.snapshot {
            FluxLinuxTheme(themeMode = ThemeMode.DARK) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    ClonedAppsScreen()
                }
            }
        }
    }

    @Test
    fun tools_hub() {
        paparazzi.snapshot {
            FluxLinuxTheme(themeMode = ThemeMode.DARK) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    // ToolsScreen owns a SAF picker; Paparazzi has no ActivityResultRegistryOwner.
                    CompositionLocalProvider(LocalActivityResultRegistryOwner provides NoopRegistryOwner) {
                        ToolsScreen(onOpenApps = {})
                    }
                }
            }
        }
    }

    /** Minimal owner so rememberLauncherForActivityResult() works off-device. */
    private object NoopRegistryOwner : ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(
                requestCode: Int,
                contract: ActivityResultContract<I, O>,
                input: I,
                options: ActivityOptionsCompat?,
            ) {
                // no-op in screenshots
            }
        }
    }
}
