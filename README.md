# aShorts

An Android app that blocks **Instagram Reels**. No root, no Instagram modification, and no
network access — the app holds no internet permission and cannot send data anywhere.

**Status:** v0.1.0 — Instagram Reels only. X (Twitter) videos and YouTube Shorts are planned;
the detection engine is app-agnostic, so each new target is mostly a new rules file.

## What it does

- **Hides the Reels tab** — a small patch is drawn exactly over the Reels button in Instagram's
  bottom bar. The button is invisible and untappable.
- **Blocks the Reels feed** — if Reels opens anyway (from a DM, a link, search, or a profile),
  a full-screen blocker covers it with a single obvious way out: *Leave Reels* (presses back),
  *Go to Home screen*, or open aShorts settings.
- **Strict mode** — turning blocking off requires sitting through a countdown (default 60 s) and
  the countdown cancels if you leave the app. Impulse-toggling is deliberately painful.
- **Zero manual activation** — the accessibility service is always on once enabled in system
  settings; you never open aShorts before opening Instagram.

## How it works

An `AccessibilityService` receives window/content-change events. It does a single package check
and returns immediately unless the event concerns a visible Instagram window. When Instagram is
on screen, it walks the view-hierarchy (breadth-first, capped at ~450 nodes / depth 30, throttled
to ~4 scans/s) and matches node ids and content descriptions against the patterns in
`InstagramRules`. Matches trigger overlay windows (`TYPE_APPLICATION_OVERLAY`) that cover the
Reels tab or the whole Reels feed. While Instagram is closed, the service does effectively
nothing — no scans run, and events from other apps are dismissed with one string comparison.

## Installing on your phone

Requirements: Android 8.0+ (API 26). The debug APK is at `aShorts-v0.1.0-debug.apk`
(rebuilt at `app/build/outputs/apk/debug/app-debug.apk`).

1. Install the APK (`adb install -r aShorts-v0.1.0-debug.apk`, or copy it to the phone and
   allow installing from that source when prompted).
2. Open aShorts. Grant **Display over other apps**.
3. Enable the **accessibility service**:
   - Settings → Accessibility → Installed apps → aShorts → on.
   - **Android 13+:** if the switch is greyed out (sideloaded apps get "restricted settings"),
     go to Settings → Apps → aShorts → ⋮ (top right) → *Allow restricted settings*, then go
     back to step 3.
4. Open Instagram. The Reels tab should be covered. Tapping through from a DM or link shows the
   full-screen blocker.

To uninstall: turn the accessibility service off first, then uninstall normally.

## Settings explained

| Setting | Meaning |
| --- | --- |
| Block Instagram Reels | Master switch. Turning it **off** triggers the strict cooldown. |
| Hide the Reels tab | The patch over the bottom-bar button. |
| Full-screen blocker | The cover shown when the Reels feed itself is visible. |
| Aggressive matching (beta) | Also treats any screen described as "Reels" as the feed. Can over-block (e.g. in DMs) — use it when id-based detection stops matching after an Instagram update. |
| Wait before disabling | The strict-mode cooldown length (off / 30 s / 1 min / 2 min / 5 min). |

## Troubleshooting

Instagram renames its internal view ids regularly. If blocking stops working after an update:

1. Open aShorts → **Detection log**. It records every match (view id, description, bounds) and
   timestamps — check that *something* still fires when you open Instagram.
2. If nothing fires, try **Aggressive matching**.
3. If it still fails, update the substring lists in
   `app/src/main/java/com/ashorts/blocker/engine/InstagramRules.kt` — the log (shareable as
   text) tells you what the relevant nodes are called now. The engine and tests stay untouched.

If the patch sits in the wrong place for a moment after rotating the screen: it is hidden on
rotation and repositioned on the next scan; that is expected.

## Building from source

```bash
# JDK 17+ and an Android SDK with platform 35 are required (see local.properties)
./gradlew assembleDebug          # APK in app/build/outputs/apk/debug/
./gradlew testDebugUnitTest      # engine unit tests (pure JVM)
```

Stack: Kotlin 2.1, AGP 8.7, Jetpack Compose (Material 3), minSdk 26 / targetSdk 35.

## Roadmap

- YouTube Shorts (new `AppBlockRules` + package wiring)
- X/Twitter video tab (same)
- Optional "temporary allow" escape hatch on the blocker screen
- Locale-aware tab descriptions

## Disclaimer

aShorts is a personal-use, unaffiliated tool. It is not endorsed by or connected to Meta or
Instagram. It reads Instagram's on-screen layout locally, on your device, only to find the
Reels UI — and nothing ever leaves the device.
