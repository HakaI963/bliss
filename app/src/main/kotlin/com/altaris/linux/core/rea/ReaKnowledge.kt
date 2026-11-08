package com.altaris.linux.core.rea

/**
 * What an agent needs to know to use REA well — one guide per server.
 *
 * There are two MCP servers, and they expose different tools:
 *
 *   `altaris` (ControlServer, :47831) — `altaris_*`: lifecycle, clones, UI,
 *       screenshots, the debugger, and `altaris_rea_call` for reaching REA.
 *   `rea` (ReaBridge, :47832) — REA's own tools, under their **bare names**:
 *       `inspect_artifact`, `search_strings`, `inspect_analysis_view`, …
 *
 * The previous version had one guide, served by the `rea` bridge, that told the
 * agent to call `altaris_rea_tools`, `altaris_rea_schema` and `altaris_rea_call`
 * — none of which exist on that server. The agent read the guide, tried them,
 * failed, and then invented `rea_tools` and `rea_mcp_config_read`, which do not
 * exist either. A guide that names a tool its own server does not have is worse
 * than no guide: it teaches the agent to guess.
 *
 * So [REA_GUIDE] is served by the `rea` bridge and speaks only bare REA names,
 * and [ALTARIS_GUIDE] is written into the distro for the `altaris` server and
 * speaks only `altaris_*` names. Neither can name a tool the other lacks.
 *
 * A second correction: REA's **MCP** tools use underscores
 * (`inspect_artifact`), while its **CLI** commands use dashes
 * (`rea inspect-artifact`). The old guide mixed them, and 18 of the 23 names it
 * gave were CLI spellings that no MCP client would accept.
 */
object ReaKnowledge {

    /** Resource URI for the REA-native guide, served by the `rea` bridge. */
    const val URI = "rea://guide"

    /** Resource URI for the Altaris-side guide, written into the distro. */
    const val ALTARIS_URI = "altaris://rea-guide"

    // ------------------------------------------------------------- REA guide

