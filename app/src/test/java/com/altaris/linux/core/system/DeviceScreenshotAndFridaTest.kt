package com.altaris.linux.core.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The physical screenshot path and the Frida Node binding.
 *
 * The screenshot test exercises the real PNG parser against a real PNG header,
 * because that is the part that decides whether the caller gets width/height or
 * a silent zero. The Frida test pins the installer behaviour that was actually
 * broken: `npm install frida` exits 0 with no native binding, so an installer
 * that trusts the exit code reports success and REA fails later.
 */
class DeviceScreenshotAndFridaTest {

    // ------------------------------------------------------------ PNG parser

    /** A 24-byte PNG header with a chosen width and height. */
    private fun pngHeader(width: Int, height: Int): ByteArray {
        val out = ByteArray(24)
        out[0] = 0x89.toByte(); out[1] = 0x50; out[2] = 0x4E; out[3] = 0x47
        out[4] = 0x0D; out[5] = 0x0A; out[6] = 0x1A; out[7] = 0x0A
        // IHDR length = 13 at offset 8..11, "IHDR" at 12..15.
        out[8] = 0; out[9] = 0; out[10] = 0; out[11] = 13
        out[12] = 'I'.code.toByte(); out[13] = 'H'.code.toByte()
        out[14] = 'D'.code.toByte(); out[15] = 'R'.code.toByte()
        fun put(at: Int, v: Int) {
            out[at] = ((v shr 24) and 0xFF).toByte()
            out[at + 1] = ((v shr 16) and 0xFF).toByte()
            out[at + 2] = ((v shr 8) and 0xFF).toByte()
            out[at + 3] = (v and 0xFF).toByte()
        }
        put(16, width)
        put(20, height)
        return out
    }

    @Test
    fun pngDimensionsAreReadFromTheHeader() {
        val file = File.createTempFile("shot", ".png")
        try {
            file.writeBytes(pngHeader(1080, 2400))
            val size = DeviceScreenshot.pngSize(file)
            assertEquals(1080 to 2400, size)
        } finally {
            file.delete()
        }
    }

