package com.altaris.linux.clone.engine;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import com.android.apksig.ApkSigner;
import com.reandroid.apk.ApkModule;
import com.reandroid.archive.ByteInputSource;
import com.reandroid.archive.InputSource;
import com.reandroid.arsc.chunk.PackageBlock;
import com.reandroid.arsc.chunk.TableBlock;
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock;
import com.reandroid.arsc.chunk.xml.ResXmlAttribute;
import com.reandroid.arsc.chunk.xml.ResXmlElement;
import com.reandroid.arsc.item.StringItem;
import com.reandroid.arsc.pool.StringPool;
import com.reandroid.dex.id.StringId;
import com.reandroid.dex.model.DexFile;
import com.reandroid.dex.sections.SectionType;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Repackaging clone engine.
 *
 * <p>A second Android user cannot render on this device: HyperOS stops
 * activities of any non-foreground user, so the task never becomes visible on
 * the virtual display. The working model is a real second installation in
 * user 0:
 *
 * <ol>
 *   <li>copy the original base + split APKs to /data/local/tmp,</li>
 *   <li>rewrite the binary manifest (package name, provider authorities,
 *       custom permissions, task affinity, relative class names), hide the
 *       launcher entry, and set the clone label,</li>
 *   <li>re-sign with the embedded clone key,</li>
 *   <li>install silently as a normal user-0 package,</li>
 *   <li>launch the recorded activity on the virtual display.</li>
 * </ol>
 *
 * <p>Because the clone lives in the foreground user it renders and receives
 * input on the headless display. Empty data, and apps that verify their own
 * signature or enforce package identity will refuse to run — documented
 * limitation of this engine.
 */
final class CloneManager {
    private static final String TAG = "BlissClone";
    private static final String DIR = "/data/local/tmp/bliss";
    private static final String REGISTRY = DIR + "/clones.json";
    private static final String KEYSTORE_ASSET = "clone.keystore";
    private static final String KEYSTORE_PASS = "headless";
    private static final String KEY_ALIAS = "clone";
    private static final String CLONE_PREFIX = "com.nopiece.hclone.h";
    private static final Pattern SESSION_ID =
            Pattern.compile("created install session \\[(\\d+)\\]");
    private static final Pattern HEX = Pattern.compile("[0-9a-f]{8}");

    private final Context context;
    private final DisplayEngine engine;
    private final Object lock = new Object();

    CloneManager(Context context, DisplayEngine engine) {
        this.context = context;
        this.engine = engine;
    }

    // ------------------------------------------------------------- registry

    private JSONArray readRegistry() {
        File file = new File(REGISTRY);
        if (!file.exists()) {
            return new JSONArray();
        }
        try {
            String text = readText(file);
            return text.trim().isEmpty() ? new JSONArray() : new JSONArray(text);
        } catch (Throwable e) {
            Log.w(TAG, "registry read failed: " + e);
            return new JSONArray();
        }
    }

    private void writeRegistry(JSONArray array) {
        try {
            File dir = new File(DIR);
            if (!dir.exists() && !dir.mkdirs()) {
                Log.w(TAG, "cannot create " + DIR);
            }
            try (OutputStream out = new FileOutputStream(REGISTRY)) {
                out.write(array.toString(2).getBytes("UTF-8"));
            }
        } catch (Throwable e) {
            Log.w(TAG, "registry write failed: " + e);
        }
    }

    private JSONObject findRecord(String clonePackage) {
        JSONArray array = readRegistry();
        for (int i = 0; i < array.length(); i++) {
            JSONObject record = array.optJSONObject(i);
            if (record != null && clonePackage.equals(record.optString("package"))) {
                return record;
            }
        }
        return null;
    }

    private void saveRecord(String clonePackage, String originalPackage, String label,
                            String activity, String source, String origin, int parts) {
        synchronized (lock) {
            JSONArray array = readRegistry();
            JSONArray fresh = new JSONArray();
            for (int i = 0; i < array.length(); i++) {
                JSONObject record = array.optJSONObject(i);
                if (record != null && !clonePackage.equals(record.optString("package"))) {
                    fresh.put(record);
                }
            }
            try {
                JSONObject record = new JSONObject();
                record.put("package", clonePackage);
                record.put("original", originalPackage);
                record.put("label", label);
                record.put("activity", activity);
                record.put("source", source);
                record.put("origin", origin == null ? "" : origin);
                record.put("created", System.currentTimeMillis());
                record.put("last_opened", 0L);
                record.put("parts", parts);
                fresh.put(record);
            } catch (Throwable ignored) {
            }
            writeRegistry(fresh);
        }
    }

    private void touchRecord(String clonePackage) {
        synchronized (lock) {
            JSONArray array = readRegistry();
            for (int i = 0; i < array.length(); i++) {
                JSONObject record = array.optJSONObject(i);
                if (record != null && clonePackage.equals(record.optString("package"))) {
                    try {
                        record.put("last_opened", System.currentTimeMillis());
                    } catch (Throwable ignored) {
                    }
                    break;
                }
            }
            writeRegistry(array);
        }
    }

    private void removeRecord(String clonePackage) {
        synchronized (lock) {
            JSONArray array = readRegistry();
            JSONArray fresh = new JSONArray();
            for (int i = 0; i < array.length(); i++) {
                JSONObject record = array.optJSONObject(i);
                if (record != null && !clonePackage.equals(record.optString("package"))) {
                    fresh.put(record);
                }
            }
            writeRegistry(fresh);
        }
    }

    // ---------------------------------------------------------------- status

