package com.altaris.linux.core.rea

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Where REA lives.
 *
 * REA installs into a distro the user already has. The requirement is strict:
 * no private distro of its own, no second base-system download, and the chosen
 * distro is remembered across restarts.
 */
class ReaDistroTest {

    /** Tests run with `app/` as the working directory. */
    private fun readSource(path: String): String {
        for (candidate in listOf(path, "../$path", "../../$path")) {
            val file = File(candidate)
            if (file.isFile) return file.readText()
        }
        throw AssertionError("source not found: $path")
    }

    @Test
    fun everyInstalledToolExistsInTheCatalog() {
        val tools = ReaDistro.ALL_TOOLS
        // APK work, native work, firmware, captures and REA's own prerequisites.
        assertTrue(tools.contains("jadx"))
        assertTrue(tools.contains("apktool"))
        assertTrue(tools.contains("aapt"))
        assertTrue(tools.contains("binutils"))
        assertTrue(tools.contains("gdb"))
        assertTrue(tools.contains("radare2"))
        assertTrue(tools.contains("binwalk"))
        assertTrue(tools.contains("python3"))
        assertTrue(tools.contains("tshark"))
        // REA refuses several families without a procps-compatible ps.
        assertTrue(tools.contains("procps"))
        // Every default must exist in the catalog, or the install silently
        // drops it.
        for (id in tools) {
            assertNotNull("$id is not in ReaCatalog", ReaCatalog.byId(id))
        }
    }

    /**
     * "Installing REA must install all its tools" — so the commercial engines
     * are the only exclusions, and every redistributable tool is included.
     */
    @Test
    fun allRedistributableToolsAreInstalledTogether() {
        val commercial = setOf("ida", "hopper", "jeb")
        val expected = ReaCatalog.DEPENDENCIES
            .map { it.id }
            .filterNot { it in commercial }
        assertEquals(
            "every redistributable tool must be part of the default install",
            expected.toSet(),
            ReaDistro.ALL_TOOLS.toSet(),
        )
    }

    /**
     * REA installs into a distro the user already has, so there is no private
     * REA distro id any more. The old hidden containers are cleaned up instead.
     */
    @Test
    fun reaNoLongerOwnsAHiddenDistro() {
        val source = readSource(
            "app/src/main/kotlin/com/altaris/linux/core/install/DistroInstallProfile.kt"
        )
        assertFalse(
            "the hidden REA profile ids must be gone",
            source.contains("altaris_rea"),
        )
        assertFalse("the hidden REA profile must be gone", source.contains("REA_PROOT"))
        assertFalse("reaProfile must be gone", source.contains("fun reaProfile("))

        // The targets come from the distros that are actually installed.
        val rea = readSource("app/src/main/kotlin/com/altaris/linux/core/rea/ReaDistro.kt")
        assertTrue(
            "targets must come from the installed catalog",
            rea.contains("DistroFsCache.get(app).installed"),
        )
        assertTrue("must expose a Target", rea.contains("data class Target("))
        assertTrue("must remember the choice", rea.contains("KEY_ACTIVE_TARGET"))
        // REA must never download a base system of its own.
        assertFalse(
            "must not create a container",
            rea.contains("ensureContainer("),
        )
    }

    /** The containers REA used to own are deleted once, on upgrade. */
    @Test
    fun legacyReaContainersAreRemovedOnce() {
        val rea = readSource("app/src/main/kotlin/com/altaris/linux/core/rea/ReaDistro.kt")
        assertTrue(
            "must delete the old proot container",
            rea.contains("LEGACY_PROOT_NAME = \"altaris_rea\""),
        )
        assertTrue(
            "must delete the old chroot",
            rea.contains("LEGACY_CHROOT_PATH"),
        )
        // A chroot must be unmounted before it is deleted, or the delete walks
        // into the host through /dev and /proc.
        assertTrue(
            "must use the unmounting script for the chroot",
            rea.contains("remove-chroot.sh"),
        )
        // A failed cleanup must retry, not be remembered as done.
        assertTrue(
            "must only mark it cleared when it really finished",
            rea.contains("if (allGone)"),
        )
    }

    @Test
    fun reaIsNotInstalledOnAFreshApp() {
        // No distro and no toolchain: the dashboard must render the "not
        // installed" state and the picker must be empty, not invent an entry.
        val absent = ReaDistro.State()
        assertFalse(absent.installed)
        assertTrue("no target may be invented", absent.targets.isEmpty())
        assertNull(absent.target)
    }

    /**
     * The "shows installed while it is still downloading" bug.
     *
     * A rootfs directory appears on the first extracted file, so presence is
     * not completion. The state carries an explicit fraction and byte
     * counters, and only a finished install reports 100%.
     */
    /**
     * A container that exists but whose toolchain failed must still open the
     * dashboard, with [ReaDistro.State.ready] false so the UI can offer a
     * repair. Reporting it as "not installed" hid a real chroot install behind
     * the frosted gate forever.
     */
    @Test
    fun aPresentContainerOpensTheDashboardButIsNotReady() {
        val partial = ReaDistro.State(
            installed = true,
            ready = false,
            busy = "Installing JADX (3/18)",
            percent = 41,
            downloadedBytes = 320L * 1024 * 1024,
            totalBytes = 780L * 1024 * 1024,
        )
        assertTrue("container present must open the dashboard", partial.installed)
        assertFalse("toolchain incomplete must not be ready", partial.ready)
        assertTrue(partial.fraction in 0.40f..0.42f)
        assertTrue(partial.downloadText.contains("left"))

        val done = ReaDistro.State(installed = true, ready = true, percent = 100)
        assertTrue(done.installed)
        assertTrue(done.ready)
        assertEquals(1f, done.fraction, 0.001f)
    }

    /** Nothing on disk is the only state that shows the frosted gate. */
    @Test
    fun onlyAnAbsentContainerIsNotInstalled() {
        val absent = ReaDistro.State()
        assertFalse(absent.installed)
        assertFalse(absent.ready)
    }


}
