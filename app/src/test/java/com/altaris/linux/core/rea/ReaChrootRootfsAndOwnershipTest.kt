package com.altaris.linux.core.rea

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The second round of device failures.
 *
 * Two separate problems, both visible in one log:
 *
 *  1. `chroot: can't execute '/usr/bin/env': No such file or directory`
 *     followed by `package manager: unknown` — the chroot "install" had
 *     succeeded against an incomplete rootfs, because the setup script
 *     accepted any one of /bin/sh, bash or apk as proof of completeness.
 *
 *  2. `EACCES: permission denied, mkdir '/home/altaris/.local/share/opencode'`
 *     — the guest setup wrote into `/home/flux` (the retired account's home)
 *     while the account was already `altaris`, so the user could not write
 *     their own dotfiles. Fixing one directory with chown only moved the
 *     failure to `~/.cache`.
 */
class ReaChrootRootfsAndOwnershipTest {

    // ------------------------------------------------------------- rootfs

    /**
     * A partially extracted tree satisfies the old check, so extraction was
     * skipped and every later command failed on a missing /usr/bin/env.
     */
    @Test
    fun aRootfsIsOnlyCompleteWithShellLoaderAndEnv() {
        val script = readAsset("scripts/chroot/setup_guest_chroot.sh")
        assertTrue(
            "must have a real completeness check",
            script.contains("rootfs_is_usable()"),
        )
        val check = script.substringAfter("rootfs_is_usable() {")
            .substringBefore("extract_file() {")
        assertTrue("must require a shell", check.contains("bin/sh"))
        assertTrue(
            "must require the dynamic loader, or the shell cannot run",
            check.contains("ld-linux-aarch64.so.1"),
        )
        assertTrue(
            "must require /usr/bin/env, which is what the failure named",
            check.contains("usr/bin/env"),
        )
        // The old one-of check must be gone.
        assertFalse(
            "must not accept bash alone as a complete rootfs",
            check.contains("[ -x \"\$_dest/usr/bin/bash\" ] || \\\n       [ -x \"\$_dest/bin/bash\" ]"),
        )
    }

    /** A tree that is present but broken must be cleared, not merged into. */
    @Test
    fun anIncompleteTreeIsClearedBeforeExtracting() {
        val script = readAsset("scripts/chroot/setup_guest_chroot.sh")
        assertTrue(
            "must detect a present-but-broken tree",
            script.contains("Incomplete rootfs at"),
        )
        assertTrue(
            "must remove it before extracting",
            script.contains("rm -rf \"\$_dest\""),
        )
        // And the order matters: clear, then extract.
        val clear = script.indexOf("Incomplete rootfs at")
        val extract = script.indexOf("tar xJf")
        assertTrue("clearing must precede extraction", clear in 0..extract)
    }

    /** "Already installed" must not skip a rootfs that cannot run a command. */
    @Test
    fun alreadyInstalledRequiresAUsableRootfs() {
        val script = readAsset("scripts/chroot/setup_guest_chroot.sh")
        val guard = script.substringAfter("progress \"Using Root Busybox: \$BB\"")
            .substringBefore("mkdir -p \"\$GUESTPATH\"")
        assertTrue(
            "the already-installed branch must check usability",
            guard.contains("rootfs_is_usable \"\$GUESTPATH\""),
        )
    }

    /** Optional mounts must not abort an install. */
    @Test
    fun optionalMountsDoNotAbortTheInstall() {
        for (asset in listOf(
            "scripts/chroot/setup_guest_chroot.sh",
            "scripts/chroot/setup_debian13_chroot.sh",
        )) {
            val script = readAsset(asset)
            val mounts = script.substringAfter("progress \"Mounting filesystems...\"")
                .substringBefore("ensure_chroot_tmp")
            assertFalse(
                "$asset must not abort when an optional mount fails",
                mounts.contains("/sdcard\" || goodbye"),
            )
            assertTrue(
                "$asset must still require /proc",
                mounts.contains("cannot mount /proc"),
            )
            assertTrue(
                "$asset must create the mount targets first",
                mounts.contains("mkdir -p"),
            )
        }
    }

    // ---------------------------------------------------------- ownership

    /**
     * The home must be resolved from the account, never hardcoded.
     *
     * This is the bug that produced the EACCES lines: setup wrote /home/flux
     * while the user's home was /home/altaris.
     */
    @Test
    fun theGuestHomeIsResolvedFromTheAccount() {
        val common = readAsset("scripts/common/setup/altaris_guest_common.sh")
        assertTrue(
            "must have a home resolver",
            common.contains("_altaris_guest_home()"),
        )
        assertTrue(
            "must read the passwd entry",
            common.contains("getent passwd") || common.contains("/etc/passwd"),
        )
        val ensure = common.substringAfter("_altaris_ensure_home() {")
            .substringBefore("_altaris_write_gpu_mode")
        assertFalse(
            "must not chown a hardcoded retired path",
            ensure.contains("chown -R flux:flux /home/flux"),
        )
        assertFalse(
            "must not create a hardcoded retired home",
            ensure.contains("mkdir -p /home/flux"),
        )
        // The whole home is fixed, not a fixed list of three directories.
        assertTrue(
            "must chown the whole home",
            ensure.contains("chown -R \"\$_uid:\${_gid:-\$_uid}\" \"\$_h\""),
        )
    }

    /** A chroot session must repair a home that a root-run tool broke. */
    @Test
    fun aChrootSessionRepairsTheHomeBeforeRunning() {
        val helper = readAsset("scripts/chroot/altaris_chroot.sh")
        assertTrue(
            "must have a repair function",
            helper.contains("fix_guest_home_ownership()"),
        )
        assertTrue(
            "must run it before the guest command",
            helper.contains("fix_guest_home_ownership\n  mkdir -p"),
        )
        // And it must cover the directories the CLIs actually use.
        val repair = helper.substringAfter("fix_guest_home_ownership() {")
            .substringBefore("ensure_mounts() {")
        for (dir in listOf(".local", ".cache", ".config", ".npm", ".bun", ".cargo")) {
            assertTrue("repair must cover $dir", repair.contains(dir))
        }
    }

    /** A proot session repairs the home from the app, which can write there. */
    @Test
    fun aProotSessionRepairsTheHomeToo() {
        val builder = readSource(
            "app/src/main/kotlin/com/altaris/linux/core/terminal/ProotCommandBuilder.kt"
        )
        assertTrue(
            "must repair before building the session",
            builder.contains("GuestHomeRepair.ensureWritable(rootfs)"),
        )
        val repair = readSource(
            "app/src/main/kotlin/com/altaris/linux/core/terminal/GuestHomeRepair.kt"
        )
        assertTrue("must add owner write", repair.contains("OWNER_WRITE"))
        // The exact paths from the failure must be covered.
        assertTrue(
            "must cover the opencode state directories",
            repair.contains(".local/share/opencode") && repair.contains(".cache/opencode"),
        )
    }

    // ------------------------------------------------------------ rename

    /** The retired product names must be gone from the setup scripts. */
    @Test
    fun theRetiredNamesAreGoneFromTheScripts() {
        val common = readAsset("scripts/common/setup/altaris_guest_common.sh")
        assertFalse("/home/flux must not appear", common.contains("/home/flux"))
        assertFalse("chown flux:flux must not appear", common.contains("chown -R flux:flux"))

        val alpine = readAsset("scripts/alpine/common/setup/setup_alpine_family.sh")
        assertFalse("no legacy alias account", alpine.contains("Legacy:/home"))
        assertFalse("no flux sudo rule", alpine.contains("\"flux ALL="))

        val helper = readAsset("scripts/chroot/altaris_chroot.sh")
        assertFalse(
            "the chroot helper must not fall back to a retired account",
            helper.contains("USER_NAME=flux"),
        )
    }

    /** The Kotlin guest home constant must match the account. */
    @Test
    fun theKotlinGuestHomeIsAltaris() {
        val store = readSource(
            "app/src/main/kotlin/com/altaris/linux/core/theme/AppearanceStore.kt"
        )
        assertTrue(store.contains("const val GUEST_HOME = \"/home/altaris\""))
        assertFalse(store.contains("GUEST_HOME = \"/home/flux\""))
    }

    // ------------------------------------------------------------ refresh

    /**
     * A poll must not walk a multi-GB rootfs or fork `su`.
     *
     * Both ran on every 4-second tick of the REA screen, which is what made
     * the whole app stutter while that screen was open.
     */
    @Test
    fun aRefreshPollIsCheap() {
        val distro = readSource(
            "app/src/main/kotlin/com/altaris/linux/core/rea/ReaDistro.kt"
        )
        assertTrue(
            "must offer a deep flag",
            distro.contains("fun refresh(context: Context, deep: Boolean = false)"),
        )
        assertTrue(
            "must throttle repeated polls",
            distro.contains("REFRESH_MIN_INTERVAL_MS"),
        )
        val refresh = distro.substringAfter("fun refresh(context: Context, deep: Boolean = false) {")
            .substringBefore("/** The distro id of the active REA distro")
        assertTrue(
            "must only walk the rootfs on a deep refresh",
            refresh.contains("if (active != null && deep) sizeOf(app, active)"),
        )
        assertTrue(
            "must not fork su unless root is already known",
            refresh.contains("probeRoot = deep && rootKnown"),
        )
    }

    /** The polling screens must not ask for a deep read. */
    @Test
    fun thePollingScreensUseAShallowRefresh() {
        val rea = readSource(
            "app/src/main/kotlin/com/altaris/linux/ui/screens/ReaScreen.kt"
        )
        val poll = rea.substringAfter("LaunchedEffect(Unit) {")
            .substringBefore("Column(")
        assertTrue(
            "the REA poll must refresh",
            poll.contains("ReaDistro.refresh(context)"),
        )
        assertFalse(
            "the REA poll must not ask for the expensive deep read",
            poll.contains("deep = true"),
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
