package com.altaris.linux.core.control

/**
 * Classifies a request and picks the tools that actually answer it.
 *
 * The refusals this replaces were not a tooling gap: every capability was
 * present. What was missing was a decision procedure. An agent asked to "open
 * the game and go to the shop" saw the word *game*, reached for the nearest
 * label it had — autonomous gameplay — and refused as unsafe, when the request
 * was three taps and a screenshot. The classification must come from the
 * **verb and the intended outcome**, never from what kind of app the target
 * happens to be.
 *
 * That is the rule this object enforces:
 *
 *  - the target's identity is one input, and it never decides the task kind;
 *  - an explicit navigation verb outranks any incidental word like "game";
 *  - a request is only ever "cannot be done" when no listed tool covers it,
 *    and then the reason names the missing capability.
 *
 * It is a pure function over the request text so it can be tested without a
 * device, and it is served to agents through `altaris_plan`.
 */
object TaskRouter {

    /** What the user is asking for, in tool terms. */
    enum class Kind(
        val label: String,
        /** The one-line rule an agent should follow for this kind. */
        val rule: String,
    ) {
        NAVIGATION(
            "navigation",
            "Use the accessibility hierarchy and semantic taps. Open the app, move " +
                "through screens, confirm you arrived. No Frida, no APK analysis.",
        ),
        INSPECTION(
            "inspection",
            "Read the current UI: altaris_ui_tree for structure, altaris_device_screenshot " +
                "for appearance. Do not interact.",
        ),
        UI_INTERACTION(
            "ui_interaction",
            "Act on one element, then read the hierarchy again to confirm the state changed.",
        ),
        UI_TEST(
            "ui_test",
            "Perform the interaction and verify the resulting state. A tap without a " +
                "verification step is not a test.",
        ),
        FUNCTIONAL_TEST(
            "functional_test",
            "Drive the app through the scenario, capture state at each step, and report " +
                "what passed and what did not.",
        ),
        APK_ANALYSIS(
            "apk_analysis",
            "Static: altaris_rea_clone_apk for the files, then REA's Android lane for " +
                "manifest, resources and classes. No UI interaction.",
        ),
        STATIC_RE(
            "static_reverse_engineering",
            "Static: identify the artifact, then use REA. Prefer symbols, sections and " +
                "targeted decompilation over whole-binary work.",
        ),
        RUNTIME_DEBUG(
            "runtime_debug",
            "Dynamic: altaris_debug_attach then altaris_debug_command. Clones only.",
        ),
        FRIDA_INSTRUMENTATION(
            "frida_instrumentation",
            "Dynamic instrumentation: only when the question needs a value at runtime " +
                "that logs and static analysis cannot answer.",
        ),
        CLONE_TEST(
            "clone_test",
            "Install or launch a clone, drive it, capture evidence, then stop it. " +
                "Uninstall what you installed.",
        ),
        BEHAVIOUR(
            "behaviour_investigation",
            "Observe first: reproduce it, read logs, inspect the APK, then instrument " +
                "only if the cause is still unknown.",
        ),
        LOG_ANALYSIS(
            "log_analysis",
            "Read altaris_logs for the package. Logs are evidence; quote them.",
        ),
        SOURCE_DEV(
            "source_development",
            "This is the Altaris project itself: edit the source and run the tests.",
        ),
        MULTI_STEP(
            "multi_step",
            "Plan the steps, keep the evidence from each, and continue past an " +
                "unavailable optional backend.",
        ),
    }

    /** Where the work happens. */
    enum class Target(val label: String) {
        PHYSICAL("physical device"),
        CLONE("clone"),
        ARTIFACT("file / APK"),
        PROJECT("Altaris source"),
        UNKNOWN("unknown"),
    }

    /** One step in the recommended sequence. */
    data class Step(
        val tool: String,
        val why: String,
        /** True when the step needs a capability that may be absent. */
        val optional: Boolean = false,
    )

