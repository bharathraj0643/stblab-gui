# StbLab GUI

Native Android TV application for the StbLab picker and Airtel Remote v2 controls. The app launches directly into the native Picker; pairing, remote controls, editing, JSON import/export, and help are under Settings.

## Build

Open this repository in Android Studio and build the `app` module, or run `./gradlew assembleDebug` from this directory. The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

The app has no LAN web server or browser UI. All app screens are native Android components.

## Reference boundary

`/Users/bharath/gitprojects/android-mystb` is behavioral and technical reference material only. The app implementation and build configuration live in this repository.


## Choose a setup

| Folder | Setup | How it works | Download |
| --- | --- | --- | --- |
| [`method-1_laptop`](https://github.com/bharathraj0643/stblab) | Laptop and browser | Runs the backend on a laptop. Open the web page from a phone or TV browser on the same network. | [releases](https://github.com/bharathraj0643/stblab/releases) |
| [`method-2_phone-or-tv`](https://github.com/bharathraj0643/stblab-phone-or-tv) | Android host | Runs the backend on android host and remote client on any browser tv or phone. | [releases](https://github.com/bharathraj0643/stblab-phone-or-tv/releases) |
| [`method-3_gui-recommended-method`](https://github.com/bharathraj0643/stblab-gui) | Native Android TV app | Runs the picker and remote controls directly as a native Android app, without a LAN web server or browser. intended to be used on android tv only, although it runs on android phone too, but ui controls are designed for a tv remote. | [releases](https://github.com/bharathraj0643/stblab-gui/releases) |