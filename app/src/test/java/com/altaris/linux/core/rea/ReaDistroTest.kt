package com.altaris.linux.core.rea

import com.altaris.linux.core.install.DistroInstallProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The hidden REA distro.
 *
 * The requirement is strict: Altaris creates the analysis environment itself
 * and it must never appear in the app. A leak here would show a distro the user
 * cannot manage, delete or understand.
 */
class ReaDistroTest {

    @Test
    fun theReaProfilesExistAndAreHidden() {
        val proot = DistroInstallProfile.reaProfile("proot")
        val chroot = DistroInstallProfile.reaProfile("chroot")
        assertNotNull(proot)
        assertNotNull(chroot)
        assertTrue("REA proot must be hidden", proot!!.hidden)
        assertTrue("REA chroot must be hidden", chroot!!.hidden)
        assertEquals("proot", proot.method)
        assertEquals("chroot", chroot.method)
    }

    /**
     * The bug this locks down: the profile was looked up by comparing a method
     * name against a distro id, so choosing chroot silently installed proot.
     */
    @Test
    fun choosingChrootResolvesTheChrootProfile() {
        val byMethod = DistroInstallProfile.reaProfile("chroot")
        val byId = DistroInstallProfile.reaProfile(DistroInstallProfile.REA_CHROOT_ID)
        assertEquals("chroot", byMethod?.method)
        assertEquals("chroot", byId?.method)
        assertEquals(DistroInstallProfile.REA_CHROOT_ID, byMethod?.distroId)
        // And the proot side still resolves to proot, by either form.
        assertEquals("proot", DistroInstallProfile.reaProfile("proot")?.method)
        assertEquals(
            "proot",
            DistroInstallProfile.reaProfile(DistroInstallProfile.REA_PROOT_ID)?.method,
        )
        assertNull(DistroInstallProfile.reaProfile("debian"))
    }

    @Test
    fun bothMethodsAreResolvableById() {
        assertEquals(
            DistroInstallProfile.REA_PROOT_ID,
            DistroInstallProfile.forId(DistroInstallProfile.REA_PROOT_ID)?.distroId,
        )
        assertEquals(
            DistroInstallProfile.REA_CHROOT_ID,
            DistroInstallProfile.forId(DistroInstallProfile.REA_CHROOT_ID)?.distroId,
        )
    }

    @Test
    fun hiddenIdsAreRecognised() {
        assertTrue(DistroInstallProfile.isReaDistro(DistroInstallProfile.REA_PROOT_ID))
        assertTrue(DistroInstallProfile.isReaDistro(DistroInstallProfile.REA_CHROOT_ID))
        assertFalse(DistroInstallProfile.isReaDistro("debian"))
        assertFalse(DistroInstallProfile.isReaDistro(null))
        assertTrue(DistroInstallProfile.isHidden(DistroInstallProfile.REA_PROOT_ID))
        assertFalse(DistroInstallProfile.isHidden("debian"))
    }

    @Test
    fun reaNeverAppearsInTheUserFacingLists() {
        val installable = DistroInstallProfile.allInstallable()
        assertTrue(
            "the hidden REA distro must not be an install card",
            installable.none { it.hidden || DistroInstallProfile.isReaDistro(it.distroId) },
        )
        val rootfs = DistroInstallProfile.allRootfsProfiles()
        assertTrue(
            "the hidden REA distro must not be offered for rootfs download",
            rootfs.none { it.hidden || DistroInstallProfile.isReaDistro(it.distroId) },
        )
    }

    @Test
    fun theChrootPathIsNotNamedAfterAUserDistro() {
        val chroot = DistroInstallProfile.reaProfile("chroot")!!
        val path = chroot.chrootPath.orEmpty()
        assertTrue(path.isNotBlank())
        // It must not collide with a real distro's chroot.
        assertFalse(path.endsWith("/debian"))
        assertFalse(path.endsWith("/fedora"))
        assertFalse(path.endsWith("/alpine"))
        assertTrue(path.endsWith("/rea"))
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

    @Test
    fun methodsMapToTheirDistroIds() {
        assertEquals(DistroInstallProfile.REA_PROOT_ID, ReaDistro.Method.PROOT.id)
        assertEquals(DistroInstallProfile.REA_CHROOT_ID, ReaDistro.Method.CHROOT.id)
        assertEquals(ReaDistro.Method.PROOT, ReaDistro.Method.fromId(DistroInstallProfile.REA_PROOT_ID))
        assertEquals(null, ReaDistro.Method.fromId("debian"))
    }

    @Test
    fun reaIsNotInstalledOnAFreshApp() {
        val dir = java.nio.file.Files.createTempDirectory("rea-fresh").toFile()
        try {
            // No container on disk: the dashboard must render the "not installed"
            // state.
            val container = File(dir, "usr/var/lib/proot-distro/containers/${DistroInstallProfile.REA_PROOT_ID}/rootfs")
            assertFalse(container.exists())
        } finally {
            dir.deleteRecursively()
        }
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

    @Test
    fun downloadDetailParsingReadsTheRunnerFormat() {
        // The runner formats "12.3 / 780.1 MB · 3.2 MB/s".
        val parsed = ReaInstaller.parseDownload("12.3 / 780.1 MB · 3.2 MB/s")
        assertNotNull(parsed)
        assertEquals(12.3 * 1_048_576.0, parsed!!.first.toDouble(), 1024.0)
        assertEquals(780.1 * 1_048_576.0, parsed.second.toDouble(), 1024.0)
        // A phase label is not a byte counter.
        assertNull(ReaInstaller.parseDownload("Extracting chroot rootfs"))
        assertNull(ReaInstaller.parseDownload("0 / 0 MB"))
    }
}
