package com.ivarna.fluxlinux.ui.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Altaris backdrop: the animated AGSL aurora of the active identity.
 *
 * The aurora is a slow sixteen-second loop of three drifting blooms; on
 * devices without AGSL (and inside Paparazzi) it degrades to the same
 * palette painted as a static gradient. The glass surfaces and press
 * feedback provide the depth on top of it.
 */
@Composable
fun FolioAnimatedBackground(modifier: Modifier = Modifier) {
    AltarisBackdrop(modifier = modifier.fillMaxSize())
}
