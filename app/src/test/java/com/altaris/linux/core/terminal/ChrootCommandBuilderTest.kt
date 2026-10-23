package com.altaris.linux.core.terminal

import com.altaris.linux.core.root.ChrootPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure argv shape tests for chroot sessions (SSOT helper; no device / no Context). */
class ChrootCommandBuilderTest {

    private val helper = ChrootPaths.CHROOT_HELPER

    @Test
    fun sessionExec_isSystemSh() {
        assertEquals("/system/bin/sh", ChrootCommandBuilder.SESSION_EXEC)
        assertEquals("/system/bin/sh", ChrootPaths.SESSION_EXEC)
    }

    @Test
    fun interactiveAltaris_loginZsh() {
        val inner = ChrootCommandBuilder.buildRootInner("exec zsh", user = "altaris")
        assertEquals("exec sh $helper login --user altaris --shell zsh", inner)
    }

    @Test
    fun interactiveRoot_loginBash() {
        val inner = ChrootCommandBuilder.buildRootInner("", user = "root")
        assertEquals("exec sh $helper login --user root --shell bash", inner)
    }

    @Test
    fun interactiveRoot_explicitZshPref() {
        val inner = ChrootCommandBuilder.buildRootInner(
            "",
            user = "root",
            loginShellRoot = GuestLoginShell.DEFAULT.chrootFlag
        )
        assertEquals("exec sh $helper login --user root --shell zsh", inner)
    }

    @Test
    fun interactiveAltaris_loginBash() {
        val inner = ChrootCommandBuilder.buildRootInner(
            "exec zsh",
            user = "altaris",
            loginShellAltaris = "bash"
        )
        assertEquals("exec sh $helper login --user altaris --shell bash", inner)
    }

    @Test
    fun interactiveRoot_honorsBashPref() {
        val inner = ChrootCommandBuilder.buildRootInner(
            "exec zsh",
            user = "root",
            loginShellRoot = "bash"
        )
        assertEquals("exec sh $helper login --user root --shell bash", inner)
    }

    @Test
    fun interactiveAlpine_loginSh() {
        val inner = ChrootCommandBuilder.buildRootInner(
            "exec zsh",
            user = "altaris",
            loginShellAltaris = "sh",
            loginShellRoot = "sh"
        )
        assertEquals("exec sh $helper login --user altaris --shell sh", inner)
    }

    @Test
    fun workdir_login_usesWorkdir() {
        val inner = ChrootCommandBuilder.buildRootInner("mkdir -p /home/flux/proj && cd /home/flux/proj && exec zsh", user = "altaris")
        assertEquals("exec sh $helper login --user altaris --shell zsh --workdir '/home/flux/proj'", inner)
    }

    @Test
    fun workdir_execBash_usesWorkdirAndShellFromParams() {
        val inner = ChrootCommandBuilder.buildRootInner(
            "mkdir -p /home/flux/proj && cd /home/flux/proj && exec bash",
            user = "altaris",
            loginShellAltaris = "bash"
        )
        assertEquals(
            "exec sh $helper login --user altaris --shell bash --workdir '/home/flux/proj'",
            inner
        )
    }

    @Test
    fun simpleCommand_routesToSh() {
        val inner = ChrootCommandBuilder.buildRootInner("whoami; id", user = "altaris")
        assertEquals("exec sh $helper sh --user altaris -- 'whoami; id'", inner)
    }

    @Test
    fun complexCommand_routesToB64() {
        val inner = ChrootCommandBuilder.buildRootInner("echo \$HOME && echo \"hi\"", user = "altaris")
        assertTrue(inner.startsWith("exec sh $helper b64 --user altaris -- "))
        assertTrue(inner.dropLast(1).endsWith("==") || inner.length > "exec sh $helper b64 --user altaris -- ".length + 8)
    }

    @Test
    fun b64Payload_decodesToOriginal() {
        val payload = "echo \$HOME && echo \"hi\""
        val inner = ChrootCommandBuilder.buildRootInner(payload, user = "altaris")
        val b64 = inner.substringAfter("-- ")
        val decoded = String(java.util.Base64.getDecoder().decode(b64))
        assertEquals(payload, decoded)
    }

    @Test
    fun rootSimpleCommand_routesToSh() {
        val inner = ChrootCommandBuilder.buildRootInner("echo hi", user = "root")
        assertEquals("exec sh $helper sh --user root -- 'echo hi'", inner)
    }

    @Test
    fun quotePayload_forcesB64() {
        // Single quotes break the `sh -- '...'` wrapper → must route to b64.
        val inner = ChrootCommandBuilder.buildRootInner("echo it's", user = "altaris")
        assertTrue(inner.startsWith("exec sh $helper b64 --user altaris -- "))
    }

    @Test
    fun winchWrap_preservesCommand() {
        val wrapped = ChrootCommandBuilder.winchWrap("su -c 'x'")
        assertTrue(wrapped.startsWith("trap 'kill -WINCH -"))
        assertTrue(wrapped.endsWith("WINCH; su -c 'x'"))
    }

    @Test
    fun rootSessionArgv_startsWithSystemShDashC() {
        val winch = InstallSessionFactory.rootSessionWinchCommand("su -c 'sh /tmp/x.sh'")
        val argv = InstallSessionFactory.rootSessionArgv(winch)
        assertEquals(3, argv.size)
        assertEquals("/system/bin/sh", argv[0])
        assertEquals("-c", argv[1])
        assertEquals(winch, argv[2])
        assertTrue(argv[2].startsWith("trap 'kill -WINCH -"))
    }

    @Test
    fun buildEnv_androidPathAndGuestHome() {
        val env = ChrootCommandBuilder.buildEnv(user = "root")
        assertEquals("/root", env["HOME"])
        assertEquals("/system/bin:/system/xbin:/sbin:" + System.getenv("PATH").orEmpty(), env["PATH"])
        val altarisEnv = ChrootCommandBuilder.buildEnv(user = "altaris")
        assertEquals("/home/flux", altarisEnv["HOME"])
        assertEquals("tcp:127.0.0.1", altarisEnv["PULSE_SERVER"])
        assertEquals(ChrootPaths.CHROOT_PATH, altarisEnv["ALTARIS_CHROOT"])
    }

    @Test
    fun buildEnv_alpinePath() {
        val env = ChrootCommandBuilder.buildEnv(
            user = "altaris",
            chrootPath = ChrootPaths.ALPINE_CHROOT_PATH
        )
        assertEquals(ChrootPaths.ALPINE_CHROOT_PATH, env["ALTARIS_CHROOT"])
    }

    @Test
    fun build_neverBlocksOnRootForSessionOpen() {
        // Root unset on purpose: composing a session must not probe/stage su.
        com.altaris.linux.core.root.RootShell.clearSuCache()
        val dir = java.nio.file.Files.createTempDirectory("altaris-chroot-build").toFile()
        try {
            val ctx = FakeContext(dir, "$dir/jni")
            val start = System.nanoTime()
            val (args, _) = ChrootCommandBuilder.build(
                ctx,
                "exec zsh",
                chrootPath = "$dir/missing-chroot"
            )
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            assertTrue("build took ${elapsedMs}ms", elapsedMs < 1_500)
            assertEquals("/system/bin/sh", args[0])
            assertEquals("-c", args[1])
            assertTrue(args[2].contains("altaris_chroot.sh"))
            assertTrue(args[2].contains("ALTARIS_CHROOT="))
        } finally {
            dir.deleteRecursively()
            com.altaris.linux.core.root.RootShell.clearSuCache()
        }
    }
}
