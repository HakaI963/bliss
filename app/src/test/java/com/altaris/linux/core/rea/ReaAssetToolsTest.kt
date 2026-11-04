package com.altaris.linux.core.rea

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The asset-tool installs that failed on a real device.
 *
 * Three of the four routes the log showed were dead on arrival: the pip
 * packages did not exist on PyPI, Debian ships no vgmstream package at all, and
 * every source build died the second time it ran because a previous attempt
 * had left its clone directory behind. Each case here is one of those.
 */
class ReaAssetToolsTest {

    private val tools: List<ReaAssetTools.Tool> by lazy {
        val groups = ReaAssetTools.GROUPS
        groups.flatMap { it.tools }
    }

    private fun byId(id: String): ReaAssetTools.Tool =
        tools.firstOrNull { it.id == id }
            ?: throw AssertionError("tool missing from the catalog: $id")

    // ------------------------------------------------------- dead pip routes

    /**
     * Every pip name must exist on PyPI.
     *
     * Three did not: `rgssad`, `unpak` and `pyfmodex` are not published there,
     * so those tools reported "could not install" no matter how many times they
     * were retried. The names here were verified against the registry; a new
     * pip route needs the same check.
     */
    @Test
    fun noPipRouteNamesAPackageThatDoesNotExist() {
        val dead = setOf("rgssad", "unpak", "pyfmodex")
        for (tool in tools) {
            assertFalse(
                "${tool.id} points at a pip package that is not on PyPI",
                tool.pip in dead,
            )
        }
    }

    /** FMOD banks are read by fsb5 — pyfmodex is engine bindings, not a reader. */
    @Test
    fun fmodUsesTheRealFsb5Reader() {
        val fmod = byId("fmod")
        assertEquals("fsb5", fmod.pip)
        // And it brings the CLI with it, so the documented command is real.
        assertNotNull("fsb5 must also come from an archive with extract.py", fmod.archive)
        assertEquals("extract.py", fmod.archive!!.launcher)
        // The module the pip fallback must prove itself by.
        val source = readSource("app/src/main/kotlin/com/altaris/linux/core/rea/ReaAssetTools.kt")
        val map = source.substringAfter("private fun probeModule(")
        assertTrue("fmod must map to the fsb5 module", map.contains("\"fmod\" -> \"fsb5\""))
    }

    /** Wwise banks are read by wwiser, which ships a zipapp rather than a PyPI package. */
    @Test
    fun wwiseComesFromTheWwiserZipapp() {
        val wwise = byId("wwise")
        val archive = wwise.archive
        assertNotNull("wwiser must come from its release", archive)
        assertTrue("the zipapp is the program", archive!!.fileName.endsWith(".pyz"))
        assertEquals("wwiser.pyz", archive.launcher)
        // The zipapp has no shebang (verified: it starts with the zip magic),
        // so the shim must run it through python3.
        assertTrue("must run through python", archive.runtime == "python")
    }

    /**
     * The too-small gate is per tool.
     *
     * It was a hardcoded 100 MB: correct for a Ghidra-class archive, and wrong
     * for a zipapp — wwiser is 330 KB, so the installer rejected the real
     * download as "not a release archive". Each archive now carries the floor
     * its own release needs.
     */
    @Test
    fun smallArchivesCarryTheirOwnSizeFloor() {
        val script = readAsset("scripts/rea/install-archive.sh")
        assertTrue(
            "the installer must take the floor as an argument",
            script.contains("MIN_BYTES=\"\$6\""),
        )
        assertTrue(
            "the hardcoded floor must be gone",
            !script.contains("-lt 100000 ]"),
        )
        // And the app passes each archive's own floor through.
        val source = readSource("app/src/main/kotlin/com/altaris/linux/core/rea/ReaAssetTools.kt")
        val flow = source.substringAfter("install-archive.sh\"").substringBefore("timeoutMs")
        assertTrue(
            "the install must pass the archive's floor",
            flow.contains("archive.minBytes"),
        )
        // Measured against the real releases: wwiser.pyz is ~330 KB, the
        // rgss3a-unpack tarball ~15 KB.
        assertEquals(300_000L, byId("wwise").archive!!.minBytes)
        assertEquals(10_000L, byId("rpgmaker").archive!!.minBytes)
    }

