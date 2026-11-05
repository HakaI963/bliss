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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The Altaris shell: static backdrop, transparent scaffold and a floating dock.
 *
 * [bottomBarInset] is how much vertical room the floating dock occupies. The
 * dock is drawn *over* the content rather than in the scaffold's bottomBar
 * slot, so without this the content has no idea it is there and the last row
 * of any scrolling tab — the final card in REA → Other, a file at the end of
 * a list — sits underneath it and cannot be reached. Callers that draw a dock
 * must pass its height; callers that draw none leave it at 0 and nothing
 * changes.
 */
@Composable
fun GlassScaffold(
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    bottomBarInset: Dp = 0.dp,
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
                        // Keep the content clear of the floating dock, so the
                        // end of every list is actually reachable.
                        .padding(bottom = bottomBarInset)
                ) {
                    content()
                }
            }
        )

        // The navigation bar spans the full width and only the system
        // navigation inset is kept clear.
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
        ) {
            bottomBar()
        }
    }
}
