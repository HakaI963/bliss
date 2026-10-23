package com.altaris.linux.ui.screens

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.altaris.linux.ui.components.AltarisSubHeader
import com.altaris.linux.core.utils.TermuxX11Preferences
import com.altaris.linux.ui.components.AltarisSwitch
import com.altaris.linux.ui.components.GlassSettingCard
import com.altaris.linux.ui.theme.AltarisBackground
import com.altaris.linux.ui.theme.AltarisSurface
import com.altaris.linux.ui.theme.AltarisSwitchUncheckedTrack
import com.altaris.linux.ui.theme.FrostHairline
import com.altaris.linux.ui.theme.TextPrimary
import com.altaris.linux.ui.theme.TextSecondary
import com.altaris.linux.ui.theme.altarisMutedText
import com.altaris.linux.ui.theme.rememberStaggerEntrance
import com.altaris.linux.ui.theme.staggerIn

/**
 * Embedded X11 display prefs — writes Lorie keys and broadcasts live updates.
 * Monochrome frost: headers/sliders/buttons in TextPrimary/TextSecondary greys only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun X11SettingsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current

    var displayScale by remember {
        mutableFloatStateOf(TermuxX11Preferences.getDisplayScale(context).toFloat())
    }
    var fullscreen by remember { mutableStateOf(TermuxX11Preferences.getFullscreen(context)) }
    var hideCutout by remember { mutableStateOf(TermuxX11Preferences.getHideCutout(context)) }
    var keepScreenOn by remember { mutableStateOf(TermuxX11Preferences.getKeepScreenOn(context)) }
    var capturePointer by remember { mutableStateOf(TermuxX11Preferences.getCapturePointer(context)) }
    var showAdditionalKbd by remember {
        mutableStateOf(TermuxX11Preferences.getShowAdditionalKeyboard(context))
    }
    var showIME by remember { mutableStateOf(TermuxX11Preferences.getShowIME(context)) }
    var preferScancodes by remember {
        mutableStateOf(TermuxX11Preferences.getPreferScancodes(context))
    }
    var scancodeWorkaround by remember {
        mutableStateOf(TermuxX11Preferences.getScancodeWorkaround(context))
    }

    // Display size: the slider drives width, height follows the device aspect
    // ratio, and the absolute "WxH" field overrides both. Seeded from the
    // current effective resolution so it reflects scaled/custom/exact prefs.
    val initialResolution = remember { TermuxX11Preferences.effectiveResolution(context) }
    var resolutionWidth by remember { mutableFloatStateOf(initialResolution.first.toFloat()) }
    var resolutionHeight by remember { mutableIntStateOf(initialResolution.second) }
    var absoluteResolution by remember {
        mutableStateOf("${initialResolution.first}x${initialResolution.second}")
    }
    var absoluteError by remember { mutableStateOf<String?>(null) }
    var resolutionFieldTouched by remember { mutableStateOf(false) }

    val deviceMetrics = remember { context.resources.displayMetrics }
    val deviceAspect = if (deviceMetrics.widthPixels > 0) {
        deviceMetrics.heightPixels.toFloat() / deviceMetrics.widthPixels.toFloat()
    } else {
        FALLBACK_DEVICE_ASPECT
    }

    /** LorieView floors the cvt mode width to a multiple of 8. */
    fun snapWidth(value: Float): Int =
        ((value.toInt() / 8) * 8).coerceIn(DISPLAY_WIDTH_MIN, DISPLAY_WIDTH_MAX)

    fun derivedHeight(width: Int): Int =
        (width * deviceAspect).toInt().coerceIn(
            TermuxX11Preferences.RESOLUTION_MIN_HEIGHT,
            TermuxX11Preferences.RESOLUTION_MAX_HEIGHT
        )

    fun showSize(width: Int, height: Int) {
        resolutionWidth = width.toFloat()
        resolutionHeight = height
        absoluteResolution = "${width}x$height"
        absoluteError = null
    }

    fun commitAbsoluteResolution() {
        val parsed = TermuxX11Preferences.parseResolution(absoluteResolution)
        if (parsed == null) {
            absoluteError = "Enter WxH between " +
                "${TermuxX11Preferences.RESOLUTION_MIN_WIDTH}x${TermuxX11Preferences.RESOLUTION_MIN_HEIGHT} and " +
                "${TermuxX11Preferences.RESOLUTION_MAX_WIDTH}x${TermuxX11Preferences.RESOLUTION_MAX_HEIGHT}"
            return
        }
        showSize(parsed.first, parsed.second)
        TermuxX11Preferences.setCustomResolution(context, parsed.first, parsed.second)
    }

    fun matchDevice() {
        // Reset to the default `scaled` mode: the X screen then follows the
        // device view at the current display scale, like a fresh install.
        TermuxX11Preferences.setResolutionMode(context, TermuxX11Preferences.RESOLUTION_MODE_SCALED)
        val effective = TermuxX11Preferences.effectiveResolution(context)
        showSize(effective.first, effective.second)
    }

    // Staggered card entrance (30ms steps) — visual only.
    val entrance = rememberStaggerEntrance()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AltarisSubHeader(
                title = "X11 Display",
                onBack = onBack,
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Changes apply to the embedded X11 display immediately (no external Termux:X11).",
                fontSize = 13.sp,
                color = altarisMutedText(),
                modifier = Modifier.staggerIn(0, entrance)
            )

            GlassSettingCard(modifier = Modifier.staggerIn(1, entrance)) {
                Column(Modifier.padding(20.dp).fillMaxWidth()) {
                    Text(
                        "Display",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Spacer(Modifier.height(16.dp))

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Display scale", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                        Text(
                            "${displayScale.toInt()}%",
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    }
                    Slider(
                        value = displayScale,
                        onValueChange = { displayScale = it },
                        onValueChangeFinished = {
                            TermuxX11Preferences.setDisplayScale(context, displayScale.toInt())
                        },
                        valueRange = 30f..300f,
                        steps = 26,
                        colors = SliderDefaults.colors(
                            thumbColor = TextPrimary,
                            activeTrackColor = TextPrimary,
                            inactiveTrackColor = AltarisSwitchUncheckedTrack
                        )
                    )

                    PrefDivider()

                    // ── Display size (custom WxH resolution) ─────────────────
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Display size",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Medium,
                            fontSize = 15.sp
                        )
                        Text(
                            "${resolutionWidth.toInt()} × $resolutionHeight",
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "X screen resolution in pixels. Slide the width; height follows the device aspect ratio.",
                        color = altarisMutedText(),
                        fontSize = 13.sp
                    )
                    Slider(
                        value = resolutionWidth,
                        onValueChange = { raw ->
                            val snapped = snapWidth(raw)
                            resolutionWidth = snapped.toFloat()
                            resolutionHeight = derivedHeight(snapped)
                            absoluteResolution = "${snapped}x$resolutionHeight"
                            absoluteError = null
                        },
                        onValueChangeFinished = {
                            TermuxX11Preferences.setCustomResolution(
                                context, resolutionWidth.toInt(), resolutionHeight
                            )
                        },
                        valueRange = DISPLAY_WIDTH_MIN.toFloat()..DISPLAY_WIDTH_MAX.toFloat(),
                        colors = SliderDefaults.colors(
                            thumbColor = TextPrimary,
                            activeTrackColor = TextPrimary,
                            inactiveTrackColor = AltarisSwitchUncheckedTrack
                        )
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = absoluteResolution,
                        onValueChange = {
                            absoluteResolution = it
                            absoluteError = null
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { focus ->
                                if (focus.isFocused) {
                                    resolutionFieldTouched = true
                                } else if (resolutionFieldTouched) {
                                    // Leaving the field applies a valid value;
                                    // garbage keeps the inline error visible.
                                    commitAbsoluteResolution()
                                }
                            },
                        singleLine = true,
                        label = { Text("Exact resolution") },
                        placeholder = { Text("1280x800") },
                        isError = absoluteError != null,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Ascii,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = { commitAbsoluteResolution() }),
                        trailingIcon = {
                            IconButton(onClick = { commitAbsoluteResolution() }) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = "Apply resolution",
                                    modifier = Modifier.size(20.dp),
                                    tint = TextSecondary
                                )
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                            cursorColor = TextPrimary,
                            focusedBorderColor = TextSecondary,
                            unfocusedBorderColor = FrostHairline,
                            focusedLabelColor = TextSecondary,
                            unfocusedLabelColor = altarisMutedText()
                        )
                    )
                    absoluteError?.let { message ->
                        Spacer(Modifier.height(6.dp))
                        Text(message, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { matchDevice() }) {
                            Text(
                                "Match device",
                                color = TextSecondary,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp
                            )
                        }
                    }
                    Text(
                        "Takes effect when the desktop (re)opens.",
                        color = altarisMutedText(),
                        fontSize = 12.5.sp
                    )

                    PrefDivider()
                    PrefSwitch(
                        title = "Fullscreen",
                        subtitle = "Immersive mode on the device display",
                        checked = fullscreen,
                        onChange = {
                            fullscreen = it
                            TermuxX11Preferences.setFullscreen(context, it)
                        }
                    )
                    PrefDivider()
                    PrefSwitch(
                        title = "Hide display cutout",
                        subtitle = "Use notch / cutout area",
                        checked = hideCutout,
                        onChange = {
                            hideCutout = it
                            TermuxX11Preferences.setHideCutout(context, it)
                        }
                    )
                    PrefDivider()
                    PrefSwitch(
                        title = "Keep screen on",
                        subtitle = "Prevent timeout while X11 is open",
                        checked = keepScreenOn,
                        onChange = {
                            keepScreenOn = it
                            TermuxX11Preferences.setKeepScreenOn(context, it)
                        }
                    )
                }
            }

            GlassSettingCard(modifier = Modifier.staggerIn(2, entrance)) {
                Column(Modifier.padding(20.dp).fillMaxWidth()) {
                    Text(
                        "Input",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Spacer(Modifier.height(12.dp))
                    PrefSwitch(
                        title = "Capture external pointer",
                        subtitle = "Intercept hardware pointer events",
                        checked = capturePointer,
                        onChange = {
                            capturePointer = it
                            TermuxX11Preferences.setCapturePointer(context, it)
                        }
                    )
                    PrefDivider()
                    PrefSwitch(
                        title = "Show additional keyboard",
                        subtitle = "Extra keys bar inside the X11 surface",
                        checked = showAdditionalKbd,
                        onChange = {
                            showAdditionalKbd = it
                            TermuxX11Preferences.setShowAdditionalKeyboard(context, it)
                        }
                    )
                    PrefDivider()
                    PrefSwitch(
                        title = "Show IME with external keyboard",
                        subtitle = "Software keyboard while a hardware keyboard is connected",
                        checked = showIME,
                        onChange = {
                            showIME = it
                            TermuxX11Preferences.setShowIME(context, it)
                        }
                    )
                    PrefDivider()
                    PrefSwitch(
                        title = "Prefer scancodes",
                        subtitle = "Let the X server handle keyboard layout",
                        checked = preferScancodes,
                        onChange = {
                            preferScancodes = it
                            TermuxX11Preferences.setPreferScancodes(context, it)
                        }
                    )
                    PrefDivider()
                    PrefSwitch(
                        title = "Hardware keyboard scancodes workaround",
                        subtitle = "Fix scancodes on some devices",
                        checked = scancodeWorkaround,
                        onChange = {
                            scancodeWorkaround = it
                            TermuxX11Preferences.setScancodeWorkaround(context, it)
                        }
                    )
                }
            }

            Button(
                onClick = {
                    TermuxX11Preferences.applyToTermux(context)
                    TermuxX11Preferences.openTermuxX11Preferences(context)
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = AltarisSurface,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ),
                border = BorderStroke(0.75.dp, FrostHairline),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .staggerIn(3, entrance)
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Icon(
                    Icons.Default.OpenInNew,
                    null,
                    Modifier.size(18.dp),
                    tint = TextSecondary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Open full X11 preferences",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = {
                    TermuxX11Preferences.applyToTermux(context)
                    Toast.makeText(context, "Applied to embedded X11", Toast.LENGTH_SHORT).show()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = TextPrimary,
                    contentColor = AltarisBackground
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .staggerIn(4, entrance)
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text(
                    "Re-apply to running display",
                    fontWeight = FontWeight.Bold,
                    color = AltarisBackground
                )
            }

            Spacer(Modifier.height(110.dp))
        }
    }
}

@Composable
private fun PrefDivider() {
    HorizontalDivider(
        color = FrostHairline,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

@Composable
private fun PrefSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium, fontSize = 15.sp)
            Text(
                subtitle,
                color = altarisMutedText(),
                fontSize = 13.sp
            )
        }
        AltarisSwitch(checked = checked, onCheckedChange = onChange)
    }
}

// Width slider bounds (px). Typed WxH values may go wider — these just keep
// the drag usable on a phone screen.
private const val DISPLAY_WIDTH_MIN = 480
private const val DISPLAY_WIDTH_MAX = 2560

/** Height/width ratio fallback when display metrics are unavailable. */
private const val FALLBACK_DEVICE_ASPECT = 16f / 9f
