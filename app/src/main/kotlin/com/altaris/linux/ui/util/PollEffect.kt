package com.altaris.linux.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay

/**
 * A poll loop that stops while the app is not visible.
 *
 * Every screen in the app polled on its own `while (true) { … delay(n) }`
 * inside a `LaunchedEffect`. A `LaunchedEffect` outlives the screen only until
 * it leaves composition — but a tab that is still composed, or the app while it
 * is in the background, kept polling forever. Each tick could fork a guest
 * shell or a root probe, so a phone left on the REA tab was waking up every
 * fifteen seconds to run work nobody could see. That is battery, and it is
 * memory churn that makes the next interaction slower.
 *
 * This runs [block] once immediately, then every [intervalMs], and suspends the
 * loop entirely while the lifecycle is below STARTED. On return it runs once
 * straight away, so the screen is current the moment it is visible again
 * instead of showing a stale value until the next tick.
 */
@Composable
fun PollEffect(
    intervalMs: Long,
    key: Any? = Unit,
    block: suspend () -> Unit,
) {
    val owner = LocalLifecycleOwner.current
    var visible by remember { mutableStateOf(true) }

    androidx.compose.runtime.DisposableEffect(owner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START, Lifecycle.Event.ON_RESUME -> visible = true
                Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_PAUSE -> visible = false
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        visible = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(key, visible) {
        if (!visible) return@LaunchedEffect
        while (true) {
            block()
            delay(intervalMs)
        }
    }
}
