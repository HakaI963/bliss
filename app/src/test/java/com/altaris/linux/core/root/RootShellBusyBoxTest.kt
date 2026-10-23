package com.altaris.linux.core.root

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RootShellBusyBoxTest {

    @After
    fun tearDown() {
        RootShell.clearBusyBoxCache()
        RootShell.clearSuCache()
    }

    @Test
    fun shellRootCommand_doesNotProbeSu() {
        RootShell.clearSuCache()
        val cmd = RootShell.shellRootCommand("echo hi")
        assertEquals("/system/bin/su -c 'echo hi'", cmd)
    }

    @Test
    fun shellRootCommand_escapesSingleQuotes() {
        RootShell.clearSuCache()
        val cmd = RootShell.shellRootCommand("echo it's")
        assertEquals("/system/bin/su -c 'echo it'\\''s'", cmd)
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
