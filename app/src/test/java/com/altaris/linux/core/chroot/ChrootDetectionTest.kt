package com.altaris.linux.core.chroot

import com.altaris.linux.core.root.ChrootPaths
import com.altaris.linux.core.terminal.FakeContext
import com.altaris.linux.core.terminal.TerminalLauncher
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ChrootDetectionTest {
    @get:Rule val tmp = TemporaryFolder()

    @After
    fun tearDown() {
        ChrootDetection.invalidate()
    }

    @Test
    fun emptyDir_isNotInstalled() {
        val dir = tmp.newFolder("empty-chroot")
        val snap = ChrootDetection.probe(forceRoot = false, path = dir.absolutePath)
        assertFalse(snap.installed)
        assertTrue(snap.dirExists)
    }

    @Test
    fun marker_isInstalled() {
        val dir = tmp.newFolder("marked")
        File(dir, ".altaris_configured").writeText("ok")
        val snap = ChrootDetection.probe(forceRoot = false, path = dir.absolutePath)
        assertTrue(snap.installed)
        assertTrue(ChrootDetection.isInstalled(dir.absolutePath))
    }

    @Test
    fun staleCache_trueUntilMarkUninstalled() {
        val path = ChrootPaths.FEDORA_CHROOT_PATH
        ChrootDetection.putCacheForTest(path, true)
        assertTrue(ChrootDetection.isInstalled(path))
        ChrootDetection.markUninstalled(path)
        assertFalse(ChrootDetection.isInstalled(path))
        val ctx = FakeContext(tmp.newFolder("files"), tmp.newFolder("jni").absolutePath)
        assertFalse(TerminalLauncher.isDistroInstalledOnFs(ctx, "fedora_chroot"))
    }

    @Test
    fun proot_filesystem_false_when_container_missing() {
        val ctx = FakeContext(tmp.newFolder("files"), tmp.newFolder("jni").absolutePath)
        assertFalse(TerminalLauncher.isDistroInstalledOnFs(ctx, "fedora"))
        assertFalse(TerminalLauncher.isProotInstalled(ctx, "fedora"))
    }
}
