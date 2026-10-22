package com.altaris.linux.core.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Dispatch + user mapping tests for LinuxCommandBuilder (explicit method param). */
class LinuxCommandBuilderTest {

    private fun ctx(): FakeContext {
        val dir = File(System.getProperty("java.io.tmpdir"), "altaris_lcb_${System.nanoTime()}")
        dir.mkdirs()
        return FakeContext(dir, "$dir/jni")
    }

    private fun withSeededSu(block: () -> Unit) {
        com.altaris.linux.core.root.RootShell.seedSuInvocationForTest(listOf("/system/bin/sh", "-c"))
        try {
            block()
        } finally {
            com.altaris.linux.core.root.RootShell.clearSuCache()
        }
    }

    @Test
    fun sessionUserForType_mapsShells() {
        assertEquals("altaris", LinuxCommandBuilder.sessionUserForType("shell"))
        assertEquals("root", LinuxCommandBuilder.sessionUserForType("shell-root"))
        // Component installs need apt/dpkg → root (not interactive altaris)
        assertEquals("root", LinuxCommandBuilder.sessionUserForType("component"))
    }

    @Test
    fun prootMethod_usesLibbashExec() {
        val (args, _) = LinuxCommandBuilder.build(ctx(), "exec zsh", method = "proot")
        assertTrue(args[0].endsWith("libbash.so"))
        assertTrue(args[2].contains("login debian"))
    }

    @Test
    fun chrootMethod_usesSystemShExec() {
        withSeededSu {
            val (args, _) = LinuxCommandBuilder.build(ctx(), "exec zsh", method = "chroot")
            assertEquals("/system/bin/sh", args[0])
            assertEquals("-c", args[1])
            assertTrue(args[2].contains("altaris_chroot.sh"))
        }
    }

    @Test
    fun explicitMethod_overridesAmbientDefault() {
        withSeededSu {
            // Product paths must pass method explicitly; ambient default is only a UI convenience.
            val (prootArgs, _) = LinuxCommandBuilder.build(ctx(), "exec zsh", method = "proot")
            val (chrootArgs, _) = LinuxCommandBuilder.build(ctx(), "exec zsh", method = "chroot")
            assertTrue(prootArgs[0].endsWith("libbash.so"))
            assertEquals("/system/bin/sh", chrootArgs[0])
        }
    }

    @Test
    fun chrootRoot_noLoginShell_keepsLegacyBash() {
        withSeededSu {
            val (args, _) = LinuxCommandBuilder.build(
                ctx(), "exec zsh", user = "root", method = "chroot"
            )
            assertTrue(args[2].contains("login --user root --shell bash"))
        }
    }

    @Test
    fun chrootRoot_zshPref_flipsToZsh() {
        withSeededSu {
            val (args, _) = LinuxCommandBuilder.build(
                ctx(), "exec zsh", user = "root", method = "chroot",
                loginShell = GuestLoginShell.ZSH
            )
            assertTrue(args[2].contains("login --user root --shell zsh"))
        }
    }

    @Test
    fun chrootRoot_bashPref_staysBash() {
        withSeededSu {
            val (args, _) = LinuxCommandBuilder.build(
                ctx(), "exec zsh", user = "root", method = "chroot",
                loginShell = GuestLoginShell.BASH
            )
            assertTrue(args[2].contains("login --user root --shell bash"))
        }
    }

    @Test
    fun chrootAltaris_noLoginShell_keepsLegacyZsh() {
        withSeededSu {
            val (args, _) = LinuxCommandBuilder.build(
                ctx(), "exec zsh", user = "altaris", method = "chroot"
            )
            assertTrue(args[2].contains("login --user altaris --shell zsh"))
        }
    }

    @Test
    fun chrootAltaris_bashPref_usesBash() {
        withSeededSu {
            val (args, _) = LinuxCommandBuilder.build(
                ctx(), "exec zsh", user = "altaris", method = "chroot",
                loginShell = GuestLoginShell.BASH
            )
            assertTrue(args[2].contains("login --user altaris --shell bash"))
        }
    }
}
