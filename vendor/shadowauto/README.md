# ShadowAuto — pristine upstream reference

This directory is an **unmodified copy** of ShadowAuto's `android-shell` module and its
hidden-API stubs (https://github.com/android-notes/ShadowAuto, mirrored at
https://github.com/vastobb/ShadowAuto-Build), Apache-2.0 — see `LICENSE` in this directory.

* **Nothing here is built, patched or shipped.** It is the reference Bliss's own display
  implementation is derived from.
* Bliss's ports live in `app/src/main/java/com/ivarna/fluxlinux/clone/Shadow*.java`
  (`ShadowDisplay`, `ShadowScreenCapture`, `ShadowInput`, `ShadowWindowManager`,
  `ShadowShellContext`).
* The side-by-side comparison of every ShadowAuto file against Bliss's is in
  `docs/shadowauto-parity.md`.

Why the reference is kept in-tree: the two places where ShadowAuto's display path has to deviate
on HyperOS (the *secure* `VIRTUAL_DISPLAY_FLAG_TRUSTED` flag, and the null-surface display coming
up OFF) are the exact reason earlier rounds produced zero frames. Keeping the original next to
Bliss's port makes those deviations auditable.
