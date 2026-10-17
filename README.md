# Simple Color Bot

Minimal Android test app for sampling screenshot pixels with `Bitmap.getPixel()`
and dispatching gestures through a user-enabled `AccessibilityService`.

## Build

Open this directory in Android Studio with Android SDK 35 installed, or run:

```sh
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. The GitHub
Actions workflow also publishes that APK as the `simple-color-bot-debug-apk`
artifact for each push, pull request, or manual workflow run.

## Device setup

1. Install and open the app.
2. Tap **Bot starten & Rechte erteilen**.
3. Allow display over other apps, then enable **Color Bot test service** when
   Android opens each settings page.
4. The floating overlay starts automatically. Tap its **START** button to run
   the bot and tap again to stop it.

There is no separate screen-capture settings page for this implementation.
Screen capture is provided by the enabled Accessibility service through its
declared screenshot capability; the app does not use MediaProjection.

The accessibility API captures the default display; this app does not use
MediaProjection or ImageReader. It samples a vertical line at the screen center,
scaled from the configured 1280x720 reference coordinates, every 500 ms
(nominally at most two captures per second). Color
matching uses tolerances around the sample RGB colors; it is a simple heuristic,
not robust scene recognition. Secure windows, game-specific color effects,
letterboxing, rotation, UI overlays, or changed game layouts can make results
inaccurate. Validate against real screenshots from the target app before use.

The 90/120 Hz display refresh rate does not increase this polling rate. A brief
color change between screenshots can be missed. Screenshot and gesture access
depend on user-enabled Accessibility settings and platform/device restrictions.
The floating control is a user-started foreground service (`specialUse` on
Android 14+) with an ongoing notification. Notification permission is requested
as a manifest permission, but is not required to start the foreground service;
without notification permission Android may show the service notification only
in its active-app/task-manager interface.