    String listJson() {
        JSONObject out = new JSONObject();
        try {
            JSONArray clones = new JSONArray();
            JSONArray registry = readRegistry();
            synchronized (lock) {
                for (int i = 0; i < registry.length(); i++) {
                    JSONObject record = registry.optJSONObject(i);
                    if (record == null) {
                        continue;
                    }
                    if (!record.has("original") || !record.has("activity")) {
                        // Stale record from an older engine version.
                        continue;
                    }
                    String clonePackage = record.optString("package");
                    JSONObject item = new JSONObject(record.toString());
                    boolean installed = packageInstalled(clonePackage);
                    item.put("installed", installed);
                    item.put("running", installed && processRunning(clonePackage));
                    item.put("status", installed
                            ? (processRunning(clonePackage) ? "running" : "installed")
                            : "missing");
                    clones.put(item);
                }
            }
            out.put("clones", clones);
            out.put("display_id", engine.displayId());
            out.put("engine", "repack");
        } catch (Throwable e) {
            return Jsons.error(e);
        }
        return out.toString();
    }

    String logsJson(String clonePackage, int maxLines) {
        JSONArray lines = new JSONArray();
        try {
            if (clonePackage == null || clonePackage.trim().isEmpty()) {
                lines.put("No clone selected");
                return lines.toString();
            }
            String pkg = clonePackage.trim();
            if (!packageInstalled(pkg)) {
                lines.put("Clone is not installed: " + pkg);
                return lines.toString();
            }
            String pid = "";
            AppCatalog.CommandResult pidResult = AppCatalog.run("pidof " + quote(pkg), 15);
            if (pidResult.ok()) {
                String[] parts = pidResult.output.trim().split("\\s+");
                if (parts.length > 0) {
                    pid = parts[0].trim();
                }
            }
            if (pid.isEmpty() || !pid.matches("\\d+")) {
                // The process may have crashed on launch. Keep the last
                // package-tagged lines so the user can see the failure
                // instead of only "not running".
                AppCatalog.CommandResult recent = AppCatalog.run(
                        "logcat -d -v time -t 1200 | grep -F " + quote(pkg), 30);
                if (recent.ok()) {
                    for (String line : recent.output.split("\n")) {
                        if (!line.trim().isEmpty()) lines.put(line);
                    }
                }
                if (lines.length() == 0) {
                    lines.put("Clone is not running. Tap View to start it.");
                } else {
                    lines.put("— clone is not running; showing last package log lines —");
                }
                return lines.toString();
            }
            int count = Math.max(20, Math.min(600, maxLines));
            AppCatalog.CommandResult result = AppCatalog.run(
                    "logcat -d -v time -t " + count + " --pid " + pid, 30);
            if (!result.ok() && result.output.trim().isEmpty()) {
                lines.put("logcat unavailable");
                return lines.toString();
            }
            for (String line : result.output.split("\n")) {
                if (!line.trim().isEmpty()) {
                    lines.put(line);
                }
            }
            if (lines.length() == 0) {
                lines.put("No log lines yet.");
            }
        } catch (Throwable e) {
            String detail = e.getMessage();
            lines.put("Log read failed: "
                    + (detail == null || detail.trim().isEmpty()
                    ? e.getClass().getSimpleName() : detail));
        }
        return lines.toString();
    }

    // ---------------------------------------------------------------- create

    String createFromInstalled(String originalPackage, String requestedLabel) {
        synchronized (lock) {
            try {
                if (originalPackage == null || originalPackage.trim().isEmpty()) {
                    return Jsons.error("empty package name");
                }
                String original = originalPackage.trim();
                if (!packageInstalled(original)) {
                    return Jsons.error("package not installed on this device: " + original);
                }
                PackageManager pm = context.getPackageManager();
                ApplicationInfo info = pm.getApplicationInfo(original, 0);
                String label = requestedLabel == null || requestedLabel.trim().isEmpty()
                        ? pm.getApplicationLabel(info).toString() + " (clone)"
                        : requestedLabel.trim();
                String activity = null;
                Intent launchIntent = pm.getLaunchIntentForPackage(original);
                if (launchIntent != null && launchIntent.getComponent() != null) {
                    activity = launchIntent.getComponent().getClassName();
                }
                List<File> sources = new ArrayList<>();
                if (info.sourceDir != null) {
                    sources.add(new File(info.sourceDir));
                }
                if (info.splitSourceDirs != null) {
                    for (String path : info.splitSourceDirs) {
                        if (path != null) {
                            sources.add(new File(path));
                        }
                    }
                }
                if (sources.isEmpty() || !sources.get(0).isFile()) {
                    sources = pathsFromPmPath(original);
                }
                if (sources.isEmpty()) {
                    return Jsons.error("cannot locate the APK of " + original);
                }
                if (activity == null) {
                    activity = readMainActivity(sources.get(0));
                }
                if (activity == null || activity.isEmpty()) {
                    return Jsons.error("no launcher activity in " + original);
                }
                activity = absoluteClass(original, activity);
                engine.log("cloning " + original + " (" + sources.size() + " APK part"
                        + (sources.size() == 1 ? "" : "s") + ") as " + label);
                return createClone(original, activity, sources, label, "installed", original);
            } catch (Throwable e) {
                Log.w(TAG, "createFromInstalled failed", e);
                return Jsons.error(e);
            }
        }
    }

