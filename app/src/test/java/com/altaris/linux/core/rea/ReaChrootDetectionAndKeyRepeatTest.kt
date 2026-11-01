package com.altaris.linux.core.rea

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two regressions reported after the ownership/lag release.
 *
 *  1. REA was installed as a chroot, but the REA tab still said "not
 *     installed". The cheap-refresh optimisation read a cache that nothing
 *     ever wrote for the REA chroot, so every poll concluded it was absent.
 *
 *  2. The terminal's special keys opened the edit menu on long-press, so an
 *     arrow could not be held to scroll.
 */
class ReaChrootDetectionAndKeyRepeatTest {

    // ------------------------------------------------------- chroot detected

    /**
     * A deep read must record what it proved, so the cheap polls that follow
     * can answer without forking `su`.
     */
    @Test
    fun aDeepRefreshRecordsTheChrootItFound() {
        val distro = readSource(
            "app/src/main/kotlin/com/altaris/linux/core/rea/ReaDistro.kt"
        )
        val refresh = distro.substringAfter("fun refresh(context: Context, deep: Boolean = false) {")
            .substringBefore("/** The distro id of the active REA distro")
        assertTrue(
            "a deep refresh must cache what it found",
            refresh.contains("ChrootInfoStore.saveInstallInfo"),
        )
        assertTrue(
            "it must cache for each present method",
            refresh.contains("for (method in present)"),
        )
        // And only a deep, root-backed read may write the cache.
        assertTrue("must be gated on a deep read", refresh.contains("if (deep && rootKnown)"))
    }

    /**
     * The cache is keyed by distro id, and the REA chroot can be written under
     * its label or its id depending on the caller. Reading only one of them is
     * what reported an installed chroot as missing.
     */
    @Test
    fun theCachedChrootIsLookedUpUnderEveryKeyItMayUse() {
        val distro = readSource(
            "app/src/main/kotlin/com/altaris/linux/core/rea/ReaDistro.kt"
        )
        val cached = distro.substringAfter("private fun cachedChrootPresent(")
            .substringBefore("/**\n     * True when REA's core is really installed.")
        assertTrue("must try the method id", cached.contains("cachedInstalled(context, method.id)"))
        assertTrue("must try the label", cached.contains("cachedInstalled(context, method.label)"))
        assertTrue(
            "must try the explicit REA chroot id",
            cached.contains("cachedInstalled(context, DistroInstallProfile.REA_CHROOT_ID)"),
        )
    }

    /** A shallow poll must read the readiness cache, never fork `su`. */
    @Test
    fun aShallowPollDoesNotForkSuForReadiness() {
        val distro = readSource(
            "app/src/main/kotlin/com/altaris/linux/core/rea/ReaDistro.kt"
        )
        val ready = distro.substringAfter("private fun isReady(")
            .substringBefore("private fun quote(")
        assertTrue(
            "a proot check stays a plain file test",
            ready.contains("if (method != Method.CHROOT) return ReaManager.isInstalledIn"),
        )
        assertTrue("must consult the cache", ready.contains("readyCache[method.id]"))
        assertTrue(
            "must only probe root on a deep read",
            ready.contains("if (!probeRoot || !RootShell.isRootAvailable())"),
        )
    }

    /** The REA screen's first read is deep, so the cache is filled on open. */
    @Test
    fun theReaScreenSeedsTheCacheOnFirstRead() {
        val screen = readSource(
            "app/src/main/kotlin/com/altaris/linux/ui/screens/ReaScreen.kt"
        )
        assertTrue(
            "must do one deep read when the screen opens",
            screen.contains("ReaDistro.refresh(context, deep = first)"),
        )
        assertTrue("must only do it once", screen.contains("first = false"))
    }

    // ---------------------------------------------------------- key repeat

    /**
     * Holding an arrow must scroll. That needs a down that stays down, repeats,
     * and an up on release — not the down+up pair a tap sends.
     */
    @Test
    fun aHeldKeyStaysDownAndRepeats() {
        val source = readSource(
            "app/src/main/kotlin/com/altaris/linux/ui/terminal/TerminalExtraKeys.kt"
        )
        assertTrue("must expose a down", source.contains("fun keyDown("))
        assertTrue("must expose a repeat", source.contains("fun keyRepeat("))
        assertTrue("must expose an up", source.contains("fun keyUp("))
        // The repeat is a repeated ACTION_DOWN, which is what a keyboard does.
        val repeat = source.substringAfter("fun keyRepeat(")
            .substringBefore("fun keyUp(")
        assertTrue(
            "repeat must be a DOWN with a non-zero repeat count",
            repeat.contains("KeyEvent.ACTION_DOWN") && repeat.contains(", 1,"),
        )
        // Only keys with a key code can repeat.
        assertTrue(
            "only special keys repeat",
            source.contains("fun isRepeatable(key: String): Boolean") &&
                source.contains("TerminalKeyCodes.SPECIAL_KEY_CODES[key] != null"),
        )
    }

    /** The hold gesture must not fire the first repeat immediately. */
    @Test
    fun theFirstRepeatWaitsSoATapStaysASingleCharacter() {
        val source = readSource(
            "app/src/main/kotlin/com/altaris/linux/ui/terminal/TerminalExtraKeys.kt"
        )
        assertTrue("must define a hold delay", source.contains("HOLD_DELAY_MS"))
        assertTrue("must define a repeat gap", source.contains("HOLD_REPEAT_MS"))
        val gesture = source.substringAfter("nextRepeatAt = System.currentTimeMillis() + HOLD_DELAY_MS")
            .substringBefore("interaction.emit(PressInteraction.Release")
        assertTrue(
            "later repeats must use the short gap",
            gesture.contains("nextRepeatAt = System.currentTimeMillis() + HOLD_REPEAT_MS"),
        )
    }

    /**
     * The bar must not carry its own editor button.
     *
     * Terminal → Tools already opens the shortcut editor, which is where keys
     * are added, edited, reordered and removed. A second entry point on the bar
     * itself just took a row of keys for something the menu already does.
     */
    @Test
    fun theBarDoesNotDuplicateTheToolsMenuEditor() {
        val source = readSource(
            "app/src/main/kotlin/com/altaris/linux/ui/terminal/TerminalExtraKeys.kt"
        )
        assertFalse(
            "the bar must not add its own edit control",
            source.contains("text = \"Edit keys\""),
        )
        // The empty-bar hint stays: with no keys there is nothing else to tap.
        assertTrue(
            "an empty bar still points at the editor",
            source.contains("No keys — tap to edit"),
        )
    }

    /** The editor the tools menu opens must still cover the per-key actions. */
    @Test
    fun theToolsMenuEditorCoversEditing() {
        val editor = readSource(
            "app/src/main/kotlin/com/altaris/linux/ui/terminal/ShortcutBarEditorSheet.kt"
        )
        assertTrue("must edit a key", editor.contains("\"Edit key\""))
        assertTrue("must remove a key", editor.contains("\"Remove key\""))
        assertTrue("must add a key", editor.contains("\"Add key\""))
        assertTrue("must reorder keys", editor.contains("fun moveKey("))
        assertTrue("must reset the bar", editor.contains("\"Reset\""))
    }

    private fun readSource(path: String): String {
        for (candidate in listOf(path, "../$path", "../../$path")) {
            val file = java.io.File(candidate)
            if (file.isFile) return file.readText()
        }
        throw AssertionError("source not found: $path")
    }
}
