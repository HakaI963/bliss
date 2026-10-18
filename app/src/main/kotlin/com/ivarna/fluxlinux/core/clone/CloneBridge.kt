package com.ivarna.fluxlinux.core.clone

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

/**
 * File bridge between AI agents (the `clone` CLI and `bliss-clone-mcp` running
 * inside distros) and this app.
 *
 * Requests land in `<filesDir>/clone/bridge/req/<id>`:
 *   line 1 = command, following lines = one argument per line.
 * Replies land in `res/<id>` (first line OK|ERR, rest = payload). Screenshots
 * additionally write `res/<id>.bin` (PNG) and report it as `FILE <path>`.
 *
 * The CLI is deployed to the host prefix and every proot distro; proot sessions
 * bind `<filesDir>/clone` at /bliss so guests reach the bridge at /bliss/bridge.
 */
object CloneBridge {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false

    private const val HELP = """Bliss clone bridge commands:
status | list | list_device | clone_device <pkg> | install <path> |
install_url <url> | launch <pkg> | kill <pkg> | uninstall <pkg> |
screenshot [maxWidth] | ocr | tap <x> <y> |
swipe <x1> <y1> <x2> <y2> [ms] | text <value> | key <BACK|HOME|ENTER> | help

Apps live in the Bliss clone space: clone_device copies an app installed on the
phone, install/install_url add APK files. Everything runs on the hidden display."""

    fun start(ctx: Context) {
        if (started) return
        started = true
        val base = File(ctx.filesDir, "clone/bridge")
        File(base, "req").mkdirs()
        File(base, "res").mkdirs()
        File(base, "inbox").mkdirs()
        scope.launch { loop(base) }
        CloneEngine.log("Agent bridge listening (${base.absolutePath})")
    }

    private suspend fun loop(base: File) {
        while (true) {
            try {
                val reqDir = File(base, "req")
                val files = reqDir.listFiles()?.sortedBy { it.name } ?: emptyList()
                for (f in files) {
                    if (f.isDirectory) continue
                    handle(base, f)
                    runCatching { f.delete() }
                }
            } catch (t: Throwable) {
                CloneEngine.log("Bridge loop error: ${t.message}")
            }
            delay(250)
        }
    }

