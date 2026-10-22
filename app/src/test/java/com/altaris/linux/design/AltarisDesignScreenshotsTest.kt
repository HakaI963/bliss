package com.altaris.linux.design

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
import com.altaris.linux.core.clone.HeadlessCloneEngine
import com.altaris.linux.core.utils.ThemeMode
import com.altaris.linux.ui.components.QCard
import com.altaris.linux.ui.components.QLogPane
import com.altaris.linux.ui.components.QPill
import com.altaris.linux.ui.components.QPrimaryButton
import com.altaris.linux.ui.components.QRow
import com.altaris.linux.ui.components.QScreenTitle
import com.altaris.linux.ui.components.QSectionHeader
import com.altaris.linux.ui.components.QStatusRow
import com.altaris.linux.ui.components.QToggleRow
import com.altaris.linux.ui.components.Quartz
import com.altaris.linux.ui.screens.ClonedAppsScreen
import com.altaris.linux.ui.screens.ToolsScreen
import com.altaris.linux.ui.theme.AltarisTheme
import org.junit.Rule
import org.junit.Test

/**
 * Screenshot verification of the Quartz UI, rendered on the JVM by Paparazzi —
 * no device needed. CI runs `recordPaparazziAltarisDebug` and uploads the PNGs.
 */
class AltarisDesignScreenshotsTest {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5,
        theme = "android:Theme.Material.NoActionBar",
        showSystemUi = false,
    )

    @Test
    fun quartz_components() {
        paparazzi.snapshot {
            AltarisTheme(themeMode = ThemeMode.DARK) {
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
            AltarisTheme(themeMode = ThemeMode.DARK) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    CompositionLocalProvider(LocalActivityResultRegistryOwner provides NoopRegistryOwner) {
                        ClonedAppsScreen()
                    }
                }
            }
        }
    }

    @Test
    fun apps_installed_folders() {
        HeadlessCloneEngine.connected.value = true
        HeadlessCloneEngine.clones.value = listOf(
            fakeClone("test1", "com.nopiece.hclone.h11111111"),
            fakeClone("test2", "com.nopiece.hclone.h22222222"),
            fakeClone("test3", "com.nopiece.hclone.h33333333"),
        )
        try {
            paparazzi.snapshot {
                AltarisTheme(themeMode = ThemeMode.DARK) {
                    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        CompositionLocalProvider(LocalActivityResultRegistryOwner provides NoopRegistryOwner) {
                            ClonedAppsScreen()
                        }
                    }
                }
            }
        } finally {
            HeadlessCloneEngine.clones.value = emptyList()
            HeadlessCloneEngine.connected.value = false
        }
    }

    private fun fakeClone(label: String, packageName: String) = HeadlessCloneEngine.CloneApp(
        packageName = packageName,
        originalPackage = "com.test.sample",
        originalLabel = "Sample app",
        label = label,
        activity = "com.test.sample.MainActivity",
        source = "installed",
        status = "installed",
        orientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
        icon = null,
    )

    @Test
    fun apps_preference() {
        paparazzi.snapshot {
            AltarisTheme(themeMode = ThemeMode.DARK) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    CompositionLocalProvider(LocalActivityResultRegistryOwner provides NoopRegistryOwner) {
                        ClonedAppsScreen(startOnPreference = true)
                    }
                }
            }
        }
    }

    @Test
    fun tools_hub() {
        paparazzi.snapshot {
            AltarisTheme(themeMode = ThemeMode.DARK) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    // ToolsScreen owns a SAF picker; Paparazzi has no ActivityResultRegistryOwner.
                    CompositionLocalProvider(LocalActivityResultRegistryOwner provides NoopRegistryOwner) {
                        ToolsScreen()
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
