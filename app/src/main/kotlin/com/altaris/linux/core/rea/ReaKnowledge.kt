package com.altaris.linux.core.rea

/**
 * What an agent needs to know to use REA well.
 *
 * This is served by the MCP bridge as a resource and a prompt, so an agent that
 * connects to the server learns the workflow without the user explaining it.
 * The content is derived from REA's own documentation and tool names — not
 * invented — and is deliberately about *how to work*, because the tool catalog
 * itself is already advertised by `tools/list`.
 */
object ReaKnowledge {

    /** Resource URI, so an agent can read it by reference. */
    const val URI = "rea://guide"

    /**
     * The guide.
     *
     * Ordered as a working method: identify the artifact, pick the tool family,
     * then verify. Kept short enough to be read in one tool call.
     */
    val GUIDE: String = """
        # Working with REA inside Altaris

        REA (Reverse Engineering Agents) is an MCP server. It exposes analysis
        tools; you decide what to call and in what order. It has no model of its
        own.

        ## 1. Identify the artifact first

        Before any analysis, establish what you are looking at:
        - `inspect-artifact` — general identification: format, architecture,
          entry point, sections, notable metadata. Start here for anything
          unknown.
        - `inspect-analysis-view` — the current analysis state, when a session
          already exists.

        Do not guess a format from the file name. A `.bin` may be an ELF, a
        firmware image, an archive or a database.

        ## 2. Choose the family by artifact type

        **Android / JVM**
        - APK or DEX: decompile to Java for logic, and to smali when you need the
          exact bytecode.
        - `AndroidManifest.xml` is binary XML; use the manifest tool rather than
          reading the bytes.
        - Managed .NET assemblies: `inspect-managed-artifact` for the assembly,
          `inspect-managed-members` for the types and methods inside it.

        **Native (ELF / PE / Mach-O)**
        - `inspect-elf-notes` for build-id, ABI and toolchain notes.
        - PE: `inspect-pe-imports`, `inspect-pe-load-config`,
          `inspect-pe-certificates`.
        - Go binaries: `inspect-go-binary` (build info, module list).
        - Rust binaries: `inspect-rust-binary`.
        - Symbols and sections: the objdump-style tool. Use it before a full
          decompile — it is far cheaper and often answers the question.

        **Firmware, archives, containers**
        - `inspect-artifact` then `extract-artifact`; entropy and signature
          scans tell you whether something is compressed or encrypted before you
          waste time on it.

        **Other formats**
        - SQLite: `inspect-sqlite-database` (schema, then rows; do not read the
          file as text).
        - Apple crash/IPSW: `inspect-apple-ips`.
        - Device tree: `inspect-devicetree`.
        - Kernel module: `inspect-kernel-module`.
        - Erlang BEAM: `inspect-erlang-beam`.
        - Python bytecode: `inspect-python-pyc`.
        - JavaScript applications: `analyze-javascript-application`.

        ## 3. Decompile only when you need logic

        A decompile is expensive. Prefer, in this order:
        1. strings and symbols
        2. sections and imports
        3. disassembly of the specific function
        4. full decompile of the specific function
        5. whole-binary decompile (last resort)

        ## 4. Compare instead of eyeballing

        When the task is "is this a faithful rebuild", use the comparison tool:
        it reports matched bytes, differing bytes, total bytes and the ranges
        that differ. Do not compare by reading two dumps side by side — you will
        miss offsets and you cannot count bytes reliably.

        A difference in padding is not a behavioural difference. Say which it is.

        ## 5. Report honestly

        - Cite the offset, section or function a claim comes from.
        - If a tool fails, read the error and try a different family; do not
          repeat the same failing call.
        - Never invent addresses, symbol names or file contents. If the evidence
          is not there, say it is unknown.

        ## 6. Splitting work

        For a large artifact, split by *artifact region* rather than by task
        type: one agent per section or per package, each reporting offsets back.
        Two agents analysing the same region waste the work.

        ## Reference material

        REA's own material, for a human to read — not required to use the tools:
        - Repository: github.com/morluto/rea
        - Site and guides: rea.tools, rea.tools/guides
        - First investigation: rea.tools/first-investigation
        - Showcases: Notion clipboard, DX-Ball reconstruction, Aegis APK,
          TH04 bullet patterns
        - Learning path that REA recommends: install → a small export/format
          task → a CTF binary → a real APK → a full reconstruction, then Ghidra,
          assembly and the research papers.
    """.trimIndent()

    /** Short system-style prompt an MCP client can request. */
    val PROMPT: String = """
        You are connected to REA, a reverse-engineering tool server running inside
        Altaris. Identify the artifact before analysing it, choose the tool family
        for its format, prefer cheap inspection over a full decompile, use the
        comparison tool for rebuild fidelity, and cite offsets for every claim.
        Read rea://guide for the detailed method.
    """.trimIndent()
}
