package com.ivarna.fluxlinux.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Catalog tests: Termux Native card dropped; installable cards are
 * Debian + Alpine (proot + rooted each).
 */
class DistroRepositoryTest {

    @Test
    fun noTermuxNativeCard() {
        assertFalse(DistroRepository.supportedDistros.any { it.id == "termux" })
    }

    @Test
    fun installableCards_includeDebianAndAlpine() {
        val available = DistroRepository.supportedDistros.filter { !it.comingSoon }.map { it.id }
        assertTrue(available.contains("debian"))
        assertTrue(available.contains("debian13_chroot"))
        assertTrue(available.contains("alpine"))
        assertTrue(available.contains("alpine_chroot"))
        assertTrue(available.contains("fedora"))
        assertTrue(available.contains("fedora_chroot"))
        assertTrue(available.contains("void"))
        assertTrue(available.contains("void_chroot"))
        assertTrue(available.contains("opensuse"))
        assertTrue(available.contains("opensuse_chroot"))
        assertTrue(available.contains("deepin"))
        assertTrue(available.contains("deepin_chroot"))
        assertTrue(available.contains("chimera"))
        assertTrue(available.contains("chimera_chroot"))
        assertTrue(available.contains("manjaro"))
        assertTrue(available.contains("manjaro_chroot"))
        assertTrue(available.contains("ubuntu"))
        assertTrue(available.contains("ubuntu_chroot"))
        assertTrue(available.contains("kali"))
        assertTrue(available.contains("kali_chroot"))
        assertTrue(available.contains("parrot"))
        assertTrue(available.contains("parrot_chroot"))
        assertTrue(available.contains("archlinux"))
        assertTrue(available.contains("archlinux_chroot"))
        assertTrue(available.contains("rocky"))
        assertTrue(available.contains("centos_stream"))
        assertEquals(26, available.size)
    }

    @Test
    fun newPairs_areProotOnly_and_chrootOnly() {
        listOf("deepin", "chimera", "manjaro", "ubuntu", "kali", "parrot", "archlinux").forEach { id ->
            val proot = DistroRepository.supportedDistros.first { it.id == id }
            assertFalse(proot.comingSoon)
            assertTrue(proot.prootSupported)
            assertFalse(proot.chrootSupported)

            val chroot = DistroRepository.supportedDistros.first { it.id == "${id}_chroot" }
            assertFalse(chroot.comingSoon)
            assertFalse(chroot.prootSupported)
            assertTrue(chroot.chrootSupported)
        }
    }

    @Test
    fun newCards_haveComponents() {
        listOf(
            "deepin", "deepin_chroot",
            "chimera", "chimera_chroot",
            "manjaro", "manjaro_chroot",
            "ubuntu", "ubuntu_chroot",
            "kali", "kali_chroot",
            "parrot", "parrot_chroot",
            "archlinux", "archlinux_chroot",
            "rocky", "centos_stream"
        ).forEach { id ->
            val distro = DistroRepository.supportedDistros.first { it.id == id }
            val ids = distro.components.map { it.id }
            assertTrue(ids.contains("xfce4_desktop"))
            assertTrue(ids.contains("hw_accel"))
            assertTrue(ids.contains("customization"))
            assertEquals(3, ids.size)
        }
    }

    @Test
    fun newCards_doNotReferenceDebianModuleScripts() {
        val scripts = DistroRepository.supportedDistros
            .filter { it.id in setOf(
                "deepin", "chimera", "manjaro",
                "ubuntu", "kali", "parrot", "archlinux",
                "rocky", "centos_stream"
            ) }
            .flatMap { it.components }
            .map { it.scriptName }
        assertFalse(scripts.any { it.contains("debian/") })
        assertFalse(scripts.any { it.startsWith("debian") })
    }

    @Test
    fun alpineIsProotOnly() {
        val alpine = DistroRepository.supportedDistros.first { it.id == "alpine" }
        assertTrue(alpine.prootSupported)
        assertFalse(alpine.chrootSupported)
    }

    @Test
    fun alpineChrootIsChrootOnly() {
        val chroot = DistroRepository.supportedDistros.first { it.id == "alpine_chroot" }
        assertFalse(chroot.prootSupported)
        assertTrue(chroot.chrootSupported)
    }

    @Test
    fun noComponentReferencesTermuxScripts() {
        val scripts = DistroRepository.supportedDistros
            .flatMap { it.components }
            .map { it.scriptName }
        assertFalse(scripts.any { it.startsWith("termux/") })
    }

    @Test
    fun debianIsProotOnly() {
        val debian = DistroRepository.supportedDistros.first { it.id == "debian" }
        assertTrue(debian.prootSupported)
        assertFalse(debian.chrootSupported)
    }