    /**
     * The zipapp must be kept intact, not unpacked: extracting it destroys it.
     */
    @Test
    fun theArchiveInstallerKeepsAPyzIntact() {
        val script = readAsset("scripts/rea/install-archive.sh")
        assertTrue("must recognise a .pyz", script.contains("*.pyz)"))
        val pyz = script.indexOf("*.pyz)")
        val move = script.indexOf("mv -f \"\$TMP\" \"\$DIR/\$FILE\"", pyz)
        assertTrue("a zipapp is placed, not extracted", move > pyz)
    }

    /**
     * A Python entry point has no shebang and usually sits in a versioned
     * directory inside the unpacked tree. The shim must resolve it at exec time
     * and run it through python3.
     */
    @Test
    fun thePythonShimResolvesTheLauncherAtExecTime() {
        val source = readSource("app/src/main/kotlin/com/altaris/linux/core/rea/ReaAssetTools.kt")
        val shim = source.substringAfter("private fun ilShim(")
            .substringBefore("private suspend fun linkBuilt(")
        assertTrue(
            "must branch on the python runtime",
            shim.contains("archive.runtime == \"python\""),
        )
        val branch = shim.substringAfter("archive.runtime == \"python\"")
        assertTrue(
            "must find the launcher under the install dir",
            branch.contains("find '"),
        )
        assertTrue(
            "must run it with python3, not exec it",
            branch.contains("exec python3"),
        )
        assertTrue(
            "a missing launcher must be reported, not exec'd empty",
            branch.contains("not installed:"),
        )
    }

    // ------------------------------------------------------- dead apt routes

    /**
     * Debian ships no vgmstream package (checked against trixie), so the apt
     * variant was a guaranteed failure followed by a pointless fallback.
     */
    @Test
    fun vgmstreamDoesNotClaimDebianPackagesIt() {
        val vgmstream = byId("vgmstream")
        assertFalse(
            "apt does not ship vgmstream; the variant must go",
            vgmstream.packages.containsKey("apt"),
        )
        // The source build is the real route, and it must be able to run twice.
        assertTrue("the build must be present", vgmstream.build.isNotBlank())
    }

    // ------------------------------------------------------- source builds

    /**
     * A source build clones into the working directory. A retry after a failed
     * or interrupted build found `vgmstream` already there and died with
     * "destination path already exists and is not an empty directory" — forever,
     * because nothing ever cleaned it.
     */
    @Test
    fun everySourceBuildClearsItsDirectoryFirst() {
        val builds = tools.filter { it.build.isNotBlank() }
        assertTrue("build routes must exist", builds.isNotEmpty())
        for (tool in builds) {
            assertTrue(
                "${tool.id} must rm -rf its directory before cloning, or a retry dies on it",
                tool.build.startsWith("rm -rf "),
            )
        }
    }

    /**
     * A build drops its binary inside the tree it cloned; the probe looks the
     * name up on PATH. Without the link step the build succeeded and the tool
     * still read as missing.
     */
    @Test
    fun aBuiltBinaryIsLinkedOntoPath() {
        val source = readSource("app/src/main/kotlin/com/altaris/linux/core/rea/ReaAssetTools.kt")
        val build = source.substringAfter("// 4. Build from source")
            .substringBefore("/**\n     * Put a freshly built binary on PATH")
        assertTrue(
            "the build route must link what it produced",
            build.contains("linkBuilt("),
        )
        val link = source.substringAfter("private suspend fun linkBuilt(")
        // Exact name first, then the ISA-suffixed variants (astcenc-neon).
        assertTrue(
            "must match the probe by exact name",
            link.contains("GuestExec.shellQuote(probe)"),
        )
        assertTrue(
            "must match ISA-suffixed variants too",
            link.contains("GuestExec.shellQuote(probe + \"-*\")"),
        )
    }

