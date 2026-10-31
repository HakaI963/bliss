package com.altaris.linux.core.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Contract for opening guest links in the phone browser.
 *
 * The bug these tests lock down: a distro shell has no browser, no `$BROWSER`
 * and no `DISPLAY`, so `xdg-open` (and the `open`/`start` fallbacks inside AI
 * CLIs and `gh auth login`) failed and the printed device-auth URL could never
 * be opened. The app now opens it through `/open-url`, and the guest gets a
 * shim + a `BROWSER` env var so the CLI finds it.
 */
class UrlOpenerTest {

    @Test
    fun httpsPassesThrough() {
        assertEquals("https://example.com/a?b=1", UrlOpener.normalize("https://example.com/a?b=1"))
        assertEquals("http://example.com", UrlOpener.normalize("http://example.com"))
    }

    @Test
    fun bareHostGetsHttps() {
        assertEquals("https://github.com/login/device", UrlOpener.normalize("github.com/login/device"))
        assertEquals("https://example.com", UrlOpener.normalize("  example.com  "))
        // A port must not be mistaken for a scheme.
        assertEquals("https://example.com:8080/x", UrlOpener.normalize("example.com:8080/x"))
    }

    @Test
    fun nonWebSchemesAreLimited() {
        assertEquals("mailto:a@b.c", UrlOpener.normalize("mailto:a@b.c"))
        assertEquals("tel:+123", UrlOpener.normalize("tel:+123"))
        assertEquals("sms:123", UrlOpener.normalize("sms:123"))
        // A guest must not be able to start arbitrary intents.
        assertNull(UrlOpener.normalize("intent://evil#Intent;end"))
        assertNull(UrlOpener.normalize("file:///etc/passwd"))
        assertNull(UrlOpener.normalize("javascript:alert(1)"))
        assertNull(UrlOpener.normalize("content://com.android.providers/x"))
    }

    @Test
    fun localPathsAndBlanksAreRefused() {
        assertNull(UrlOpener.normalize(null))
        assertNull(UrlOpener.normalize(""))
        assertNull(UrlOpener.normalize("   "))
        assertNull(UrlOpener.normalize("/etc/passwd"))
        assertNull(UrlOpener.normalize("./README.md"))
        assertNull(UrlOpener.normalize("has spaces in it"))
    }

    @Test
    fun emptyAuthoritiesAreRefused() {
        assertNull(UrlOpener.normalize("https://"))
        assertNull(UrlOpener.normalize("https:///path"))
        // Credentials/host confusion in the authority.
        assertNull(UrlOpener.normalize("https://user@evil.example"))
        assertNull(UrlOpener.normalize("mailto:"))
    }

    @Test
    fun openReturnsNullWithoutAContext() {
        assertNull(UrlOpener.open(null, "https://example.com"))
    }

    @Test
    fun shimIsShippedAndInstalledEverywhere() {
        val shim = repoFile("src/main/assets/scripts/altaris-open.sh").readText()
        assertTrue(shim.startsWith("#!/bin/sh"))
        assertTrue(shim.contains("POST"))
        assertTrue(shim.contains("/open-url"))
        assertTrue(shim.contains("BROWSER"))
        // A GUI session with a real browser keeps the link inside the guest.
        assertTrue(shim.contains("\${DISPLAY:-}"))

        val installer = repoFile(
            "src/main/kotlin/com/altaris/linux/core/control/AltarisCliInstaller.kt"
        ).readText()
        for (alias in listOf(
            "xdg-open", "sensible-browser", "x-www-browser", "www-browser",
            "termux-open", "termux-open-url",
        )) {
            assertTrue("shim alias $alias is not installed", installer.contains("\"$alias\""))
        }
        // Proot + chroot + host prefix all get it.
        assertTrue(installer.contains("writeOpenShim("))
        assertTrue(installer.contains("OPEN_ASSET"))
        // Content-addressed marker: a fixed one meant a guest that already had
        // the helper never received a newer one.
        assertTrue(installer.contains("markerName("))
    }

    @Test
    fun guestSessionsExportBrowserShim() {
        val proot = repoFile("src/main/kotlin/com/altaris/linux/core/terminal/ProotCommandBuilder.kt")
            .readText()
        assertTrue(proot.contains("BROWSER=altaris-open"))
        val host = repoFile("src/main/kotlin/com/altaris/linux/core/terminal/HostCommandBuilder.kt")
            .readText()
        assertTrue(host.contains("\"BROWSER\"") && host.contains("altaris-open"))
        val chroot = repoFile("src/main/assets/scripts/chroot/altaris_chroot.sh").readText()
        // Both the root and the user env list, since `env -i` drops everything.
        assertEquals(2, Regex("BROWSER=altaris-open").findAll(chroot).count())
    }

    @Test
    fun controlServerExposesTheEndpoint() {
        val server = repoFile("src/main/kotlin/com/altaris/linux/core/control/ControlServer.kt").readText()
        assertTrue(server.contains("\"/open-url\""))
        assertTrue(server.contains("UrlOpener.open("))
        val cli = repoFile("src/main/assets/scripts/altaris-cli.sh").readText()
        assertTrue(cli.contains("open|browse|xdg-open)"))
    }

    @Test
    fun theShimFailsLoudlyWhenTheAppIsDown() {
        val shim = repoFile("src/main/assets/scripts/altaris-open.sh").readText()
        // A silent success would look like the browser opened.
        assertTrue(shim.contains("could not reach the Altaris app"))
        assertTrue(shim.contains("exit 1"))
        assertFalse("the shim must not swallow errors", shim.contains("|| true"))
    }

    private fun repoFile(rel: String): File {
        val cwd = File("").absoluteFile
        val candidates = listOf(
            File(cwd, rel),
            File(cwd, "app/$rel"),
            File(cwd.parentFile, rel),
            File(cwd.parentFile, "app/$rel"),
        )
        return candidates.firstOrNull { it.exists() } ?: error("missing $rel (cwd=$cwd)")
    }
}
