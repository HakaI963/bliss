package com.altaris.linux.core.rea

/**
 * External tools REA can drive. REA itself is pure Node and needs none of
 * these; each entry unlocks one analysis family (Android resources, native
 * disassembly, firmware, captures …).
 *
 * [variants] is the ordered install plan per package manager. Every element is
 * one *batch* of package names that must be installed together (python3 needs
 * pip in the same command, for instance); the installer walks the list in order
 * and stops at the first batch that both installs and produces the probe binary.
 * That is what makes "openjdk-21 first, openjdk-17 second" work on a distro that
 * only ships one of the two, instead of failing the whole tool.
 *
 * An empty list means "this distro does not package the tool"; the installer
 * reports that instead of pretending to have failed silently.
 */
data class ReaDependency(
    val id: String,
    val label: String,
    val description: String,
    /** manager -> ordered batches of package names. */
    val variants: Map<String, List<List<String>>> = emptyMap(),
    val kind: String = "package", // "package" | "ghidra" | "license"
    val note: String = "",
    /** Binaries that prove the tool is present. Empty means "ask the special case". */
    val probes: List<String> = emptyList(),
)

object ReaCatalog {

    val MANAGERS = listOf("apt", "apk", "dnf", "pacman")

    private fun every(vararg batches: List<String>): List<List<String>> = batches.toList()

