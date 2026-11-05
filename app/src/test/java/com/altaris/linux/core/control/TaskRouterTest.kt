package com.altaris.linux.core.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The refusal fix, tested at the decision layer.
 *
 * Every case here is a request that used to be answered with a refusal or with
 * the wrong tool. The classifier is a pure function, so this runs without a
 * device — which matters, because the bug was in the decision, not the device.
 */
class TaskRouterTest {

    // ------------------------------------------------------- classification

    /**
     * A game is an app.
     *
     * This is the reported failure: "open the game and navigate to the shop"
     * was classified as autonomous gameplay and refused. The word *game* is
     * incidental; the verb is "navigate".
     */
    @Test
    fun navigationWinsEvenWhenTheTargetIsAGame() {
        for (request in listOf(
            "open the game and navigate to the shop",
            "open the app and play a level for me",
            "launch the game and go to settings",
            "start the app, then move to the inventory screen",
        )) {
            val plan = TaskRouter.classify(request)
            assertEquals(request, TaskRouter.Kind.NAVIGATION, plan.kind)
            assertFalse("must not refuse: $request", plan.blocked)
        }
    }

    /** The incidental word is recorded, and still not used to classify. */
    @Test
    fun incidentalWordsAreSignalsNotClasses() {
        val plan = TaskRouter.classify("open the game and go to the shop")
        assertTrue(TaskRouter.mentionsGame("open the game and go to the shop"))
        assertEquals(TaskRouter.Kind.NAVIGATION, plan.kind)
        assertTrue(
            "the signal should be recorded for audit",
            plan.signals.any { it.startsWith("incidental:") },
        )
    }

    /** The twelve kinds the brief names all have a distinct route. */
    @Test
    fun everyNamedTaskKindIsReachable() {
        val cases = mapOf(
            "open the app" to TaskRouter.Kind.NAVIGATION,
            "what is on screen" to TaskRouter.Kind.INSPECTION,
            "tap the shop icon" to TaskRouter.Kind.UI_INTERACTION,
            "tap start and verify the result" to TaskRouter.Kind.UI_TEST,
            "test the flow end to end" to TaskRouter.Kind.FUNCTIONAL_TEST,
            "inspect the APK" to TaskRouter.Kind.APK_ANALYSIS,
            "find the class that handles the button" to TaskRouter.Kind.STATIC_RE,
            "observe the method at runtime" to TaskRouter.Kind.RUNTIME_DEBUG,
            "use frida to hook the method" to TaskRouter.Kind.FRIDA_INSTRUMENTATION,
            "install the apk as a clone and test it" to TaskRouter.Kind.CLONE_TEST,
            "find out why this button does not work" to TaskRouter.Kind.BEHAVIOUR,
            "read the logs" to TaskRouter.Kind.LOG_ANALYSIS,
            "run the tests in altaris source" to TaskRouter.Kind.SOURCE_DEV,
        )
        for ((request, want) in cases) {
            assertEquals(request, want, TaskRouter.classify(request).kind)
        }
    }

    /**
     * "tap X and verify Y" is a test, not a tap.
     *
     * The first implementation scored it as ui_interaction because "tap "
     * matched first, which would have dropped the verification step — the
     * exact omission this router exists to prevent.
     */
    @Test
    fun aVerificationVerbOutranksTheActionVerb() {
        val plan = TaskRouter.classify("tap the start button and verify the result")
        assertEquals(TaskRouter.Kind.UI_TEST, plan.kind)
        assertTrue(
            "the sequence must include a state check after the tap",
            plan.steps.count { it.tool == "altaris_ui_tree" } >= 2,
        )
    }

    /** Every plan names real tools from this app. */
    @Test
    fun everyStepNamesAToolThatExists() {
        // Use the shared resolver: Gradle runs with `app/` as the working
        // directory, so a bare "app/src/..." path does not resolve there and
        // the assertion would fail for a tool that is in fact declared.
        val text = readSource("app/src/main/kotlin/com/altaris/linux/core/control/McpProtocol.kt")
        val declared = Regex("\"altaris_[a-z_]+\"").findAll(text).map { it.value }.toSet()

        val kinds = TaskRouter.Kind.entries
        for (kind in kinds) {
            val plan = TaskRouter.classify(sampleFor(kind))
            assertEquals(kind, plan.kind)
            for (step in plan.steps) {
                assertTrue(
                    "step ${step.tool} in ${kind.label} is not a declared tool",
                    step.tool in declared,
                )
            }
        }
    }

    /** A request that maps to each kind, used by the coverage test above. */
    private fun sampleFor(kind: TaskRouter.Kind): String = when (kind) {
        TaskRouter.Kind.NAVIGATION -> "open the app"
        TaskRouter.Kind.INSPECTION -> "what is on screen"
        TaskRouter.Kind.UI_INTERACTION -> "tap the shop icon"
        TaskRouter.Kind.UI_TEST -> "tap start and verify the result"
        TaskRouter.Kind.FUNCTIONAL_TEST -> "test the flow end to end"
        TaskRouter.Kind.APK_ANALYSIS -> "inspect the APK"
        TaskRouter.Kind.STATIC_RE -> "find the class that handles the button"
        TaskRouter.Kind.RUNTIME_DEBUG -> "observe the method at runtime"
        TaskRouter.Kind.FRIDA_INSTRUMENTATION -> "use frida to hook the method"
        TaskRouter.Kind.CLONE_TEST -> "install the apk as a clone and test it"
        TaskRouter.Kind.BEHAVIOUR -> "find out why this button does not work"
        TaskRouter.Kind.LOG_ANALYSIS -> "read the logs"
        TaskRouter.Kind.SOURCE_DEV -> "run the tests in altaris source"
        TaskRouter.Kind.MULTI_STEP -> "open the app"
    }