    /**
     * A source build needs git, a compiler and cmake, and a minimal rootfs has
     * none of them. They are installed before the build, best-effort.
     */
    @Test
    fun aSourceBuildInstallsItsToolchainFirst() {
        val source = readSource("app/src/main/kotlin/com/altaris/linux/core/rea/ReaAssetTools.kt")
        val build = source.substringAfter("// 4. Build from source")
            .substringBefore("/**\n     * Put a freshly built binary on PATH")
        for (manager in listOf("apt", "apk", "dnf", "pacman")) {
            assertTrue(
                "build tools must be named for $manager",
                build.contains("\"$manager\" -> listOf(\"git\""),
            )
        }
        assertTrue(
            "the toolchain install must go through the package manager",
            build.contains("ReaManager.installPackages("),
        )
    }

    // ------------------------------------------------------- install all

    /**
     * Install-all stopped on the first error: a throw escaped the per-tool
     * loop, `running` stayed true and every button froze. The loop must catch
     * per tool and reset in a finally.
     */
    @Test
    fun installAllContinuesPastAFailingTool() {
        val source = readSource("app/src/main/kotlin/com/altaris/linux/core/rea/ReaAssetTools.kt")
        val all = source.substringAfter("suspend fun installAll(")
            .substringBefore("private fun append(")
        assertTrue(
            "must reset the busy state in a finally",
            all.contains("finally {"),
        )
        assertTrue(
            "each tool must be caught separately",
            all.contains("catch (t: Throwable) {"),
        )
        assertTrue(
            "a per-tool install must not own the global running flag",
            all.contains("ownsRun = false"),
        )
    }

    /**
     * Nothing showed any progress: a long install looked frozen. The state
     * carries a percentage and a phase, and the tab renders them.
     */
    @Test
    fun installsReportProgress() {
        val source = readSource("app/src/main/kotlin/com/altaris/linux/core/rea/ReaAssetTools.kt")
        assertTrue("state must carry a percentage", source.contains("val percent: Int = 0"))
        assertTrue("state must carry a phase", source.contains("val phase: String = \"\""))
        // Each route publishes where it is.
        for (marker in listOf("phase(20,", "phase(70,", "phase(80,")) {
            assertTrue("routes must report progress ($marker)", source.contains(marker))
        }
        val screen = readSource("app/src/main/kotlin/com/altaris/linux/ui/screens/ReaScreen.kt")
        val other = screen.substringAfter("private fun OtherTab(")
        assertTrue(
            "the tab must show the percentage",
            other.contains("state.percent"),
        )
        assertTrue(
            "the tab must show the phase",
            other.contains("state.phase"),
        )
    }

    // ------------------------------------------------------- pip hygiene

    /**
     * pip ran as root in a throwaway distro and printed the full
     * "may render your system unusable" essay ahead of the result, which hid
     * the real outcome. It is silenced rather than heeded — the guest is a
     * disposable analysis environment, not anyone's desktop.
     */
    @Test
    fun pipWarnsAreSilenced() {
        val assets = readSource("app/src/main/kotlin/com/altaris/linux/core/rea/ReaAssetTools.kt")
        val frida = readSource("app/src/main/kotlin/com/altaris/linux/core/rea/ReaFrida.kt")
        for (source in listOf(assets, frida)) {
            assertTrue(
                "pip must carry --root-user-action=ignore",
                source.contains("--root-user-action=ignore"),
            )
        }
    }

    // ------------------------------------------------------- the contract

    /** Every listed tool must have at least one route that can work. */
    @Test
    fun everyListedToolIsInstallable() {
        assertTrue("the catalog must not be empty", tools.isNotEmpty())
        for (tool in tools) {
            assertTrue(
                "${tool.id} has no working route",
                tool.installable,
            )
        }
        // And the FMOD/Wwise pair replaced the one misleading entry.
        assertNotNull(byId("fmod"))
        assertNotNull(byId("wwise"))
    }

