package com.altaris.linux.core.rea

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Launcher resolution in the archive installer.
 *
 * "Failed radare2 — launcher bin/r2 is missing" was the installer assuming a
 * fixed top-level `bin/` path. Release tarballs nest arbitrarily:
 *
 *   jadx     jadx-1.5.6/bin/jadx
 *   radare2  data/data/org.radare.radare2installer/radare2/bin/radare2
 *   rizin    data/data/org.rizinorg.rizininstaller/bin/rizin
 *
 * Flattening cannot fix that, because every level of the radare2 tree is a
 * single directory literally named `data`, so the move collides. The installer
 * therefore searches for the launcher by basename.
 */
class ReaArchiveLauncherTest {

    @Test
    fun theInstallerFindsTheLauncherAnywhereInTheTree() {
        val script = readAsset("scripts/rea/install-archive.sh")
        assertTrue(
            "must search for the launcher by base name",
            script.contains("LAUNCH_BASE=\$(basename \"\$LAUNCHER\")"),
        )
        assertTrue(
            "must search the extracted tree",
            script.contains("find \"\$DIR\" -type f -name \"\$LAUNCH_BASE\""),
        )
        assertTrue(
            "must also accept a symlink launcher",
            script.contains("find \"\$DIR\" -type l -name \"\$LAUNCH_BASE\""),
        )
        assertTrue(
            "must prefer a bin/ candidate",
            script.contains("*/bin/*) TARGET=\"\$candidate\"; break ;;"),
        )
        // A direct hit is still tried first (jadx keeps a top-level bin/), but
        // it must fall back to the search rather than failing outright.
        assertTrue(
            "a direct hit is tried first",
            script.contains("if [ -f \"\$DIR/\$LAUNCHER\" ]; then"),
        )
        assertTrue(
            "and the search runs when it misses",
            script.contains("else\n    # Prefer a real file over a symlink"),
        )
    }

    /**
     * The flattening loops are gone. They could not work (name collisions at
     * every level) and the unbounded one could spin forever.
     */
    @Test
    fun theBrokenFlatteningLoopsAreGone() {
        val script = readAsset("scripts/rea/install-archive.sh")
        assertFalse(
            "the unbounded flatten loop must not exist",
            script.contains("while :; do"),
        )
        // The single-level flatten for zips collided on "data" -> "data".
        val zipBranch = script.substringAfter("unzip -oq")
            .substringBefore("*.tar.gz")
        assertFalse(
            "the zip flatten must not move directories over themselves",
            zipBranch.contains("mv -f \"\$item\" \"\$DIR/\""),
        )
    }

    /** radare2's r2 is a symlink; the real executable is radare2. */
    @Test
    fun radare2LaunchesTheRealBinary() {
        val dep = ReaCatalog.byId("radare2")
        assertNotNull(dep)
        assertNotNull("radare2 must have an archive", dep!!.archive)
        assertTrue(
            "must launch bin/radare2, not the r2 symlink",
            dep.archive!!.launcher == "bin/radare2",
        )
        // Both names must be probed, since either may be on PATH.
        assertTrue(dep.probes.contains("radare2"))
        assertTrue(dep.probes.contains("r2"))
    }

    @Test
    fun rizinLaunchesFromItsBinDirectory() {
        val dep = ReaCatalog.byId("rizin")
        assertNotNull(dep)
        assertNotNull(dep!!.archive)
        assertTrue(dep.archive!!.launcher == "bin/rizin")
        assertTrue(dep.probes.contains("rizin"))
    }

    /**
     * A launcher that cannot be found must still be a clear failure, not a
     * silent success.
     */
    @Test
    fun aMissingLauncherStillFailsClearly() {
        val script = readAsset("scripts/rea/install-archive.sh")
        assertTrue(
            "must report the launcher as missing",
            script.contains("launcher \$LAUNCHER is missing from \$FILE"),
        )
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
}
