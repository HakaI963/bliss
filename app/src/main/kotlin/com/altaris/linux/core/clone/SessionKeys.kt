package com.altaris.linux.core.clone

/**
 * Display-session keys.
 *
 * Every virtual display is addressed by a key: the clone package for a clone,
 * [PRIMARY] for an ad-hoc installed app. Machines (MCP, HTTP API, the guest
 * CLI) may pass a package name; when they do not, the request belongs to the
 * clone the viewer is showing.
 *
 * This is deliberately pure Kotlin so the routing rule — the part that decides
 * whether two agents collide — is unit tested without a device.
 */
object SessionKeys {

    /** Key used when no clone owns the display. */
    const val PRIMARY = "primary"

    /** Trimmed package name, or null when the caller did not provide one. */
    fun normalize(packageName: String?): String? =
        packageName?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * The display a request must address.
     *
     * Explicit package wins (that is what lets agent A talk to clone A while
     * agent B talks to clone B); otherwise the viewer's clone, otherwise the
     * shared ad-hoc display.
     */
    fun resolve(packageName: String?, viewerPackage: String?): String =
        normalize(packageName) ?: normalize(viewerPackage) ?: PRIMARY

    /**
     * Whether [key] names a clone rather than the ad-hoc display. Clone
     * packages are the only keys the app generates itself.
     */
    fun isCloneKey(key: String?): Boolean {
        val clean = normalize(key) ?: return false
        return clean.startsWith(CLONE_PREFIX) || clean.startsWith(LEGACY_CLONE_PREFIX)
    }

    private const val CLONE_PREFIX = "com.altaris.apps."
    private const val LEGACY_CLONE_PREFIX = "com.nopiece.hclone."
}