    @Test
    fun debian13ChrootIsChrootOnly() {
        val chroot = DistroRepository.supportedDistros.first { it.id == "debian13_chroot" }
        assertFalse(chroot.prootSupported)
        assertTrue(chroot.chrootSupported)
    }

    @Test
    fun alpineAndDebian_haveHwAccel() {
        listOf("alpine", "alpine_chroot", "debian", "debian13_chroot").forEach { id ->
            val distro = DistroRepository.supportedDistros.first { it.id == id }
            val ids = distro.components.map { it.id }
            assertTrue("$id missing hw_accel", ids.contains("hw_accel"))
            val hw = distro.components.first { it.id == "hw_accel" }
            assertTrue(
                "$id hw_accel should use guest SSOT",
                hw.scriptName.contains("setup_hw_accel_guest")
            )
        }
        val alpine = DistroRepository.supportedDistros.first { it.id == "alpine" }
        assertEquals(3, alpine.components.size)
        val alpineChroot = DistroRepository.supportedDistros.first { it.id == "alpine_chroot" }
        assertEquals(3, alpineChroot.components.size)
    }

    @Test
    fun installableVariant_mapsFamilyAcrossMethods() {
        assertEquals(
            "debian13_chroot",
            DistroRepository.installableVariant("debian", chroot = true)?.id
        )
        assertEquals(
            "debian",
            DistroRepository.installableVariant("debian13_chroot", chroot = false)?.id
        )
        assertEquals(
            "alpine_chroot",
            DistroRepository.installableVariant("alpine", chroot = true)?.id
        )
        assertEquals(
            "fedora",
            DistroRepository.installableVariant("fedora_chroot", chroot = false)?.id
        )
        assertEquals(
            "archlinux_chroot",
            DistroRepository.installableVariant("archlinux", chroot = true)?.id
        )
    }

    @Test
    fun installableCards_splitBetweenProotAndChroot() {
        val available = DistroRepository.supportedDistros.filter { !it.comingSoon }
        // 14 proot cards (12 proot/chroot pairs + Rocky / CentOS Stream
        // proot-only) against 12 chroot cards.
        assertEquals(14, available.count { it.prootSupported })
        assertEquals(12, available.count { it.chrootSupported })
        assertTrue(available.none { it.prootSupported && it.chrootSupported })
    }

    @Test
    fun distroPageSort_isCaseInsensitiveAndComingSoonLast() {
        val sorted = DistroRepository.sortForDistroPage(DistroRepository.supportedDistros)
        val available = sorted.filter { !it.comingSoon }.map { it.name }
        assertEquals("Alpine", available.first())
        assertTrue(available.indexOf("openSUSE") < available.indexOf("Parrot"))
        assertTrue(available.indexOf("openSUSE") < available.indexOf("Void"))
        // Every catalog card now ships — the "Coming Soon" section is empty.
        assertTrue(sorted.none { it.comingSoon })
        val prootSorted = DistroRepository.sortForDistroPage(
            DistroRepository.supportedDistros.filter { it.prootSupported }
        )
        assertTrue(prootSorted.any { it.id == "rocky" && !it.comingSoon })
        assertTrue(prootSorted.any { it.id == "centos_stream" && !it.comingSoon })
    }

    @Test
    fun removedComingSoonCards_areGone() {
        val ids = DistroRepository.supportedDistros.map { it.id }.toSet()
        listOf("adelie", "artix", "backbox", "gentoo", "openkylin").forEach { removed ->
            assertFalse("$removed should be removed", ids.contains(removed))
        }
        assertTrue(DistroRepository.supportedDistros.none { it.comingSoon })
    }

    @Test
    fun rockyAndCentosStream_areProotOnlyRpmCards() {
        listOf("rocky" to "Rocky Linux", "centos_stream" to "CentOS Stream").forEach { (id, name) ->
            val distro = DistroRepository.supportedDistros.first { it.id == id }
            assertEquals(name, distro.name)
            assertFalse(distro.comingSoon)
            assertTrue(distro.prootSupported)
            assertFalse(distro.chrootSupported)
            val componentIds = distro.components.map { it.id }
            assertEquals(listOf("xfce4_desktop", "hw_accel", "customization"), componentIds)
            assertTrue(distro.components.none { it.id.contains("kde") })
            assertEquals(
                "rpm/common/setup/setup_rpm_family.sh",
                distro.components.first { it.id == "xfce4_desktop" }.scriptName
            )
            val hw = distro.components.first { it.id == "hw_accel" }
            assertTrue(hw.isMandatory)
            assertEquals("common/setup/setup_hw_accel_guest.sh", hw.scriptName)
        }
    }
}