    /**
     * Tools with no working ARM64 route must be gone, not shown as dead buttons.
     *
     * Each was verified against its upstream release: AssetRipper's tarball
     * really ships `AssetRipper.GUI.Free` (the catalog had a launcher name that
     * matched nothing, so a good 26 MB download was thrown away), UnityPy has
     * no linux-aarch64 wheels past 1.10.18, no ARM64 QuickBMS exists anywhere
     * and its source does not compile on a modern toolchain, and UE4SS does
     * not support native Linux at all.
     */
    @Test
    fun assetripperUsesTheRealLauncher() {
        val assetripper = byId("assetripper")
        assertEquals(
            "the launcher must be the binary the archive actually contains",
            "AssetRipper.GUI.Free",
            assetripper.archive?.launcher,
        )
    }

    @Test
    fun unityPyIsPinnedToAVersionWithAnArm64Wheel() {
        val unityPy = byId("unitypy")
        assertTrue(
            "must pin a version that has cp313 manylinux-aarch64 wheels",
            unityPy.pip == "UnityPy==1.10.18",
        )
    }

    @Test
    fun toolsWithNoWorkingArm64RouteAreNotListed() {
        // QuickBMS: 32-bit x86 only anywhere upstream; source does not build
        // on GNU 16; Debian ships nothing. UE4SS: Windows-only upstream.
        for (id in listOf("quickbms", "ue4ss", "unrealpak")) {
            assertTrue(
                "$id has no working ARM64 route and must not be a dead button",
                tools.none { it.id == id },
            )
        }
    }

    /**
     * GodotPckTool vendors json in a git submodule; a plain shallow clone
     * fails on \"nlohmann/json.hpp: No such file or directory\". The clone
     * must recurse submodules, or the build is another guaranteed failure.
     */
    @Test
    fun theGodotPckToolCloneRecursesSubmodules() {
        val godot = byId("godot")
        assertTrue(
            "the clone must recurse submodules",
            godot.build.contains("--recurse-submodules"),
        )
    }

    /**
     * vgmstream's cmake defaults the Audacious plugin, the vgmstream123
     * player, FFmpeg, Speex and CELT ON. The plugins need headers a phone
     * rootfs has not got, and the codec libraries build themselves with
     * autoconf. The build died at configure time with only "Configuring
     * incomplete, errors occurred!" — the flags are the whole fix, verified by
     * building the CLI end to end.
     */
    @Test
    fun vgmstreamBuildsOnlyTheCli() {
        val vgm = byId("vgmstream")
        for (flag in listOf(
            "-DBUILD_AUDACIOUS=OFF",
            "-DBUILD_V123=OFF",
            "-DUSE_FFMPEG=OFF",
            "-DUSE_SPEEX=OFF",
            "-DUSE_CELT=OFF",
        )) {
            assertTrue("vgmstream must pass $flag", vgm.build.contains(flag))
        }
        assertTrue("the CLI itself must stay on", vgm.build.contains("-DBUILD_CLI=ON"))
    }

    /**
     * GodotPckTool hardcodes `-fuse-ld=gold`, and Debian 13 dropped the gold
     * linker: the link fails with \"collect2: fatal error: cannot find 'ld'\",
     * which reads like missing binutils and is not. The flag must be stripped.
     */
    @Test
    fun theGoldLinkerRequirementIsStripped() {
        val godot = byId("godot")
        assertTrue(
            "the hardcoded gold linker must be removed before building",
            godot.build.contains("s/ -fuse-ld=gold//"),
        )
        // And the build route retries once for any tree that still asks for a
        // linker this distro does not ship.
        val source = readSource("app/src/main/kotlin/com/altaris/linux/core/rea/ReaAssetTools.kt")
        assertTrue(
            "the build route must retry when the linker is missing",
            source.contains("the build asked for a linker this distro does not ship"),
        )
        // binutils provides ld and is part of the toolchain now.
        assertTrue(
            "binutils must be in the build toolchain",
            source.contains("\"binutils\""),
        )
    }

