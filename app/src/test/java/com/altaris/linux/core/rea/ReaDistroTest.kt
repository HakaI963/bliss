package com.altaris.linux.core.rea

import com.altaris.linux.core.install.DistroInstallProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
    fun defaultToolsCoverTheAnalysisFamiliesReaAdvertises() {
        val tools = ReaDistro.DEFAULT_TOOLS
        // APK work, native work, firmware, captures and REA's own prerequisites.
        assertTrue(tools.contains("jadx"))
        assertTrue(tools.contains("apktool"))
        assertTrue(tools.contains("binutils"))
        assertTrue(tools.contains("gdb"))
        assertTrue(tools.contains("radare2"))
        assertTrue(tools.contains("binwalk"))
        assertTrue(tools.contains("python3"))
        // REA refuses several families without a procps-compatible ps.
        assertTrue(tools.contains("procps"))
        // Every default must exist in the catalog, or the install silently
        // drops it.
        for (id in tools) {
            assertNotNull("$id is not in ReaCatalog", ReaCatalog.byId(id))
        }
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
            // No container on disk: the tab must render the "not installed" state.
            val container = File(dir, "usr/var/lib/proot-distro/containers/${DistroInstallProfile.REA_PROOT_ID}/rootfs")
            assertFalse(container.exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}
