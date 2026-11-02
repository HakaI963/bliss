package com.altaris.linux.core.rea

import android.util.Log
import com.reandroid.apk.ApkModule
import com.reandroid.archive.ByteInputSource
import com.reandroid.dex.data.InstructionList
import com.reandroid.dex.ins.Opcode
import com.reandroid.dex.key.MethodKey
import com.reandroid.dex.key.ProtoKey
import com.reandroid.dex.key.StringKey
import com.reandroid.dex.key.TypeKey
import com.reandroid.dex.model.DexClass
import com.reandroid.dex.model.DexClassModule
import com.reandroid.dex.model.DexFile
import com.reandroid.dex.model.DexInstruction
import java.io.File

/**
 * Injects Frida Gadget into a clone's APK.
 *
 * Root attaches from outside. Without root the only place instrumentation can
 * run is **inside the clone's own process**, so the library has to be part of
 * the APK and loaded by the app itself. This does that at build time, on the
 * APK the clone engine has already patched and before it is signed.
 *
 * Two edits, both through ARSCLib's DEX model (already a dependency):
 *
 *  1. add `lib/<abi>/libfrida-gadget.so` and its config to the archive;
 *  2. add a `GadgetLoader` class and make the application class's `<clinit>`
 *     call it, because a library that is present but never loaded does nothing.
 *
 * The second edit is emitted as real DEX instructions. The engine's existing
 * `patchDexStrings` only rewrites string constants, which cannot express a
 * method call.
 */
object FridaGadgetInjector {

    private const val TAG = "AltarisGadgetInject"

    private const val ABI_DIR = "lib/" + FridaGadget.ABI + "/"

    /** `System.loadLibrary("frida-gadget")` — the stem, not the file name. */
    private const val LOADER_METHOD = "load"

    /**
     * Inject the gadget into [apk].
     *
     * @return true only when the library is in the archive *and* the loader call
     *   is in the DEX. A half-injection is reported as failure, because a
     *   library nothing loads looks exactly like a working debugger that never
     *   connects.
     */
    fun inject(apk: File, gadget: File): Boolean {
        if (!gadget.isFile || gadget.length() < 1_000_000) {
            Log.w(TAG, "gadget is missing or truncated: ${gadget.absolutePath}")
            return false
        }
        var module: ApkModule? = null
        return try {
            module = ApkModule.loadApkFile(apk)

            // 1. The library and the config that tells it to listen.
            module.add(ByteInputSource(gadget.readBytes(), ABI_DIR + FridaGadget.LIB_NAME))
            module.add(
                ByteInputSource(
                    FridaGadget.CONFIG_JSON.toByteArray(),
                    ABI_DIR + FridaGadget.CONFIG_NAME,
                )
            )

            // 2. The loader, and the call that runs it.
            //
            // ApkModule has no `dexFile` accessor, so the primary classes.dex is
            // read, patched as a DexFile and written back into the archive.
            val application = applicationClass(module)
            if (application == null) {
                Log.w(
                    TAG,
                    "the clone declares no Application class; the gadget would be " +
                        "packaged but never loaded, so this is reported as a failure",
                )
                return false
            }
            val dexSource = module.listInputSources()
                .firstOrNull { it.name == "classes.dex" }
            if (dexSource == null) {
                Log.w(TAG, "clone has no classes.dex to patch")
                return false
            }
            val original = dexSource.openStream().use { it.readBytes() }
            val dex = DexFile.read(original)
            val patched = try {
                if (!addLoader(dex, application)) {
                    Log.w(TAG, "could not add the gadget loader")
                    return false
                }
                java.io.ByteArrayOutputStream().use { out ->
                    dex.write(out)
                    out.toByteArray()
                }
            } finally {
                runCatching { dex.close() }
            }
            module.removeInputSource("classes.dex")
            module.add(ByteInputSource(patched, "classes.dex"))
            module.writeApk(apk)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "gadget injection failed", t)
            false
        } finally {
            runCatching { module?.close() }
        }
    }

    /**
     * The app's own `Application` class, as a DEX type descriptor.
     *
     * Gadget must load before the app's code runs, and that class's static
     * initialiser is the earliest hook reachable without patching the framework.
     * Returns null when the app declares none: `android.app.Application` lives in
     * the framework, not the APK, so there is nothing in this archive to patch.
     */
    private fun applicationClass(module: ApkModule): String? {
        val manifest = runCatching { module.androidManifest }.getOrNull() ?: return null
        val root = runCatching { manifest.documentElement }.getOrNull() ?: return null
        val app = runCatching { root.getElement("application") }.getOrNull() ?: return null
        // ResXmlElement has no getAttributeValue; attributes are an iterator,
        // exactly as the Java clone engine reads them.
        val name = runCatching {
            app.attributes.asSequence()
                .firstOrNull { it.getName() == "name" }
                ?.getValueString()
                .orEmpty()
        }.getOrDefault("")
        if (name.isBlank()) return null
        return if (name.startsWith("L")) name else "L" + name.replace('.', '/') + ";"
    }

    /**
     * Create `GadgetLoader` and call it from [application]'s `<clinit>`.
     *
     * The call is **prepended**, not appended: the app's own static initialiser
     * may do work that Gadget should already be watching.
     */
    private fun addLoader(dex: DexFile, application: String): Boolean {
        return try {
            // A new class is created through a layout: DexFile is a repository,
            // and DexLayout is the DexClassModule that owns class creation.
            val layout: DexClassModule = dex.getOrCreateFirst()
            val loaderType = TypeKey(FridaGadget.LOADER_CLASS)
            val loader: DexClass = layout.getOrCreateClass(loaderType)
            loader.setSuperClass(TypeKey("Ljava/lang/Object;"))
            // MethodKey takes (type, name, ProtoKey); a ProtoKey is the return
            // type plus the parameter types, not a "V" string.
            val voidProto = ProtoKey.emptyParameters(TypeKey("V"))
            val loadKey = MethodKey(loaderType, StringKey(LOADER_METHOD), voidProto)
            val load = loader.getOrCreateStaticMethod(loadKey)
            val loadCode = load.definition.getOrCreateCodeItem()
            val loadList: InstructionList = loadCode.instructionList
            // System.loadLibrary("frida-gadget")
            //
            // `Ins` itself has no setKey; the model wrapper does, so each
            // created instruction is wrapped before its key is set. Without
            // that the invoke has no method reference and the DEX will not
            // verify.
            loadList.createStringAt(0, 0, FridaGadget.LOAD_LIBRARY_NAME)
            val invoke = loadList.createAt(1, Opcode.INVOKE_STATIC)
            DexInstruction(load, invoke).setKey(
                MethodKey(
                    TypeKey("Ljava/lang/System;"),
                    StringKey("loadLibrary"),
                    ProtoKey.create(TypeKey("V"), TypeKey("Ljava/lang/String;")),
                )
            )
            loadList.createAt(2, Opcode.RETURN_VOID)

            // --- the call from <clinit> -------------------------------------
            val appType = TypeKey(application)
            val appClass = dex.getDexClass(appType) ?: return false
            val clinitKey = MethodKey(appType, StringKey("<clinit>"), voidProto)
            val clinit = appClass.getOrCreateStaticMethod(clinitKey)
            val clinitCode = clinit.definition.getOrCreateCodeItem()
            val clinitList: InstructionList = clinitCode.instructionList
            val call = clinitList.createAt(0, Opcode.INVOKE_STATIC)
            DexInstruction(clinit, call).setKey(loadKey)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "cannot add the loader call", t)
            false
        }
    }
}
