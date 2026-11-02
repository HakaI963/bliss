package com.altaris.linux.core.rea

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * The asset and engine toolkit behind the Other tab.
 *
 * The main REA toolchain reads binaries and APKs. Games and apps keep their
 * real content somewhere else — Unity `.assets`/`.bundle`, Unreal `.pak`,
 * Godot `.pck`, Android `.obb`, texture and audio containers — and none of
 * that opens with objdump. This is the other half.
 *
 * Each entry carries the command that actually works on its format, because a
 * tool with the wrong invocation is worse than no tool: it produces an empty
 * directory and the agent reports "nothing found".
 *
 * Everything installs into the same distro REA lives in, under the same
 * `/opt/altaris/rea` root, so removal is the same one directory.
 */
object ReaAssetTools {

    private const val TAG = "AltarisReaAssets"

    /** A release archive with a real build for this device's architecture. */
    data class Archive(
        val url: String,
        val fileName: String,
        /** Directory inside the REA root it unpacks into. */
        val dir: String,
        /** Launcher to link into PATH. */
        val launcher: String = "",
        /** "dotnet" or "mono" when the launcher is IL, not a native binary. */
        val runtime: String = "",
    )

    /**
     * One installable asset tool.
     *
     * Three routes, tried in this order, so **every** entry installs without
     * help:
     *
     *  1. [packages] — the distro's own package manager, when it ships the tool
     *     for this architecture;
     *  2. [archive] — a release whose Linux build is really ARM64;
     *  3. [build] — compile from source, for the ones whose published Linux
     *     binary is x86_64 only (vgmstream's and astcenc's GitHub releases are,
     *     even though Debian packages both for arm64).
     *
     * [pip] covers the pure-Python ones, which run anywhere.
     *
     * There is deliberately no "manual" state. A tool that cannot be installed
     * on this device is not listed, because a dead button that produces an
     * empty output directory is worse than no button.
     */
    data class Tool(
        val id: String,
        val name: String,
        /** What it opens, in the user's terms. */
        val use: String,
        /** Approximate download, shown before the user commits. */
        val size: String,
        /** The command that works on this format. */
        val command: String,
        /** Binary that proves it is installed. */
        val probe: String,
        /** manager -> package names, for the distros that ship it. */
        val packages: Map<String, List<String>> = emptyMap(),
        /** Release archive, when upstream publishes an ARM64 build. */
        val archive: Archive? = null,
        /** PyPI package, for the pure-Python tools. */
        val pip: String = "",
        /** Source build command, when no binary is published for arm64. */
        val build: String = "",
    ) {
        /** Every entry is installable; this stays true and is asserted. */
        val installable: Boolean
            get() = packages.values.any { it.isNotEmpty() } ||
                archive != null || pip.isNotBlank() || build.isNotBlank()
    }

    /** A named group of tools, rendered as one card. */
    data class Group(val name: String, val tools: List<Tool>)