    /**
     * A failed build must say why.
     *
     * \"did not produce X\" was printed while the actual cause — a missing
     * header, a missing linker, a cmake error — scrolled past in the log.
     */
    @Test
    fun aFailedBuildNamesItsCause() {
        val source = readSource("app/src/main/kotlin/com/altaris/linux/core/rea/ReaAssetTools.kt")
        assertTrue(
            "the build route must report a reason",
            source.contains("buildFailureReason("),
        )
        val fn = source.substringAfter("private fun buildFailureReason(")
            .substringBefore("/** Mark one tool installed")
        assertTrue("must recognise a missing linker", fn.contains("cannot find"))
        assertTrue("must recognise a missing cmake package", fn.contains("Could NOT find"))
        assertTrue("must recognise a missing header", fn.contains("fatal error"))
    }

    /**
     * Every source build's toolchain must include pkg-config: vgmstream's
     * cmake died with \"Could NOT find PkgConfig\" on a minimal rootfs.
     */
    @Test
    fun theBuildToolchainIncludesPkgConfig() {
        val source = readSource("app/src/main/kotlin/com/altaris/linux/core/rea/ReaAssetTools.kt")
        val build = source.substringAfter("// 4. Build from source")
        assertTrue("apt must get pkg-config", build.contains("\"pkg-config\""))
        assertTrue("zlib must come along", build.contains("zlib1g-dev"))
        // A source build needs Python headers for pip-built siblings.
        assertTrue("apt must get python3-dev", build.contains("python3-dev"))
    }

    /**
     * The Other tab used to launch installs on the screen's own
     * rememberCoroutineScope(), so leaving the tab cancelled the build and
     * logged \"The coroutine scope left the composition\". Installs now belong
     * to the app.
     */
    @Test
    fun theOtherTabDoesNotLaunchInstallsOnTheScreenScope() {
        val screen = readSource("app/src/main/kotlin/com/altaris/linux/ui/screens/ReaScreen.kt")
        val other = screen.substringAfter("private fun OtherTab(")
        assertFalse(
            "installs must not run on the composition scope",
            other.contains("rememberCoroutineScope()"),
        )
        assertTrue("install-all runs app-owned", other.contains("ReaAssetTools.installAllAsync("))
        assertTrue("one-tool install runs app-owned", other.contains("ReaAssetTools.installOneAsync("))
        val debugger = screen.substringAfter("private fun DebuggerTab(")
        assertTrue(
            "the frida install belongs to the app too",
            debugger.contains("ReaDebugger.installFridaAsync("),
        )
        assertTrue(
            "the gadget download belongs to the app too",
            debugger.contains("ReaDebugger.prepareGadgetAsync("),
        )
    }

    /** A .tar.xz must install xz on the fly, not fail with \"launcher missing\". */
    @Test
    fun tarXzInstallsXzOnTheFly() {
        val script = readAsset("scripts/rea/install-archive.sh")
        val txz = script.indexOf("*.tar.xz|*.txz)")
        assertTrue("the tar.xz branch must exist", txz >= 0)
        val branch = script.substring(txz)
        assertTrue(
            "xz must be ensured before extraction",
            branch.contains("xz-utils") || branch.contains("xz-\"e") || branch.contains("pkg_run dnf install -y xz") || branch.contains("pkg_run apk add --no-cache xz"),
        )
        assertTrue(
            "and must be installed through the lock helper",
            branch.contains("pkg_run"),
        )
    }

    private fun readAsset(path: String): String {
        val candidates = listOf(
            "app/src/main/assets/$path",
            "src/main/assets/$path",
            "../app/src/main/assets/$path",
        )
        for (candidate in candidates) {
            val file = java.io.File(candidate)
            if (file.isFile) return file.readText()
        }
        throw AssertionError("asset not found: $path")
    }

    private fun readSource(path: String): String {
        val candidates = listOf(path, "../$path", "../../$path")
        for (candidate in candidates) {
            val file = java.io.File(candidate)
            if (file.isFile) return file.readText()
        }
        throw AssertionError("source not found: $path")
    }
}
