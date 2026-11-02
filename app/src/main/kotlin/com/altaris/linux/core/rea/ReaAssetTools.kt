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

    /**
     * One installable asset tool.
     *
     * [packages] maps a package manager to the real package name. It is empty
     * when no distro ships the tool: those are shown with their command and a
     * "manual" chip instead of an Install button that would fail. An install
     * button that cannot work is worse than none — it produces an empty output
     * directory and the agent reports "nothing found".
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
        /** manager -> package names. Empty means "not packaged anywhere". */
        val packages: Map<String, List<String>> = emptyMap(),
        /** Where to get it when no manager ships it. */
        val upstream: String = "",
    ) {
        val installable: Boolean get() = packages.isNotEmpty()
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
     * they are the reason this tab exists. Everything after that is the
     * long tail of engines and formats a clone of an app or game runs into.
     */
    val GROUPS: List<Group> = listOf(
        Group(
            "Unity",
            listOf(
                Tool(
                    id = "assetripper",
                    name = "AssetRipper",
                    use = "Unpack .assets/.bundle and rebuild a Unity project (scripts, textures, meshes, audio)",
                    size = "~80 MB",
                    command = "AssetRipper <input.apk|dir> -o <out>",
                    probe = "AssetRipper",
                    upstream = "github.com/AssetRipper/AssetRipper releases (.NET build)",
                ),
                Tool(
                    id = "unitypy",
                    name = "UnityPy",
                    use = "Scripted Unity extraction: Texture2D, AudioClip, Mesh, MonoBehaviour, SpriteAtlas",
                    size = "~5 MB",
                    command = "python3 -c 'import UnityPy; UnityPy.load(\"data.unity3d\")'",
                    probe = "unitypy",
                    packages = mapOf("apt" to listOf("python3-pip"), "apk" to listOf("py3-pip"),
                        "dnf" to listOf("python3-pip"), "pacman" to listOf("python-pip")),
                    upstream = "pip install UnityPy",
                ),
                Tool(
                    id = "il2cppdumper",
                    name = "Il2CppDumper",
                    use = "Recover class and method names from libil2cpp.so + global-metadata.dat",
                    size = "~15 MB",
                    command = "Il2CppDumper libil2cpp.so global-metadata.dat <out>",
                    probe = "Il2CppDumper",
                    upstream = "github.com/Perfare/Il2CppDumper releases",
                ),
                Tool(
                    id = "assetstudio",
                    name = "AssetStudio CLI",
                    use = "Batch-export every asset type without a project rebuild",
                    size = "~40 MB",
                    command = "AssetStudio.CLI <input> <out>",
                    probe = "AssetStudio",
                    upstream = "github.com/Perfare/AssetStudio releases",
                ),
            ),
        ),
        Group(
            "Unreal Engine",
            listOf(
                Tool(
                    id = "fmodel",
                    name = "FModel",
                    use = "Browse and export .pak / .uasset / .uexp (cooked content)",
                    size = "~60 MB",
                    command = "FModel (GUI) — or use umodel for a headless export",
                    probe = "FModel",
                    upstream = "fmodel.app — desktop only, not installable here",
                ),
                Tool(
                    id = "umodel",
                    name = "umodel",
                    use = "Headless export of meshes, textures and animations from a .pak",
                    size = "~10 MB",
                    command = "umodel -export -path=<pak> <package>",
                    probe = "umodel",
                    upstream = "github.com/gildor2/UEViewer releases",
                ),
                Tool(
                    id = "quickbms",
                    name = "QuickBMS + UE scripts",
                    use = "Open .pak, .ucas, .utoc and the many custom containers built on them",
                    size = "~2 MB",
                    command = "quickbms <script.bms> <archive> <outdir>",
                    probe = "quickbms",
                    packages = mapOf("apt" to listOf("quickbms"), "apk" to emptyList(),
                        "dnf" to emptyList(), "pacman" to listOf("quickbms")),
                    upstream = "aluigi.altervista.org/quickbms.htm",
                ),
                Tool(
                    id = "ue4ss",
                    name = "UE4SS",
                    use = "Runtime hooking and script dumping for a running Unreal game",
                    size = "~5 MB",
                    command = "ue4ss (deploy into the game, then read UE4SS.log)",
                    probe = "ue4ss",
                    upstream = "github.com/UE4SS-RE/RE-UE4SS releases",
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
                    packages = mapOf("apt" to listOf("aapt"), "apk" to emptyList(),
                        "dnf" to emptyList(), "pacman" to listOf("android-tools")),
                    upstream = "android-sdk build-tools",
                ),
                Tool(
                    id = "splitjoin",
                    name = "Split APK joiner",
                    use = "Merge a base APK with its config splits into one analyzable set",
                    size = "~1 MB",
                    command = "merge-apks <base.apk> <split*.apk> -o <out.apk>",
                    probe = "merge-apks",
                    upstream = "provided by the Altaris helper scripts",
                ),
                Tool(
                    id = "obb",
                    name = "OBB / expansion extractor",
                    use = "Open main.<n>.<pkg>.obb, which usually holds the real game data",
                    size = "~1 MB",
                    command = "unzip main.*.obb -d <out>",
                    probe = "unzip",
                    packages = mapOf("apt" to listOf("unzip"), "apk" to listOf("unzip"),
                        "dnf" to listOf("unzip"), "pacman" to listOf("unzip")),
                ),
                Tool(
                    id = "protobuf",
                    name = "protobuf decoder",
                    use = "Read .proto-encoded payloads and AAB metadata without the schema",
                    size = "~3 MB",
                    command = "protoc --decode_raw < payload.bin",
                    probe = "protoc",
                    packages = mapOf("apt" to listOf("protobuf-compiler"),
                        "apk" to listOf("protobuf"), "dnf" to listOf("protobuf-compiler"),
                        "pacman" to listOf("protobuf")),
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
                    upstream = "github.com/hhyyrylainen/GodotPckTool releases",
                ),
                Tool(
                    id = "gamemaker",
                    name = "GameMaker data.win",
                    use = "Decompile GML bytecode and extract sprites/sounds",
                    size = "~8 MB",
                    command = "UndertaleModCli dump <data.win> <out>",
                    probe = "UndertaleModCli",
                    upstream = "github.com/UnderminersTeam/UndertaleModTool releases",
                ),
                Tool(
                    id = "rpgmaker",
                    name = "RPG Maker / Cocos2d",
                    use = "Open .rgss archive and Cocos2d atlas/plist pairs",
                    size = "~2 MB",
                    command = "rgssad-extract <Game.rgss3a> <out>",
                    probe = "rgssad-extract",
                    upstream = "github.com/uuksu/RPGMakerDecrypter releases",
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
                    packages = mapOf("apt" to listOf("assimp-utils"), "apk" to listOf("assimp"),
                        "dnf" to listOf("assimp"), "pacman" to listOf("assimp")),
                ),
                Tool(
                    id = "texturetools",
                    name = "ASTC / ETC2 / DDS decoders",
                    use = "Turn GPU-compressed textures into PNG for reading",
                    size = "~3 MB",
                    command = "astcenc -d <in.astc> <out.png> <block> <mode>",
                    probe = "astcenc",
                    packages = mapOf("apt" to listOf("astcenc"), "apk" to emptyList(),
                        "dnf" to emptyList(), "pacman" to emptyList()),
                    upstream = "github.com/ARM-software/astc-encoder releases",
                ),
                Tool(
                    id = "vgmstream",
                    name = "vgmstream",
                    use = "Decode FMOD/Wwise/Unity audio containers to WAV",
                    size = "~4 MB",
                    command = "vgmstream-cli -o <out.wav> <in.fsb|bnk>",
                    probe = "vgmstream-cli",
                    packages = mapOf("apt" to listOf("vgmstream-cli"), "apk" to emptyList(),
                        "dnf" to emptyList(), "pacman" to emptyList()),
                    upstream = "github.com/vgmstream/vgmstream releases",
                ),
                Tool(
                    id = "fmod",
                    name = "FMOD / Wwise bank tools",
                    use = "List and extract events and samples from .bank/.fsb",
                    size = "~3 MB",
                    command = "fsbext <in.fsb> <outdir>",
                    probe = "fsbext",
                    upstream = "aluigi.altervista.org/mytoolz.htm (fsbext)",
                ),
            ),
        ),
        Group(
            "Unknown containers",
            listOf(
                Tool(
                    id = "binwalk",
                    name = "binwalk + 7z",
                    use = "Identify and carve an archive when the format is unknown",
                    size = "~5 MB",
                    command = "binwalk -e <file>",
                    probe = "binwalk",
                    packages = mapOf("apt" to listOf("binwalk"), "apk" to emptyList(),
                        "dnf" to listOf("binwalk"), "pacman" to listOf("binwalk")),
                    upstream = "pip install binwalk",
                ),
                Tool(
                    id = "exiftool",
                    name = "exiftool",
                    use = "Read every metadata field, including embedded thumbnails",
                    size = "~4 MB",
                    command = "exiftool -a -u -g1 <file>",
                    probe = "exiftool",
                    packages = mapOf("apt" to listOf("libimage-exiftool-perl"),
                        "apk" to listOf("exiftool"), "dnf" to listOf("perl-Image-ExifTool"),
                        "pacman" to listOf("perl-image-exiftool")),
                ),
                Tool(
                    id = "cabextract",
                    name = "unshield / cabextract",
                    use = "Open InstallShield .cab and Microsoft cabinet archives",
                    size = "~1 MB",
                    command = "unshield x <data.cab>",
                    probe = "unshield",
                    packages = mapOf("apt" to listOf("unshield"), "apk" to listOf("unshield"),
                        "dnf" to listOf("unshield"), "pacman" to listOf("unshield")),
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
     * Install one tool through the distro's package manager.
     *
     * The package name is looked up per manager: "exiftool" is
     * `libimage-exiftool-perl` on Debian, `perl-Image-ExifTool` on Fedora and
     * `exiftool` on Alpine. Installing the id as if it were a package name is
     * how a tool silently reports "not packaged" and never installs.
     */
    suspend fun install(context: Context, tool: Tool) = withContext(Dispatchers.IO) {
        val id = ReaDistro.distroId(context) ?: return@withContext
        if (!tool.installable) {
            append(tool.name + " has no package on any supported distro. Get it from " +
                tool.upstream)
            return@withContext
        }
        _state.value = _state.value.copy(busyId = tool.id, running = true)
        val manager = ReaManager.detectPackageManager(context, id)
        val packages = tool.packages[manager].orEmpty()
        if (packages.isEmpty()) {
            append(tool.name + " is not packaged for " + manager +
                (if (tool.upstream.isNotBlank()) ". Get it from " + tool.upstream else ""))
            _state.value = _state.value.copy(busyId = "", running = false)
            return@withContext
        }
        append("installing " + tool.name + " via " + manager + ": " + packages.joinToString(" "))
        val result = runCatching {
            ReaManager.installPackages(context, id, packages) { line -> append(line) }
        }.getOrNull()
        if (result?.error?.isNotBlank() == true) append("ERROR: " + result.error)
        _state.value = _state.value.copy(busyId = "", running = false)
        refresh(context)
    }

    /** Install everything that is packaged and missing. */
    suspend fun installAll(context: Context) = withContext(Dispatchers.IO) {
        val id = ReaDistro.distroId(context) ?: return@withContext
        val manager = ReaManager.detectPackageManager(context, id)
        val wanted = ALL.filter { it.installable && it.id !in _state.value.installed }
        val packages = wanted.flatMap { it.packages[manager].orEmpty() }.distinct()
        if (packages.isEmpty()) {
            append("Nothing to install for " + manager)
            return@withContext
        }
        _state.value = _state.value.copy(running = true)
        append("installing " + packages.size + " packages via " + manager)
        val result = runCatching {
            ReaManager.installPackages(context, id, packages) { line -> append(line) }
        }.getOrNull()
        if (result?.error?.isNotBlank() == true) append("ERROR: " + result.error)
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
