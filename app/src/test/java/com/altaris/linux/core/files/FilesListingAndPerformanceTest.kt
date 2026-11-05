package com.altaris.linux.core.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The two device reports behind this change.
 *
 * 1. The External space listed nothing even with a chroot installed.
 * 2. The Files filter field drew its text clipped and off-baseline.
 *
 * Both are tested as source facts, because neither can be executed here: one
 * needs root on a device, the other needs a real text layout pass.
 */
class FilesListingAndPerformanceTest {

    private fun readSource(path: String): String {
        for (candidate in listOf(path, "../$path", "../../$path")) {
            val file = File(candidate)
            if (file.isFile) return file.readText()
        }
        throw AssertionError("source not found: $path")
    }

    // --------------------------------------------------------- external space

    /**
     * The external listing must not use `find -printf`.
     *
     * That is a GNU extension. Android's toybox `find` has no `-printf`, so the
     * command failed, printed nothing, and the pipe to `head` swallowed the
     * failure. The result was an empty list that looked like a valid empty
     * directory — exactly the report "external directory is empty even though I
     * have a chroot".
     */
    @Test
    fun theExternalListingUsesOnlyToyboxCommands() {
        val source = readSource(
            "app/src/main/kotlin/com/altaris/linux/core/files/FileBrowser.kt"
        )
        // Comments may name the GNU command to explain why it is not used;
        // only a live call matters.
        assertFalse(
            "find -printf is GNU-only and absent on Android",
            source.contains("\"-printf") || source.contains(" -printf '%"),
        )
        // Names come from ls -a1, metadata from stat -c: both toybox.
        assertTrue("must list names with ls", source.contains("\"ls -a1 \""))
        assertTrue("must read metadata with stat", source.contains("stat -c"))
        assertTrue(
            "must read the type from the mode string, not %F",
            source.contains("mode.startsWith(\"d\")"),
        )
        assertFalse(
            "toybox has no %F in the stat format",
            source.contains("'%s|%Y|%A|%F|%n'"),
        )
    }

    /**
     * A pipeline hides the exit status of everything but its last command.
     *
     * `find … | head` exits 0 when `find` fails, so the "cannot read" branch
     * never ran and a broken listing rendered as an empty folder. The listing
     * must check the status of the command that actually ran.
     */
    @Test
    fun aFailedListingIsReportedNotShownAsEmpty() {
        val source = readSource(
            "app/src/main/kotlin/com/altaris/linux/core/files/FileBrowser.kt"
        )
        val fn = source.substringAfter("private fun listExternal(")
            .substringBefore("private data class Meta(")
        assertTrue("must check the exit status", fn.contains("exitCode != 0"))
        assertTrue(
            "must recognise a missing directory",
            fn.contains("No such file"),
        )
        assertTrue(
            "must recognise a permission failure",
            fn.contains("Permission denied"),
        )
        assertFalse(
            "must not pipe the listing through head",
            fn.contains("| head"),
        )
    }

    /** Metadata is read in one call, not one shell per file. */
    @Test
    fun metadataIsOneCallForAllEntries() {
        val source = readSource(
            "app/src/main/kotlin/com/altaris/linux/core/files/FileBrowser.kt"
        )
        val fn = source.substringAfter("private fun readMetadata(")
            .substringBefore("    // ---------------------------------------------------------------- content")
        assertTrue("must loop inside one shell", fn.contains("for f in"))
        assertTrue("must use a separator the name cannot shift", fn.contains("split('|', limit = 4)"))
    }

    // ------------------------------------------------------------ filter field

    /**
     * The filter must not be an OutlinedTextField forced to a fixed height.
     *
     * The outlined variant reserves room for a floating label and its own
     * internal padding. Constraining it to 42 dp made it measure below its
     * minimum, and the text was clipped and drawn off its baseline — the
     * "broken text" report.
     */
    @Test
    fun theFilterFieldDoesNotClipItsText() {
        val screen = readSource(
            "app/src/main/kotlin/com/altaris/linux/ui/screens/FilesScreen.kt"
        )
        val fn = screen.substringAfter("// ---------------------------------------------------------- filter")
            .substringBefore("// ----------------------------------------------------------- tools")
        assertTrue("must use a plain text field", fn.contains("BasicTextField("))
        assertFalse(
            "the outlined variant is what clipped it",
            fn.contains("OutlinedTextField("),
        )
        assertTrue(
            "must centre the text in its box",
            fn.contains("contentAlignment = Alignment.CenterStart"),
        )
    }

    // ---------------------------------------------------------- performance

