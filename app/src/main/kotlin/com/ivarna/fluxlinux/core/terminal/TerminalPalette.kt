package com.ivarna.fluxlinux.core.terminal

import com.termux.terminal.TerminalColors
import java.util.Properties

/**
 * Terminal colours are intentionally independent of the app theme. A terminal
 * must stay dark and readable even when the app is in Day mode.
 */
object TerminalPalette {
    @Volatile
    private var applied = false

    fun apply() {
        if (applied) return
        synchronized(this) {
            if (applied) return
            val props = Properties().apply {
                setProperty("foreground", "#E7F3EF")
                setProperty("background", "#05080B")
                setProperty("cursor", "#79F2C4")
                setProperty("color0", "#0A1014")
                setProperty("color1", "#FF8A80")
                setProperty("color2", "#79F2C4")
                setProperty("color3", "#FFD08A")
                setProperty("color4", "#7DDFF3")
                setProperty("color5", "#C7B8FF")
                setProperty("color6", "#66E3D0")
                setProperty("color7", "#D8E8E3")
                setProperty("color8", "#5E7771")
                setProperty("color9", "#FFB4AB")
                setProperty("color10", "#A6FFD9")
                setProperty("color11", "#FFE0AE")
                setProperty("color12", "#A9ECF8")
                setProperty("color13", "#DDD3FF")
                setProperty("color14", "#A5F4E6")
                setProperty("color15", "#F5FFFC")
            }
            runCatching { TerminalColors.COLOR_SCHEME.updateWith(props) }
            applied = true
        }
    }
}
