<p align="center">  
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="112" alt="Flyme Freeform icon" />  
</p>

<h1 align="center">Flyme Freeform · No-root Edition</h1>

<p align="center">  
  <img src="https://img.shields.io/badge/ROM-ColorOS-00A862" alt="Built for ColorOS" />  
  <img src="https://img.shields.io/badge/minSdk-35-3DDC84?logo=android" alt="minSdk 35" />  
  <img src="https://img.shields.io/badge/license-GPL--3.0-4285F4" alt="GPL-3.0" />  
  <img src="https://img.shields.io/github/v/release/m-secret/FlymeFreeform?label=release" alt="Latest release" />  
  <img src="https://github.com/m-secret/FlymeFreeform/actions/workflows/build.yml/badge.svg" alt="Build APK" />  
</p>

<p align="center">
  <a href="README.md">简体中文</a> · <a href="README.en.md">English</a>
</p>

Bringing the snappy, friction-free feel of Meizu Flyme's floating window to ColorOS — **no root, no Xposed, not a single hook.**

## What this is

Flyme's floating window nails the "summon it, flick it away" interaction: a couple of swipes and taps and the thing you need is right there. ColorOS has floating windows too, but the entry point and the feel are different. This project brings that interaction over — without any system-level modification.

Swipe diagonally inward from a bottom corner → the radial menu expands and follows your finger → release → the app opens as a floating window.

## Relationship to upstream

