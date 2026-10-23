package com.altaris.linux.core.utils

import com.altaris.linux.core.root.RootShell

/**
 * Small compatibility facade over [RootShell] for older call sites.
 *
 * The previous implementation used `Runtime.getRuntime().exec("su")` and
 * `waitFor()` with **no timeout**, including from Compose composition. On a
 * fresh root grant the manager prompt could therefore freeze the UI forever.
 * Every command now goes through RootShell's bounded `captureResult`.
 */
object RootUtils {

    /** Checks if the device is rooted and if we can obtain root access. */
    fun isRootAvailable(): Boolean = runCatching { RootShell.isRootAvailable() }.getOrDefault(false)

    /**
     * Runs a command as root (su). Returns a Result object containing exit code and output.
     *
     * @param timeoutMs 0 waits forever (avoid on UI paths); >0 kills on timeout.
     */
    fun runRootCommand(command: String, timeoutMs: Long = 120_000L): ShellResult {
        val result = RootShell.captureResult(command, timeoutMs)
        return ShellResult(
            isSuccess = result.exitCode == 0,
            output = result.stdout.trim(),
            error = when (result.exitCode) {
                -1 -> "no working su"
                -2 -> "root command timed out after ${timeoutMs}ms"
                else -> ""
            }
        )
    }

    data class ShellResult(
        val isSuccess: Boolean,
        val output: String,
        val error: String
    )
}
