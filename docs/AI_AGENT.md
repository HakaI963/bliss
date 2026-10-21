# Bliss agent guide

Bliss runs a loopback-only control plane for Android app clones. An agent can
install, launch, observe, drive and stop a clone without the Bliss UI being in
front. Everything below is local to the device (`127.0.0.1`); it is not exposed
to the LAN.

## Connect

MCP endpoint: `http://127.0.0.1:47831/mcp`

OpenCode / compatible client:

```json
{
  "mcp": {
    "bliss": {
      "type": "remote",
      "url": "http://127.0.0.1:47831/mcp",
      "enabled": true
    }
  }
}
```

Protocol: JSON-RPC 2.0 over HTTP. Send `initialize`, then `tools/list`, then
`tools/call` with `{ "name": "...", "arguments": { ... } }`.

The same operations are available in any distro terminal through the `bliss`
helper, and over HTTP at `http://127.0.0.1:47831`.

## Mental model

- A **source app** is installed on the phone and can be cloned.
- A **clone** is a separate user-0 package named `com.nopiece.hclone.h...`.
- There is **one virtual display** at a time. `bliss_launch_clone` creates it
  and launches the clone there; coordinates are display pixels, not screen pixels.
- `bliss_screenshot` and `bliss_ocr` work even when no viewer is open.
- `bliss_stop_clone` stops the process and releases the display.
- Opening the same clone again with `bliss_launch_clone` returns
  `already_running`; it does **not** restart the app.

## First calls

```text
bliss_status
bliss_clones          # registry: package, label, status, created, last_opened, parts
bliss_apps            # installed source apps available for cloning
```

Use `bliss_install_apk` with either a shared-storage `path` (e.g.
`/sdcard/Download/app.apk`) or a direct `url`. Guest paths such as
`/emulated/...` are staged through shared storage by the CLI.

After install, pick the clone package from `bliss_clones`, then:

```text
bliss_launch_clone(package, width?, height?, fps?)
```

`width`/`height` are virtual-display pixels. Omit them to use the saved or
default profile.

## Perceive and act

```text
bliss_screenshot(width?)     -> MCP image content
bliss_ocr(width?)            -> text + boxes with x,y,w,h,cx,cy
bliss_tap(x, y)
bliss_swipe(x1, y1, x2, y2, ms?)
bliss_text(text)
bliss_key(key)               # back | home | enter | tab | esc | recents | keycode
```

Recommended loop:

1. `bliss_screenshot` or `bliss_ocr`.
2. Choose a target box centre from OCR, or a coordinate from the screenshot.
3. One action (`tap`/`swipe`/`text`/`key`).
4. Screenshot/OCR again to verify.
5. On a crash, call `bliss_logs(package, 300)` before stopping.

The clone viewer's ACTIONS strip mirrors every agent action with a timestamp,
so a person watching the display can see what the agent is doing. The Live
logs panel auto-scrolls and has copy/clear controls.

## Recording and diagnostics

```text
bliss_record_start(fps?, width?)
bliss_record_status
bliss_record_stop
bliss_logs(package, lines?)
```

Recordings are stored at
`/sdcard/Android/data/com.nopiece.bliss/files/recordings/`.

## CLI equivalents

The installed `bliss` helper uses the same API. For local files it streams the
APK bytes to `/install-upload`, so any path the distro can read works, including
`/emulated`, `/OpenCode...` and chroot `/root`.

```sh
bliss status
bliss clones
bliss apps
bliss list
bliss ps
bliss install /sdcard/Download/app.apk "My app"
bliss install /emulated/SomeApp.apk "My app"   # bytes uploaded; no shared path needed
bliss launch com.nopiece.hclone.hXXXX 720 1280 30
bliss shot /sdcard/Download/state.jpg
bliss ocr
bliss tap 540 1200
bliss swipe 540 1600 540 500 280
bliss text hello
bliss key back
bliss logs com.nopiece.hclone.hXXXX 300
bliss record start 6 720
bliss record stop
bliss stop com.nopiece.hclone.hXXXX
bliss uninstall com.nopiece.hclone.hXXXX
```

If `curl`/`wget` is missing in a guest, install one (`apt install curl`,
`apk add curl`, `dnf install curl`, …). Tools → Clone scans every installed
distro for supported agent binaries and gives each distro its own MCP config
button; it also detects host binaries and Android agent apps, and can copy this
brief to the clipboard.
