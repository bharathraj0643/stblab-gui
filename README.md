# StbLab GUI

Native Android TV application for the StbLab picker and Airtel Remote v2 controls. The app launches directly into the native Picker; pairing, LAN server management, editing, JSON import/export, and help are under Settings.

## Build

Open this repository in Android Studio and build the `app` module, or run `./gradlew assembleDebug` from this directory. The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

The app includes a LAN API host on port 8080 for external browser clients. The Android app's own Picker and Settings UI are native and do not use WebView.

## Reference boundary

`/Users/bharath/gitprojects/android-mystb` is behavioral and technical reference material only. The app implementation and build configuration live in this repository.