    /**
     * The guide served by the `rea` MCP server.
     *
     * Every tool it names is a real entry in REA 6.8.0's `tools/list`, spelled
     * with underscores. Ordered as a working method: identify, choose the
     * family, then verify.
     */
    val REA_GUIDE: String = """
        # Working with REA

        You are connected to REA (Reverse Engineering Agents), an MCP server.
        It exposes analysis tools; you decide what to call and in what order. It
        has no model of its own.

        **For any binary, APK, DEX, native library, firmware or asset question,
        REA is the tool — not your own reading of the bytes.** Do not reason
        from a file name, do not shell out to strings/objdump/readelf when REA
        has a tool for it, and do not guess.

        ## 0. Finding a tool

        **There is no index tool on this server.** The tool catalog *is*
        `tools/list`: every tool REA has is advertised there with its input
        schema. Do not call `rea_tools`, `list_tools`, `help` or any other
        invented name — they do not exist, and the failure costs a round trip.

        Tool names use **underscores** everywhere. The dashed spelling belongs to
        REA's command-line tool, not to this server, and a client that sends it
        gets "unknown tool". If a name was rejected, retry it with underscores
        before concluding the capability is missing.

        ## 0b. Start an analysis

        REA analyses one open target at a time:

        1. `open_binary { "path": "<absolute path>" }` — opens the artifact and
           reports its format, SHA-256 and architecture. This is the call that
           takes a path.
        2. `inspect_artifact` — identification of the **already open** target:
           sections, entry point, notable metadata. It takes **no path**; passing
           one is rejected.
        3. `close_binary` when you are done. Its `snapshot_path` lets a later
           session resume without re-analysing.

        ## 0c. Arguments are objects, not names

        A nested argument is an object discriminated by a `kind` field, not a
        string. `source` and `view` are the common cases:

            // rejected: expected object
            { "source": "jadx", "view": "decompiled" }

            // accepted
            { "source": { "kind": "retained-evidence", "evidence_id": "ev_..." },
              "view":   { "kind": "summary" } }

        REA rejects unknown keys on nearly every tool, so do not add fields its
        schema does not list. Read the tool's `inputSchema` from `tools/list`
        before the first call.

        ### When a call is rejected, read the field list

        A shape mistake comes back as a structured reply, not REA's generic
        sentence. It carries:

            "problems": ["class_name: is required — a string",
                         "path: is not a recognised argument"]
            "required": ["class_name", ...]
            "example":  { ... }   <- a minimal call that its own schema accepts

        Copy `example`, fill in the real values, and call once more. The call
        was **not sent**, so nothing was analysed and nothing is running. Do not
        retry with a different guess: a second guess is a second round trip for
        the same information, which `required` already gave you.

        A missing required key and an unknown key look the same from REA's side
        (one generic line) but not here: `is required` means add it, `is not a
        recognised argument` means remove it. Both at once usually means the
        argument belongs to a different tool in the same family.

        ## 0d. Evidence ids: use the parent, not the receipt

        A summary reply carries two ids and they are not interchangeable:

            "evidence_id"                     <- the summary receipt itself
            "normalized_result": {
              "parent_evidence_id": "ev_..."  <- the record you can page
            }

        `inspect_analysis_view` rejects the receipt id with `unsupported_target`
        ("Parent Evidence operation inspect_analysis_view does not support
        analysis views"). Always page with `normalized_result.parent_evidence_id`.

        An `ev_...` id is valid only for the session that produced it. On
        `evidence_integrity_mismatch`, re-run the producer and take the parent id
        from the fresh reply, or pass the Evidence inline with
        `source: { "kind": "inline", "evidence": ... }`. Retrying the same id
        cannot succeed.

        ## 1. Identify the artifact first

        `inspect_artifact` for anything already open; `open_binary` to open
        something new. Do not guess a format from the file name — a `.bin` may be
        an ELF, a firmware image, an archive or a database.

        ## 2. Choose the family by artifact type

        **Android / JVM** — these need a jadx bridge jar; if REA answers
        `capability_unavailable` naming `REA_JADX_MCP_JAR`, the Altaris side can
        install it (`altaris_jadx_bridge_install` on the `altaris` server). REA
        does not ship or fetch the jar itself.
        - `inspect_android_package` — manifest, permissions, components.
        - `search_android_classes` — find a class by name.
        - `inspect_android_class`, `inspect_android_method` — what is inside one.
        - `decode_android_resources` — resources and the binary manifest.
        - `decompile_all`, `batch_decompile` — Java, and smali for exact bytecode.

        **Native (ELF / PE / Mach-O)**
        - `inspect_elf_notes` — build-id, ABI and toolchain notes.
        - `inspect_pe_imports`, `inspect_pe_load_config`, `inspect_pe_certificates`.
        - `inspect_go_binary`, `inspect_rust_binary` — build info and modules.
        - `inspect_with_objdump` — symbols and sections. Use this before a full
          decompile: it is far cheaper and often answers the question.

        **Firmware, archives, containers**
        - `inspect_artifact` then `extract_artifact`; `inspect_byte_entropy` tells
          you whether something is compressed or encrypted before you spend time
          on it.

        **Other formats**
        - `inspect_sqlite_database` (schema, then rows — do not read it as text),
          `inspect_apple_ips`, `inspect_devicetree`, `inspect_kernel_module`,
          `inspect_erlang_beam`, `inspect_python_pyc`,
          `analyze_javascript_application`.

        **Managed .NET**
        - `inspect_managed_artifact`, `inspect_managed_members`.

        ## 3. Decompile only when you need logic

        A decompile is expensive. Prefer, in this order:
        1. strings and symbols (`search_strings`, `list_strings`, `list_names`)
        2. sections and imports
        3. disassembly of the specific function (`read_function_instructions`)
        4. decompile of the specific function (`analyze_function`)
        5. whole-binary decompile (last resort)

        ## 4. Compare instead of eyeballing

        `compare_artifacts` reports matched bytes, differing bytes, total bytes
        and the ranges that differ. Do not compare two dumps by eye — you will
        miss offsets and cannot count bytes reliably. A difference in padding is
        not a behavioural difference; say which it is.

        ## 5. Report honestly

        - Cite the offset, section or function a claim comes from.
        - If a tool fails, read the error and try a different family; do not
          repeat the same failing call.
        - Never invent addresses, symbol names or file contents. If the evidence
          is not there, say it is unknown.

        ## 6. Runtime and device work

        REA's own Frida and ADB tools (`list_frida_devices`, `start_frida_session`,
        `list_adb_devices`, `capture_adb_screen`, …) operate on a device REA can
        reach. On Altaris the target is usually a **clone** on the same phone, and
        the `altaris` server has the clone, screenshot, UI and debugger tools that
        fit it. Use those for clone work; use REA for the artifact.

        `start_frida_session` takes a clone's package name or its pid; both
        work. Keep the target a clone (`com.altaris.apps.*`): the daemon runs as
        root and will attach to whatever it is given, including a system app.

        A `capability_unavailable` from the frida provider means the Node
        `frida` binding is missing, not that the target is wrong: the binding
        is an optional dependency npm can skip silently. Repair it from the
        `altaris` server's Frida repair action, which installs the binding and
        restarts REA so the provider picks it up. Do not conclude that Frida is
        unavailable because of that error, and do not try to install the npm
        module by hand.

        ## Reference material

        For a human, not required to use the tools:
        - Repository: github.com/morluto/rea
        - Site and guides: rea.tools, rea.tools/guides
    """.trimIndent()