    /**
     * Clone an APK addressed by a path the *shell* process can read.
     *
     * <p>The app process is scoped-storage limited (and cannot traverse
     * /data/local/tmp on recent Android builds), while the Shizuku service
     * usually runs as root/UID 2000. Resolve common guest/Android aliases here
     * instead of asking the CLI to guess. This is what makes
     * {@code bliss install /emulated/…}, {@code /sdcard/…} and
     * {@code /root/…} work from inside a proot guest.
     */
    String createFromPath(String rawPath, String requestedLabel) {
        String clean = rawPath == null ? "" : rawPath.trim();
        if (clean.startsWith("file://")) {
            clean = clean.substring("file://".length());
        }
        if (clean.isEmpty()) {
            return Jsons.error("missing APK path");
        }
        File resolved = resolveShellReadableFile(clean);
        if (resolved == null) {
            return Jsons.error("APK path is not readable: " + clean);
        }
        try {
            engine.log("cloning APK path " + resolved.getAbsolutePath());
            ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(
                    resolved, ParcelFileDescriptor.MODE_READ_ONLY);
            return createFromApk(descriptor, requestedLabel);
        } catch (Throwable e) {
            Log.w(TAG, "createFromPath failed: " + resolved.getAbsolutePath(), e);
            return Jsons.error("cannot open APK: " + resolved.getAbsolutePath()
                    + " (" + (e.getMessage() == null ? e.getClass().getSimpleName()
                    : e.getMessage()) + ")");
        }
    }

    /** Candidate paths for common shared-storage / chroot aliases. */
    private File resolveShellReadableFile(String clean) {
        List<String> candidates = new ArrayList<>();
        addCandidate(candidates, clean);

        String emulated = null;
        if (clean.startsWith("/emulated/")) {
            emulated = clean.substring("/emulated/".length());
        } else if (clean.startsWith("/sdcard/")) {
            emulated = clean.substring("/sdcard/".length());
        } else if (clean.startsWith("/storage/emulated/0/")) {
            emulated = clean.substring("/storage/emulated/0/".length());
        }
        if (emulated != null) {
            addCandidate(candidates, "/storage/emulated/0/" + emulated);
            addCandidate(candidates, "/sdcard/" + emulated);
            addCandidate(candidates, "/data/media/0/" + emulated);
        }

        // Guest-visible paths on this device: the agent/Kali chroot is exposed
        // under /data/local/tmp/chrootKali, while Bliss proot containers live
        // under the app's files/usr tree.
        String base = clean.startsWith("/") ? clean : "/" + clean;
        String name = new File(base).getName();
        addCandidate(candidates, "/data/local/tmp/chrootKali" + base);
        addCandidate(candidates, "/data/local/tmp/chrootKali/emulated" + base);
        addCandidate(candidates, "/data/local/tmp/chrootKali/" + name);
        addCandidate(candidates, "/data/local/tmp/chrootKali/emulated/" + name);
        addCandidate(candidates, "/data/local/tmp/" + name);

        File tmp = new File("/data/local/tmp");
        File[] tmpDirs = tmp.listFiles();
        if (tmpDirs != null) {
            for (File dir : tmpDirs) {
                if (dir == null || !dir.isDirectory()) continue;
                addCandidate(candidates, dir.getAbsolutePath() + base);
                addCandidate(candidates, dir.getAbsolutePath() + "/emulated" + base);
                addCandidate(candidates, dir.getAbsolutePath() + "/" + name);
            }
        }

        File containers = new File(context.getFilesDir(),
                "usr/var/lib/proot-distro/containers");
        File[] containerDirs = containers.listFiles();
        if (containerDirs != null) {
            for (File container : containerDirs) {
                if (container == null || !container.isDirectory()) continue;
                addCandidate(candidates, container.getAbsolutePath() + "/rootfs" + base);
                addCandidate(candidates, container.getAbsolutePath() + "/rootfs/root/" + name);
            }
        }

        File chroots = new File("/data/local/bliss/chroots");
        File[] chrootDirs = chroots.listFiles();
        if (chrootDirs != null) {
            for (File chroot : chrootDirs) {
                if (chroot == null || !chroot.isDirectory()) continue;
                addCandidate(candidates, chroot.getAbsolutePath() + base);
                addCandidate(candidates, chroot.getAbsolutePath() + "/root/" + name);
            }
        }

        for (String candidate : candidates) {
            if (candidate == null || candidate.isEmpty()) continue;
            File file = new File(candidate);
            if (file.isFile() && file.canRead()) {
                if (!candidate.equals(clean)) {
                    engine.log("APK path alias " + clean + " → " + candidate);
                }
                return file;
            }
        }
        return null;
    }

    private static void addCandidate(List<String> candidates, String path) {
        if (path == null || path.isEmpty()) return;
        if (!candidates.contains(path)) candidates.add(path);
    }

