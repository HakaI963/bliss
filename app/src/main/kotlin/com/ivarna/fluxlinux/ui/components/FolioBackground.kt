package com.ivarna.fluxlinux.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Flat AOSP background. The old drifting light-pool effect is intentionally
 * gone: it added motion and noise without helping readability.
 */
@Composable
fun FolioAnimatedBackground(modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    )
}