    private suspend fun handle(base: File, requestFile: File) {
        val id = requestFile.name
        val lines = runCatching { requestFile.readLines() }.getOrDefault(emptyList())
        val cmd = lines.firstOrNull()?.trim().orEmpty()
        val args = lines.drop(1)
        var status = "OK"
        var payload = ""
        var binFile: File? = null

        try {
            when (cmd) {
                "status" -> payload = CloneEngine.statusLines()

                "list" -> payload = CloneEngine.listLines()

                "install" -> {
                    val path = args.firstOrNull() ?: throw IllegalArgumentException("install needs a path")
                    val err = CloneEngine.installApkNow(path)
                    if (err != null) throw IllegalStateException(err)
                    payload = "installed"
                }

                "install_url" -> {
                    val url = args.firstOrNull() ?: throw IllegalArgumentException("install_url needs a url")
                    val err = CloneEngine.installUrlNow(url)
                    if (err != null) throw IllegalStateException(err)
                    payload = "installed"
                }

                "launch" -> {
                    val pkg = args.firstOrNull() ?: throw IllegalArgumentException("launch needs a package")
                    val err = CloneEngine.launchNow(pkg)
                    if (err != null) throw IllegalStateException(err)
                    payload = "launched $pkg"
                }

                "kill" -> {
                    val pkg = args.firstOrNull() ?: throw IllegalArgumentException("kill needs a package")
                    val err = CloneEngine.killNow(pkg)
                    if (err != null) throw IllegalStateException(err)
                    payload = "killed $pkg"
                }

                "uninstall" -> {
                    val pkg = args.firstOrNull() ?: throw IllegalArgumentException("uninstall needs a package")
                    val err = CloneEngine.uninstallNow(pkg)
                    if (err != null) throw IllegalStateException(err)
                    payload = "uninstalled $pkg"
                }

                "tap" -> {
                    val x = args.getOrNull(0)?.toFloatOrNull()
                        ?: throw IllegalArgumentException("tap needs x and y")
                    val y = args.getOrNull(1)?.toFloatOrNull()
                        ?: throw IllegalArgumentException("tap needs x and y")
                    val ok = CloneEngine.inputSync(
                        listOf("tap", x.roundToInt().toString(), y.roundToInt().toString()),
                    )
                    if (!ok) throw IllegalStateException(
                        "tap failed - touch backend: ${CloneEngine.touchBackend.value.label}",
                    )
                    payload = "tapped ${x.roundToInt()} ${y.roundToInt()}"
                }

                "swipe" -> {
                    val coords = (0..3).map { i ->
                        args.getOrNull(i)?.toFloatOrNull()
                            ?: throw IllegalArgumentException("swipe needs x1 y1 x2 y2 [ms]")
                    }
                    val ms = args.getOrNull(4)?.toIntOrNull() ?: 300
                    val ok = CloneEngine.inputSync(
                        listOf(
                            "swipe",
                            coords[0].roundToInt().toString(), coords[1].roundToInt().toString(),
                            coords[2].roundToInt().toString(), coords[3].roundToInt().toString(),
                            ms.toString(),
                        ),
                    )
                    if (!ok) throw IllegalStateException("swipe failed")
                    payload = "swiped"
                }

                "text" -> {
                    val text = args.joinToString(" ").trim()
                    if (text.isEmpty()) throw IllegalArgumentException("text needs a value")
                    val escaped = text.replace(" ", "%s")
                    val ok = CloneEngine.inputSync(listOf("text", escaped))
                    if (!ok) throw IllegalStateException("text failed")
                    payload = "typed ${text.length} chars"
                }

                "key" -> {
                    val key = args.firstOrNull() ?: throw IllegalArgumentException("key needs a value")
                    val ok = CloneEngine.inputSync(listOf("keyevent", key))
                    if (!ok) throw IllegalStateException("key failed")
                    payload = "key $key"
                }

                "screenshot" -> {
                    val maxW = args.getOrNull(0)?.toIntOrNull() ?: 720
                    val bmp = CloneEngine.latestFrameBitmap()
                        ?: throw IllegalStateException("no frame yet - is a clone app running?")
                    val scaled = scale(bmp, maxW)
                    val out = File(base, "res/$id.bin")
                    FileOutputStream(out).use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    binFile = out
                    payload = "FILE ${out.absolutePath}\nSIZE ${scaled.width} ${scaled.height}"
                }

                "ocr" -> {
                    val bmp = CloneEngine.latestFrameBitmap()
                        ?: throw IllegalStateException("no frame yet - is a clone app running?")
                    val ocrLines = CloneOcr.recognize(bmp)
                        ?: throw IllegalStateException("ocr unavailable")
                    payload = if (ocrLines.isEmpty()) {
                        "(no text found)"
                    } else {
                        ocrLines.joinToString("\n") { "${it.text}|${it.x}|${it.y}|${it.w}|${it.h}" }
                    }
                }

                "list_device" -> payload = CloneEngine.deviceAppLines()

                "clone_device" -> {
                    val pkg = args.firstOrNull() ?: throw IllegalArgumentException("clone_device needs a package")
                    val err = CloneEngine.cloneDeviceNow(pkg)
                    if (err != null) throw IllegalStateException(err)
                    payload = "cloned $pkg into the Bliss space"
                }

                "help" -> payload = HELP

                "" -> throw IllegalArgumentException("empty command")

                else -> throw IllegalArgumentException("unknown command: $cmd")
            }
        } catch (t: Throwable) {
            status = "ERR"
            payload = t.message ?: "command failed"
            binFile?.let { b -> runCatching { b.delete() } }
            binFile = null
            CloneEngine.log("Bridge $cmd failed: $payload")
        }

        try {
            val res = File(base, "res/$id")
            val tmp = File(base, "res/$id.tmp")
            tmp.writeText("$status\n$payload")
            tmp.renameTo(res)
        } catch (t: Throwable) {
            CloneEngine.log("Bridge reply failed: ${t.message}")
        }
    }

    private fun scale(src: Bitmap, maxW: Int): Bitmap {
        if (maxW <= 0 || src.width <= maxW) return src
        val h = (src.height.toFloat() * maxW / src.width).roundToInt().coerceAtLeast(1)
        return runCatching { Bitmap.createScaledBitmap(src, maxW, h, true) }.getOrDefault(src)
    }
}
