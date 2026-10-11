#!/usr/bin/env python3
"""E2E driver for aShorts on the emulator, using the ReelsHarness fake Instagram.

All coordinates are deterministic: the harness lays its controls out at fixed fractions of the
real screen size, so no UI-tree scraping is needed. Screenshots are analyzed with PIL; harness
events are read from logcat; aShorts' own detection log is read via run-as.

Scenarios:
  T1  the patch covers the Reels tab cell (glyph invisible, neighbor cell visible)
  T2  tapping the covered tab does nothing (no Reels, no back press)
  T3  opening Reels "via a link" auto-backs out: exactly one back press, no cover ever shown
  T4  with "ignore all backs" on: cover appears; Leave hides it; it must NOT flash back
      instantly (the press-Leave-twice bug); it returns because back is genuinely ignored
  T5  aShorts' detection log recorded the tab and the reels screen; calibration lines, if any,
      are printed so placement issues come with exact numbers
"""
import io
import os
import re
import subprocess
import sys
import time
from PIL import Image

ADB = os.path.expanduser("~/android-sdk/platform-tools/adb")
HARNESS_PKG = "com.ashorts.blocker.harness"
BLOCKER_PKG = "com.ashorts.blocker"
ACCENT = (0x2E, 0x7D, 0x5B)  # Leave button color

results = []


def sh(*args, timeout=90):
    return subprocess.run([ADB, *args], capture_output=True, timeout=timeout)


def shell(cmd, timeout=90):
    return sh("shell", cmd, timeout).stdout.decode(errors="replace")


def tap(x, y):
    sh("shell", "input", "tap", str(x), str(y))


def screenshot():
    data = sh("exec-out", "screencap", "-p", timeout=120).stdout
    return Image.open(io.BytesIO(data)).convert("RGB")


def dark_ratio(img, box):
    region = img.crop(box)
    data = list(region.getdata())
    dark = sum(1 for (r, g, b) in data if r + g + b < 200)
    return dark / max(1, len(data))


def accent_box(img):
    """Bounding box of Leave-button-green pixels, or None."""
    w, h = img.size
    px = img.load()
    minx = miny = 10 ** 9
    maxx = maxy = -1
    for y in range(0, h, 4):
        for x in range(0, w, 4):
            r, g, b = px[x, y]
            if abs(r - ACCENT[0]) <= 30 and abs(g - ACCENT[1]) <= 30 and abs(b - ACCENT[2]) <= 30:
                minx, miny = min(minx, x), min(miny, y)
                maxx, maxy = max(maxx, x), max(maxy, y)
    if maxx < 0:
        return None
    return (minx, miny, maxx, maxy)


def wait_accent(present, timeout=8.0):
    deadline = time.time() + timeout
    while time.time() < deadline:
        box = accent_box(screenshot())
        if present == (box is not None):
            return box
        time.sleep(0.3)
    return accent_box(screenshot())


def harness_back_count():
    out = shell("logcat -d -s Harness:I")
    counts = re.findall(r"BACK_PRESSED count=(\d+)", out)
    return int(counts[-1]) if counts else 0


def check(name, ok, detail=""):
    results.append(ok)
    print(("PASS  " if ok else "FAIL  ") + name + ("   [" + detail + "]" if detail else ""))


def main():
    m = re.search(r"Physical size:\s*(\d+)x(\d+)", shell("wm size"))
    W, H = int(m.group(1)), int(m.group(2))
    print(f"Screen: {W}x{H}")

    open_x, open_y = W // 2, H * 30 // 100 + 70
    check_x, check_y = W // 2, H * 45 // 100 + 60
    tab_x, tab_y = W // 5 + W // 10, H - 300 + 75
    reels_cell = (W // 5 + 10, H - 290, 2 * W // 5 - 10, H - 160)
    home_cell = (10, H - 290, W // 5 - 10, H - 160)

    # Fresh start.
    shell(f"am force-stop {HARNESS_PKG}")
    shell("logcat -c")
    shell(f"am start -n {HARNESS_PKG}/.HarnessMainActivity")
    time.sleep(5)  # service settles, patch appears

    # T1: patch coverage.
    img = screenshot()
    r_reels = dark_ratio(img, reels_cell)
    r_home = dark_ratio(img, home_cell)
    check("T1 patch hides the Reels tab", r_reels < 0.01, f"dark={r_reels:.4f}")
    check("T1 neighbor tab still visible", r_home > 0.005, f"dark={r_home:.4f}")

    # T2: the covered tab must swallow taps.
    tap(tab_x, tab_y)
    time.sleep(2.5)
    img = screenshot()
    stayed = dark_ratio(img, (0, 0, W, H)) < 0.4
    check("T2 tap on covered tab opens nothing", stayed and harness_back_count() == 0)

    # T3: reels via link -> automatic back, no cover.
    shell("logcat -c")
    tap(open_x, open_y)
    time.sleep(4.0)
    count = harness_back_count()
    img = screenshot()
    on_main = dark_ratio(img, (0, 0, W, H)) < 0.4
    cover_visible = accent_box(img) is not None
    check("T3 auto-back left Reels (exactly 1 back)", count == 1, f"backs={count}")
    check("T3 back on main screen", on_main)
    check("T3 no cover was ever needed", not cover_visible)

    # T4: cover fallback when back is ignored.
    tap(check_x, check_y)  # enable "IGNORE ALL BACKS"
    time.sleep(0.5)
    shell("logcat -c")
    tap(open_x, open_y)
    box = wait_accent(present=True, timeout=10)
    count_after_auto = harness_back_count()
    check("T4 cover appeared (auto-back ignored)", box is not None, f"backs={count_after_auto}")
    if box:
        cx, cy = (box[0] + box[2]) // 2, (box[1] + box[3]) // 2
        tap(cx, cy)  # press Leave
        time.sleep(0.15)
        flash = accent_box(screenshot()) is not None
        check("T4 no instant re-cover after Leave", not flash)
        back_box = wait_accent(present=True, timeout=8)
        check("T4 cover returns (back genuinely failed)", back_box is not None,
              f"backs={harness_back_count()}")
    else:
        check("T4 no instant re-cover after Leave", False, "cover never appeared")
        check("T4 cover returns (back genuinely failed)", False, "cover never appeared")

    # T5: aShorts' detection log.
    log = shell(f"run-as {BLOCKER_PKG} cat files/detection_log.txt")
    check("T5 log records the tab node", "clips_tab" in log)
    check("T5 log records the reels screen", "clips_viewer_debug_container" in log)
    calib = [line for line in log.splitlines() if "calibration" in line]
    print("Calibration lines: " + ("\n  ".join(calib) if calib else "none (placement was exact)"))

    failed = results.count(False)
    print(f"\n{len(results) - failed}/{len(results)} checks passed")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
