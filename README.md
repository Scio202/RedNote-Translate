# RedNote (小红书) Translate

Live in-place translation for RedNote (小红书 / `com.xingin.xhs`) on Android, without
touching the app's own auto-translate — so your feed and recommendations stay exactly
as they are.

## How it works

Android has no extension API, so nothing can inject into another app's UI. The closest
mechanism is an **AccessibilityService**:

1. RedNote's on-screen text is read from the accessibility tree, with each string's exact
   screen rectangle.
2. Unique strings go to the selected engine: **on-device** (ML Kit, offline),
   **Google** (the web endpoint browser clients call - cloud quality, no key), or
   **DeepSeek** (best on Chinese slang; needs your API key). A cloud engine that
   fails falls through to on-device, so the overlay never goes blank.
3. A translated label is drawn over each original at its exact bounds, in a
   `TYPE_ACCESSIBILITY_OVERLAY` window.

The overlay is `FLAG_NOT_TOUCHABLE`, so every tap, swipe and scroll passes straight
through to RedNote. `TYPE_ACCESSIBILITY_OVERLAY` also means **no "draw over other apps"
permission** is needed.

### On the package filter

The service originally declared `android:packageNames="com.xingin.xhs"`, so Android
delivered it nothing but RedNote events. That had to be relaxed: with the filter on, the
service never learns that RedNote left the foreground, and the labels keep floating over
whatever replaced it — the notification shade, the launcher, this app's own settings screen.

It now receives window events from every app, but only ever *reads a tree* when
`rootInActiveWindow` belongs to `com.xingin.xhs`. Every other event is used for exactly one
decision: is RedNote still in front, and if not, drop the overlay.

## API key

The DeepSeek key is encrypted with a hardware-backed AES key from the Android Keystore and
stored as ciphertext; the Keystore key is non-extractable, so a copy of the prefs file is
useless on its own. Viewing or changing it requires fingerprint, face or device PIN.

It is deliberately readable *without* authentication at translation time - the accessibility
service runs in the background and cannot put a prompt in your way. Authentication guards the
UI, where a person is present to answer it.

## Limits

- **Text baked into images or video is not translated.** Only real text nodes. Adding OCR
  (ML Kit text recognition + MediaProjection) is the upgrade path if that matters.
- **Grid feed titles are approximate.** RedNote publishes each feed card as a single merged
  accessibility node whose description holds the title; the title has no node of its own, so
  there is no exact rectangle to cover. The band is anchored between the estimated image
  bottom and the author row. A card scrolled half off the top of the screen reports clipped
  bounds and can leave its first line showing. Note bodies, comments and author names *are*
  real nodes and get covered exactly.
- Machine translation of hashtag strings is poor, because the source is barely a sentence.
- Labels clear the instant you scroll and redraw ~280ms after it settles, and bounds are
  re-measured after translation, so a label never lands on a position that has moved.

## Install

Grab the APK from [Releases](../../releases) and open it on the phone (allow install
from unknown sources). Then turn the service on under
**Settings > Accessibility > Installed apps > RedNote Translate**.

A Quick Settings tile is included so you can pause the overlay without leaving RedNote.

## Build it yourself

```
gradlew assembleRelease
```

Needs JDK 17 and an Android SDK; `tools/setup.ps1` installs both into
`%USERPROFILE%\android-toolchain` on Windows if you have neither. Output lands in
`app/build/outputs/apk/release/`.

## UI

The settings screen follows One UI (24dp keylines, 26dp flat cards, collapsing large title,
true-black dark mode) and takes its accent colour from the Galaxy theme via Android dynamic
colour, so it matches whatever palette is set in Wallpaper and style. The rules are written
up in `.claude/skills/oneui-design/SKILL.md`.