    /**
     * The app must not contain an endless full-screen animation.
     *
     * The hero ran a 16-second infinite zoom, the backdrop a 26-second drift,
     * the REA chunk map a 2.2-second sweep. A Compose animation invalidates a
     * frame, so any one of them means the app never reaches a still frame.
     */
    @Test
    fun noScreenRunsAnEndlessAnimation() {
        val hero = readSource(
            "app/src/main/kotlin/com/altaris/linux/ui/components/HomeHero.kt"
        )
        assertFalse("the hero must not drift", hero.contains("rememberInfiniteTransition"))

        val backdrop = readSource(
            "app/src/main/kotlin/com/altaris/linux/ui/components/PrismBackdrop.kt"
        )
        assertFalse(
            "the backdrop must not animate",
            backdrop.contains("rememberInfiniteTransition"),
        )

        val rea = readSource(
            "app/src/main/kotlin/com/altaris/linux/ui/screens/ReaScreen.kt"
        )
        assertFalse(
            "the chunk map must not sweep",
            rea.contains("rememberInfiniteTransition"),
        )
    }

    /** No blur: it is the most expensive per-frame effect on a phone GPU. */
    @Test
    fun noScreenBlursAFullWindow() {
        val rea = readSource(
            "app/src/main/kotlin/com/altaris/linux/ui/screens/ReaScreen.kt"
        )
        assertFalse("must not blur the not-installed state", rea.contains(".blur("))
    }

    /**
     * The haze/blur dependency must be gone.
     *
     * It was declared and plumbed through five screens as a `hazeState`
     * parameter, and no live screen ever called `hazeChild` with it — the only
     * use was in an onboarding screen nothing rendered. It cost a dependency
     * and a parameter on every screen for no pixels.
     */
    @Test
    fun theFrostedGlassDependencyIsRemoved() {
        val gradle = readSource("app/build.gradle.kts")
        assertFalse("must not depend on haze", gradle.contains("libs.haze"))
        val catalog = readSource("gradle/libs.versions.toml")
        assertFalse("must not declare haze", catalog.contains("chrisbanes.haze"))
        val scaffold = readSource(
            "app/src/main/kotlin/com/altaris/linux/ui/components/GlassScaffold.kt"
        )
        assertFalse("must not take a haze state", scaffold.contains("HazeState"))
        assertFalse("must not take a blur toggle", scaffold.contains("blurContent"))
    }

    /**
     * Poll loops must stop when the app is not visible.
     *
     * Every screen polled on its own `while (true)` inside a LaunchedEffect.
     * A composed-but-backgrounded tab kept forking guest shells every few
     * seconds for output nobody could see.
     */
    @Test
    fun pollingStopsWhenTheAppIsBackgrounded() {
        val poll = readSource(
            "app/src/main/kotlin/com/altaris/linux/ui/util/PollEffect.kt"
        )
        assertTrue("must observe the lifecycle", poll.contains("LifecycleEventObserver"))
        assertTrue(
            "must gate on STARTED",
            poll.contains("isAtLeast(Lifecycle.State.STARTED)"),
        )
        // The screens that used to loop forever must use it.
        for (path in listOf(
            "app/src/main/kotlin/com/altaris/linux/ui/screens/ReaScreen.kt",
            "app/src/main/kotlin/com/altaris/linux/ui/screens/ToolsScreen.kt",
        )) {
            val source = readSource(path)
            assertTrue(
                "$path must use the lifecycle-aware poll",
                source.contains("PollEffect("),
            )
        }
    }

    /**
     * The refresh trigger must not be read at the root of the composition.
     *
     * Reading a StateFlow there means every install tick and every remote
     * refresh recomposes every screen, the dock and the top bar — for a counter
     * only Home consumes.
     */
    @Test
    fun theRefreshTriggerIsScopedToHome() {
        val activity = readSource("app/src/main/kotlin/com/altaris/linux/MainActivity.kt")
        val home = activity.substringAfter("BottomTab.HOME ->")
            .substringBefore("BottomTab.REA ->")
        assertTrue(
            "Home must collect the trigger",
            home.contains("refreshTrigger.collectAsState()"),
        )
        // And the top-level state block must not.
        val preamble = activity.substringAfter("PrismOrbPulseProvider {")
            .substringBefore("BottomTab.HOME ->")
        assertFalse(
            "the root must not collect it",
            preamble.contains("refreshTrigger.collectAsState()"),
        )
    }

    /**
     * The status-dot phase must be read in the draw phase.
     *
     * Providing a plain Float through a CompositionLocal meant the 1.8-second
     * loop recomposed the whole app subtree every frame. A State read inside
     * graphicsLayer invalidates one layer instead.
     */
    @Test
    fun theStatusDotPhaseDoesNotRecomposeTheApp() {
        val prism = readSource(
            "app/src/main/kotlin/com/altaris/linux/ui/components/Prism.kt"
        )
        assertTrue(
            "the local must hold a State",
            prism.contains("State<Float>"),
        )
        assertTrue(
            "the orb must read it in the draw phase",
            prism.contains("val halo = phase.value"),
        )
    }
}
