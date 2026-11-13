# ember for Android

This port uses the same sun model as the Linux daemon. Android doesn't let regular apps change the display's colour matrix without root, so ember draws a warm, see-through overlay that touches pass through. Twilight-style filters work the same way.

- Same day/night/late-night/wake-time model as the desktop version
- Stops recomputing while the screen is off and catches up the moment it turns on
- Updates adaptively: every 5 minutes when the target is flat, every 30 s during transitions
- Has notification actions for pause and turn-off, and restarts after reboot
- Uses no AndroidX or Gradle, so the APK stays small
- Needs Android 10 or newer

## Covering the status bar and notifications

A normal app overlay sits below the status bar, navigation bar, notification shade and quick settings. To tint those as well, tap **Cover status bar & notifications…** in the app. Then turn on **Ember (cover system UI)** under Accessibility → Downloaded apps. Ember switches to an accessibility overlay, which draws above system UI, and drops its notification.

The accessibility service subscribes to no events and can't read screen content. It exists only to get that window type. On Android 13+, sideloaded apps can't turn on accessibility until you allow it: go to App info → ⋮ → **Allow restricted settings**, then try again.

## Install

Download `ember.apk` from the [Releases](https://github.com/abyts1/ember/releases) page and allow **Display over other apps** when asked.

## Build

You need JDK 17 or newer and the Android SDK command-line tools:

```sh
sdkmanager "platforms;android-35" "build-tools;35.0.0"
ANDROID_HOME=~/Android/Sdk ./build.sh     # -> build/ember.apk
```

The first build creates a signing key at `~/.android/ember-release.jks`. Keep it, because later updates must be signed with the same key. Set `KS_PASS` to use a password other than the default.

## Limitations

An overlay can only add a tint. It can't remove blue light the way a colour matrix does, so very warm settings also dim the screen a little. Opacity is capped at 75%, below Android 12's 80% limit for overlays that let touches through. Without the accessibility service, some system screens such as permission dialogs hide the overlay by design.
