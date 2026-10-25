package com.altaris.linux.core.rea

/**
 * External tools REA can drive. REA itself is pure Node and needs none of
 * these; each entry unlocks one analysis family (Android resources, native
 * disassembly, firmware, captures …).
 *
 * Package names are per package manager because the supported distros disagree
 * (Debian ships `aapt` and `android-tools-adb`, Arch ships `android-tools`).
 * A missing manager entry means "not packaged there"; the installer reports it
 * instead of failing the whole batch.
 */
data class ReaDependency(
    val id: String,
    val label: String,
    val description: String,
    val packages: Map<String, List<String>> = emptyMap(),
    val kind: String = "package", // "package" | "ghidra" | "license"
    val note: String = "",
)

object ReaCatalog {

    val MANAGERS = listOf("apt", "apk", "dnf", "pacman")

    val DEPENDENCIES: List<ReaDependency> = listOf(
        ReaDependency(
            id = "jadx",
            label = "JADX",
            description = "Decompile DEX to Java for APK analysis (REA's Android decompiler).",
            packages = mapOf(
                "apt" to listOf("jadx"),
                "apk" to listOf("jadx"),
                "dnf" to listOf("jadx"),
                "pacman" to listOf("jadx"),
            ),
        ),
        ReaDependency(
            id = "apktool",
            label = "Apktool",
            description = "Decode resources and the binary manifest; needed by resource workflows.",
            packages = mapOf(
                "apt" to listOf("apktool"),
                "apk" to listOf("apktool"),
                "dnf" to listOf("apktool"),
                "pacman" to listOf("apktool"),
            ),
        ),
        ReaDependency(
            id = "aapt",
            label = "AAPT",
            description = "Read APK badging, permissions and resource ids.",
            packages = mapOf(
                "apt" to listOf("aapt"),
                "apk" to listOf("aapt"),
                "dnf" to listOf("android-tools"),
                "pacman" to listOf("android-tools"),
            ),
        ),
        ReaDependency(
            id = "adb",
            label = "ADB",
            description = "Live device analysis: pull APKs, inspect running processes, attach.",
            packages = mapOf(
                "apt" to listOf("android-tools-adb"),
                "apk" to listOf("android-tools"),
                "dnf" to listOf("android-tools"),
                "pacman" to listOf("android-tools"),
            ),
            note = "Wireless debugging must be enabled on the device.",
        ),
        ReaDependency(
            id = "java",
            label = "Java runtime",
            description = "JADX and Apktool are JVM tools; without it they cannot start.",
            packages = mapOf(
                "apt" to listOf("openjdk-17-jre-headless"),
                "apk" to listOf("openjdk17-jre-headless"),
                "dnf" to listOf("java-17-openjdk-headless"),
                "pacman" to listOf("jre17-openjdk-headless"),
            ),
        ),
        ReaDependency(
            id = "binutils",
            label = "GNU binutils",
            description = "objdump: headers, sections, symbols, relocations, DWARF, disassembly.",
            packages = mapOf(
                "apt" to listOf("binutils"),
                "apk" to listOf("binutils"),
                "dnf" to listOf("binutils"),
                "pacman" to listOf("binutils"),
            ),
        ),
        ReaDependency(
            id = "gdb",
            label = "GDB",
            description = "Persistent debugger sessions for native binaries.",
            packages = mapOf(
                "apt" to listOf("gdb"),
                "apk" to listOf("gdb"),
                "dnf" to listOf("gdb"),
                "pacman" to listOf("gdb"),
            ),
        ),
        ReaDependency(
            id = "rizin",
            label = "Rizin",
            description = "Scriptable binary analysis and one-shot command execution.",
            packages = mapOf(
                "apt" to listOf("rizin"),
                "apk" to listOf("rizin"),
                "dnf" to listOf("rizin"),
                "pacman" to listOf("rizin"),
            ),
        ),
        ReaDependency(
            id = "python3",
            label = "Python 3",
            description = "Scripted analysis, pwntools layouts and REA helper workflows.",
            packages = mapOf(
                "apt" to listOf("python3", "python3-pip"),
                "apk" to listOf("python3", "py3-pip"),
                "dnf" to listOf("python3", "python3-pip"),
                "pacman" to listOf("python", "python-pip"),
            ),
        ),
        ReaDependency(
            id = "binwalk",
            label = "Binwalk",
            description = "Firmware and embedded-filesystem carving.",
            packages = mapOf(
                "apt" to listOf("binwalk"),
                "apk" to listOf("binwalk"),
                "dnf" to listOf("binwalk"),
                "pacman" to listOf("binwalk"),
            ),
        ),
        ReaDependency(
            id = "p7zip",
            label = "7-Zip",
            description = "Unpack OBB, XAPK, firmware archives and nested images.",
            packages = mapOf(
                "apt" to listOf("p7zip-full"),
                "apk" to listOf("p7zip"),
                "dnf" to listOf("p7zip"),
                "pacman" to listOf("p7zip"),
            ),
        ),
        ReaDependency(
            id = "tshark",
            label = "TShark",
            description = "Packet capture analysis for network evidence.",
            packages = mapOf(
                "apt" to listOf("tshark"),
                "apk" to listOf("tshark"),
                "dnf" to listOf("wireshark-cli"),
                "pacman" to listOf("wireshark-cli"),
            ),
        ),
        ReaDependency(
            id = "sqlite3",
            label = "SQLite CLI",
            description = "Inspect app databases pulled from a device or clone.",
            packages = mapOf(
                "apt" to listOf("sqlite3"),
                "apk" to listOf("sqlite"),
                "dnf" to listOf("sqlite"),
                "pacman" to listOf("sqlite"),
            ),
        ),
        ReaDependency(
            id = "procps",
            label = "procps",
            description = "REA reads process tables on Linux; busybox ps is not enough.",
            packages = mapOf(
                "apt" to listOf("procps"),
                "apk" to listOf("procps-ng"),
                "dnf" to listOf("procps-ng"),
                "pacman" to listOf("procps-ng"),
            ),
        ),
        ReaDependency(
            id = "file",
            label = "file",
            description = "Magic-based type identification used by several workflows.",
            packages = mapOf(
                "apt" to listOf("file"),
                "apk" to listOf("file"),
                "dnf" to listOf("file"),
                "pacman" to listOf("file"),
            ),
        ),
        ReaDependency(
            id = "radare2",
            label = "radare2",
            description = "Native disassembler and debugger; fast on ARM, ideal for quick triage beside Ghidra.",
            packages = mapOf(
                "apt" to listOf("radare2"),
                "apk" to listOf("radare2"),
                "dnf" to listOf("radare2"),
                "pacman" to listOf("radare2"),
            ),
        ),
        ReaDependency(
            id = "ghidra",
            label = "Ghidra",
            description = "NSA open-source decompiler. The headless analyzer runs on ARM64 via proot/chroot; only the GUI needs a desktop.",
            kind = "ghidra",
            note = "Java 17+ is installed automatically. First analysis compiles and runs all analyzers, so it is slow on mobile ARM. The GUI is not used.",
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

    /** Package list for [manager], or null when this distro does not ship it. */
    fun packagesFor(dep: ReaDependency, manager: String): List<String>? = dep.packages[manager]
}
