package com.altaris.linux.core.rea

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Removing the REA environment must never touch the host.
 *
 * The device froze and needed two reboots because `rm -rf <chroot>` walked
 * through the chroot's bind mounts (/dev, /sys, /proc, /dev/pts, /dev/shm,
 * /tmp, /mnt/host-tmp, /sdcard) and deleted the live system through them.
 *
 * I verified while fixing this that neither busybox `rm -rf` nor
 * `find -xdev` protects against that: busybox rm has no -x, and -xdev does not
 * prune a bind mount because it is the same filesystem. So the only safe rule is
 * to unmount, then *verify*, then refuse to delete if anything is still mounted.
 * These tests pin that rule and the ordering it depends on.
 */
class ReaRemoveSafetyTest {

    /** The script must verify before deleting, and refuse when mounts remain. */
    @Test
    fun theRemovalRefusesWhileAnythingIsStillMounted() {
        val script = readAsset("scripts/chroot/remove-chroot.sh")
        assertTrue("must have a mount check", script.contains("still_mounted()"))
        assertTrue(
            "must refuse rather than delete through a mount",
            script.contains("REFUSING to delete"),
        )
        assertTrue(
            "must fail the run so the caller knows nothing was removed",
            script.contains("fail \"unmount failed; nothing was deleted\""),
        )
        // The guard must run BEFORE the delete.
        val guard = script.indexOf("REFUSING to delete")
        val delete = script.indexOf("deleting \$GUEST")
        assertTrue("the guard must precede the delete", guard in 0..delete)
    }

    /**
     * Every mount point the setup script creates must be unmounted here, or the
     * guard will always refuse and removal can never succeed.
     */
    @Test
    fun everyMountPointTheSetupCreatesIsUnmounted() {
        val setup = readAsset("scripts/chroot/setup_guest_chroot.sh")
        val remove = readAsset("scripts/chroot/remove-chroot.sh")
        // Mount points present in the setup script.
        for (rel in listOf(
            "sdcard", "mnt/host-tmp", "dev/shm", "dev/pts", "proc", "sys", "dev", "tmp",
        )) {
            assertTrue(
                "setup must mount $rel (fixture check)",
                setup.contains("\$GUESTPATH/$rel"),
            )
            assertTrue(
                "removal must unmount $rel",
                remove.contains(rel),
            )
        }
    }

    /** Deepest-first ordering matters: a parent cannot be released while a child is busy. */
    @Test
    fun unmountingIsDeepestFirst() {
        val script = readAsset("scripts/chroot/remove-chroot.sh")
        val block = script.substringAfter("for rel in")
            .substringBefore("done")
        val devPts = block.indexOf("dev/pts")
        val dev = block.indexOf("\n  dev\n")
        assertTrue("dev/pts must be listed", devPts > 0)
        assertTrue("plain dev must come after dev/pts", dev > devPts)
    }

    /**
     * The caller must stop everything that could hold a mount open before it
     * tries to unmount, or the unmount always fails.
     */
    @Test
    fun theCallerStopsTheBridgeAndManagerFirst() {
        val distro = readSource(
            "app/src/main/kotlin/com/altaris/linux/core/rea/ReaDistro.kt"
        )
        val uninstall = distro.substringAfter("suspend fun uninstallBlocking(")
            .substringBefore("private fun appendLog(")
        assertTrue("must stop the MCP bridge", uninstall.contains("ReaBridge.stop()"))
        assertTrue("must stop the REA manager", uninstall.contains("ReaManager.stop()"))
        // Both stops must happen before the container is removed.
        val bridgeStop = uninstall.indexOf("ReaBridge.stop()")
        val remove = uninstall.indexOf("ReaInstaller.removeContainer")
        assertTrue("the bridge must stop before removal", bridgeStop in 0..remove)
    }

    /** The chroot path must go through the script, never a bare rm -rf. */
    @Test
    fun chrootRemovalNeverRunsABareRmRf() {
        val installer = readSource(
            "app/src/main/kotlin/com/altaris/linux/core/rea/ReaInstaller.kt"
        )
        // Anchor on removeContainer: there is another `method == "chroot"` check
        // in ensureContainer, and searching from the first one read the wrong
        // function entirely.
        val removeFn = installer.substringAfter("fun removeContainer(")
            .substringBefore("/** Unmount, then delete")
        assertTrue(
            "chroot removal must call the script",
            removeFn.contains("removeChroot(context, rootfs.absolutePath)"),
        )
        assertFalse(
            "chroot removal must not run rm -rf",
            removeFn.substringAfter("if (profile.method == \"chroot\") {")
                .substringBefore("} else {")
                .contains("rm -rf"),
        )
        // The proot branch is allowed a recursive delete: no mounts there.
        assertTrue(
            "proot keeps the recursive delete",
            removeFn.contains("deleteRecursively()"),
        )
        // And the script itself must never issue a blind recursive delete
        // without the mount guard having passed first.
        val script = readAsset("scripts/chroot/remove-chroot.sh")
        val guard = script.indexOf("REFUSING to delete")
        val del = script.indexOf('deleting \$GUEST')
        assertTrue("the guard must come first", guard in 0..del)
    }

    /**
     * A failed removal must not look like a success. The old code reset the state
     * unconditionally, so the distro reappeared on the next refresh.
     */
    @Test
    fun aFailedRemovalIsReported() {
        val distro = readSource(
            "app/src/main/kotlin/com/altaris/linux/core/rea/ReaDistro.kt"
        )
        val uninstall = distro.substringAfter("suspend fun uninstallBlocking(")
            .substringBefore("private fun appendLog(")
        assertTrue("must track whether it was removed", uninstall.contains("var removed"))
        assertTrue(
            "must report a failure",
            uninstall.contains("Could not remove the analysis environment"),
        )
        assertTrue(
            "must re-read the real state after a failure",
            uninstall.contains("refresh(app)"),
        )
    }

    /** Removal is destructive, so the UI must confirm first. */
    @Test
    fun theUiConfirmsBeforeRemoving() {
        val screen = readSource(
            "app/src/main/kotlin/com/altaris/linux/ui/screens/ToolsScreen.kt"
        )
        assertTrue("must ask for confirmation", screen.contains("confirmRemove"))
        assertTrue(
            "the dialog must name what is lost",
            screen.contains("Remove the analysis environment?"),
        )
        assertTrue(
            "the dialog must say it cannot be undone",
            screen.contains("cannot be undone"),
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

    private fun readSource(path: String): String {
        for (candidate in listOf(path, "../$path", "../../$path")) {
            val file = java.io.File(candidate)
            if (file.isFile) return file.readText()
        }
        throw AssertionError("source not found: $path")
    }
}
