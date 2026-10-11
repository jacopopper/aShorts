# aShorts

**Keep the app, lose the scroll.**

An Android app that blocks **Instagram Reels**. No root, no Instagram modification, and no
network access — the app holds no internet permission and cannot send data anywhere.

**Status:** v0.2.0 — Instagram Reels only. X (Twitter) videos and YouTube Shorts are planned;
the detection engine is app-agnostic, so each new target is mostly a new rules file.

## What it does

- **Hides the Reels tab** — a patch is drawn over the Reels button's full tappable cell in
  Instagram's bottom bar (matched to the bar color, so it looks like the tab was never there)
  and swallows any taps.
- **Leaves Reels instantly** — if the Reels feed opens anyway (from a DM, a link, search, or a
  profile), aShorts presses Back for you the moment it appears. The feed closes and its audio
  stops; you land back wherever you came from.
- **Minimal fallback cover** — only if the automatic Back fails (deep links, stubborn back
  stacks) does a calm full-screen cover appear with a single button: *Leave*. No menus, no
  extra buttons.
- **Strict mode** — turning blocking off requires sitting through a countdown (default 60 s)
  that cancels if you leave the app. Impulse-toggling is deliberately painful.
- **Zero manual activation** — the accessibility service is always on once enabled in system
  settings; you never open aShorts before opening Instagram.

## How it works

An `AccessibilityService` receives window/content-change events. It does a single package check
and returns immediately unless a visible Instagram window produced the event. When Instagram is
on screen, it walks the view hierarchy (breadth-first, capped at ~450 nodes / depth 30,
throttled to ~4 scans/s) and matches node ids and content descriptions against the patterns in
`InstagramRules`. The matched tab node's bounds are expanded up its parent chain so the patch
covers the whole cell and the full bar height. While Instagram is closed, the service does
effectively nothing. Memory footprint stays flat: node references are transient, only a tiny
patch window persists while the bottom bar is visible.

## Installing and updating on your phone

Requirements: Android 8.0+ (API 26), phone and PC on the same Wi-Fi.

1. Build and publish: `./gradlew assembleDebug && tools/publish-latest.sh`
2. Serve (leave running): `tools/serve-latest.sh`
3. Scan `apk-qr.png` with the phone camera → install.

**Updates are in place.** Every build is signed with the same debug key and bumps its
versionCode, so Android installs right over the old version: no uninstalling, all data and
settings kept. The QR never changes — it always points at `http://<pc-ip>:8000/latest.apk`.

First-run setup on the phone:

1. Open aShorts. Grant **Display over other apps**.
2. Enable the **accessibility service**: Settings → Accessibility → Installed apps → aShorts → on.
   - **Android 13+:** if the switch is greyed out (sideloaded apps get "restricted settings"),
     go to Settings → Apps → aShorts → ⋮ → *Allow restricted settings*, then try again.
   - When asked for a shortcut activation method (accessibility button / volume keys), prefer
     the two-key combo ("side and volume up"): it can't be triggered by accident, and any
     system shortcut bypasses aShorts' strict cooldown.
3. Open Instagram. The Reels tab should be covered; opening Reels from a DM should bounce you
   straight back out.

To uninstall: turn the accessibility service off first, then uninstall normally.

## Settings explained

| Setting | Meaning |
| --- | --- |
| Block Instagram Reels | Master switch. Turning it **off** triggers the strict countdown. |
| Hide the Reels tab | The patch over the bottom-bar button. Its color follows the phone's dark/light mode. |
| Fallback cover | The minimal one-button cover, shown only if up to three automatic Backs fail to leave Reels. |
| Wait before disabling | The strict-mode countdown length (off / 30 s / 1 min / 2 min / 5 min). |

## Troubleshooting

Instagram renames its internal view ids regularly. If blocking stops working after an update:

1. Open aShorts → **Detection log**. It records every match (view id, description, bounds) and
   timestamps — check that *something* still fires when you open Instagram.
2. If nothing fires, update the substring lists in
   `app/src/main/java/com/ashorts/blocker/engine/InstagramRules.kt` — the log (shareable as
   text) tells you what the relevant nodes are called now. The engine and tests stay untouched.

If the patch sits in the wrong place for a moment after rotating the screen: it is hidden on
rotation and repositioned on the next scan; that is expected.

## Building from source

```bash
# JDK 17+ and an Android SDK with platform 36 are required (see local.properties)
./gradlew assembleDebug          # APK in app/build/outputs/apk/debug/
./gradlew testDebugUnitTest      # engine, gatekeeper and calibration tests (pure JVM)
./gradlew :app:bundleRelease     # signed Play bundle (needs keystore.properties, see .example)
python3 tools/render_store_assets.py   # Play icon + feature graphic from the launcher icon
```

Stack: Kotlin 2.1, AGP 8.10, Jetpack Compose (Material 3), minSdk 26 / targetSdk 36.

Publishing on Google Play: see [play/PLAY-CONSOLE.md](play/PLAY-CONSOLE.md).

## Testing

Three layers, so phone installs are (hopefully) boring:

1. **Engine tests** (`testDebugUnitTest`, pure JVM). Detection rules, patch-bounds expansion,
   the patch-calibration math, and the full decision timeline in `ReelsGatekeeperTest` —
   including a replay of real on-device logs: enter Reels → auto back → lingering tree →
   cover → manual leave → the cover must *not* flash back (the "press Leave twice" bug).
2. **Device E2E** (`tools/e2e.sh`). If an adb device is attached (a phone over USB — the
   recommended way to run it), the suite drives *that* device; otherwise it boots the `ashorts`
   AVD, which requires hardware acceleration (`/dev/kvm` — the current dev box has no KVM, so
   on it the suite only runs against a real phone). It installs `harness/` — a tiny fake
   Instagram reproducing the exact structure aShorts matches (`clips_tab` button,
   `clips_viewer_debug_container` screen, with a switch to ignore back presses) — and drives
   five scenarios with screenshots and logcat:
   patch covers the tab / covered tab swallows taps / link-opened Reels auto-backs out /
   cover fallback appears only when back fails / Leave hides the cover without flashing back.
3. **On-device observability.** The patch self-calibrates: it reads where its window actually
   rendered, corrects any OEM offset, and writes the numbers to the Detection log
   (`patch calibration: asked=[..] rendered=[..] corrected=[..]`). If placement is still off
   on a specific phone, that log line contains everything needed to fix it.

## Roadmap

- YouTube Shorts (new `AppBlockRules` + package wiring)
- X/Twitter video tab (same)
- Locale-aware tab descriptions
- Optional "temporary allow" escape hatch on the fallback cover

## Disclaimer

aShorts is a personal-use, unaffiliated tool. It is not endorsed by or connected to Meta or
Instagram. It reads Instagram's on-screen layout locally, on your device, only to find the
Reels UI — and nothing ever leaves the device.
