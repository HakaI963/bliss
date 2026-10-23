package com.altaris.linux.core.root

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RootShellBusyBoxTest {

    @After
    fun tearDown() {
        RootShell.clearBusyBoxCache()
        RootShell.clearSuCache()
    }

    /**
     * Cold cache must not hard-code one su path: KernelSU, Magisk and AOSP
     * disagree, and a wrong guess makes the pty exit while the tab is still
     * reported as opened — the "root granted, terminal never opens" bug.
     */
    @Test
    fun shellRootCommand_resolvesSuAtRuntimeWhenCacheIsCold() {
        RootShell.clearSuCache()
        val cmd = RootShell.shellRootCommand("echo hi")
        assertTrue("walks a candidate list", cmd.contains("for s in "))
        assertTrue("covers KernelSU", cmd.contains("/debug_ramdisk/su"))
        assertTrue("covers Magisk/AOSP", cmd.contains("/system/bin/su"))
        assertTrue("execs the resolved su", cmd.contains("exec \"\$s\" -c 'echo hi'"))
        assertTrue("fails loudly when nothing works", cmd.contains("exit 127"))
    }

    @Test
    fun shellRootCommand_escapesSingleQuotes() {
        RootShell.clearSuCache()
        val cmd = RootShell.shellRootCommand("echo it's")
        assertTrue(cmd.contains("echo it'\\''s"))
    }

    /** A warm cache keeps the cheap single-path form (no loop, no extra forks). */
    @Test
    fun shellRootCommand_usesCachedInvocation() {
        RootShell.seedSuInvocationForTest(listOf("/debug_ramdisk/su", "-c"))
        assertEquals("/debug_ramdisk/su -c 'echo hi'", RootShell.shellRootCommand("echo hi"))
    }

    @Test
    fun seed_then_cached_returns_path() {
        RootShell.seedBusyBoxForTest("/data/adb/ksu/bin/busybox")
        assertEquals("/data/adb/ksu/bin/busybox", RootShell.cachedBusyBox())
    }

    @Test
    fun clear_cache_returns_null() {
        RootShell.seedBusyBoxForTest("/data/adb/ksu/bin/busybox")
        RootShell.clearBusyBoxCache()
        assertNull(RootShell.cachedBusyBox())
    }
}