    /** The classification and the sequence that carries it out. */
    data class Plan(
        val kind: Kind,
        val target: Target,
        val packageName: String?,
        val steps: List<Step>,
        /** Signals found in the request, for auditability. */
        val signals: List<String>,
        /**
         * Set only when no tool covers the request. Empty for everything the
         * app can do — that is the point.
         */
        val blockedReason: String = "",
        /** Capabilities this plan can proceed without. */
        val degradedBy: List<String> = emptyList(),
    ) {
        val blocked: Boolean get() = blockedReason.isNotBlank()
    }

    // ------------------------------------------------------------- signals

    /**
     * Intent verbs, in priority order.
     *
     * Order matters and is the whole fix. "Open the game and tap the shop"
     * contains *game* and *tap*; navigation must win because that is the
     * outcome the user asked for. Each entry is checked top to bottom and the
     * first hit decides, so the list is written most-specific-first.
     */
    private val INTENTS: List<Triple<Kind, Int, List<String>>> = listOf(
        // Explicit instrument/attach wording is unambiguous.
        Triple(
            Kind.FRIDA_INSTRUMENTATION, 100,
            listOf(
                "frida", "hook the method", "hook a method", "instrument", "intercept",
                "patch at runtime", "trace the call", "spy on",
            ),
        ),
        Triple(
            Kind.RUNTIME_DEBUG, 95,
            listOf(
                "breakpoint", "debug", "attach to", "at runtime", "watch the value",
                "register", "stack trace", "gdb",
            ),
        ),
        Triple(
            Kind.LOG_ANALYSIS, 90,
            listOf("logcat", "logs", "log output", "crash log", "error log", "why did it crash"),
        ),
        Triple(
            Kind.UI_TEST, 85,
            listOf(
                // Verification verbs. These outrank the bare action verbs
                // below: "tap X and verify Y" is a test, not a tap. The first
                // version scored it as ui_interaction because "tap " matched
                // first, which would have skipped the verification step — the
                // exact failure this router exists to prevent.
                "verify", "verifies", "verify that", "verifying",
                "does the button work", "test the button",
                "check that tapping", "make sure it", "confirm it", "test that",
                "should open", "should show", "should navigate", "and check",
                "then check", "ensure it",
            ),
        ),
        Triple(
            Kind.FUNCTIONAL_TEST, 82,
            listOf(
                "test the flow", "test this feature", "end to end", "regression",
                "run the scenario", "test the app",
            ),
        ),
        Triple(
            Kind.CLONE_TEST, 80,
            listOf(
                "clone", "cloned", "install the apk", "install this apk", "test apk",
                "sandbox copy", "compare the clone",
            ),
        ),
        Triple(
            Kind.BEHAVIOUR, 75,
            listOf(
                "why does", "why is", "why does it", "find out why", "investigate",
                "what happens when", "figure out why", "does not work", "doesn't work",
                "not working", "broken",
            ),
        ),
        Triple(
            Kind.APK_ANALYSIS, 72,
            listOf(
                "inspect the apk", "analyse the apk", "analyze the apk", "decode resources",
                "manifest", "permissions of", "what permissions", "apk contents",
                "dex", "classes.dex", "decompile the apk",
            ),
        ),
        Triple(
            Kind.STATIC_RE, 70,
            listOf(
                "find the class", "find the method", "which class", "which method",
                "reverse engineer", "decompile", "ghidra", "disassemble", "the handler",
                "click handler", "source of the function", "what function",
            ),
        ),
        Triple(
            Kind.SOURCE_DEV, 65,
            listOf(
                "altaris source", "the project source", "fix the bug in altaris",
                "add a feature to altaris", "run the tests", "unit test",
            ),
        ),
        Triple(
            Kind.INSPECTION, 60,
            listOf(
                "what is on screen", "what does the screen show", "inspect the ui",
                "inspect the screen", "screenshot", "look at the screen", "what is visible",
                "read the screen",
            ),
        ),
        Triple(
            Kind.UI_INTERACTION, 55,
            listOf(
                "tap ", "click ", "press ", "swipe", "scroll", "type ", "enter text",
                "select ", "toggle ", "open the menu",
            ),
        ),
        Triple(
            Kind.NAVIGATION, 50,
            listOf(
                "open the app", "open app", "launch the app", "launch app", "navigate",
                "go to", "go back", "navigate to", "move to the", "reach the",
                "get to the", "open the", "start the app", "browse",
            ),
        ),
    )

