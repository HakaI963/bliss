package com.altaris.linux.core.rea

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract for the REA tool catalog.
 *
 * The bug these tests lock down: a tool whose only package name did not exist
 * in the distro repos failed silently, and a wrong/empty package manager made
 * *every* tool look "not packaged". The catalog now has to describe either a
 * real install plan per manager or say nothing at all.
 */
class ReaCatalogTest {

    @Test
    fun idsAreUniqueAndStable() {
        val ids = ReaCatalog.DEPENDENCIES.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.contains("java"))
        assertTrue(ids.contains("ghidra"))
        assertNotNull(ReaCatalog.byId("jadx"))
    }

    @Test
    fun everyInstallableToolKnowsHowToProveItIsInstalled() {
        for (dep in ReaCatalog.installable()) {
            if (dep.kind == "ghidra") continue
            assertTrue(
                "${dep.id} needs at least one probe command",
                ReaCatalog.probesFor(dep).isNotEmpty(),
            )
            for (probe in ReaCatalog.probesFor(dep)) {
                assertFalse("probe must be a bare command: $probe", probe.contains(' '))
                assertFalse("probe must not be a path: $probe", probe.contains('/'))
            }
        }
    }

    @Test
    fun variantsOnlyNameSupportedManagers() {
        for (dep in ReaCatalog.DEPENDENCIES) {
            for (manager in dep.variants.keys) {
                assertTrue(
                    "${dep.id} lists unsupported manager $manager",
                    ReaCatalog.MANAGERS.contains(manager),
                )
            }
        }
    }

    @Test
    fun batchContentsAreNotEmpty() {
        for (dep in ReaCatalog.DEPENDENCIES) {
            for ((manager, batches) in dep.variants) {
                for (batch in batches) {
                    assertTrue(
                        "${dep.id}/$manager has an empty package batch",
                        batch.isNotEmpty(),
                    )
                    for (pkg in batch) {
                        assertFalse(
                            "${dep.id}/$manager has a blank package name",
                            pkg.isBlank(),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun javaPrefersTwentyOneThenFallsBack() {
        val java = ReaCatalog.byId("java")!!
        val apt = ReaCatalog.variantsFor(java, "apt")
        assertTrue("java needs at least two apt attempts", apt.size >= 2)
        assertTrue(apt.first().any { it.contains("21") })
        assertTrue(apt.drop(1).any { batch -> batch.any { it.contains("17") } })
    }

    @Test
    fun fedoraNamesMatchWhatFedoraActuallyShips() {
        // Checked against Fedora 44's aarch64 primary metadata. The previous
        // catalog asked dnf for `p7zip`, `p7zip-plugins` and
        // `java-21-openjdk-devel`; none of those exist, so every one of them
        // reported "dnf could not provide ..." on a Fedora install.
        val sevenZip = ReaCatalog.byId("p7zip")!!
        val dnf = ReaCatalog.variantsFor(sevenZip, "dnf")
        assertTrue("dnf must try 7zip first", dnf.first().contains("7zip"))

        val java = ReaCatalog.byId("java")!!
        val javaDnf = ReaCatalog.variantsFor(java, "dnf")
        assertTrue(
            "Fedora only ships java-25-openjdk-devel / java-latest-openjdk-devel",
            javaDnf.first().contains("java-25-openjdk-devel"),
        )
        assertTrue(
            "the old 17/21 names are kept only as later fallbacks",
            javaDnf.drop(1).any { b -> b.any { it.contains("21") || it.contains("17") } },
        )
    }

    @Test
    fun toolsFedoraDoesNotPackageInstallFromTheirRelease() {
        for (id in listOf("jadx", "apktool")) {
            val dep = ReaCatalog.byId(id)!!
            assertTrue("$id has no dnf package on Fedora", ReaCatalog.variantsFor(dep, "dnf").isEmpty())
            assertNotNull("$id needs an archive fallback", dep.archive)
            assertTrue(
                "$id must still be installable on dnf",
                ReaCatalog.packagedFor(dep, "dnf"),
            )
            assertTrue(
                "$id on dnf must be routed to the archive installer",
                ReaCatalog.usesArchive(dep, "dnf"),
            )
            // And the package path still wins where the distro does ship it.
            assertFalse(ReaCatalog.usesArchive(dep, "apt"))
        }
    }

    @Test
    fun archiveToolsPointAtAPinnedReleaseAsset() {
        for (dep in ReaCatalog.DEPENDENCIES) {
            val archive = dep.archive ?: continue
            assertTrue(
                "${dep.id} must pin a release asset, not a /latest redirect",
                archive.url.contains("/releases/download/"),
            )
            assertTrue("${dep.id} archive needs a file name", archive.fileName.isNotBlank())
            assertTrue("${dep.id} archive needs an install dir", archive.dir.isNotBlank())
            // A launcher path or a generated wrapper, never neither.
            assertTrue(
                "${dep.id} needs a launcher or a wrapper",
                archive.launcher.isNotBlank() || archive.wrapper.isNotBlank(),
            )
            assertTrue("${dep.id} needs a size floor", archive.minBytes > 0L)
            assertTrue(
                "${dep.id} probes must be declared so the installer can verify",
                ReaCatalog.probesFor(dep).isNotEmpty(),
            )
        }
    }

    @Test
    fun procpsIsListedBecauseReaRequiresIt() {
        // REA's own docs make a procps-compatible `ps` a hard prerequisite for
        // Ghidra, JADX, firmware and JavaScript analysis.
        val procps = ReaCatalog.byId("procps")
        assertNotNull("REA needs procps on PATH", procps)
        assertTrue(ReaCatalog.probesFor(procps!!).contains("pgrep"))
        assertTrue(ReaCatalog.packagedFor(procps, "dnf"))
    }

    @Test
    fun pythonShipsPipInTheSameBatch() {
        val python = ReaCatalog.byId("python3")!!
        val apt = ReaCatalog.variantsFor(python, "apt")
        assertTrue(apt.isNotEmpty())
        assertTrue(
            "python3 must install pip in the same transaction",
            apt.first().any { it.startsWith("python3-pip") },
        )
    }

    @Test
    fun knownUnsupportedCombinationsSaySoInsteadOfFailing() {
        val jadx = ReaCatalog.byId("jadx")!!
        // Arch does ship jadx (extra), so pacman is the plain path there.
        assertTrue(ReaCatalog.packagedFor(jadx, "pacman"))
        assertFalse(ReaCatalog.usesArchive(jadx, "pacman"))
        assertTrue(ReaCatalog.packagedFor(jadx, "apt"))

        // Ghidra is not a package: it installs from the release zip.
        val ghidra = ReaCatalog.byId("ghidra")!!
        assertEquals("ghidra", ghidra.kind)
        assertTrue(ReaCatalog.packagedFor(ghidra, "nothing-at-all"))
    }

    @Test
    fun unknownManagerHasNoPlan() {
        val binutils = ReaCatalog.byId("binutils")!!
        assertTrue(ReaCatalog.variantsFor(binutils, "").isEmpty())
        assertFalse(ReaCatalog.packagedFor(binutils, ""))
    }

    @Test
    fun licenseToolsAreNotInstallable() {
        val installable = ReaCatalog.installable().map { it.id }
        assertFalse(installable.contains("ida"))
        assertFalse(installable.contains("hopper"))
        assertFalse(installable.contains("jeb"))
        assertTrue(installable.contains("gdb"))
        assertTrue(installable.contains("radare2"))
    }

    @Test
    fun everyPackageToolIsSupportedSomewhere() {
        for (dep in ReaCatalog.DEPENDENCIES) {
            if (dep.kind != "package") continue
            // Either a package manager ships it, or the release archive does.
            val supported = dep.variants.values.any { it.isNotEmpty() } || dep.archive != null
            assertTrue("${dep.id} has no install plan on any manager", supported)
        }
    }
}