    String createFromApk(ParcelFileDescriptor descriptor, String requestedLabel) {
        synchronized (lock) {
            if (descriptor == null) {
                return Jsons.error("no APK file received");
            }
            File incoming = new File(DIR, "incoming-" + randomId() + ".apk");
            try {
                File dir = new File(DIR);
                if (!dir.exists() && !dir.mkdirs()) {
                    return Jsons.error("cannot create " + DIR);
                }
                long bytes = copy(descriptor, incoming);
                if (bytes <= 0) {
                    deleteFile(incoming);
                    return Jsons.error("empty APK file");
                }
                PackageManager pm = context.getPackageManager();
                PackageInfo info = pm.getPackageArchiveInfo(incoming.getAbsolutePath(), 0);
                if (info == null || info.applicationInfo == null) {
                    deleteFile(incoming);
                    return Jsons.error("cannot read package information from the APK");
                }
                if (isSplitApk(incoming)) {
                    deleteFile(incoming);
                    return Jsons.error("this is a split APK, not a base APK"
                            + " (use the installed-app clone instead)");
                }
                info.applicationInfo.sourceDir = incoming.getAbsolutePath();
                info.applicationInfo.publicSourceDir = incoming.getAbsolutePath();
                String label = requestedLabel == null || requestedLabel.trim().isEmpty()
                        ? pm.getApplicationLabel(info.applicationInfo).toString() + " (clone)"
                        : requestedLabel.trim();
                String activity = readMainActivity(incoming);
                if (activity == null || activity.isEmpty()) {
                    deleteFile(incoming);
                    return Jsons.error("the APK has no launcher activity");
                }
                activity = absoluteClass(info.packageName, activity);
                engine.log("cloning APK " + info.packageName + " as " + label);
                String result = createClone(info.packageName, activity,
                        Collections.singletonList(incoming), label, "apk", incoming.getName());
                deleteFile(incoming);
                return result;
            } catch (Throwable e) {
                deleteFile(incoming);
                Log.w(TAG, "createFromApk failed", e);
                return Jsons.error(e);
            } finally {
                try {
                    descriptor.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** Shared pipeline: stage, patch, sign, install, register. */
    private String createClone(String originalPackage, String activity, List<File> sources,
                               String label, String source, String origin) {
        String id = randomId();
        String clonePackage = newClonePackage();
        File stage = new File(DIR, "stage-" + id);
        File signedDir = new File(stage, "signed");
        List<File> signed = new ArrayList<>();
        Map<String, String> authorityPatches = new LinkedHashMap<>();
        try {
            if (!stage.mkdirs() && !stage.isDirectory()) {
                return Jsons.error("cannot create staging directory");
            }
            if (!signedDir.mkdirs() && !signedDir.isDirectory()) {
                return Jsons.error("cannot create signed staging directory");
            }
            for (int i = 0; i < sources.size(); i++) {
                File sourceFile = sources.get(i);
                boolean base = i == 0;
                String name = base ? "base.apk" : splitFileName(sourceFile, i);
                File staged = new File(stage, name);
                copyFile(sourceFile, staged);
                File unsigned = new File(stage, "unsigned-" + name);
                File signedFile = new File(signedDir, name);
                patchApk(staged, unsigned, clonePackage, label, base, originalPackage,
                        authorityPatches);
                signApk(unsigned, signedFile);
                deleteFile(unsigned);
                signed.add(signedFile);
            }
            engine.log("install " + clonePackage + " (" + signed.size() + " part"
                    + (signed.size() == 1 ? "" : "s") + ")");
            String installLog = installApkSet(clonePackage, signed);
            if (!packageInstalled(clonePackage)) {
                AppCatalog.run("pm uninstall " + quote(clonePackage), 60);
                return Jsons.error("install failed: " + installLog);
            }
            copyObb(originalPackage, clonePackage);
            int installedParts = installedPartCount(clonePackage);
            engine.log("installed parts for " + clonePackage + ": " + installedParts
                    + " (expected " + signed.size() + ")");
            if (installedParts > 0 && installedParts < signed.size()) {
                engine.log("WARNING: split resources may be incomplete; re-clone the source app");
            }
            saveRecord(clonePackage, originalPackage, label, activity, source, origin, sources.size());
            engine.log("clone ready: " + label + " (" + clonePackage + ")");
            return Jsons.ok("clone created: " + label + " · " + clonePackage);
        } catch (Throwable e) {
            Log.w(TAG, "createClone failed", e);
            try {
                AppCatalog.run("pm uninstall " + quote(clonePackage), 60);
            } catch (Throwable ignored) {
            }
            return Jsons.error(e);
        } finally {
            deleteDir(stage);
        }
    }

    // ---------------------------------------------------------------- launch

    String launch(String clonePackage) {
        synchronized (lock) {
            try {
                if (clonePackage == null || clonePackage.trim().isEmpty()) {
                    return Jsons.error("missing clone package");
                }
                String clone = clonePackage.trim();
                JSONObject record = findRecord(clone);
                if (record == null) {
                    return Jsons.error("unknown clone: " + clone);
                }
                if (!packageInstalled(clone)) {
                    return Jsons.error("clone is not installed: " + clone);
                }
                int displayId = engine.displayId();
                if (displayId <= 0) {
                    return Jsons.error("create the virtual display first");
                }
                String activity = record.optString("activity");
                if (activity == null || activity.isEmpty()) {
                    return Jsons.error("no launch activity recorded for " + clone);
                }
                android.content.ComponentName component =
                        new android.content.ComponentName(clone, activity);
                AppCatalog.CommandResult result =
                        AppCatalog.launchComponent(component, displayId, true);
                engine.log("clone launch " + clone + " display=" + displayId
                        + " component=" + component.flattenToShortString()
                        + " exit=" + result.exit + " " + oneLine(result.output));
                if (result.ok() && !result.output.contains("Error:")) {
                    touchRecord(clone);
                    return Jsons.ok(oneLine(result.output));
                }
                // Fallback: some ROMs want activity start-activity form.
                String command = "cmd activity start-activity --display " + displayId
                        + " -f 0x10000000 -n " + quote(component.flattenToShortString());
                AppCatalog.CommandResult second = AppCatalog.run(command, 60);
                if (second.ok() && !second.output.contains("Error:")) {
                    touchRecord(clone);
                    return Jsons.ok(oneLine(second.output));
                }
                return Jsons.error("launch failed: " + oneLine(result.output));
            } catch (Throwable e) {
                return Jsons.error(e);
            }
        }
    }

    String kill(String clonePackage) {
        synchronized (lock) {
            try {
                if (clonePackage == null || clonePackage.trim().isEmpty()) {
                    return Jsons.error("missing clone package");
                }
                AppCatalog.run("am force-stop " + quote(clonePackage.trim()), 30);
                engine.log("clone kill " + clonePackage);
                return Jsons.ok("stopped " + clonePackage);
            } catch (Throwable e) {
                return Jsons.error(e);
            }
        }
    }

    String delete(String clonePackage) {
        synchronized (lock) {
            try {
                if (clonePackage == null || clonePackage.trim().isEmpty()) {
                    return Jsons.error("missing clone package");
                }
                String clone = clonePackage.trim();
                AppCatalog.run("am force-stop " + quote(clone), 30);
                AppCatalog.CommandResult result =
                        AppCatalog.run("pm uninstall " + quote(clone), 180);
                if (packageInstalled(clone)) {
                    return Jsons.error("uninstall failed: " + oneLine(result.output));
                }
                removeRecord(clone);
                engine.log("clone deleted: " + clone);
                return Jsons.ok("clone deleted");
            } catch (Throwable e) {
                return Jsons.error(e);
            }
        }
    }

    // --------------------------------------------------------------- patching

    private void patchApk(File input, File output, String newPackage, String label,
                          boolean base, String originalPackage,
                          Map<String, String> authorityPatches) throws Exception {
        ApkModule apk = ApkModule.loadApkFile(input);
        try {
            AndroidManifestBlock manifest = apk.getAndroidManifest();
            if (manifest == null) {
                throw new IllegalStateException("APK has no AndroidManifest.xml");
            }
            String manifestPackage = manifest.getPackageName();
            if (manifestPackage == null || !manifestPackage.equals(originalPackage)) {
                throw new IllegalStateException("package mismatch: " + manifestPackage
                        + " (expected " + originalPackage + ")");
            }
            ResXmlElement root = manifest.getDocumentElement();
            if (root == null) {
                throw new IllegalStateException("empty manifest");
            }
            String sharedUserId = attr(root, "sharedUserId");
            if (sharedUserId != null && !sharedUserId.trim().isEmpty()) {
                throw new IllegalStateException("apps with sharedUserId are not supported");
            }
            fixRelativeNames(manifest, originalPackage);
            if (base) {
                authorityPatches.putAll(
                        rewriteProviderAuthorities(root, originalPackage, newPackage));
            }
            patchPackageStrings(manifest, root, originalPackage, newPackage);
            manifest.setPackageName(newPackage);
            if (base) {
                if (label != null && !label.isEmpty()) {
                    manifest.setApplicationLabel(label);
                }
                hideLauncher(manifest, root);
            }
            if (!authorityPatches.isEmpty()) {
                patchTableStrings(apk, authorityPatches, originalPackage);
                patchDexStrings(apk, authorityPatches, originalPackage);
            }
            // Dynamic resource lookups (Resources.getIdentifier, BuildConfig
            // identity checks) use the package name, not just resource IDs.
            // Keep the resource table and exact identity strings in sync with
            // the cloned manifest; this is what fixes apps such as Alight
            // Motion that resolve shape strings at runtime.
            patchPackageIdentity(apk, originalPackage, newPackage);
            apk.keepManifestChanges();
            apk.writeApk(output);
        } finally {
            try {
                apk.close();
            } catch (Throwable ignored) {
            }
        }
        if (!output.isFile() || output.length() == 0) {
            throw new IllegalStateException("repackaged APK is empty");
        }
    }

    private static void hideLauncher(AndroidManifestBlock manifest, ResXmlElement root) {
        ResXmlElement app = root.getElement("application");
        if (app == null) {
            return;
        }
        for (ResXmlElement activity : app.listElements("activity")) {
            hideInComponent(activity);
        }
        for (ResXmlElement alias : app.listElements("activity-alias")) {
            hideInComponent(alias);
        }
    }

    private static void hideInComponent(ResXmlElement component) {
        for (ResXmlElement filter : component.listElements("intent-filter")) {
            filter.removeElementsIf(element -> element.equalsName("category")
                    && "android.intent.category.LAUNCHER".equals(attr(element, "name")));
        }
    }

    private static void fixRelativeNames(AndroidManifestBlock manifest, String oldPackage) {
        Iterator<ResXmlAttribute> attributes = manifest.recursiveAttributes();
        while (attributes.hasNext()) {
            ResXmlAttribute attribute = attributes.next();
            String name = attribute.getName();
            ResXmlElement parent = attribute.getParentElement();
            String parentName = parent == null ? "" : parent.getName();
            boolean classAttribute = ("name".equals(name) && isComponentTag(parentName))
                    || "targetActivity".equals(name)
                    || "backupAgent".equals(name)
                    || "appComponentFactory".equals(name)
                    || "manageSpaceActivity".equals(name)
                    || "parentActivityName".equals(name)
                    || "settingsActivity".equals(name);
            if (!classAttribute) {
                continue;
            }
            String value = attribute.getValueString();
            if (value == null || value.isEmpty() || value.startsWith("@")
                    || value.startsWith("?")) {
                continue;
            }
            if (value.startsWith(".")) {
                attribute.setValueAsString(oldPackage + value);
            } else if (!value.contains(".")) {
                attribute.setValueAsString(oldPackage + "." + value);
            }
        }
    }

    private static boolean isComponentTag(String tag) {
        switch (tag) {
            case "application":
            case "activity":
            case "activity-alias":
            case "service":
            case "receiver":
            case "provider":
            case "instrumentation":
                return true;
            default:
                return false;
        }
    }

    private static void patchPackageStrings(AndroidManifestBlock manifest, ResXmlElement root,
                                            String oldPackage, String newPackage) {
        Iterator<ResXmlAttribute> attributes = manifest.recursiveAttributes();
        while (attributes.hasNext()) {
            ResXmlAttribute attribute = attributes.next();
            String name = attribute.getName();
            if (!"authorities".equals(name) && !"taskAffinity".equals(name)
                    && !"permission".equals(name) && !"process".equals(name)
                    && !"targetPackage".equals(name)) {
                continue;
            }
            String value = attribute.getValueString();
            if (value != null && value.contains(oldPackage)) {
                attribute.setValueAsString(value.replace(oldPackage, newPackage));
            }
        }
        String[] tags = {"permission", "uses-permission", "uses-permission-sdk-23",
                "permission-group", "permission-tree"};
        for (String tag : tags) {
            for (ResXmlElement element : root.listElements(tag)) {
                String value = attr(element, "name");
                if (value != null && (value.equals(oldPackage)
                        || value.startsWith(oldPackage + "."))) {
                    setAttr(element, "name", newPackage + value.substring(oldPackage.length()));
                }
            }
        }
    }

    /**
     * Provider authorities are global per Android user. Reusing the original
     * authority makes {@code pm install} fail with
     * {@code INSTALL_FAILED_CONFLICTING_PROVIDER}, so every provider gets a
     * clone-prefixed authority. Known references in the manifest, resources and
     * dex strings are rewritten with the same map.
     */
    private static Map<String, String> rewriteProviderAuthorities(
            ResXmlElement root, String oldPackage, String newPackage) {
        Map<String, String> patches = new LinkedHashMap<>();
        ResXmlElement app = root.getElement("application");
        if (app == null) {
            return patches;
        }
        Set<String> used = new HashSet<>();
        int fallbackIndex = 0;
        for (ResXmlElement provider : app.listElements("provider")) {
            String authorities = attr(provider, "authorities");
            if (authorities == null || authorities.trim().isEmpty()) {
                continue;
            }
            String[] parts = authorities.split(";");
            StringBuilder rebuilt = new StringBuilder();
            for (String part : parts) {
                String original = part.trim();
                if (original.isEmpty()) {
                    continue;
                }
                String suffix;
                if (original.equals(oldPackage)) {
                    suffix = "";
                } else if (original.startsWith(oldPackage + ".")) {
                    suffix = original.substring(oldPackage.length());
                } else if (original.startsWith("@") || original.startsWith("?")) {
                    suffix = ".provider" + (fallbackIndex == 0 ? "" : "." + fallbackIndex);
                    fallbackIndex++;
                } else {
                    int dot = original.lastIndexOf('.');
                    String tail = dot < 0 ? original : original.substring(dot + 1);
                    tail = tail.replaceAll("[^A-Za-z0-9_]", "_");
                    suffix = "." + (tail.isEmpty() ? "provider" : tail);
                }
                String replacement = newPackage + suffix;
                if (used.contains(replacement)) {
                    int index = 2;
                    while (used.contains(replacement + "." + index)) {
                        index++;
                    }
                    replacement = replacement + "." + index;
                }
                used.add(replacement);
                patches.put(original, replacement);
                if (rebuilt.length() > 0) {
                    rebuilt.append(';');
                }
                rebuilt.append(replacement);
            }
            if (rebuilt.length() > 0) {
                setAttr(provider, "authorities", rebuilt.toString());
            }
        }
        return patches;
    }

    private static void patchPackageIdentity(ApkModule apk, String oldPackage,
                                             String newPackage) {
        try {
            TableBlock table = apk.getTableBlock();
            if (table != null) {
                for (PackageBlock pkg : table.listPackages()) {
                    if (pkg != null && oldPackage.equals(pkg.getName())) {
                        pkg.setName(newPackage);
                    }
                }
                apk.keepTableBlockChanges();
            }
            for (InputSource source : new ArrayList<>(apk.listInputSources())) {
                String name = source.getName();
                if (name == null || !name.matches("classes[0-9]*\\.dex")) {
                    continue;
                }
                byte[] original;
                try (InputStream in = source.openStream()) {
                    original = readAllBytes(in);
                }
                DexFile dex = DexFile.read(original);
                boolean changed = false;
                try {
                    Iterator<StringId> items = (Iterator<StringId>)
                            (Iterator<?>) dex.getRootRepository().getItems(SectionType.STRING_ID);
                    while (items != null && items.hasNext()) {
                        StringId id = items.next();
                        if (id != null && oldPackage.equals(id.getString())) {
                            id.setString(newPackage);
                            changed = true;
                        }
                    }
                    if (!changed) {
                        continue;
                    }
                    dex.refreshFull();
                    byte[] patched = dex.getBytes();
                    apk.removeInputSource(name);
                    apk.add(new ByteInputSource(patched, name));
                } finally {
                    try {
                        dex.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable e) {
            Log.w(TAG, "package identity patch failed", e);
        }
    }

    private static void patchTableStrings(ApkModule apk, Map<String, String> patches,
                                          String oldPackage) {
        try {
            com.reandroid.arsc.chunk.TableBlock table = apk.getTableBlock();
            if (table == null) {
                return;
            }
            StringPool pool = table.getStringPool();
            if (pool == null) {
                return;
            }
            for (Map.Entry<String, String> entry : patches.entrySet()) {
                if (entry.getKey().equals(oldPackage)) {
                    // Do not rewrite a bare package name: it appears in class
                    // names and component references throughout the app.
                    continue;
                }
                List<StringItem> found = new ArrayList<>();
                Iterator<StringItem> items = pool.getAll(entry.getKey());
                while (items != null && items.hasNext()) {
                    StringItem item = items.next();
                    if (item != null) {
                        found.add(item);
                    }
                }
                for (StringItem item : found) {
                    item.set(entry.getValue());
                }
            }
            apk.keepTableBlockChanges();
        } catch (Throwable e) {
            Log.w(TAG, "resource authority patch failed", e);
        }
    }

    private static void patchDexStrings(ApkModule apk, Map<String, String> patches,
                                        String oldPackage) {
        try {
            for (InputSource source : new ArrayList<>(apk.listInputSources())) {
                String name = source.getName();
                if (name == null || !name.matches("classes[0-9]*\\.dex")) {
                    continue;
                }
                byte[] original;
                try (InputStream in = source.openStream()) {
                    original = readAllBytes(in);
                }
                byte[] patched = patchDexBytes(original, patches, oldPackage);
                if (patched != null && patched.length > 0) {
                    apk.removeInputSource(name);
                    apk.add(new ByteInputSource(patched, name));
                    Log.i(TAG, "patched provider references in " + name);
                }
            }
        } catch (Throwable e) {
            Log.w(TAG, "dex authority patch failed", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static byte[] patchDexBytes(byte[] input, Map<String, String> patches,
                                        String oldPackage) throws Exception {
        DexFile dex = DexFile.read(input);
        try {
            List<StringId> targets = new ArrayList<>();
            List<String> replacements = new ArrayList<>();
            Iterator<StringId> items = (Iterator<StringId>)
                    (Iterator<?>) dex.getRootRepository().getItems(SectionType.STRING_ID);
            while (items != null && items.hasNext()) {
                StringId id = items.next();
                if (id == null) {
                    continue;
                }
                String value = id.getString();
                if (value == null || value.isEmpty()) {
                    continue;
                }
                String replaced = replaceAuthorities(value, patches, oldPackage);
                if (!replaced.equals(value)) {
                    targets.add(id);
                    replacements.add(replaced);
                }
            }
            if (targets.isEmpty()) {
                return null;
            }
            for (int i = 0; i < targets.size(); i++) {
                targets.get(i).setString(replacements.get(i));
            }
            dex.refreshFull();
            return dex.getBytes();
        } finally {
            try {
                dex.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static String replaceAuthorities(String value, Map<String, String> patches,
                                             String oldPackage) {
        String result = value;
        for (Map.Entry<String, String> entry : patches.entrySet()) {
            if (entry.getKey().equals(oldPackage)) {
                continue;
            }
            if (result.contains(entry.getKey())) {
                result = result.replace(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    private static byte[] readAllBytes(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = input.read(buffer)) > 0) {
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static String attr(ResXmlElement element, String name) {
        if (element == null) {
            return null;
        }
        Iterator<ResXmlAttribute> attributes = element.getAttributes();
        while (attributes.hasNext()) {
            ResXmlAttribute attribute = attributes.next();
            if (name.equals(attribute.getName())) {
                return attribute.getValueString();
            }
        }
        return null;
    }

    private static void setAttr(ResXmlElement element, String name, String value) {
        Iterator<ResXmlAttribute> attributes = element.getAttributes();
        while (attributes.hasNext()) {
            ResXmlAttribute attribute = attributes.next();
            if (name.equals(attribute.getName())) {
                attribute.setValueAsString(value);
                return;
            }
        }
    }

    // ---------------------------------------------------------------- signing

    private void signApk(File input, File output) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = context.getAssets().open(KEYSTORE_ASSET)) {
            keyStore.load(in, KEYSTORE_PASS.toCharArray());
        }
        PrivateKey key = (PrivateKey) keyStore.getKey(KEY_ALIAS, KEYSTORE_PASS.toCharArray());
        X509Certificate certificate = (X509Certificate) keyStore.getCertificate(KEY_ALIAS);
        if (key == null || certificate == null) {
            throw new IllegalStateException("clone keystore is invalid");
        }
        ApkSigner.SignerConfig signerConfig = new ApkSigner.SignerConfig.Builder(
                "headless-clone", key, Collections.singletonList(certificate)).build();
        deleteFile(output);
        ApkSigner signer = new ApkSigner.Builder(Collections.singletonList(signerConfig))
                .setInputApk(input)
                .setOutputApk(output)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .setMinSdkVersion(29)
                .build();
        signer.sign();
        if (!output.isFile() || output.length() == 0) {
            throw new IllegalStateException("signing produced no output");
        }
    }

    // ---------------------------------------------------------------- install

    /** Preserve the split name declared by the APK; install sessions use it. */
    private String splitFileName(File apk, int index) {
        ApkModule module = null;
        try {
            module = ApkModule.loadApkFile(apk);
            AndroidManifestBlock manifest = module.getAndroidManifest();
            String split = manifest == null ? null : manifest.getSplit();
            if (split != null && !split.trim().isEmpty()) {
                String safe = split.trim().replaceAll("[^A-Za-z0-9_.-]", "_");
                if (!safe.isEmpty()) {
                    return safe + ".apk";
                }
            }
        } catch (Throwable e) {
            Log.w(TAG, "split name read failed for " + apk.getName(), e);
        } finally {
            if (module != null) {
                try {
                    module.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return "split-" + index + ".apk";
    }

    /**
     * Copy legacy OBB assets to the clone's package path. Some media-heavy
     * apps (for example Alight Motion asset packs) open an OBB during startup;
     * without this the clone can install cleanly and then crash on launch.
     */
    private void copyObb(String originalPackage, String clonePackage) {
        try {
            File source = new File("/sdcard/Android/obb/" + originalPackage);
            if (!source.isDirectory()) {
                return;
            }
            File destination = new File("/sdcard/Android/obb/" + clonePackage);
            if (!destination.exists() && !destination.mkdirs()) {
                return;
            }
            AppCatalog.CommandResult result = AppCatalog.run(
                    "cp -R " + quote(source.getAbsolutePath() + "/.") + " "
                            + quote(destination.getAbsolutePath() + "/"), 180);
            engine.log("OBB copy " + originalPackage + " -> " + clonePackage
                    + " exit=" + result.exit);
        } catch (Throwable e) {
            Log.w(TAG, "OBB copy failed", e);
        }
    }

    private int installedPartCount(String clonePackage) {
        try {
            AppCatalog.CommandResult paths = AppCatalog.run("pm path " + quote(clonePackage), 30);
            if (!paths.ok()) return 0;
            int count = 0;
            for (String line : paths.output.split("\\n")) {
                if (line.trim().startsWith("package:")) count++;
            }
            return count;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private String installApkSet(String clonePackage, List<File> apks) throws Exception {
        if (apks.size() == 1) {
            AppCatalog.CommandResult result = AppCatalog.run(
                    "pm install -r -t " + quote(apks.get(0).getAbsolutePath()), 300);
            engine.log("pm install " + clonePackage + " exit=" + result.exit
                    + " " + oneLine(result.output));
            return result.output;
        }
        AppCatalog.CommandResult create = AppCatalog.run("pm install-create -r -t", 60);
        Matcher matcher = SESSION_ID.matcher(create.output);
        if (!matcher.find()) {
            return "install-create failed: " + oneLine(create.output);
        }
        String session = matcher.group(1);
        StringBuilder log = new StringBuilder();
        for (File apk : apks) {
            AppCatalog.CommandResult write = AppCatalog.run(
                    "pm install-write -S " + apk.length() + " " + session + " "
                            + quote(apk.getName()) + " " + quote(apk.getAbsolutePath()), 300);
            log.append(apk.getName()).append('=').append(write.exit).append(' ');
        }
        AppCatalog.CommandResult commit = AppCatalog.run(
                "pm install-commit " + session, 300);
        log.append("commit=").append(commit.exit).append(' ').append(oneLine(commit.output));
        engine.log("pm install session " + session + " " + oneLine(log.toString()));
        return log.toString();
    }

    // ----------------------------------------------------------------- utils

    private List<File> pathsFromPmPath(String packageName) {
        List<File> result = new ArrayList<>();
        try {
            AppCatalog.CommandResult paths = AppCatalog.run(
                    "pm path " + quote(packageName), 30);
            for (String line : paths.output.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("package:")) {
                    File file = new File(trimmed.substring("package:".length()));
                    if (file.isFile()) {
                        result.add(file);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return result;
    }

    private String readMainActivity(File apk) {
        ApkModule module = null;
        try {
            module = ApkModule.loadApkFile(apk);
            AndroidManifestBlock manifest = module.getAndroidManifest();
            if (manifest == null || manifest.isSplit()) {
                return null;
            }
            String activity = manifest.getMainActivityClassName();
            if (activity == null) {
                ResXmlElement main = manifest.getMainActivity();
                activity = attr(main, "name");
            }
            return activity;
        } catch (Throwable e) {
            Log.w(TAG, "readMainActivity failed: " + e);
            return null;
        } finally {
            if (module != null) {
                try {
                    module.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private boolean isSplitApk(File apk) {
        ApkModule module = null;
        try {
            module = ApkModule.loadApkFile(apk);
            AndroidManifestBlock manifest = module.getAndroidManifest();
            return manifest != null && manifest.isSplit();
        } catch (Throwable e) {
            return false;
        } finally {
            if (module != null) {
                try {
                    module.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static String absoluteClass(String packageName, String className) {
        if (className == null || className.isEmpty()) {
            return className;
        }
        if (className.startsWith(".")) {
            return packageName + className;
        }
        if (!className.contains(".")) {
            return packageName + "." + className;
        }
        return className;
    }

    private String newClonePackage() {
        for (int i = 0; i < 32; i++) {
            String candidate = CLONE_PREFIX + randomId();
            if (!packageInstalled(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("cannot allocate a clone package name");
    }

    private boolean packageInstalled(String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return false;
        }
        try {
            AppCatalog.CommandResult result = AppCatalog.run(
                    "pm list packages " + quote(packageName), 30);
            return result.output.contains("package:" + packageName);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean processRunning(String packageName) {
        try {
            AppCatalog.CommandResult result = AppCatalog.run("ps -A -o NAME", 20);
            for (String line : result.output.split("\n")) {
                if (line.trim().equals(packageName)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static String randomId() {
        byte[] bytes = new byte[4];
        new SecureRandom().nextBytes(bytes);
        StringBuilder builder = new StringBuilder();
        for (byte value : bytes) {
            builder.append(String.format(Locale.US, "%02x", value & 0xFF));
        }
        String id = builder.toString();
        return HEX.matcher(id).matches() ? id : String.format(Locale.US, "%08x",
                Math.abs(new SecureRandom().nextInt()));
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static String oneLine(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\n', ' ').trim();
    }

    private static String readText(File file) throws Exception {
        long length = file.length();
        if (length > 4 * 1024 * 1024) {
            throw new IllegalStateException("registry too large");
        }
        byte[] buffer = new byte[(int) Math.max(16, length)];
        try (InputStream in = new FileInputStream(file)) {
            int read = in.read(buffer);
            return read <= 0 ? "" : new String(buffer, 0, read, "UTF-8");
        }
    }

    private static void copyFile(File source, File destination) throws Exception {
        try (InputStream in = new FileInputStream(source);
             OutputStream out = new FileOutputStream(destination)) {
            byte[] buffer = new byte[128 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
        }
    }

    private static long copy(ParcelFileDescriptor descriptor, File destination) throws Exception {
        long total = 0;
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
             OutputStream out = new FileOutputStream(destination)) {
            byte[] buffer = new byte[128 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
                total += read;
            }
        }
        return total;
    }

    private static void deleteFile(File file) {
        try {
            if (file != null) {
                file.delete();
            }
        } catch (Throwable ignored) {
        }
    }

    private static void deleteFile(String path) {
        if (path != null && !path.isEmpty()) {
            deleteFile(new File(path));
        }
    }

    private static void deleteDir(File dir) {
        if (dir == null || !dir.exists()) {
            return;
        }
        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory()) {
                    deleteDir(child);
                } else {
                    deleteFile(child);
                }
            }
        }
        try {
            dir.delete();
        } catch (Throwable ignored) {
        }
    }
}
