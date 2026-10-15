package com.ivarna.fluxlinux.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze

@Composable
fun GlassScaffold(
    hazeState: HazeState,
    blurContent: Boolean = true,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Folio ambient backdrop: pure carbon + very faint drifting light pools.
        // Sits behind the Scaffold and behind the floating nav overlay, and
        // never installs pointer input, so it can never eat touches. Shared by
        // every GlassScaffold caller (Home, install wizard, distro settings).
        FolioAnimatedBackground(modifier = Modifier.fillMaxSize())

        Scaffold(
            containerColor = Color.Transparent,
            topBar = topBar,
            // Do not consume IME / status-bar insets here. Terminal owns IME
            // padding so ExtraKeys sit on the keyboard; Home TopBar owns status bars.
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            content = { paddingValues ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(if (blurContent) Modifier.haze(state = hazeState) else Modifier)
                        .padding(top = paddingValues.calculateTopPadding()) // Only respect top padding from Scaffold
                ) {
                    content()
                }
            }
        )

        // Overlay Floating Bottom Bar
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
        ) {
            bottomBar()
        }
    }
}
