package com.altaris.linux.core.rea

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Frida Gadget: the non-root half of the debugger.
 *
 * Root can attach to any process, so `frida-server` is the default. Without
 * root, ptrace across UIDs is refused by the kernel and no amount of in-app
 * cleverness changes that — a clone is a **separate package with its own UID**,
 * not a child of Altaris.
 *
 * Gadget sidesteps the problem instead of fighting it: the library is embedded
 * in the clone's APK and loaded *inside the clone's own process*, so the code
 * doing the instrumentation runs in the same UID as the code being
 * instrumented. No ptrace, no root.
 *
 * The cost is honest and worth stating: Gadget has to be baked in when the
 * clone is built, so it cannot attach to a clone that already exists, and an
 * app that verifies its own APK contents will notice the extra library.
 */
object FridaGadget {

    private const val TAG = "AltarisFridaGadget"

    /** Pinned to the same release as the server, so the two never disagree. */
    const val VERSION = ReaFrida.VERSION

    /**
     * The gadget for this device's architecture.
     *
     * Android's own ABI names, because the file is copied into the APK's
     * `lib/<abi>/` and the loader picks it up from there.
     */
    const val ABI = "arm64-v8a"

    const val LIB_NAME = "libfrida-gadget.so"

    private const val URL_TEMPLATE =
        "https://github.com/frida/frida/releases/download/$VERSION/frida-gadget-$VERSION-android-arm64.so.xz"

    /**
     * The class that loads the gadget.
     *
     * It is added to the clone's DEX and referenced from the application class's
     * static initialiser, which runs before any of the app's own code — the
     * earliest point an instrumentation library can be installed.
     */
    const val LOADER_CLASS = "Lcom/altaris/gadget/GadgetLoader;"

    /** `System.loadLibrary("frida-gadget")` resolves to `libfrida-gadget.so`. */
    const val LOAD_LIBRARY_NAME = "frida-gadget"

    /**
     * The gadget config written next to the library.
     *
     * `listen` makes it wait for a client on a Unix socket owned by the app, so
     * the CLI reaches it without a network port and without root.
     */
    val CONFIG_JSON: String = """
        {
          "interaction": {
            "type": "listen",
            "address": "127.0.0.1",
            "port": 27043,
            "on_load": "wait"
          }
        }
    """.trimIndent()

    /** The config's name is fixed by Frida: same stem as the library. */
    const val CONFIG_NAME = "libfrida-gadget.config.so"

    data class State(
        val cached: Boolean = false,
        val error: String = "",
    )

    /** True when the gadget binary is already on disk. */
    fun isCached(context: Context): Boolean = cacheFile(context).let {
        it.isFile && it.length() > 1_000_000
    }

    /** Where the decompressed library is kept between clones. */
    fun cacheFile(context: Context): File =
        File(context.applicationContext.filesDir, "frida/$LIB_NAME")

    /**
     * Make sure the gadget is downloaded and decompressed.
     *
     * Done once and reused for every clone; it is the same library regardless
     * of which app it is injected into.
     */
    suspend fun ensure(context: Context): State {
        val target = cacheFile(context)
        if (isCached(context)) return State(cached = true)
        return try {
            target.parentFile?.mkdirs()
            val compressed = File(target.parentFile, "$LIB_NAME.xz")
            download(URL_TEMPLATE, compressed)
            if (!decompress(compressed, target)) {
                return State(error = "cannot decompress the gadget")
            }
            compressed.delete()
            State(cached = isCached(context))
        } catch (t: Throwable) {
            Log.w(TAG, "gadget download failed", t)
            State(error = t.message ?: t.javaClass.simpleName)
        }
    }

    private fun download(url: String, target: File) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 90_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "altaris")
        }
        try {
            if (connection.responseCode != 200) {
                throw IllegalStateException("HTTP " + connection.responseCode + " for " + url)
            }
            connection.inputStream.use { input ->
                target.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Unpack the gadget.
     *
     * Delegates to [XzArchive] for the same reason as the server: no `xz` and
     * no `python3` exist on an Android host, so the in-app decoder is the only
     * route that works.
     */
    private fun decompress(source: File, target: File): Boolean =
        XzArchive.decompress(source, target)
}
