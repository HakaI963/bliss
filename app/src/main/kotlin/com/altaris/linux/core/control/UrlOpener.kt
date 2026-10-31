package com.altaris.linux.core.control

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * Opens a link from a guest shell in the phone's browser.
 *
 * A distro running under Altaris has no browser, no X display for one and no
 * `$BROWSER`, so `xdg-open`, `sensible-browser`, `termux-open-url` and the
 * `open`/`start` fallbacks that AI CLIs and `gh auth login` use all fail — the
 * device-auth links they print could never be clicked. The guest shim
 * (`assets/scripts/altaris-open.sh`) posts the URL to the loopback control
 * server and the app starts a plain `ACTION_VIEW` intent, which is exactly what
 * a browser needs.
 *
 * Only web-ish schemes are allowed: a guest must not be able to launch
 * arbitrary intents (APK installs, settings panels) just by printing a link.
 */
object UrlOpener {

    private const val TAG = "AltarisUrlOpener"

    /** Schemes a guest may ask the phone to open. */
    private val ALLOWED_SCHEMES = setOf("http", "https", "mailto", "tel", "sms")

    /**
     * Normalise and validate a guest-supplied URL; null when it is refused.
     *
     * Deliberately string-only (no `android.net.Uri`) so the accept/refuse rule
     * — the part that decides whether a guest can reach an arbitrary intent —
     * is unit tested on the JVM.
     */
    fun normalize(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        // Whitespace or control characters mean the argument was split wrong,
        // or somebody is trying to smuggle an intent through the parser.
        if (trimmed.any { it.isWhitespace() || it.isISOControl() }) return null
        // A local path is not a link. "file:" would hand the guest the file
        // provider surface, so paths are refused rather than rewritten.
        if (trimmed.startsWith("/") || trimmed.startsWith(".")) return null

        val colon = trimmed.indexOf(':')
        val slash = trimmed.indexOf('/')
        // "scheme:..." only when the colon comes before any path separator.
        val looksLikeScheme = colon > 0 && (slash < 0 || colon < slash)
        val prefix = if (looksLikeScheme) trimmed.substring(0, colon) else ""
        val scheme = prefix.lowercase().takeIf { isValidSchemeToken(it) }

        val candidate = when {
            // A real scheme token that we do not allow ("file:", "intent:",
            // "javascript:", "content:") is refused, never rewritten.
            scheme != null && scheme !in ALLOWED_SCHEMES -> return null
            scheme != null -> trimmed
            // Bare host, possibly with a port: "github.com/login/device" or
            // "example.com:8080/x" — the prefix is not a scheme token.
            else -> "https://$trimmed"
        }

        val effective = scheme ?: "https"
        val rest = candidate.substringAfter(':', "").removePrefix("//")
        return when (effective) {
            "http", "https" -> {
                val authority = rest.substringBefore('/').substringBefore('?')
                // "https://" alone would open a blank tab.
                if (authority.isEmpty()) return null
                // Reject credentials and host confusion in the authority.
                if (authority.any { it == '@' || it == '\\' }) return null
                candidate
            }
            else -> {
                // mailto:/tel:/sms: need a recipient, not an empty intent.
                if (rest.isEmpty()) return null
                candidate
            }
        }
    }

    /**
     * RFC 3986 scheme: ALPHA *( ALPHA / DIGIT / "+" / "-" / "." ).
     *
     * Dots are excluded on purpose: no scheme we allow contains one, and
     * without that rule a bare host with a port ("example.com:8080") parses as
     * a scheme token and gets refused instead of opened.
     */
    private fun isValidSchemeToken(value: String): Boolean {
        if (value.isEmpty()) return false
        if (!value[0].isLetter()) return false
        return value.all { it.isLetterOrDigit() || it == '+' || it == '-' }
    }

    /**
     * Start a VIEW intent for [url]. Returns the normalised URL on success, or
     * null when the URL is not allowed / no browser can handle it.
     */
    fun open(context: Context?, url: String?): String? {
        val ctx = context?.applicationContext ?: return null
        val target = normalize(url) ?: return null
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(target)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            ctx.startActivity(intent)
            target
        } catch (t: Throwable) {
            // No browser installed, or the activity is blocked by the OEM.
            Log.w(TAG, "cannot open $target", t)
            null
        }
    }
}