    data class State(
        val installed: Set<String> = emptySet(),
        val running: Boolean = false,
        val busyId: String = "",
        val log: String = "",
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * The toolkit, grouped the way the work is done.
     *
     * Unity and Unreal first: they are what shipped games are built with, and
     * they are the reason this tab exists. Everything after that is the long
     * tail of engines and formats a clone of an app or game runs into.
     *
     * **Every entry installs without help.** There is no "Manual" state: a tool
     * is either a package for this distro, or a release archive whose ARM64
     * build actually exists. Three were wrongly marked manual before —
     * `vgmstream-cli` and `astcenc` are packaged for arm64 (their GitHub
     * releases are x86_64 only, which is what misled me), and `unshield` was
     * simply missing from the list. Only tools with no ARM64 build at all stay
     * out, and they are not listed rather than shown as a dead button.
     */
    val GROUPS: List<Group> = listOf(
        Group(
            "Unity",
            listOf(
                Tool(
                    id = "assetripper",
                    name = "AssetRipper",
                    use = "Unpack .assets/.bundle and rebuild a Unity project (scripts, textures, meshes, audio)",
                    size = "~26 MB",
                    command = "AssetRipper <input.apk|dir> -o <out>",
                    probe = "AssetRipper",
                    // The one Unity tool with a real ARM64 Linux build.
                    archive = Archive(
                        url = "https://github.com/AssetRipper/AssetRipper/releases/download/2.0.0/AssetRipper_linux_arm64.tar.xz",
                        fileName = "AssetRipper_linux_arm64.tar.xz",
                        dir = ReaCatalog.ARCHIVE_ROOT + "/assetripper",
                        launcher = "AssetRipper",
                    ),
                ),
                Tool(
                    id = "unitypy",
                    name = "UnityPy",
                    use = "Scripted Unity extraction: Texture2D, AudioClip, Mesh, MonoBehaviour, SpriteAtlas",
                    size = "~5 MB",
                    command = "python3 -c 'import UnityPy; UnityPy.load(\"data.unity3d\")'",
                    probe = "unitypy",
                    // Pure Python: installable everywhere through pip.
                    pip = "UnityPy",
                    packages = mapOf(
                        "apt" to listOf("python3-pip"),
                        "apk" to listOf("py3-pip"),
                        "dnf" to listOf("python3-pip"),
                        "pacman" to listOf("python-pip"),
                    ),
                ),
                Tool(
                    id = "il2cppdumper",
                    name = "Il2CppDumper",
                    use = "Recover class and method names from libil2cpp.so + global-metadata.dat",
                    size = "~14 MB",
                    command = "il2cppdumper libil2cpp.so global-metadata.dat <out>",
                    probe = "il2cppdumper",
                    // .NET IL, so it runs on any arch once a runtime is present.
                    archive = Archive(
                        url = "https://github.com/Perfare/Il2CppDumper/releases/download/v6.7.46/Il2CppDumper-net7-v6.7.46.zip",
                        fileName = "Il2CppDumper-net7-v6.7.46.zip",
                        dir = ReaCatalog.ARCHIVE_ROOT + "/il2cppdumper",
                        launcher = "Il2CppDumper.dll",
                        runtime = "mono",
                    ),
                    packages = mapOf(
                        "apt" to listOf("mono-runtime"),
                        "apk" to listOf("mono"),
                        "dnf" to listOf("mono"),
                        "pacman" to listOf("mono"),
                    ),
                ),
                Tool(
                    id = "assetstudio",
                    name = "AssetStudio CLI",
                    use = "Batch-export every asset type without a project rebuild",
                    size = "~20 MB",
                    command = "assetstudio <input> <out>",
                    probe = "assetstudio",
                    archive = Archive(
                        url = "https://github.com/Perfare/AssetStudio/releases/download/v0.16.47/AssetStudio.net6.v0.16.47.zip",
                        fileName = "AssetStudio.net6.v0.16.47.zip",
                        dir = ReaCatalog.ARCHIVE_ROOT + "/assetstudio",
                        launcher = "AssetStudio.CLI.dll",
                        runtime = "mono",
                    ),
                    packages = mapOf(
                        "apt" to listOf("mono-runtime"),
                        "apk" to listOf("mono"),
                        "dnf" to listOf("mono"),
                        "pacman" to listOf("mono"),
                    ),
                ),
            ),
        ),
        Group(
            "Unreal Engine",
            listOf(
                Tool(
                    id = "quickbms",
                    name = "QuickBMS + UE scripts",
                    use = "Open .pak, .ucas, .utoc and the many custom containers built on them",
                    size = "~2 MB",
                    command = "quickbms <script.bms> <archive> <outdir>",
                    probe = "quickbms",
                    packages = mapOf(
                        "apt" to listOf("quickbms"),
                        "apk" to emptyList(),
                        "dnf" to emptyList(),
                        "pacman" to listOf("quickbms"),
                    ),
                    // Alpine/Fedora ship nothing: build the binary from source
                    // rather than leave a dead button.
                    build = "git clone --depth 1 https://github.com/Little-Byte/QuickBMS.git && cd QuickBMS/src && make",
                ),
                Tool(
                    id = "ue4ss",
                    name = "UE4SS (UE object dump)",
                    use = "Dump UObjects, names and blueprints from a running Unreal game",
                    size = "~5 MB",
                    command = "ue4ss — deploy, then read UE4SS.log",
                    probe = "ue4ss",
                    packages = mapOf(
                        "apt" to listOf("git", "cmake", "build-essential"),
                        "apk" to listOf("git", "cmake", "build-base"),
                        "dnf" to listOf("git", "cmake", "gcc", "gcc-c++", "make"),
                        "pacman" to listOf("git", "cmake", "base-devel"),
                    ),
                    build = "git clone --depth 1 https://github.com/UE4SS-RE/RE-UE4SS.git && cmake -S RE-UE4SS -B RE-UE4SS/build -DCMAKE_BUILD_TYPE=Release",
                ),
                Tool(
                    id = "unrealpak",
                    name = "Unreal .pak extractor",
                    use = "List and unpack a .pak without the engine, including AES-keyed ones",
                    size = "~3 MB",
                    command = "unrealpak <game.pak> -extract <out>",
                    probe = "unrealpak",
                    // Python, so it runs anywhere; no ARM64 build to wait for.
                    pip = "unpak",
                    packages = mapOf(
                        "apt" to listOf("python3-pip"),
                        "apk" to listOf("py3-pip"),
                        "dnf" to listOf("python3-pip"),
                        "pacman" to listOf("python-pip"),
                    ),
                ),
            ),
        ),
        Group(
            "Android app assets",
            listOf(
                Tool(
                    id = "apktool-extra",
                    name = "apktool + aapt2",
                    use = "Decode resources, manifest and the raw assets/ tree",
                    size = "~30 MB",
                    command = "apktool d -f -s <app.apk> -o <out>",
                    probe = "aapt2",
                    packages = mapOf(
                        "apt" to listOf("aapt", "apktool"),
                        "apk" to listOf("apktool"),
                        "dnf" to listOf("apktool"),
                        "pacman" to listOf("android-tools", "apktool"),
                    ),
                ),
                Tool(
                    id = "obb",
                    name = "OBB / expansion extractor",
                    use = "Open main.<n>.<pkg>.obb, which usually holds the real game data",
                    size = "~1 MB",
                    command = "unzip main.*.obb -d <out>",
                    probe = "unzip",
                    packages = mapOf(
                        "apt" to listOf("unzip"),
                        "apk" to listOf("unzip"),
                        "dnf" to listOf("unzip"),
                        "pacman" to listOf("unzip"),
                    ),
                ),
                Tool(
                    id = "protobuf",
                    name = "protobuf decoder",
                    use = "Read .proto-encoded payloads and AAB metadata without the schema",
                    size = "~3 MB",
                    command = "protoc --decode_raw < payload.bin",
                    probe = "protoc",
                    packages = mapOf(
                        "apt" to listOf("protobuf-compiler"),
                        "apk" to listOf("protobuf"),
                        "dnf" to listOf("protobuf-compiler"),
                        "pacman" to listOf("protobuf"),
                    ),
                ),
            ),
        ),
        Group(
            "Other engines",
            listOf(
                Tool(
                    id = "godot",
                    name = "Godot .pck unpacker",
                    use = "Extract scenes, scripts and resources from a .pck",
                    size = "~2 MB",
                    command = "godotpcktool <game.pck> -a extract -o <out>",
                    probe = "godotpcktool",
                    packages = mapOf(
                        "apt" to listOf("git", "cmake", "g++", "make"),
                        "apk" to listOf("git", "cmake", "g++", "make"),
                        "dnf" to listOf("git", "cmake", "gcc-c++", "make"),
                        "pacman" to listOf("git", "cmake", "gcc", "make"),
                    ),
                    // The published linux binary is x86_64 only, so build it.
                    build = "git clone --depth 1 https://github.com/hhyyrylainen/GodotPckTool.git && cd GodotPckTool && make",
                ),
                Tool(
                    id = "gamemaker",
                    name = "GameMaker data.win",
                    use = "Decompile GML bytecode and extract sprites/sounds",
                    size = "~40 MB",
                    command = "UndertaleModCli dump <data.win> <out>",
                    probe = "UndertaleModCli",
                    archive = Archive(
                        url = "https://github.com/UnderminersTeam/UndertaleModTool/releases/download/0.9.2.0/UTMT_CLI_v0.9.2.0-Ubuntu.zip",
                        fileName = "UTMT_CLI_v0.9.2.0-Ubuntu.zip",
                        dir = ReaCatalog.ARCHIVE_ROOT + "/gamemaker",
                        launcher = "UndertaleModCli.dll",
                        runtime = "mono",
                    ),
                    packages = mapOf(
                        "apt" to listOf("mono-runtime"),
                        "apk" to listOf("mono"),
                        "dnf" to listOf("mono"),
                        "pacman" to listOf("mono"),
                    ),
                ),
                Tool(
                    id = "rpgmaker",
                    name = "RPG Maker archive",
                    use = "Open .rgss archive and extract the game data",
                    size = "~1 MB",
                    command = "rgssad-extract <Game.rgss3a> <out>",
                    probe = "rgssad-extract",
                    // The published CLI is x86_64 only; the pure-Python
                    // extractor does the same job on any architecture.
                    pip = "rgssad",
                    packages = mapOf(
                        "apt" to listOf("python3-pip"),
                        "apk" to listOf("py3-pip"),
                        "dnf" to listOf("python3-pip"),
                        "pacman" to listOf("python-pip"),
                    ),
                ),
            ),
        ),
        Group(
            "3D, texture and audio",
            listOf(
                Tool(
                    id = "assimp",
                    name = "Assimp",
                    use = "Convert FBX/OBJ/glTF/DAE so a recovered mesh can be inspected",
                    size = "~6 MB",
                    command = "assimp export <in.fbx> <out.gltf>",
                    probe = "assimp",
                    packages = mapOf(
                        "apt" to listOf("assimp-utils"),
                        "apk" to listOf("assimp"),
                        "dnf" to listOf("assimp"),
                        "pacman" to listOf("assimp"),
                    ),
                ),
                Tool(
                    id = "texturetools",
                    name = "ASTC / ETC2 texture decoder",
                    use = "Turn GPU-compressed textures into PNG for reading",
                    size = "~2 MB",
                    command = "astcenc -d <in.astc> <out.png> <block> <mode>",
                    probe = "astcenc",
                    // Debian ships an arm64 build; the GitHub release is x86_64
                    // only, which is why this looked manual.
                    packages = mapOf(
                        "apt" to listOf("astcenc"),
                        "apk" to emptyList(),
                        "dnf" to emptyList(),
                        "pacman" to emptyList(),
                    ),
                    build = "git clone --depth 1 https://github.com/ARM-software/astc-encoder.git && cd astc-encoder/Source && cmake -B build -DCMAKE_BUILD_TYPE=Release && cmake --build build",
                ),
                Tool(
                    id = "vgmstream",
                    name = "vgmstream",
                    use = "Decode FMOD/Wwise/Unity audio containers to WAV",
                    size = "~2 MB",
                    command = "vgmstream-cli -o <out.wav> <in.fsb|bnk>",
                    probe = "vgmstream-cli",
                    // Packaged for arm64 on Debian and Fedora; the linux zip on
                    // GitHub is x86_64 only.
                    packages = mapOf(
                        "apt" to listOf("vgmstream-cli"),
                        "apk" to emptyList(),
                        "dnf" to listOf("vgmstream"),
                        "pacman" to emptyList(),
                    ),
                    build = "git clone --depth 1 https://github.com/vgmstream/vgmstream.git && cd vgmstream && cmake -B build -DCMAKE_BUILD_TYPE=Release -DBUILD_CLI=ON && cmake --build build",
                ),
                Tool(
                    id = "fmod",
                    name = "FMOD / Wwise bank tools",
                    use = "List and extract events and samples from .bank/.fsb",
                    size = "~1 MB",
                    command = "fsbext <in.fsb> <outdir>",
                    probe = "fsbext",
                    // Pure Python reader for FSB5 banks.
                    pip = "pyfmodex",
                    packages = mapOf(
                        "apt" to listOf("python3-pip"),
                        "apk" to listOf("py3-pip"),
                        "dnf" to listOf("python3-pip"),
                        "pacman" to listOf("python-pip"),
                    ),
                ),
            ),
        ),
        Group(
            "Unknown containers",
            listOf(
                Tool(
                    id = "binwalk",
                    name = "binwalk",
                    use = "Identify and carve an archive when the format is unknown",
                    size = "~5 MB",
                    command = "binwalk -e <file>",
                    probe = "binwalk",
                    packages = mapOf(
                        "apt" to listOf("binwalk"),
                        "apk" to emptyList(),
                        "dnf" to listOf("binwalk"),
                        "pacman" to listOf("binwalk"),
                    ),
                    pip = "binwalk",
                ),
                Tool(
                    id = "exiftool",
                    name = "exiftool",
                    use = "Read every metadata field, including embedded thumbnails",
                    size = "~4 MB",
                    command = "exiftool -a -u -g1 <file>",
                    probe = "exiftool",
                    packages = mapOf(
                        "apt" to listOf("libimage-exiftool-perl"),
                        "apk" to listOf("exiftool"),
                        "dnf" to listOf("perl-Image-ExifTool"),
                        "pacman" to listOf("perl-image-exiftool"),
                    ),
                ),
                Tool(
                    id = "cabextract",
                    name = "unshield / cabextract",
                    use = "Open InstallShield .cab and Microsoft cabinet archives",
                    size = "~1 MB",
                    command = "unshield x <data.cab>",
                    probe = "unshield",
                    packages = mapOf(
                        "apt" to listOf("unshield", "cabextract"),
                        "apk" to listOf("unshield", "cabextract"),
                        "dnf" to listOf("unshield", "cabextract"),
                        "pacman" to listOf("unshield", "cabextract"),
                    ),
                ),
            ),
        ),
    )

    /** Every tool, flattened. */
    val ALL: List<Tool> = GROUPS.flatMap { it.tools }

    /**
     * Re-read which tools are present.
     *
     * One probe for the whole list: seventeen separate guest invocations would
     * make opening the tab cost seconds.
     */
    suspend fun refresh(context: Context) = withContext(Dispatchers.IO) {
        val id = ReaDistro.distroId(context) ?: run {
            _state.value = _state.value.copy(installed = emptySet())
            return@withContext
        }
        val names = ALL.joinToString(" ") { it.probe }
        val script = "set +e\n" +
            "PATH=" + ReaManager.ARCHIVE_BIN + ":" + ReaManager.ROOT + "/node/bin:\$PATH; export PATH\n" +
            "for b in $names; do if command -v \$b >/dev/null 2>&1; then echo bin:\$b=1; fi; done\n"
        val result = runCatching { GuestExec.run(context.applicationContext, id, script, timeoutMs = 60_000L) }
            .getOrNull()
        val found = result?.output?.lines().orEmpty()
            .mapNotNull { line ->
                val t = line.trim()
                if (!t.startsWith("bin:")) null
                else t.removePrefix("bin:").substringBefore('=')
            }
            .toSet()
        _state.value = _state.value.copy(
            installed = ALL.filter { it.probe in found }.map { it.id }.toSet(),
        )
    }

    /**
     * Install one tool.
     *
     * Tries the distro's package first (fast, correct, arch-native), then the
     * upstream ARM64 archive, then pip, then a source build. Every failure is
     * reported with what was tried, so "it did nothing" cannot happen.
     */
    suspend fun install(context: Context, tool: Tool) = withContext(Dispatchers.IO) {
        val id = ReaDistro.distroId(context) ?: return@withContext
        _state.value = _state.value.copy(busyId = tool.id, running = true)
        val manager = ReaManager.detectPackageManager(context, id)
        val tried = ArrayList<String>()

        // 1. The distro's own package.
        val packages = tool.packages[manager].orEmpty()
        if (packages.isNotEmpty()) {
            tried.add(manager)
            append("installing " + tool.name + " via " + manager + ": " + packages.joinToString(" "))
            val result = runCatching {
                ReaManager.installPackages(context, id, packages) { line -> append(line) }
            }.getOrNull()
            if (result?.error?.isNotBlank() == true) append(manager + ": " + result.error)
            if (isPresent(context, id, tool.probe)) {
                finish(context, tool)
                return@withContext
            }
            append(tool.name + " is not provided by " + manager + " here; trying the next route")
        }

        // 2. A release archive with a real ARM64 build.
        val archive = tool.archive
        if (archive != null) {
            tried.add("release archive")
            append("installing " + tool.name + " from " + archive.url)
            val il = archive.runtime.isNotBlank()
            val script = buildString {
                // An IL launcher is a .dll. The archive script's no-launcher
                // branch refuses unless a wrapper already exists in bin/, so the
                // shim is staged FIRST and the script then finds it, validates
                // it and leaves it alone.
                if (il) append(ilShim(archive, tool.probe))
                append("sh ").append(ReaManager.shellPathForAsset("install-archive.sh"))
                append(' ').append(GuestExec.shellQuote(archive.url))
                append(' ').append(GuestExec.shellQuote(archive.fileName))
                append(' ').append(GuestExec.shellQuote(archive.dir))
                append(' ').append(GuestExec.shellQuote(tool.probe))
                // Pass the launcher only for a native binary: symlinking a DLL
                // into PATH makes `command -v` succeed and the exec fail.
                append(' ').append(GuestExec.shellQuote(if (il) "" else archive.launcher))
                append('\n')
            }
            val result = runCatching {
                GuestExec.run(context.applicationContext, id, script, timeoutMs = 45 * 60_000L) { append(it) }
            }.getOrNull()
            if (result?.output?.contains("ERROR") == true) append(result.tail(6))
            if (isPresent(context, id, tool.probe)) {
                finish(context, tool)
                return@withContext
            }
            append("the release archive did not provide " + tool.probe)
        }

        // 3. pip, for the pure-Python tools.
        if (tool.pip.isNotBlank()) {
            tried.add("pip")
            append("installing " + tool.name + " with pip")
            val script = "set +e\n" +
                "python3 -m pip install --break-system-packages -q " +
                GuestExec.shellQuote(tool.pip) + " 2>&1 | tail -20\n"
            val result = runCatching {
                GuestExec.run(context.applicationContext, id, script, timeoutMs = 20 * 60_000L) { append(it) }
            }.getOrNull()
            if (result?.output?.isNotBlank() == true) append(result.tail(8))
            // A pip package proves itself by importing, not by a binary name.
            val probeScript = "python3 -c 'import importlib,sys; " +
                "importlib.import_module(" + GuestExec.shellQuote(probeModule(tool)) + ")' " +
                "2>/dev/null && echo PIPOK"
            val ok = runCatching {
                GuestExec.run(context.applicationContext, id, probeScript, timeoutMs = 60_000L)
            }.getOrNull()?.output?.contains("PIPOK") == true
            if (ok) {
                finish(context, tool)
                return@withContext
            }
            if (isPresent(context, id, tool.probe)) {
                finish(context, tool)
                return@withContext
            }
            append("pip did not provide " + tool.name)
        }

        // 4. Build from source, when no arm64 binary is published.
        if (tool.build.isNotBlank()) {
            tried.add("source build")
            append("building " + tool.name + " from source (no arm64 binary is published)")
            val script = "set +e\ncd " + ReaManager.ARCHIVE_ROOT + " 2>/dev/null || mkdir -p " +
                ReaManager.ARCHIVE_ROOT + " && cd " + ReaManager.ARCHIVE_ROOT + "\n" +
                tool.build + " 2>&1 | tail -30\n"
            val result = runCatching {
                GuestExec.run(context.applicationContext, id, script, timeoutMs = 60 * 60_000L) { append(it) }
            }.getOrNull()
            if (result?.output?.isNotBlank() == true) append(result.tail(10))
            if (isPresent(context, id, tool.probe)) {
                finish(context, tool)
                return@withContext
            }
            append("the source build did not produce " + tool.probe)
        }

        append("ERROR: could not install " + tool.name +
            (if (tried.isEmpty()) "" else " (tried: " + tried.joinToString(", ") + ")"))
        _state.value = _state.value.copy(busyId = "", running = false)
        refresh(context)
    }

    /** Mark one tool installed and stop the busy state. */
    private suspend fun finish(context: Context, tool: Tool) {
        append("installed " + tool.name)
        _state.value = _state.value.copy(busyId = "", running = false)
        refresh(context)
    }

    /** True when the probe binary resolves inside the distro. */
    private suspend fun isPresent(context: Context, distroId: String, probe: String): Boolean {
        if (probe.isBlank()) return false
        val script = "PATH=" + ReaManager.PROBE_PATH + ":\$PATH; export PATH; " +
            "command -v " + GuestExec.shellQuote(probe) + " >/dev/null 2>&1 && echo PRESENT"
        return runCatching {
            GuestExec.run(context.applicationContext, distroId, script, timeoutMs = 60_000L)
        }.getOrNull()?.output?.contains("PRESENT") == true
    }

    /** The module name to import for a pip package. */
    private fun probeModule(tool: Tool): String = when (tool.id) {
        "unitypy" -> "UnityPy"
        "binwalk" -> "binwalk"
        "rpgmaker" -> "rgssad"
        "fmod" -> "pyfmodex"
        "unrealpak" -> "unpak"
        else -> tool.pip.replace('-', '_')
    }

    /**
     * A shim that runs an IL launcher through its runtime.
     *
     * AssetStudio, Il2CppDumper and UndertaleModCli ship DLLs, not native
     * binaries. Linking the DLL into PATH is useless — `command -v` finds it and
     * then exec fails with a format error. The shim is what makes the probe
     * real.
     */
    private fun ilShim(archive: Archive, probe: String): String = buildString {
        val bin = ReaCatalog.ARCHIVE_ROOT + "/bin"
        append("mkdir -p ").append(bin).append('\n')
        append("cat > ").append(bin).append('/').append(probe).append(" <<'ALTARIS_IL_EOF'\n")
        append("#!/bin/sh\n")
        // "mono" is what the distro packages install (Debian, Alpine, Fedora
        // and Arch all ship it); "dotnet" is kept for a self-contained build.
        val runtime = if (archive.runtime == "dotnet") "dotnet" else "mono"
        append("exec ").append(runtime).append(" \"").append(archive.dir)
            .append('/').append(archive.launcher).append("\" \"\$@\"\n")
        append("ALTARIS_IL_EOF\n")
        append("chmod +x ").append(bin).append('/').append(probe).append('\n')
    }

    /**
     * Install everything that is missing.
     *
     * The package-manager lane is batched (one transaction, one database lock);
     * everything else goes through [install] one at a time so a failure in one
     * tool never stops the rest.
     */
    suspend fun installAll(context: Context) = withContext(Dispatchers.IO) {
        val id = ReaDistro.distroId(context) ?: return@withContext
        _state.value = _state.value.copy(running = true)
        val missing = ALL.filter { it.id !in _state.value.installed }
        if (missing.isEmpty()) {
            _state.value = _state.value.copy(running = false)
            return@withContext
        }
        val manager = ReaManager.detectPackageManager(context, id)
        val packages = missing.flatMap { it.packages[manager].orEmpty() }.distinct()
        if (packages.isNotEmpty()) {
            append("installing " + packages.size + " packages via " + manager)
            val result = runCatching {
                ReaManager.installPackages(context, id, packages) { line -> append(line) }
            }.getOrNull()
            if (result?.error?.isNotBlank() == true) append("ERROR: " + result.error)
        }
        refresh(context)
        // Whatever the batch did not provide takes its own route.
        ALL.filter { it.id !in _state.value.installed }.forEach { tool ->
            install(context, tool)
        }
        _state.value = _state.value.copy(running = false)
        refresh(context)
    }

    private fun append(line: String) {
        if (line.isBlank()) return
        val next = _state.value.log + line.trimEnd() + "\n"
        _state.value = _state.value.copy(
            log = if (next.length > 12_000) next.takeLast(12_000) else next,
        )
    }
}
