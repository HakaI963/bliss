# ShadowAuto → Bliss parity

*Reference tree: `vendor/shadowauto/` — **pristine upstream** (https://github.com/android-notes/ShadowAuto,
also mirrored at https://github.com/vastobb/ShadowAuto-Build), Apache-2.0, licence included in
`vendor/shadowauto/LICENSE`. **Nothing in this tree is modified or compiled**; it is the reference
that Bliss's implementation is derived from. Bliss's own ports live in
`app/src/main/java/com/ivarna/fluxlinux/clone/Shadow*.java` and carry the same licence header.

---

## 1. ShadowAuto source, marked

`vendor/shadowauto/android-shell/src/main/java/com/silentauto/shell/`

| File | Lines | Role | What Bliss takes from it |
|---|---:|---|---|
| `ShellContext.java` | 157 | Fabricates a system `Context` inside the shell process (reflective `ActivityThread` surgery: `getSystemContext`, `mBoundApplication`, `mInitialApplication`, `mConfigurationController`). | **Ported** → `ShadowShellContext.java` (lazy + failure-proof: upstream builds it in a static initialiser, which can brick the process). |
| `VirtualDisplaySession.java` | 153 | Creates the `VirtualDisplay`, keeps an `ImageReader` for capture, swaps its surface in/out for on-demand screenshots, sets the IME policy to LOCAL. | **Ported** → `ShadowDisplay.java`, with the two HyperOS fixes below. |
| `ScreenCapture.java` | 31 | `Image` → `Bitmap` honouring `rowStride`/`pixelStride`, then cropping the padded rows. | **Ported verbatim** → `ShadowScreenCapture.java`. |
| `InputBridge.java` | 112 | Injects touch/key events **with the virtual display id** (`InputEvent.setDisplayId` + `injectInputEvent`). | **Ported verbatim** → `ShadowInput.java`. |
| `WindowManagerBridge.java` | 81 | `IWindowManager` reflection to read/set the display IME policy. | **Ported verbatim** → `ShadowWindowManager.java`. |
| `AppCatalog.java` | 100 | Installed-app listing; launches a package with `am start --display N -f 0x10008000 -n pkg/cls`. | **Launch form adopted** in `ShellVdService.launch()`; Bliss resolves the component for the *target user* first (`cmd package query-activities --brief --user N`) and appends `--user N`, because Bliss' clones live in user 10. |
| `DisplaySpec.java` | 129 | Device size/density via `wm size` / `wm density`. | Not needed: Bliss takes the size from its own settings. |
| `UiBridge.java` | 1037 | Accessibility tree dump, focus/click by node, `UiAutomation` over a hidden `IUiAutomationConnection`. | **Not ported yet** (accessibility, not required for display+capture+input). |
| `UiNodeUtils.java` | 222 | Helpers for the accessibility tree. | Not ported yet. |
| `ClipboardBridge.java` | ~40 | Clipboard get/set for the shell user. | Not ported yet (Bliss injects text through the key path). |
| `VideoStreamer.java` | 245 | H.264 encoder for a *video* pipe (used by the web launcher). | **Not used** — this was Bliss' round-23 mistake; the encoder surface is not how the display is captured. |
| `McpSessions.java` | 466 | The MCP session API (open/close session, launch, screenshot, tap, swipe, keys, text, UI dump). | Not used — the in-app AI loop (`CloneAi`) performs the same actions through the binder surface. |
| `RpcServer.java` | 236 | Newline-delimited JSON-RPC 2.0 server on `127.0.0.1:43110`. | Not used (no RPC link). |
| `JsonRpc.java` / `Config.java` / `LogHub.java` | 66/66/153 | JSON-RPC envelope, CLI config, log fan-out to RPC clients. | `LogHub`'s idea (service-side log lines streamed to the app) is implemented in `ShellVdService.log()` + `IShellVdCallback.onLog`. |
| `AutomationEngine.java` | 985 | The AI task loop: picks an app for a goal, drives it, OCR fallback. | Replaced by `CloneAi.kt`: the same observe → decide → act loop, in-app and without the daemon. |
| `AiClient.java` | 223 | OpenAI client (okhttp). | `CloneAi.chat()` uses the same OpenAI-compatible chat-completions shape (non-streaming, JSON actions). |
| `ActionTools.java` / `ActionDecision.java` | 188/207 | AI action vocabulary and decisions. | `AiProtocol` + `AiAction` implement the action vocabulary as JSON. |
| `OcrTest.java`, `VideoTest.java` | — | Self-tests for the OCR and video paths. | Not ported; `VideoTest` documents the capture path Bliss now uses. |
| `android-stubs/` | 33 files | Compile-only stubs for hidden APIs (`DisplayManagerGlobal`, `IUiAutomationConnection`, `ActivityThread`, …). | Not needed in Bliss: the ports use reflection so the app module compiles against the public SDK. |

`vendor/shadowauto/android-shell/build.gradle` also shows how it is deployed: the shell is a **separate
APK** run through `app_process` with `CLASSPATH`, started from `adb shell`. Bliss does **not** need
that indirection: its display owner is already a shell-uid process (the Shizuku user service), so the
same classes run in-process.

---

## 2. Bliss source, marked

| Area | File | State after this round |
|---|---|---|
| Display owner (shell uid) | `app/src/main/java/com/ivarna/fluxlinux/clone/ShellVdService.java` | Rewritten: creates the display via `ShadowDisplay`, pumps still frames (JPEG) to the app, injects input via `ShadowInput`, launches with `am start --user N --display D`, keeps staging/`pm install`/crash reporting. |
| Display implementation | `.../ShadowDisplay.java` | **New** — ShadowAuto's `VirtualDisplaySession` with the HyperOS fixes (see below). |
| Capture conversion | `.../ShadowScreenCapture.java` | **New** — verbatim port. |
| Input injection | `.../ShadowInput.java` | **New** — verbatim port. |
| IME policy | `.../ShadowWindowManager.java` | **New** — verbatim port. |
| System context | `.../ShadowShellContext.java` | **New** — port of upstream `ShellContext`, made lazy and failure-proof. |
| Binder API | `app/src/main/aidl/com/ivarna/fluxlinux/clone/IShellVd.aidl` | `createDisplay`, `displayId`, `startFrames`/`stopFrames`/`releaseDisplay`, `tap`/`longPress`/`swipe`/`key`/`text`, `launch`/`kill`, `stageWrite`/`pmInstall`/`deleteStage`, `attach`, `logs`, `exit`. |
| Frame callback | `.../IShellVdCallback.aidl` | `onScreenshot(jpeg, w, h)` + `onLog`. |
| App-side client | `app/src/main/kotlin/com/ivarna/fluxlinux/clone/ShellVd.kt` | Binds the service, decodes frames into `frames`, forwards input, stages APKs, exposes `screenshot()` for the AI observer. |
| Orchestration/UI state | `app/src/main/kotlin/com/ivarna/fluxlinux/core/clone/CloneEngine.kt` | Uses the binder client for display/frames/input; watchdog and heal paths kept. |
| AI control loop | `app/src/main/kotlin/com/ivarna/fluxlinux/core/clone/CloneAi.kt` | Screenshot → ML Kit OCR → one JSON action from an OpenAI-compatible model → shell-side tap/swipe/type. No MCP, no daemon. |
| AI tab UI | `app/src/main/kotlin/com/ivarna/fluxlinux/ui/screens/ClonedAppsScreen.kt` | Endpoint/key/model/vision/steps settings, goal box, run/stop, step status, action log. |


---

## 3. What ShadowAuto had that Bliss did not — and is now in Bliss

1. **The system-context fabrication** (`ShellContext`) — Bliss had a hand-rolled variant; now a
   faithful port.
2. **The capture path** (`VirtualDisplaySession` + `ScreenCapture`) — Bliss had three different
   home-grown variants (permanent ImageReader, swap-in reader, MediaCodec encoder surface); all
   failed. The ported path is the one the reference implementation ships.
3. **The stride-correct `Image` → `Bitmap` conversion** — the row/pixel-stride handling is what makes
   padded reader rows crop back to the display's real size.
4. **The input bridge** — display-scoped `injectInputEvent`, so input can never reach the phone screen.
5. **The IME-policy handling** — the clone keyboard appears on the clone display only.
6. **The launch form** — `am start --display N -f 0x10008000 -n pkg/cls`.

## 4. AI control (added)

Bliss now has the ShadowAuto-style control loop, implemented in-app instead of
via the separate shell APK / JSON-RPC daemon:

1. **Observation** — `ShellVd.screenshot()` captures an owned copy of the newest
   composed frame from the shell-owned virtual display (the same capture path as
   the live preview, now race-free: `ShadowDisplay.captureBitmap()` returns a
   copy, so the drain thread can recycle its frame without invalidating an
   encoder in flight).
2. **Perception** — ML Kit bundled text recognition runs on-device over that
   frame. Lines are numbered with display-pixel bounds; the screenshot can also
   be sent to a vision-capable model (toggle in the AI tab).
3. **Decision** — an OpenAI-compatible `POST /chat/completions` call receives the
   goal, app, screen size, recent actions and OCR lines, and must answer with a
   single JSON action (`tap_text`, `tap` by OCR index/coordinates, `long_press`,
   `swipe`, `input_text`, `key`, `launch`, `wait`, `finish`).
4. **Action** — coordinates are scaled back to the display and injected through
   the existing display-scoped `ShadowInput` bridge, so the physical screen is
   never an input target.
5. **Loop** — repeats until `finish`, the step limit, the user presses Stop, or
   the app/display disappears. Every action and its result is logged in the AI
   tab.

The old file-bridge (`clone` CLI + `bliss-clone-mcp`) was removed: it depended
on a `/bliss` bind and a request poller that could not work reliably, and it had
no AI loop of its own.

### Not taken

* **MCP / JSON-RPC daemon** — replaced by the in-app loop above.
* **The H.264 video pipe** (`VideoStreamer`) — it is the web launcher's preview
  feature; the live tab uses the JPEG still-frame pump.
* **The separate `app_process` deployment** — Bliss already owns a shell-uid
  Shizuku user service.


## 5. The two HyperOS deviations from upstream (both verified on the device)

1. **No `VIRTUAL_DISPLAY_FLAG_TRUSTED`.** On HyperOS that flag *is* the secure flag: the created
   display reports `FLAG_SECURE`, and a secure display is never composited into a non-secure
   destination surface. Every capture path therefore saw zero frames — the ImageReader, the MediaCodec
   encoder surface *and* `screencap` ("Display Id '44' is not valid / Capturing failed") — while the
   display sat in state ON with the app's layers present in SurfaceFlinger. The app never got past its
   splash screen either, because its window was never composited.
2. **The capture surface is attached at creation.** A display created with a null output surface comes
   up in state OFF on this ROM and never composites at all. A background drain thread keeps the reader
   empty so the buffer queue never backpressures the compositor, and the newest frame is kept for
   capture (conversion throttled to ~5/s, because every conversion allocates a full-res bitmap).