    // ------------------------------------------------------------- targets

    @Test
    fun targetsAreIdentifiedFromTheRequest() {
        assertEquals(
            TaskRouter.Target.ARTIFACT,
            TaskRouter.classify("inspect the APK at /sdcard/Download/app.apk").target,
        )
        assertEquals(
            TaskRouter.Target.CLONE,
            TaskRouter.classify("install the apk as a clone and test it").target,
        )
        assertEquals(
            TaskRouter.Target.PROJECT,
            TaskRouter.classify("run the tests in altaris source").target,
        )
        assertEquals(
            TaskRouter.Target.PHYSICAL,
            TaskRouter.classify("open the app").target,
        )
    }

    @Test
    fun aPackageNameIsExtractedWhenPresent() {
        val plan = TaskRouter.classify("open com.example.game and go to the shop")
        assertEquals("com.example.game", plan.packageName)
        assertNull(TaskRouter.classify("open the app").packageName)
    }

    // -------------------------------------------------- no false refusals

    /**
     * Nothing in the supported set is refused.
     *
     * The old behaviour was to answer "I cannot do that" for requests the
     * tools handle. A plan may be *degraded* (a step optional) but never
     * blocked unless no tool covers the work.
     */
    @Test
    fun supportedRequestsAreNeverRefused() {
        val requests = listOf(
            "open the app",
            "navigate to the shop",
            "what is on screen",
            "take a screenshot",
            "tap the buy button and verify it opens the dialog",
            "inspect the apk",
            "find the class for the login button",
            "read the logs for the crash",
            "install the apk as a clone and test it",
            "use frida to hook the login method",
            "find out why the button does nothing",
            "run the tests in altaris source",
        )
        for (request in requests) {
            val plan = TaskRouter.classify(request)
            assertFalse("must not refuse: $request", plan.blocked)
            assertTrue("must have steps: $request", plan.steps.isNotEmpty())
        }
    }

    /**
     * A Frida task is degraded, not blocked, when Frida is missing.
     *
     * The brief is explicit: an investigation must continue without an
     * optional backend. The plan records the dependency so the agent can say
     * what it skipped, and still proceeds.
     */
    @Test
    fun aFridaTaskIsDegradedNotBlocked() {
        val plan = TaskRouter.classify("use frida to hook the login method")
        assertFalse(plan.blocked)
        assertTrue("must record the Frida dependency", plan.degradedBy.contains("frida"))
        // Every Frida step is optional, so the plan survives its absence.
        assertTrue(
            "Frida steps must be marked optional",
            plan.steps.filter { it.tool.contains("frida") }.all { it.optional },
        )
    }

    /** A physical inspection degrades gracefully when capture is unavailable. */
    @Test
    fun physicalInspectionDegradesWhenScreenshotIsUnavailable() {
        val plan = TaskRouter.classify("what is on screen")
        assertEquals(TaskRouter.Kind.INSPECTION, plan.kind)
        assertTrue(plan.degradedBy.contains("device_screenshot"))
        val shot = plan.steps.first { it.tool == "altaris_device_screenshot" }
        assertTrue("the screenshot step must be optional", shot.optional)
        // The tree step is the one that must never be optional.
        val tree = plan.steps.first { it.tool == "altaris_ui_tree" }
        assertFalse(tree.optional)
    }

    /** The classification never needs a network or a device to decide. */
    @Test
    fun classificationIsPure() {
        val a = TaskRouter.classify("open the app")
        val b = TaskRouter.classify("open the app")
        assertEquals(a.kind, b.kind)
        assertEquals(a.target, b.target)
        assertEquals(a.steps.map { it.tool }, b.steps.map { it.tool })
    }

    // ----------------------------------------------------- source wiring

    /** The plan tool must be advertised and handled. */
    @Test
    fun thePlanToolIsAdvertisedAndHandled() {
        val text = readSource("app/src/main/kotlin/com/altaris/linux/core/control/McpProtocol.kt")
        assertTrue("must advertise altaris_plan", text.contains("\"altaris_plan\""))
        assertTrue("must route it", text.contains("\"altaris_plan\" ->"))
        assertTrue("must use the router", text.contains("TaskRouter.classify("))
    }

    /** The instructions must tell the agent to plan before refusing. */
    @Test
    fun theInstructionsRequirePlanningBeforeRefusing() {
        val text = readSource("app/src/main/kotlin/com/altaris/linux/core/control/McpProtocol.kt")
        val flat = text.replace(Regex("\\s+"), " ")
        assertTrue(
            "must tell the agent to plan",
            flat.contains("altaris_plan") && flat.contains("Classify the task before you act"),
        )
        assertTrue("must name the game trap", flat.contains("A game is an app"))
        assertTrue(
            "must forbid the unreachable-device claim",
            flat.contains("Do not claim the physical device is unreachable"),
        )
        assertTrue(
            "must say a missing capability is not a failed task",
            flat.contains("A missing capability is not a failed task"),
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
