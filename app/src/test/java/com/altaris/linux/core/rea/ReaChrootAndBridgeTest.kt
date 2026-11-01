package com.altaris.linux.core.rea

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two failures from the device log.
 *
 * 1. `useradd: user 'flux' already exists` — the family script created an
 *    account under the retired product name and then granted sudo to a
 *    different, non-existent one.
 * 2. `bind failed: EADDRINUSE` — the MCP bridge could not bind its port, so the
 *    server never came up.
 */
class ReaChrootAndBridgeTest {

    /**
     * The guest account is `altaris`, and the script must create and grant that
     * account rather than a hardcoded legacy name.
     */
    @Test
    fun theFamilyScriptCreatesTheAltarisAccount() {
        val script = readAsset("scripts/debian/common/setup/setup_debian_family.sh")
        assertTrue(
            "the guest user must be defined once",
            script.contains("GUEST_USER=\"altaris\""),
        )
        assertTrue(
            "the account must be created from that variable",
            script.contains("useradd -m -s /bin/bash \"\$GUEST_USER\""),
        )
        assertTrue(
            "sudo must be granted to the account that exists",
            script.contains("/etc/sudoers.d/\$GUEST_USER"),
        )
        // The old bug: creating one account, then granting sudo to another that
        // did not exist.
        assertFalse(
            "must not create an account under the retired name",
            script.contains("useradd -m -s /bin/bash flux"),
        )
        assertFalse(
            "must not grant sudo to a hardcoded account name",
            script.contains("usermod -aG sudo altaris"),
        )
    }

    /** The retired account name must not appear in the setup script at all. */
    @Test
    fun theRetiredAccountNameIsGone() {
        val script = readAsset("scripts/debian/common/setup/setup_debian_family.sh")
        assertFalse(
            "no flux account may be created or referenced",
            script.contains("flux"),
        )
    }

    /**
     * A chroot container is unreadable from the app process, so presence has to
     * be checked through root. Reporting it absent made a finished install look
     * like "the container was not created".
     */
    @Test
    fun chrootPresenceIsCheckedThroughRoot() {
        val source = readSource(
            "app/src/main/kotlin/com/altaris/linux/core/rea/ReaInstaller.kt"
        )
        val exists = source.substringAfter("fun containerExists")
            .substringBefore("suspend fun ensureContainer")
        assertTrue(
            "chroot presence must not rely on a direct directory read",
            exists.contains("profile.method != \"chroot\""),
        )
        assertTrue("must go through root", exists.contains("RootShell.captureResult"))
        assertTrue(
            "must fall back to the cached probe without root",
            exists.contains("cachedInstalled"),
        )
    }

    /** The bridge must survive a port left in TIME_WAIT by a killed process. */
    @Test
    fun theBridgeReusesTheAddressAndFallsBack() {
        val source = readSource(
            "app/src/main/kotlin/com/altaris/linux/core/rea/BridgeServer.kt"
        )
        assertTrue("must enable SO_REUSEADDR", source.contains("reuseAddress = true"))
        assertTrue("must catch a bind failure", source.contains("BindException"))
        assertTrue("must try another port", source.contains("ReaBridge.PORT + offset"))
        assertTrue("must record the port it got", source.contains("boundPort = server.localPort"))
    }

    /** The config an agent copies must name the port that is actually open. */
    @Test
    fun theAdvertisedEndpointTracksTheBoundPort() {
        val source = readSource(
            "app/src/main/kotlin/com/altaris/linux/core/rea/ReaBridge.kt"
        )
        assertTrue("must expose the live endpoint", source.contains("val liveEndpoint"))
        assertTrue(
            "the config block must use it",
            source.contains("\"url\": \"\${liveEndpoint}\""),
        )
        // The default stays available for docs and tests.
        assertTrue(source.contains("const val ENDPOINT"))
    }

    @Test
    fun theDefaultPortIsStillTheDocumentedOne() {
        assertEquals(47832, ReaBridge.PORT)
        assertTrue(ReaBridge.ENDPOINT.endsWith(":47832/mcp"))
    }

    private fun readAsset(path: String): String {
        for (candidate in listOf(
            "app/src/main/assets/$path",
            "src/main/assets/$path",
            "../app/src/main/assets/$path",
        )) {
            val file = java.io.File(candidate)
            if (file.isFile) return file.readText()
        }
        throw AssertionError("asset not found: $path")
    }

    private fun readSource(path: String): String {
        for (candidate in listOf(path, "../$path", "../../$path")) {
            val file = java.io.File(candidate)
            if (file.isFile) return file.readText()
        }
        throw AssertionError("source not found: $path")
    }
}
