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

If the Accessibility service is disabled or Android denies screenshot access,
the app explains the issue and stops the bot rather than sending gestures based
on missing screenshots. If Android shows the service as restricted, open the
app-info page from the setup screen and use its three-dot menu to allow
restricted settings, then return and enable the service.

There is no separate screen-capture settings page for this implementation.
Screen capture is provided by the enabled Accessibility service through its
declared screenshot capability; the app does not use MediaProjection.

The accessibility API captures the default display; this app does not use
MediaProjection or ImageReader. It samples small grayscale patches around the
player and eight nearby directions every 500 ms (nominally at most two captures
per second). Sobel edge density and grayscale variance are used as a color-
independent bush-texture heuristic; HSV green ratio plus low edge density
estimates flat toxic-zone patches. These thresholds are heuristic, not trained
or calibrated against a particular game. Secure windows, game-specific
effects, letterboxing, rotation, UI overlays, or changed layouts can make
results inaccurate. Validate against real screenshots from the target app.

Directional joystick paths last 450 ms and are renewed on the 500 ms loop.
When the center patch looks bush-like, the joystick traces a circular path;
when the center or nearby sector looks like a flat toxic-green zone, the bot
heads toward the strongest nearby bush-like patch. These are local visual
heuristics, not map-wide path planning or a guarantee of a safe destination.

The 90/120 Hz display refresh rate does not increase this polling rate. A brief
color change between screenshots can be missed. Screenshot and gesture access
depend on user-enabled Accessibility settings and platform/device restrictions.
The floating control is a user-started foreground service (`specialUse` on
Android 14+) with an ongoing notification. Notification permission is requested
as a manifest permission, but is not required to start the foreground service;
without notification permission Android may show the service notification only
in its active-app/task-manager interface.