    @Test
    fun aNonPngIsRejectedRatherThanGuessed() {
        val file = File.createTempFile("notpng", ".png")
        try {
            file.writeBytes(ByteArray(24) { 0x42 })
            assertNull("a file that is not a PNG must not report a size", DeviceScreenshot.pngSize(file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun aTruncatedFileIsRejected() {
        val file = File.createTempFile("short", ".png")
        try {
            file.writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))
            assertNull(DeviceScreenshot.pngSize(file))
        } finally {
            file.delete()
        }
    }

    // ------------------------------------------------------- source wiring

    /**
     * The capture must go through accessibility first.
     *
     * The clone engine can only see a clone's virtual display, which is why
     * `altaris_screenshot` answered "No display frame" for the physical phone.
     */
    @Test
    fun thePhysicalCaptureUsesAccessibilityFirst() {
        val text = readSource("app/src/main/kotlin/com/altaris/linux/core/system/DeviceScreenshot.kt")
        val a11y = text.indexOf("A11yBridge.screenshot(")
        val shizuku = text.indexOf("captureWithShizuku(")
        assertTrue("must have an accessibility path", a11y > 0)
        assertTrue("must have a Shizuku fallback", shizuku > 0)
        assertTrue("accessibility must be tried first", a11y < shizuku)
        assertTrue(
            "must gate accessibility on API 30",
            text.contains("Build.VERSION_CODES.R"),
        )
    }

    /** FLAG_SECURE must be reported, never worked around. */
    @Test
    fun aSecureWindowIsReportedNotBypassed() {
        val text = readSource("app/src/main/kotlin/com/altaris/linux/core/system/DeviceScreenshot.kt")
        assertTrue("must recognise a secure window", text.contains("FLAG_SECURE"))
        // The sentence is built from concatenated literals, so assert on the
        // parts that carry the meaning rather than on one long phrase.
        assertTrue(
            "must say it will not attempt a bypass",
            text.contains("attempt to bypass"),
        )
        assertTrue(
            "must state that Android does not allow it",
            text.contains("Android does not allow"),
        )
    }

    /** The result is a file path, not a base64 blob. */
    @Test
    fun theResultIsAFilePath() {
        val text = readSource("app/src/main/kotlin/com/altaris/linux/core/system/DeviceScreenshot.kt")
        assertTrue("must return a file", text.contains("\"file\""))
        assertTrue("must return dimensions", text.contains("\"width\""))
        assertTrue("must return the backend", text.contains("\"backend\""))
        assertTrue("must return a timestamp", text.contains("\"at\""))
        assertTrue(
            "must not base64-encode the image",
            !text.contains("Base64"),
        )
    }

    /** The service must actually expose a screenshot method. */
    @Test
    fun theAccessibilityServiceExposesScreenshot() {
        val service = readSource(
            "app/src/main/kotlin/com/altaris/linux/core/a11y/AltarisAccessibilityService.kt"
        )
        assertTrue("must use takeScreenshot", service.contains("takeScreenshot("))
        assertTrue("must handle the callback", service.contains("TakeScreenshotCallback"))
        assertTrue("must close the buffer", service.contains("hardwareBuffer.close()"))
        assertTrue("must recycle the bitmap", service.contains("bitmap.recycle()"))
        // And the manifest capability it depends on.
        val config = readSource("app/src/main/res/xml/altaris_accessibility_service.xml")
        assertTrue(
            "the service config must declare screenshot capability",
            config.contains("canTakeScreenshot=\"true\""),
        )
    }

    // -------------------------------------------------------- frida install

    /**
     * The installer must verify the import, not the exit code.
     *
     * Reproduced on linux-arm64: `npm install frida@17.23.2` exits 0, unpacks
     * the package, and leaves no `frida_binding.node`. Trusting the exit code
     * is why a tree could look installed and fail at `import('frida')`.
     */
    @Test
    fun theInstallerVerifiesTheBindingNotTheExitCode() {
        val script = readSource("app/src/main/assets/scripts/rea/rea-frida-module.sh")
        assertTrue("must force optional dependencies", script.contains("--include=optional"))
        assertTrue("must force a reinstall so the script runs", script.contains("--force"))
        assertTrue(
            "must check for the native binding itself",
            script.contains("frida_binding.node"),
        )
        assertTrue(
            "must verify by importing",
            script.contains("import('frida')"),
        )
        assertTrue(
            "must run the prebuild step directly when npm swallowed it",
            script.contains("prebuild-install"),
        )
        assertTrue(
            "must fall back to a source build",
            script.contains("build-essential") && script.contains("gcc"),
        )
    }

    /** A failure must be a diagnostic, not a one-line warning. */
    @Test
    fun aFailedInstallExplainsItself() {
        val script = readSource("app/src/main/assets/scripts/rea/rea-frida-module.sh")
        assertTrue("must report the expected version", script.contains("expected: frida@"))
        assertTrue("must report the node version", script.contains("node:"))
        assertTrue("must report the platform", script.contains("platform:"))
        assertTrue("must report npm omit", script.contains("npm omit"))
        assertTrue("must show the import error", script.contains("import error"))
        assertTrue(
            "must say static tools still work",
            script.contains("static tools are unaffected"),
        )
        assertTrue(
            "must name the Gadget alternative",
            script.contains("altaris_gadget_prepare"),
        )
    }

    /** There must be a non-installing diagnostic mode. */
    @Test
    fun theInstallerHasADiagnoseMode() {
        val script = readSource("app/src/main/assets/scripts/rea/rea-frida-module.sh")
        assertTrue("must accept --diagnose", script.contains("--diagnose"))
        // And it must not install in that mode.
        val diagnose = script.substringAfter("if [ \"$MODE\" = \"--diagnose\" ]")
            .substringBefore("exit 0")
        assertTrue("diagnose must not run npm install", !diagnose.contains("install_once"))
    }

    /** The Kotlin side must probe the binding, not the package directory. */
    @Test
    fun theRuntimeProbeChecksImportability() {
        val manager = readSource("app/src/main/kotlin/com/altaris/linux/core/rea/ReaManager.kt")
        assertTrue("must define the probe result", manager.contains("data class FridaNode"))
        assertTrue(
            "must test the import",
            manager.contains("import('frida')"),
        )
        assertTrue(
            "must distinguish installed from importable",
            manager.contains("val importable") && manager.contains("val installed"),
        )
    }

    // ------------------------------------------------------------ apktool

    /**
     * Apktool must be the pinned jar everywhere.
     *
     * Every distro package is older than 2.9.2 (CVE-2024-21633) and Debian's is
     * a "dirty" build whose version does not identify its source.
     */
    @Test
    fun apktoolIsPinnedAndVerified() {
        val catalog = readSource("app/src/main/kotlin/com/altaris/linux/core/rea/ReaCatalog.kt")
        assertTrue("must pin 3.0.3", catalog.contains("apktool_3.0.3.jar"))
        assertTrue(
            "must verify the digest",
            catalog.contains("dbf930b076c6b9be08d57c449cacefc3bdd6b71ebd59b3066fc0e1f5b14f9423"),
        )
        // No package-manager variant may install an old distro build.
        val apktool = catalog.substringAfter("id = \"apktool\"")
            .substringBefore("id = \"aapt\"")
        for (manager in listOf("apt", "apk", "dnf", "pacman")) {
            assertTrue(
                "the $manager variant must be empty so the jar is used",
                apktool.contains("\"$manager\" to emptyList()"),
            )
        }
        assertTrue(
            "must record the CVE that motivates the pin",
            catalog.contains("CVE-2024-21633"),
        )
    }

    /** The archive installer must honour a pinned digest. */
    @Test
    fun theArchiveInstallerVerifiesTheDigest() {
        val script = readSource("app/src/main/assets/scripts/rea/install-archive.sh")
        assertTrue("must accept a digest argument", script.contains("EXPECTED_SHA"))
        assertTrue("must compare", script.contains("SHA-256 mismatch"))
        assertTrue("must refuse before unpacking", script.contains("rm -f \"$TMP\""))
    }

    // ------------------------------------------------------------- doctor

    /** The doctor must exist, be advertised, and use the eight labels. */
    @Test
    fun theDoctorReportsEveryCapabilityWithALabel() {
        val doctor = readSource("app/src/main/kotlin/com/altaris/linux/core/control/AltarisDoctor.kt")
        for (label in listOf(
            "AVAILABLE_AND_TESTED", "AVAILABLE_NOT_TESTED", "MISSING_DEPENDENCY",
            "PERMISSION_REQUIRED", "TARGET_UNAVAILABLE", "TRANSPORT_UNAVAILABLE",
            "UNSUPPORTED", "TEMPORARY_FAILURE",
        )) {
            assertTrue("must define $label", doctor.contains(label))
        }
        val protocol = readSource("app/src/main/kotlin/com/altaris/linux/core/control/McpProtocol.kt")
        assertTrue("must advertise altaris_doctor", protocol.contains("\"altaris_doctor\""))
        assertTrue("must handle it", protocol.contains("\"altaris_doctor\" ->"))
    }

    /** Frida's three parts must be reported separately. */
    @Test
    fun theDoctorSeparatesTheThreeFridaParts() {
        val doctor = readSource("app/src/main/kotlin/com/altaris/linux/core/control/AltarisDoctor.kt")
        assertTrue("must report the server", doctor.contains("server running"))
        assertTrue("must report the CLI", doctor.contains("CLI present"))
        assertTrue("must report the Node binding", doctor.contains("Node binding"))
        assertTrue(
            "must explain that the binding is what hook tools need",
            doctor.contains("optional npm dependency") ||
                doctor.contains("optional") && doctor.contains("binding"),
        )
    }

    private fun readSource(path: String): String {
        for (candidate in listOf(path, "../$path", "../../$path")) {
            val file = File(candidate)
            if (file.isFile) return file.readText()
        }
        throw AssertionError("source not found: $path")
    }
}