    /**
     * Words that used to cause a refusal.
     *
     * Recorded as *signals*, never as the classification. A game is an app;
     * playing a level on request is navigation.
     */
    private val INCIDENTAL = listOf(
        "game", "gameplay", "play", "level", "autonomous", "bot", "farm",
        "automate", "grind", "cheat",
    )

    private val ARTIFACT_HINTS = listOf(
        ".apk", ".so", ".dex", ".elf", ".jar", ".zip", ".bin", ".dll", ".exe",
    )

    // ------------------------------------------------------------ classify

    /** Classify [request] into a kind, a target and a tool sequence. */
    fun classify(request: String): Plan {
        val text = request.lowercase()
        val signals = ArrayList<String>()

        val kind = INTENTS.firstOrNull { (_, _, words) ->
            words.any { text.contains(it) }
        }?.let { (k, _, words) ->
            signals.addAll(words.filter { text.contains(it) })
            k
        } ?: Kind.NAVIGATION

        val incidental = INCIDENTAL.filter { text.contains(it) }
        if (incidental.isNotEmpty()) {
            // Named so the agent can see it was noticed and deliberately not
            // used as the classification.
            signals.add("incidental:" + incidental.joinToString(","))
        }

        val target = when {
            kind == Kind.SOURCE_DEV -> Target.PROJECT
            ARTIFACT_HINTS.any { text.contains(it) } &&
                kind in setOf(Kind.APK_ANALYSIS, Kind.STATIC_RE) -> Target.ARTIFACT
            kind == Kind.APK_ANALYSIS || kind == Kind.STATIC_RE -> Target.ARTIFACT
            kind == Kind.CLONE_TEST -> Target.CLONE
            kind in setOf(Kind.RUNTIME_DEBUG, Kind.FRIDA_INSTRUMENTATION) -> Target.CLONE
            else -> Target.PHYSICAL
        }

        val pkg = extractPackage(request)
        val steps = stepsFor(kind, target)
        val degraded = ArrayList<String>()
        if (kind in setOf(Kind.RUNTIME_DEBUG, Kind.FRIDA_INSTRUMENTATION)) {
            degraded += "frida"
        }
        if (target == Target.PHYSICAL && kind == Kind.INSPECTION) {
            degraded += "device_screenshot"
        }

        return Plan(
            kind = kind,
            target = target,
            packageName = pkg,
            steps = steps,
            signals = signals,
            degradedBy = degraded,
        )
    }

