package com.altaris.linux.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState

/**
 * The Altaris shell: layered backdrop, transparent scaffold and a floating
 * dock. The backdrop reacts to touches through [spotlight].
 */
@Composable
fun GlassScaffold(
    hazeState: HazeState,
    blurContent: Boolean = true,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        PrismBackdrop()
        Scaffold(
            containerColor = Color.Transparent,
            topBar = topBar,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            content = { paddingValues ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = paddingValues.calculateTopPadding())
                ) {
                    content()
                }
            }
        )

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 14.dp, vertical = 6.dp)
        ) {
            bottomBar()
        }
    }
}
