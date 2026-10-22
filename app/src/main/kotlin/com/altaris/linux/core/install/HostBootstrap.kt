package com.altaris.linux.core.install

/**
 * Host `bootstrap.tar` pins for the GitHub release tag `rootfs`.
 *
 * Both flavors ship `assets/bootstrap.tar` in the APK; this URL is only a
 * repair fallback used when the asset is missing.
 *
 * Filename is stable per applicationId. A SHA change requires a **new**
 * filename (same D9 rule as distro rootfs) so old APKs keep working.
 */
object HostBootstrap {

    const val RELEASE_BASE =
        "https://github.com/abhay-byte/fluxlinux/releases/download/rootfs"

    const val IVARNA_PACKAGE = "com.ivarna.fluxlinux"
    const val ZENITHBLUE_PACKAGE = "com.zenithblue.fluxlinux"
    const val ALTARIS_PACKAGE = "com.altaris.linux"

    /** Pre-rename applicationId of the Altaris flavor. */
    const val LEGACY_BLISS_PACKAGE = "com.nopiece.bliss"

    /** Legacy applicationId (pre-Altaris rename); kept so old copies still resolve. */
    const val LEGACY_FOLIO_PACKAGE = "com.nopiece.folio"

    val IVARNA = PinnedReleaseArchive(
        fileName = "bootstrap_com.ivarna.fluxlinux.tar",
        sha256 = "5b16c6597d38380c0cab9471d3cf69a0c7a23d9a4191125bd9dd6ddc77277f5c",
        url = "$RELEASE_BASE/bootstrap_com.ivarna.fluxlinux.tar",
        minBytes = 50L * 1024L * 1024L
    )

    val ZENITHBLUE = PinnedReleaseArchive(
        fileName = "bootstrap_com.zenithblue.fluxlinux.tar",
        sha256 = "b0856e1009b8718455bfaf6cb5332e57473ab7d58200f8c28a6db89b30a30cfd",
        url = "$RELEASE_BASE/bootstrap_com.zenithblue.fluxlinux.tar",
        minBytes = 50L * 1024L * 1024L
    )

    // Altaris ships assets/bootstrap.tar like the other flavors; the release tag
    // has no altaris tar yet, so its repair fallback reuses the ivarna tar
    // (same usr/ payload; prefix is rewritten at extract time).
    val ALTARIS = PinnedReleaseArchive(
        fileName = IVARNA.fileName,
        sha256 = IVARNA.sha256,
        url = IVARNA.url,
        minBytes = IVARNA.minBytes
    )

    fun forApplicationId(applicationId: String): PinnedReleaseArchive =
        when (applicationId) {
            ZENITHBLUE_PACKAGE -> ZENITHBLUE
            ALTARIS_PACKAGE, LEGACY_BLISS_PACKAGE -> ALTARIS
            // Legacy com.nopiece.folio id — same flavor before the Altaris rename.
            LEGACY_FOLIO_PACKAGE -> ALTARIS
            else -> IVARNA
        }

    /**
     * True when this flavor does **not** package `assets/bootstrap.tar`.
     * Both flavors now ship it (F-Droid builds it from source); the release
     * URL is only a repair fallback when the asset is missing.
     */
    @Suppress("UNUSED_PARAMETER")
    fun downloadsFromRelease(applicationId: String): Boolean = false
}
