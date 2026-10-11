# Publishing aShorts on Google Play

Everything that can live in the repo is ready. This file covers the Play Console steps, with the
exact answers to give. Steps marked **(you)** need your account or your phone.

| What | Where |
| --- | --- |
| Signed bundle | `./gradlew :app:bundleRelease` → `app/build/outputs/bundle/release/app-release.aab` |
| Upload key | `~/keys/ashorts-upload.jks` + `keystore.properties` (both private, never committed) |
| Listing text + icon + feature graphic | `fastlane/metadata/android/en-US/` |
| Privacy policy | `play/privacy-policy.md` → `python3 tools/build_privacy_html.py --email you@…` |
| Offline policy scan | `gplay preflight --file app/build/outputs/bundle/release/app-release.aab --listings-dir fastlane/metadata/android` |

## 0. Back up the upload key (you)

Copy `~/keys/ashorts-upload.jks` and `keystore.properties` to a password manager or an encrypted
backup. With Play App Signing (the default), Google holds the real app-signing key; if the upload
key is lost it can be reset through Play support, but that takes days.

## 1. Create the app (you)

Play Console → Create app: name **aShorts**, default language English (US), **App**, **Free**.
Accept the declarations.

## 2. App content forms

Play Console → Policy and programs → App content.

**Privacy policy** — the public URL where `play/privacy-policy.html` is hosted.

**Ads** — No, the app does not contain ads.

**App access** — All functionality is available without special access. Add this note for the
reviewer: "No login. aShorts works inside the Instagram app: install Instagram and sign in with any
account, then open aShorts, tap Turn on, agree, enable aShorts in Accessibility settings, and allow
display over other apps. Opening the Reels tab in Instagram is then blocked."

**Content rating** — category: All other app types (utility). Answer No to every content question
(violence, sexuality, language, controlled substances, gambling, user interaction/sharing,
location sharing, digital purchases). Expected result: Everyone / PEGI 3.

**Target audience** — 13–15, 16–17 and 18+. Do not select any age under 13 (that puts the app
under the Families policy). "Could the app unintentionally appeal to children?" → No.

**News app** — No. **Government app** — No. **Financial features** — none. **Health** — none.

**Data safety** (task 4):

- Does your app collect or share any of the required user data types? → **No**.
  Reason: nothing leaves the device (no INTERNET permission). Play only counts data as
  "collected" when it is sent off the device; on-device processing does not count.
- Because the answer is No, the encryption and deletion questions do not apply.
- Result shown on the listing: "No data collected", "No data shared with third parties".

**Sensitive permissions and APIs → Accessibility API** (task 2):

1. Is the app an accessibility tool? → **No** (the manifest sets `isAccessibilityTool="false"`).
2. Why does your app need the Accessibility Services API? → **App functionality** only.
3. Describe the feature (paste):
   > aShorts is a digital-wellbeing app that blocks Instagram's Reels feed. Its
   > AccessibilityService listens only while the Instagram app is in the foreground and reads the
   > view identifiers and positions of on-screen elements to find two things: the Reels tab in the
   > bottom bar and the Reels viewer. When the Reels tab is visible, the app draws an overlay over
   > it so it cannot be tapped. When the Reels viewer opens, the app performs a single Back action
   > (performGlobalAction(GLOBAL_ACTION_BACK)) to close it. This is fixed, rule-based behavior:
   > if Reels is shown, press Back. The app has no INTERNET permission. It does not collect,
   > store or share any personal or sensitive data, and it does not read text input or message
   > content. An in-app prominent disclosure explains this and requires the user to tap Agree
   > before the app opens Accessibility settings.
4. Do you collect and/or share personal or sensitive data using the accessibility capabilities? →
   **No**.
5. Video link (YouTube unlisted or Google Drive "anyone with the link") — see the script below.

## 3. Disclosure video (you, about 60–90 seconds, screen recording on your phone)

Turn aShorts off in Accessibility settings first, then record:

1. Open aShorts from the launcher. The main screen shows "Accessibility service is off".
2. Tap **Turn on**. The disclosure dialog appears; pause so all the text is readable (scroll it
   slowly if it does not fit).
3. Tap **No thanks**. Show that nothing happens and the service stays off.
4. Tap **Turn on** again. The disclosure shows again. Tap **Agree**.
5. Android's Accessibility settings open. Enable aShorts and accept the system dialog. Return,
   and allow "display over other apps" if asked.
6. Open Instagram: the Reels tab is covered and tapping it does nothing. Open a Reel from a
   shared link or a profile: aShorts closes it right away.
7. Optional caption: "aShorts reads only Instagram's on-screen layout to find Reels, and nothing
   leaves the device."

## 4. Store listing (task 6)

Play Console → Grow → Store presence → Main store listing. Text and graphics are in
`fastlane/metadata/android/en-US/` (title 22/30, short description 75/80, full description
1033/4000 characters; icon 512×512; feature graphic 1024×500).

**Phone screenshots (you):** Play needs at least 2 (portrait, 1080×1920 to 1080×2400 is ideal).
Suggested set, captured on your phone:

1. aShorts main screen with "Blocking Reels".
2. Instagram home with the Reels tab covered.
3. The disclosure dialog (shows reviewers and users what it reads).
4. The fallback "Reels is blocked" screen.

Save them as `fastlane/metadata/android/en-US/images/phoneScreenshots/1.png`, `2.png`, … They may
show your feed: crop or use a test account so no personal content appears.

After the first manual upload you can push the listing with gplay:

```bash
P=com.ashorts.blocker; D=fastlane/metadata/android
EDIT=$(gplay edits create --package $P | jq -r .id)
gplay sync import-listings --package $P --edit "$EDIT" --dir $D --dry-run   # review first
gplay sync import-listings --package $P --edit "$EDIT" --dir $D
gplay sync import-images   --package $P --edit "$EDIT" --dir $D
gplay edits validate --package $P --edit "$EDIT" && gplay edits commit --package $P --edit "$EDIT"
```

## 5. Closed testing (task 5)

Personal developer accounts created after 13 November 2023 must run a closed test with at least
12 testers opted in for 14 days in a row before they can apply for production. Organization
accounts skip this.

1. Testing → Closed testing → Create track (or use "Alpha"). Add testers by email list or a
   Google Group.
2. Create a release, upload `app-release.aab` (the first upload has to be done in the Console),
   release notes: "First test build: Reels tab hidden, Reels closed automatically, strict mode."
3. Accept Play App Signing when asked (the default).
4. Send testers the opt-in link. Keep at least 12 opted in for 14 days; they should open the app
   a few times. Then apply for production access from the Dashboard.

Later uploads can go through gplay once a service account is linked
(`gplay setup --auto` or `gplay auth login --service-account /path/key.json`; keep that JSON out
of the repo). See the `gplay-release-flow` skill.

## Known preflight warnings (expected)

- `SYSTEM_ALERT_WINDOW`: needed to draw the cover. It is not a restricted permission on Play and
  needs no declaration form, but it is explained in the listing and disclosure.
- `restricted_service` (AccessibilityService): allowed for non-accessibility tools with the
  prominent disclosure + Accessibility declaration above.
- `misplaced_files: BUNDLE-METADATA/.../app-metadata.properties`: written by the Android Gradle
  plugin into every bundle; Play reads it. Not a secret.
