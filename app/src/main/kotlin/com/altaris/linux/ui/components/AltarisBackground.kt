package com.altaris.linux.ui.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The app backdrop. Kept as its own entry point so screens can request the
 * identity's quiet surface without knowing how it is painted.
 */
@Composable
fun AltarisAnimatedBackground(modifier: Modifier = Modifier) {
    PrismBackdrop(modifier = modifier.fillMaxSize())
}