This project is a derivative work of [Mangi-11/FlymeFreeform](https://github.com/Mangi-11/FlymeFreeform). Upstream uses the **libxposed API** to hook SystemUI / system_server directly, which requires root or Xposed.

This repository rewrites the implementation from scratch: **nothing is hooked.** Instead it relies on three capabilities any ordinary app can obtain. The two share no code and can be installed side by side. See [NOTICE.md](NOTICE.md) for provenance and licensing details.

## How it works

| Capability | Carried by | Used for |
| -- | -- | -- |
| See | Accessibility service | `getWindows()` gives every window's bounds, which is how the floating window is detected and how the outside-tap mask is laid out |
| Draw | System overlay windows | Corner gesture strip, radial menu, outside-tap mask and app panel are all drawn by the app itself |
| Act | Accessibility gestures (default) | `dispatchGesture()` replays a "quick swipe up" on the little handle at the bottom of the floating window — that is ColorOS's own gesture-mode close action |
| Act (optional) | Shizuku (shell identity) | Reads the handle's real coordinates from the system log, injects touches, force-stops apps, and **writes the accessibility toggle back after the system clears it** |

One hard-won lesson is recorded in the source comments: `setMotionEventSources()` **truncates** input rather than observing it — once enabled, events stop being dispatched to the system and the device freezes. So global touch sensing here goes through "lay down our own transparent window and receive it", never through intercepting system input.

## Features

- **Corner gesture summon**: swipe diagonally inward from the bottom-left / bottom-right corner to open the radial menu, with haptic feedback as you pass over icons. If you release without resting on an icon, the wheel **stays on screen (sticky mode)** where swiping and tapping still vibrate — tap an icon to launch it, tap empty space to dismiss. Each corner can be toggled independently; touch-zone size and the screen-edge margin are configurable.
- **Radial menu**: the **span is fixed at 86°** — the number of items only decides how the icons are distributed along the arc, so adding or removing a pinned item never shifts the arc itself. "More" always occupies the slot at the low end. What you can tune is the **radius** (menu width / height), the **distance from the screen corner**, and the **icon diameter**. Only raw icons are drawn — no scrim, no labels — matching the look of Meizu's official screenshots. Icon size is uncapped: set it larger than the cell and icons will overlap, which is intentional.
- **Launch into a floating window**: `startActivity` with `windowingMode` first; falls back to Shizuku `am start` if that is refused.
- **Pinning**: up to 6 items in the radial menu, reorderable by **long-press drag** on the "Selected" strip in the panel or with ↑↓ in the standalone management page. A newly added item is inserted in the slot **closest to "More"**, and lands at the right end of the "Selected" strip.
- **The "More" panel**: a self-drawn rounded card, split into **Apps / Tools** tabs. The tabs are **two independent buttons** (not one segmented control on a grey background), and the two columns are **two independent pages** you swipe between (dragging follows your finger, releasing snaps). **A "Selected" strip sits above the tab bar** (it holds both apps and tools, shared by both pages); while you scroll it **shrinks its height and gets pushed off** following your finger, then returns when you stop — so it stays reachable without eating the screen. Below it on the Apps page come "Recent" (the last 8 **apps**, tools excluded) and an A–Z grid with an alphabet index on the right. Under the card there is also a **dock row** of up to 4 one-tap entries: no background, and only the icon body is tappable.
- **Adding and removing pins**: tap "Manage" in the top-right to enter manage mode — unselected items show a **green "＋"** (tap to add to the wheel), selected ones a **red "－"** (tap to remove). Both pin strips (the "Selected" strip and the dock under the card) also light up with red "－" in manage mode, one tap removes from that strip. Long-pressing an icon opens an action card with "Add / remove from wheel" and "Add / remove from dock".
- **Built-in tools (10)**: Screen text, screenshot, flashlight, recorder, notes, WeChat scan, Alipay scan, WeChat pay code, Alipay pay code, one-tap lock.
  - **Screen text**: replays a **two-finger long press** to invoke ColorOS's native "Xiao Bu screen recognition" (it exposes no public entry point — even the smart sidebar feature scheme used by similar third-party apps no longer works on ColorOS 17). If accessibility is unavailable it falls back to reading accessibility text itself.
  - **Scan / pay code**: explicit component + extras to jump straight to the right page (WeChat's `weixin://` functional schemes were closed off by a newer version; Alipay needs `chInfo=ch_oppoSide`).
  - **Screenshot**: taken through the accessibility API and saved to `Pictures/FlymeFreeform` — **no storage permission**, and no screen-capture consent dialog.
- **Tap outside to close**: tapping anywhere outside the floating window closes it. By default it replays a **quick swipe up** on the little handle at the bottom of the window (ColorOS gesture mode's own close action), with the anchor estimated from the window bounds — **no third-party dependency**. Two **calibration-free** alternatives live under "Advanced": read the handle's real coordinates (`mStartHandleBottomPoint`) from ColorOS's own log, then swipe up or single-tap on the real point. Both require Shizuku.
  - **This action has exactly one meaning: close the window.** To shrink it into the small floating bubble, use ColorOS's **native gesture** (swipe the handle yourself) — this app deliberately stays out of it. An earlier version tried to make "tap outside" shrink it instead; that does not work on real devices and broke the plain close path, so it was removed.
  - **The mask automatically avoids the soft keyboard**, so typing inside the floating window will not accidentally close it.
  - The page shows only the switch and the "open accessibility settings" entry by default; everything else lives in a collapsible **"Advanced (you normally don't need this)"** section.
- **Corner tap passthrough**: a press eaten by the corner strip is re-injected at the original coordinates and duration, equivalent to the user actually tapping there (long presses stay long presses).
- **Keeping the accessibility toggle alive**: ColorOS clears the accessibility toggle in several situations and the app itself may not write it back. Three channels cover this:
  - **Quick Settings tile**: bright = ready, dim = tap to write it back through Shizuku.
  - **Automatic restore**: on boot, after an app update, and once Shizuku connects, the toggle is checked and re-added — appending only itself, never disturbing other accessibility services.
  - **Persistent diagnostics**: key events are written to `filesDir/a11y-trace.log` (at most 60 lines, independent of the debug switch), surviving restarts and merged into the log page in settings.
  - In addition, every system callback is wrapped in `safeRefresh()`: an exception thrown from a callback makes the system **disable the accessibility service outright**, which the user perceives as "the permission disappeared after a reboot". Window events are coalesced into a single relayout as well, so the main thread is never held hostage by IPC long enough to be judged unresponsive.

### Where the settings are

The main screen is ordered as "Permissions & status → Settings → Debug", with three sub-pages under Settings:

- **Summon**: left / right corner toggles, touch-zone size and edge margin, menu appearance (width / height / corner distance / icon size), haptics, corner tap passthrough, icon pack, and "restore defaults".
- **Tap outside to close**: master switch and a shortcut to the accessibility settings; everything else is under "Advanced" (sides-only masking, close method, swipe distance/duration, anchor calibration and crosshair, Shizuku fallback and coverage overlay).
- **App management**: the items pinned to the wheel, reorderable and removable.

### Known limitations

- **Shrinking to a bubble on outside tap**: not implemented, by choice. "Tap outside" means close; use ColorOS's native handle swipe to shrink. Dragging the window's bottom-right corner to minimum yields a *close* on ColorOS 17, so there is no usable injection entry for it.
- **Xiao Bu screen recognition / One-tap flash note**: internal ColorOS features with no public entry point for third parties. The "Screen text" tool works by **replaying the two-finger long press** and letting the system respond.
- **Screen text cannot read text inside images**: it reads `text` / `contentDescription` that the UI actively exposes to accessibility. Images, video and web canvases (text drawn on a Canvas) are out of reach — the ceiling when no OCR engine is bundled.
- **The native sidebar's "All" panel cannot be reused** (it would require injecting a Binder into `com.coloros.smartsidebar`), hence the self-drawn "More" panel.
- **The corner strip is an exclusive window**: touches landing inside its rectangle are consumed by it. That is a side effect of how overlay windows work, and "corner tap passthrough" exists to mitigate it.
- **Corner gestures do not work with 3-button navigation** (by design, not a defect).
- **Only verified on ColorOS**: parameters are tuned against ColorOS's window behaviour and gesture semantics. Other ROMs (including stock Android) are not guaranteed to work.

## Permissions

This project asks for a fair number of permissions. Here is what each one is for — judge for yourself:

| Permission | Purpose |
| -- | -- |
| Display over other apps | Draw the corner strip, radial menu, mask and panel. The core permission |
| Accessibility service | Read window bounds to detect the floating window, inject gestures, and lay the outside-tap layer; the "Screen text" tool reads text the current UI exposes to accessibility (**processed in local memory only, never uploaded**); screenshot uses its capture API (no storage permission, no capture dialog) |
| Shizuku (`moe.shizuku.manager.permission.API_V23`) | Inject touches and run `am` / `settings` commands as shell. **Optional**: closing the window works without it (via accessibility gestures), but the calibration-free close methods, the force-stop fallback and writing the accessibility toggle back all need it |
| Query all installed apps | Render the app list and the "More" panel |
| Notifications / foreground service | Keep the corner gesture service alive, with a persistent notification |
| Run at startup | Restore the gesture service and Shizuku listener after a reboot or app update |
| Vibrate | Haptic feedback when passing over icons in the radial menu |
| Device admin | The "Lock" tool uses `lockNow()`; you must activate it manually in system settings (revocable at any time) |

Two notes: the **flashlight** uses `setTorchMode`, which needs no permission, so this app **does not request the camera permission**; the **screenshot** is written through MediaStore, so it **does not request storage permission** either.

## Building

Requires JDK 17, Android SDK Platform 37 (`platforms;android-37.0`) and build-tools 36.0.0. The SDK location goes in `sdk.dir` in the root `local.properties` (that file is not committed).

```bash
./gradlew :app:assembleDebug
```

Output: `app/build/outputs/apk/debug/FlymeFreeform-<version>-Debug.apk`

Build targets: `compileSdk 37` / `minSdk 35` / `targetSdk 37`. The version comes from `flymeFreeformVersionCode` and `flymeFreeformVersionName` in the root `gradle.properties`.

> Note: do **not** add the `org.jetbrains.kotlin.android` plugin to this module — AGP 9 ships Kotlin support, and declaring it again makes Gradle fail with “plugin is already on the classpath with an unknown version”.

## Download / CI

If you would rather not set up a build environment, grab an APK produced by GitHub Actions:

- **Every commit**: open the [Actions](../../actions/workflows/build.yml) page, pick the latest successful run, and download `FlymeFreeform-debug-apk` from the **Artifacts** section at the bottom.
- **Releases**: push a `v*` tag (e.g. `git tag v0.6.23 && git push origin v0.6.23`) and a Release is created automatically with the APK attached, ready to download and install.

CI lives in [`.github/workflows/build.yml`](.github/workflows/build.yml): JDK 17 + Android SDK (`platforms;android-37.0`, `build-tools;36.0.0`) + Gradle. The artifact is a **debug-signed** APK, installable as-is.

## Documentation

- [docs/no-root-verify.md](docs/no-root-verify.md) — on-device verification checklist: what each step proves, how to read the logs, how to tune the parameters. (Chinese)
- [docs/no-root-feasibility.md](docs/no-root-feasibility.md) — feasibility analysis for the no-root approach: which capabilities are reachable, which are not, and why. (Chinese)

## License

[GPL-3.0](LICENSE). As a derivative work of the upstream project, this project is distributed under the same terms; provenance, modifications and third-party components are documented in [NOTICE.md](NOTICE.md).

“Flyme” is a trademark of Zhuhai Meizu Technology Co., Ltd.; “ColorOS” is a trademark of OPPO Guangdong Mobile Communications Corp., Ltd. “WeChat” and “Alipay” are trademarks of Shenzhen Tencent Computer Systems Company Limited and Alipay (China) Network Technology Co., Ltd. respectively. This project is an unofficial, independent implementation for ColorOS, not affiliated with, authorized, endorsed or supported by any of the above.