    val DEPENDENCIES: List<ReaDependency> = listOf(
        ReaDependency(
            id = "jadx",
            label = "JADX",
            description = "Decompile DEX to Java for APK analysis (REA's Android decompiler).",
            variants = mapOf(
                "apt" to every(listOf("jadx")),
                "dnf" to every(listOf("jadx")),
                // Alpine and Arch package jadx only in community/AUR repos.
                "apk" to every(listOf("jadx")),
                "pacman" to emptyList(),
            ),
            probes = listOf("jadx"),
        ),
        ReaDependency(
            id = "apktool",
            label = "Apktool",
            description = "Decode resources and the binary manifest; needed by resource workflows.",
            variants = mapOf(
                "apt" to every(listOf("apktool")),
                "dnf" to every(listOf("apktool")),
                "apk" to every(listOf("apktool")),
                "pacman" to emptyList(),
            ),
            probes = listOf("apktool"),
        ),
        ReaDependency(
            id = "aapt",
            label = "AAPT",
            description = "Read APK badging, permissions and resource ids.",
            variants = mapOf(
                "apt" to every(listOf("aapt"), listOf("android-sdk-build-tools")),
                "dnf" to every(listOf("android-tools")),
                "pacman" to every(listOf("android-tools")),
                "apk" to emptyList(),
            ),
            probes = listOf("aapt", "aapt2"),
        ),
        ReaDependency(
            id = "adb",
            label = "ADB",
            description = "Live device analysis: pull APKs, inspect running processes, attach.",
            variants = mapOf(
                "apt" to every(listOf("android-tools-adb"), listOf("adb")),
                "apk" to every(listOf("android-tools")),
                "dnf" to every(listOf("android-tools")),
                "pacman" to every(listOf("android-tools")),
            ),
            probes = listOf("adb"),
            note = "Wireless debugging must be enabled on the device.",
        ),
        ReaDependency(
            id = "java",
            label = "Java (JDK 21)",
            description = "JADX and Apktool are JVM tools; Ghidra needs a full JDK with javac and Java 21+.",
            variants = mapOf(
                "apt" to every(listOf("openjdk-21-jdk-headless"), listOf("openjdk-17-jdk-headless")),
                "apk" to every(listOf("openjdk21-jdk"), listOf("openjdk17-jdk")),
                "dnf" to every(listOf("java-21-openjdk-devel"), listOf("java-17-openjdk-devel")),
                "pacman" to every(listOf("jdk21-openjdk"), listOf("jdk-openjdk")),
            ),
            probes = listOf("javac"),
            note = "Ghidra also needs Java 21+; its installer provisions Temurin 21 when the distro ships an older JDK.",
        ),
        ReaDependency(
            id = "binutils",
            label = "GNU binutils",
            description = "objdump: headers, sections, symbols, relocations, DWARF, disassembly.",
            variants = mapOf(
                "apt" to every(listOf("binutils")),
                "apk" to every(listOf("binutils")),
                "dnf" to every(listOf("binutils")),
                "pacman" to every(listOf("binutils")),
            ),
            probes = listOf("objdump"),
        ),
        ReaDependency(
            id = "gdb",
            label = "GDB",
            description = "Persistent debugger sessions for native binaries.",
            variants = mapOf(
                "apt" to every(listOf("gdb")),
                "apk" to every(listOf("gdb")),
                "dnf" to every(listOf("gdb")),
                "pacman" to every(listOf("gdb")),
            ),
            probes = listOf("gdb"),
        ),
        ReaDependency(
            id = "rizin",
            label = "Rizin",
            description = "Scriptable binary analysis and one-shot command execution.",
            variants = mapOf(
                "apt" to every(listOf("rizin")),
                "apk" to every(listOf("rizin")),
                "dnf" to every(listOf("rizin")),
                "pacman" to every(listOf("rizin")),
            ),
            probes = listOf("rizin"),
        ),
        ReaDependency(
            id = "python3",
            label = "Python 3",
            description = "Scripted analysis, pwntools layouts and REA helper workflows.",
            variants = mapOf(
                "apt" to every(listOf("python3", "python3-pip")),
                "apk" to every(listOf("python3", "py3-pip")),
                "dnf" to every(listOf("python3", "python3-pip")),
                "pacman" to every(listOf("python", "python-pip")),
            ),
            probes = listOf("python3"),
        ),
        ReaDependency(
            id = "binwalk",
            label = "Binwalk",
            description = "Firmware and embedded-filesystem carving.",
            variants = mapOf(
                "apt" to every(listOf("binwalk")),
                "dnf" to every(listOf("binwalk")),
                "pacman" to every(listOf("binwalk")),
                "apk" to emptyList(),
            ),
            probes = listOf("binwalk"),
        ),
        ReaDependency(
            id = "p7zip",
            label = "7-Zip",
            description = "Unpack OBB, XAPK, firmware archives and nested images.",
            variants = mapOf(
                "apt" to every(listOf("p7zip-full"), listOf("p7zip")),
                "apk" to every(listOf("p7zip")),
                "dnf" to every(listOf("p7zip", "p7zip-plugins")),
                "pacman" to every(listOf("p7zip")),
            ),
            probes = listOf("7z", "7za", "7zr"),
        ),
        ReaDependency(
            id = "tshark",
            label = "TShark",
            description = "Packet capture analysis for network evidence.",
            variants = mapOf(
                "apt" to every(listOf("tshark")),
                "apk" to every(listOf("tshark")),
                "dnf" to every(listOf("wireshark-cli")),
                "pacman" to every(listOf("wireshark-cli")),
            ),
            probes = listOf("tshark"),
        ),
        ReaDependency(
            id = "sqlite3",
            label = "SQLite CLI",
            description = "Inspect app databases pulled from a device or clone.",
            variants = mapOf(
                "apt" to every(listOf("sqlite3")),
                "apk" to every(listOf("sqlite")),
                "dnf" to every(listOf("sqlite")),
                "pacman" to every(listOf("sqlite")),
            ),
            probes = listOf("sqlite3"),
        ),
        ReaDependency(
            id = "procps",
            label = "procps",
            description = "REA reads process tables on Linux; busybox ps is not enough.",
            variants = mapOf(
                "apt" to every(listOf("procps")),
                "apk" to every(listOf("procps-ng")),
                "dnf" to every(listOf("procps-ng")),
                "pacman" to every(listOf("procps-ng")),
            ),
            probes = listOf("pgrep"),
        ),
        ReaDependency(
            id = "file",
            label = "file",
            description = "Magic-based type identification used by several workflows.",
            variants = mapOf(
                "apt" to every(listOf("file")),
                "apk" to every(listOf("file")),
                "dnf" to every(listOf("file")),
                "pacman" to every(listOf("file")),
            ),
            probes = listOf("file"),
        ),
        ReaDependency(
            id = "radare2",
            label = "radare2",
            description = "Native disassembler and debugger; fast on ARM, ideal for quick triage beside Ghidra.",
            variants = mapOf(
                "apt" to every(listOf("radare2")),
                "apk" to every(listOf("radare2")),
                "dnf" to every(listOf("radare2")),
                "pacman" to every(listOf("radare2")),
            ),
            probes = listOf("radare2", "r2"),
        ),
        ReaDependency(
            id = "ghidra",
            label = "Ghidra",
            description = "NSA open-source decompiler. The headless analyzer runs on ARM64 via proot/chroot; only the GUI needs a desktop.",
            kind = "ghidra",
            note = "A full JDK 21+ (with javac, required by Ghidra's launcher) is installed automatically. First analysis runs every analyzer, so it is slow on mobile ARM. The GUI is not used.",
        ),
        ReaDependency(
            id = "ida",
            label = "IDA Pro",
            description = "Commercial decompiler. Provide your own idat64 binary and Altaris drives it if present.",
            kind = "license",
            note = "Drop idat64 into /opt/altaris/rea/ida to make it callable. No license, not installed.",
        ),
        ReaDependency(
            id = "hopper",
            label = "Hopper",
            description = "macOS-only commercial decompiler; cannot run on Android.",
            kind = "license",
            note = "Unavailable on this platform.",
        ),
        ReaDependency(
            id = "jeb",
            label = "JEB",
            description = "Commercial Android decompiler. Provide your licensed jeb.jar; JADX covers the same workflows on device.",
            kind = "license",
            note = "No license, not installed.",
        ),
    )

    fun byId(id: String): ReaDependency? = DEPENDENCIES.firstOrNull { it.id == id }

    /** Tools the UI may install. License-gated (commercial) entries are excluded. */
    fun installable(): List<ReaDependency> = DEPENDENCIES.filter { it.kind != "license" }

    /** Ordered install batches for [manager]; empty when the distro cannot ship it. */
    fun variantsFor(dep: ReaDependency, manager: String): List<List<String>> =
        dep.variants[manager].orEmpty()

    /** True when this manager has at least one install batch for the tool. */
    fun packagedFor(dep: ReaDependency, manager: String): Boolean =
        dep.kind != "package" || variantsFor(dep, manager).isNotEmpty()

    /** Binaries checked in the guest to decide whether the tool is usable. */
    fun probesFor(dep: ReaDependency): List<String> = dep.probes
}