    /**
     * The tool sequence for a kind.
     *
     * Every entry names a tool that exists in this app. Nothing here is
     * aspirational: if a step is listed, it can be called.
     */
    private fun stepsFor(kind: Kind, target: Target): List<Step> = when (kind) {
        Kind.NAVIGATION -> listOf(
            Step("altaris_clones", "is the target already a clone"),
            Step("altaris_launch_clone", "start it if it is not running"),
            Step("altaris_ui_tree", "find the element to move through"),
            Step("altaris_ui_tap_text", "act on it by its label, not coordinates"),
            Step("altaris_wait", "until the next screen's element is present"),
            Step("altaris_ui_tree", "confirm the screen changed"),
        )

        Kind.INSPECTION -> listOf(
            Step("altaris_ui_tree", "structure, labels and bounds"),
            Step("altaris_device_screenshot", "what it looks like", optional = true),
        )

        Kind.UI_INTERACTION -> listOf(
            Step("altaris_ui_tree", "capture the state before"),
            Step("altaris_ui_tap_text", "act on the named element"),
            Step("altaris_wait", "until the tap's result is on screen"),
            Step("altaris_ui_tree", "capture the state after"),
        )

        Kind.UI_TEST -> listOf(
            Step("altaris_ui_tree", "record the state before"),
            Step("altaris_ui_tap_text", "act on the named element"),
            Step("altaris_wait", "until the expected state is present"),
            Step("altaris_ui_tree", "verify the expected state changed"),
            Step("altaris_logs", "confirm no error was logged", optional = true),
        )

        Kind.FUNCTIONAL_TEST -> listOf(
            Step("altaris_clones", "identify the build under test"),
            Step("altaris_launch_clone", "start it"),
            Step("altaris_ui_tree", "state at each step"),
            Step("altaris_ui_tap_text", "drive the scenario"),
            Step("altaris_wait", "until each step's screen is ready"),
            Step("altaris_logs", "evidence for each step"),
            Step("altaris_stop_clone", "stop it when done"),
        )

        Kind.APK_ANALYSIS -> listOf(
            Step("altaris_rea_status", "is the analyzer up"),
            Step("altaris_rea_clone_apk", "the APK and native libraries, with paths"),
            Step("altaris_analyze", "open and identify it — takes the path"),
            Step("altaris_apk_info", "manifest: package, permissions, components"),
            Step("altaris_apk_search", "classes and strings matching a term"),
        )

        Kind.STATIC_RE -> listOf(
            Step("altaris_rea_status", "is the analyzer up"),
            Step("altaris_rea_clone_apk", "get the artifact path"),
            Step("altaris_analyze", "open and identify it — takes the path"),
            Step("altaris_rea_tools", "the catalog, for anything the above does not cover"),
            Step("altaris_rea_schema", "one tool's real shape before calling it"),
            Step("altaris_rea_call", "targeted decompilation"),
            Step("altaris_ghidra_analyze", "native code only, low mode", optional = true),
        )

        Kind.RUNTIME_DEBUG -> listOf(
            Step("altaris_debug_attach", "start the clone and attach"),
            Step("altaris_debug_command", "read registers, memory, a value"),
            Step("altaris_debug_detach", "leave the clone running"),
        )

        Kind.FRIDA_INSTRUMENTATION -> listOf(
            Step("altaris_frida_list", "the clones and their pids"),
            Step("altaris_frida_install", "start frida-server, if it is not running", optional = true),
            Step("altaris_frida_attach", "package or pid; a pid is resolved to its package"),
            Step("altaris_frida_run", "JavaScript in the clone's own runtime"),
            Step("altaris_frida_detach", "close the session"),
        )

        Kind.CLONE_TEST -> listOf(
            Step("altaris_apps", "what is installed to clone"),
            Step("altaris_install_apk", "install the APK as a clone"),
            Step("altaris_launch_clone", "start it on its own display"),
            Step("altaris_wait", "until the first screen is up"),
            Step("altaris_ui_tree", "inspect it"),
            Step("altaris_screenshot", "capture it"),
            Step("altaris_logs", "read its logs"),
            Step("altaris_stop_clone", "stop it"),
            Step("altaris_uninstall_clone", "remove what you installed"),
        )

        Kind.BEHAVIOUR -> listOf(
            Step("altaris_ui_tree", "reproduce and locate the element"),
            Step("altaris_ui_tap_text", "perform the action"),
            Step("altaris_wait", "until the app has reacted"),
            Step("altaris_ui_tree", "observe what actually happened"),
            Step("altaris_logs", "look for an exception or refusal"),
            Step("altaris_rea_clone_apk", "the APK, if the cause is in the code"),
            Step("altaris_rea_call", "targeted static analysis"),
            Step("altaris_debug_attach", "only if the cause is still unknown", optional = true),
        )

        Kind.LOG_ANALYSIS -> listOf(
            Step("altaris_clones", "confirm the package"),
            Step("altaris_logs", "read the recent lines"),
        )

        Kind.SOURCE_DEV -> listOf(
            Step("altaris_doctor", "what this build can do"),
        )

        Kind.MULTI_STEP -> listOf(
            Step("altaris_plan", "split the request into the steps above"),
            Step("altaris_ui_tree", "start from the real current state"),
        )
    }

    /** A package name in the request, if one was written. */
    private fun extractPackage(request: String): String? =
        Regex("\\b([a-z][a-z0-9_]*(?:\\.[a-z0-9_]+){2,})\\b")
            .find(request)
            ?.value
            ?.takeIf { it.count { c -> c == '.' } >= 2 }

    /**
     * True when the request names a game.
     *
     * Exposed so a caller can log that it saw the word and still routed the
     * request as navigation — the exact case that used to be misclassified.
     */
    fun mentionsGame(request: String): Boolean =
        INCIDENTAL.any { request.lowercase().contains(it) }
}
