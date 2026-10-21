package com.ivarna.fluxlinux.design

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.ivarna.fluxlinux.core.utils.ThemeMode
import com.ivarna.fluxlinux.core.utils.ThemePalette
import com.ivarna.fluxlinux.ui.components.AltarisBackdrop
import com.ivarna.fluxlinux.ui.components.QCard
import com.ivarna.fluxlinux.ui.components.QPill
import com.ivarna.fluxlinux.ui.components.QPrimaryButton
import com.ivarna.fluxlinux.ui.components.QRow
import com.ivarna.fluxlinux.ui.components.Quartz
import com.ivarna.fluxlinux.ui.theme.AltarisPalettes
import com.ivarna.fluxlinux.ui.theme.AltarisScheme
import com.ivarna.fluxlinux.ui.theme.FolioTheme
import org.junit.Rule
import org.junit.Test

/**
 * Palette boards for the five Altaris identities, dark and light.
 *
 * Each snapshot renders the wordmark, the identity gradient, a glass card, a
 * primary action, a pill row, two rows and a caption — enough to judge the
 * identity at a glance. The aurora backdrop falls back to its static gradient
 * inside Paparazzi, so every PNG is deterministic.
 */
class PalettePreviewTest {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5,
        theme = "android:Theme.Material.NoActionBar",
        showSystemUi = false,
    )

    @Test
    fun palette_aurora_neon_dark() = snapshotBoard(
        scheme = AltarisScheme.AURORA_NEON,
        dark = true,
        name = "palette_aurora_neon_dark",
    )

    @Test
    fun palette_aurora_neon_light() = snapshotBoard(
        scheme = AltarisScheme.AURORA_NEON,
        dark = false,
        name = "palette_aurora_neon_light",
    )

    @Test
    fun palette_liquid_glass_dark() = snapshotBoard(
        scheme = AltarisScheme.LIQUID_GLASS,
        dark = true,
        name = "palette_liquid_glass_dark",
    )

    @Test
    fun palette_liquid_glass_light() = snapshotBoard(
        scheme = AltarisScheme.LIQUID_GLASS,
        dark = false,
        name = "palette_liquid_glass_light",
    )

    @Test
    fun palette_solar_flare_dark() = snapshotBoard(
        scheme = AltarisScheme.SOLAR_FLARE,
        dark = true,
        name = "palette_solar_flare_dark",
    )

    @Test
    fun palette_solar_flare_light() = snapshotBoard(
        scheme = AltarisScheme.SOLAR_FLARE,
        dark = false,
        name = "palette_solar_flare_light",
    )

    @Test
    fun palette_mint_prism_dark() = snapshotBoard(
        scheme = AltarisScheme.MINT_PRISM,
        dark = true,
        name = "palette_mint_prism_dark",
    )

    @Test
    fun palette_mint_prism_light() = snapshotBoard(
        scheme = AltarisScheme.MINT_PRISM,
        dark = false,
        name = "palette_mint_prism_light",
    )

    @Test
    fun palette_iridescent_dark() = snapshotBoard(
        scheme = AltarisScheme.IRIDESCENT,
        dark = true,
        name = "palette_iridescent_dark",
    )

    @Test
    fun palette_iridescent_light() = snapshotBoard(
        scheme = AltarisScheme.IRIDESCENT,
        dark = false,
        name = "palette_iridescent_light",
    )

    private fun snapshotBoard(scheme: AltarisScheme, dark: Boolean, name: String) {
        paparazzi.snapshot(name = name) {
            FolioTheme(
                themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT,
                scheme = scheme,
                accent = ThemePalette.SCHEME,
            ) {
                PaletteBoard(scheme = scheme, dark = dark)
            }
        }
    }
}

/**
 * The board itself: wordmark, identity gradient, glass card, primary action,
 * pill row, two rows and a caption.
 */
@Composable
private fun PaletteBoard(scheme: AltarisScheme, dark: Boolean) {
    val palette = AltarisPalettes.of(scheme, dark)
    Box(modifier = Modifier.fillMaxSize().background(palette.base)) {
        AltarisBackdrop(modifier = Modifier.fillMaxSize(), palette = palette)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(Quartz.ScreenPad),
            verticalArrangement = Arrangement.spacedBy(Quartz.Gap),
        ) {
            // Wordmark.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(11.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Brush.linearGradient(palette.accentStops))
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Altaris",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.3).sp,
                    color = palette.onSurface,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = scheme.label.uppercase(),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.4.sp,
                    color = palette.primary,
                )
            }

            // Hero panel painted with the identity gradient.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(132.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(Brush.linearGradient(palette.heroStops))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                0.55f to Color.Black.copy(alpha = 0.08f),
                                1f to Color.Black.copy(alpha = 0.45f),
                            )
                        )
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(16.dp)
                ) {
                    Text(
                        text = "Premium identity",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                    )
                    Text(
                        text = scheme.tagline,
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.88f),
                    )
                }
            }

            // Glass card.
            QCard {
                Column(modifier = Modifier.padding(Quartz.CardPad)) {
                    Text(
                        text = "Glass card",
                        fontSize = Quartz.RowTitleSize,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.onSurface,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = "Frosted surface, gradient hairline and a specular top edge.",
                        fontSize = Quartz.RowSubSize,
                        lineHeight = 16.sp,
                        color = palette.onSurfaceVariant,
                    )
                }
            }

            QPrimaryButton("Primary action") {}

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QPill("Selected", true) {}
                QPill("Option", false) {}
                QPill("Option", false) {}
            }

            QCard {
                QRow(
                    title = "Clone",
                    subtitle = "Clone an installed app, pick an APK",
                    onClick = {},
                    divider = true,
                )
                QRow(
                    title = "Display",
                    subtitle = "FPS, resolution and DPI",
                    onClick = {},
                )
            }

            Text(
                text = "Altaris · ${scheme.label} · ${if (dark) "Dark" else "Light"}",
                fontSize = 10.sp,
                color = palette.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}
