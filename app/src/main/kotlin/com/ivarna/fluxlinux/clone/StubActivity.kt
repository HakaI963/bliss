package com.ivarna.fluxlinux.clone

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.WindowManager

/**
 * Invisible wallpaper-layer activity pinned onto the hidden clone display.
 *
 * Its only job is to hold FLAG_KEEP_SCREEN_ON on that display so SurfaceFlinger
 * keeps compositing it (HyperOS leaves freshly created secondary display
 * groups powered down until something on the display asks for the screen).
 * Fully transparent, non-focusable (the clone app below keeps input) and
 * non-touchable, so it stays invisible to the user and to the clone app.
 */
class StubActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        } catch (ignored: Throwable) {
        }
    }
}
