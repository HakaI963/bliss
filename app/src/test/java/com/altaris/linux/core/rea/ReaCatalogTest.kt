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
        // Arch's jadx lives in the AUR, so pacman cannot install it.
        assertFalse(ReaCatalog.packagedFor(jadx, "pacman"))
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
            val supported = dep.variants.values.any { it.isNotEmpty() }
            assertTrue("${dep.id} has no install plan on any manager", supported)
        }
    }
}