    /**
     * The guide for the `altaris` server, written into the distro.
     *
     * Speaks only `altaris_*` names, because that is all that server exposes.
     * It explains how to reach REA *through* it and what to do when REA is the
     * wrong tool — the two things an agent gets wrong most.
     */
    val ALTARIS_GUIDE: String = """
        # Working with Altaris

        This server is `altaris`. It owns the device: clones, the accessibility
        tree, screenshots, the debugger and the REA lifecycle.

        ## Reaching REA

        REA is a separate MCP server (`rea`) with its own ~229 tools under their
        bare names. From here you reach it with:

        1. `altaris_rea_status` — installed and running?
        2. `altaris_rea_tools` — an index: name, one-line summary, required keys.
        3. `altaris_rea_schema { "name": "<tool>" }` — that tool's real input
           schema and a minimal example. Do this before the first call to a tool
           you have not used this session.
        4. `altaris_rea_call { "name": "<tool>", "arguments": { ... } }` — the
           arguments in exactly the shape the schema showed.

        These start REA themselves; you never need to start it first.

        If you are connected to `rea` directly, use REA's bare tool names instead
        (`open_binary`, `inspect_artifact`, `search_strings`, …) and read
        `rea://guide` there. **Tool names use underscores, never dashes** — the
        dashed form is REA's CLI, not its MCP surface.

        REA's Android lane needs a jadx bridge jar. If an REA tool answers
        `capability_unavailable` naming `REA_JADX_MCP_JAR`, call
        `altaris_jadx_bridge_install` here: REA does not ship or fetch the jar,
        so that whole lane stays blocked until it is installed.

        ### Nested arguments are objects

        A nested argument is an object discriminated by a `kind` field, not a
        string:

            // rejected: expected object
            { "source": "jadx", "view": "decompiled" }

            // accepted
            { "source": { "kind": "retained-evidence", "evidence_id": "ev_..." },
              "view":   { "kind": "summary" } }

        `altaris_rea_call` checks this for you and names the field it objected
        to. Fix that field and call once more; do not repeat the unchanged call.

        ### Evidence ids: use the parent, not the receipt

            "evidence_id"                     <- the summary receipt
            "normalized_result": {
              "parent_evidence_id": "ev_..."  <- the record you can page
            }

        `inspect_analysis_view` rejects the receipt id with `unsupported_target`.
        An id is also valid only for the session that produced it; on
        `evidence_integrity_mismatch`, re-run the producer rather than retrying.

        ## The device is this one

        You have a device — this one. There is no separate physical device and no
        USB cable, and you never need either. Never answer "no device connected":
        you can install, launch and drive an app yourself, on the phone Altaris
        runs on.

        The target is a clone: an isolated copy with its own package, storage
        and virtual display. Do not search the filesystem — do not look in
        /sdcard or /data for a package. Use `altaris_apps`, `altaris_clones`,
        `altaris_install_apk` instead.

        ## The loop

        1. `altaris_apps` — what is installed. `altaris_clones` — what exists.
           `altaris_install_apk { "path": "..." }` — install a new clone.
        2. `altaris_launch_clone { "package": "<pkg>" }` — start it. "Not running"
           is never a reason to stop; launching is one call.
        3. `altaris_rea_clone_apk { "package": "<pkg>" }` — export its APKs and
           native libraries and return the paths to analyse.
        4. Observe: `altaris_ui_tree` (structure), `altaris_device_screenshot`
           (the phone's own screen), `altaris_screenshot` (a clone's display).
           They are different displays — asking the wrong one is why a screenshot
           can fail with "No display frame".
        5. Act: `altaris_ui_tap_text`, `altaris_tap`, `altaris_swipe`,
           `altaris_text`. Prefer the semantic tools; use coordinates only when
           nothing else fits. Re-read `altaris_ui_tree` afterwards to confirm.
        5b. Wait: `altaris_wait { "until": "<text>" }` between the tap and the
           re-read. There is **no `setTimeout`, `sleep` or `Promise` timer** in
           the environment that runs your tool calls — referencing one throws
           `Unknown identifier 'setTimeout'` and the call fails before the tool
           runs, so the tap never happens. Use the tool, not a timer.
        6. Runtime: `altaris_debug_attach { "target": "<pkg>" }` starts the clone
           if needed, then attaches. Use it for runtime questions — what a method
           returns, what a value is at an address, what the process maps.
           `altaris_debug_command`, then `altaris_debug_detach`. A command
           starting with `$` is JavaScript run by Frida; anything else is a gdb
           command.

           Do not hand-roll `frida` or `frida-ps` commands. The CLI lives
           **inside this distro**, not on the host, and the server listens on
           `127.0.0.1:27042` — not on the proot gateway address. A guessed
           command fails with `unrecognized arguments` or
           `unable to connect to remote frida-server` while the server is up,
           which reads as a broken install. The debugger tools already do the
           attaching; use them.
        7. Clean up: `altaris_stop_clone`. Uninstall a clone **you** installed
           with `altaris_uninstall_clone`; a clone the user already had is theirs.

        **The debugger only attaches to clones.** A real app on the device is
        refused by design — clone it first.

        ## When REA is not the answer

        Navigation, UI inspection, screenshots, tapping and the debugger are
        Altaris's job, not REA's. Do not open REA for a question the accessibility
        tree answers. A missing optional backend is one sentence in the report,
        not a reason to abandon the task.
    """.trimIndent()

    /** Short prompt an MCP client can request from the `rea` server. */
    val PROMPT: String = """
        You are connected to REA, a reverse-engineering tool server. Its tools
        are advertised in tools/list under underscore names (inspect_artifact,
        not inspect-artifact); there is no index tool. Identify the artifact,
        choose the tool family for its format, prefer cheap inspection over a
        full decompile, and cite offsets for every claim. Read rea://guide.
    """.trimIndent()

    /** Short prompt for the `altaris` server. */
    val ALTARIS_PROMPT: String = """
        You are connected to Altaris, which owns this device: clones, UI,
        screenshots and the debugger. Reach REA through altaris_rea_tools,
        altaris_rea_schema and altaris_rea_call. The target is usually a clone:
        use altaris_clones and altaris_launch_clone, and altaris_debug_attach
        for runtime answers. Read altaris://rea-guide.
    """.trimIndent()
}
