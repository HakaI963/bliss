package com.altaris.linux

import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.asImageBitmap
import com.altaris.linux.core.clone.HeadlessCloneEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Dedicated PiP surface: only the virtual display, no app chrome, tabs, logs
 * or controls. The normal viewer stays responsible for Resume/Stop/Leave.
 */
class ClonePipActivity : ComponentActivity() {
    private var enteredPip = false
    private var requestedPip = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val width = intent.getIntExtra("width", 720).coerceAtLeast(1)
        val height = intent.getIntExtra("height", 1280).coerceAtLeast(1)
        val fps = intent.getIntExtra("fps", 30).coerceIn(10, 120)
        // PiP can be opened while several clones run; show this one, not
        // whichever display happens to be the viewer's.
        val packageName = intent.getStringExtra("package")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                setPictureInPictureParams(
                    PictureInPictureParams.Builder()
                        .setAspectRatio(Rational(width, height))
                        .build()
                )
            }
        }
        setContent {
            val preview by HeadlessCloneEngine.preview.collectAsState()
            val connected by HeadlessCloneEngine.connected.collectAsState()
            LaunchedEffect(connected, fps, packageName) {
                if (!connected) return@LaunchedEffect
                // PiP is a small surface: 24 fps is enough and leaves the CPU
                // headroom for the cloned app and the rest of the UI.
                val frameDelay = (1000 / fps.coerceIn(10, 24)).coerceAtLeast(42).toLong()
                while (isActive) {
                    if (packageName.isNullOrBlank()) {
                        HeadlessCloneEngine.capturePreview()
                    } else {
                        HeadlessCloneEngine.capturePreviewOf(packageName)
                    }
                    delay(frameDelay)
                }
            }
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                preview?.let { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (requestedPip) return
        requestedPip = true
        window.decorView.post {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val entered = runCatching {
                    val width = intent.getIntExtra("width", 720).coerceAtLeast(1)
                    val height = intent.getIntExtra("height", 1280).coerceAtLeast(1)
                    enterPictureInPictureMode(
                        PictureInPictureParams.Builder()
                            .setAspectRatio(Rational(width, height))
                            .build()
                    )
                }.getOrDefault(false)
                enteredPip = entered
                if (!entered) finish()
            } else {
                finish()
            }
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (enteredPip && !isInPictureInPictureMode) finish()
    }
}
