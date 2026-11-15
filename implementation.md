# StbLab GUI Implementation

## Project boundary

All implementation and build files live in `/Users/bharath/gitprojects/stblab-gui`. `/Users/bharath/gitprojects/android-mystb` is read-only reference material for behavior and protocol details.

## App structure

- Launch directly into the native Android TV **Picker**.
- Keep a visible **Settings** entry point on Picker.
- Put pairing/discovery, remote controls and text-input preference, channel/genre/favorite editing, JSON import/export, and help under Settings.
- Use Android UI components only. The app does not include a web server, hosted browser picker, HTTP API, WebView, or packaged web assets.

## Picker behavior

- Genre and channel columns with counts, dummy and redirect numbers, 11-item paging, wraparound selection, and per-genre channel-position memory.
- Show the virtual ⭐ Favorites genre first when there are favorites.
- Keep the NumPad collapsed by default; when opened, it provides digits, CH ▲/▼, Favorites, Recall, and OK.
- Preserve physical remote navigation, digit dialing, Favorites keycode 177, Recall keycode 229, and CH+/CH− stepping.
- Preserve three-digit direct dialing, dummy-number-first then redirect-number lookup, Favorites-first match selection, timeout feedback, tuning, last-tuned state, and previous-channel history.
- Show connection, power, volume/mute, current app, tuning status, and errors.

## Settings behavior

- **Airtel box pairing:** host/IP field, saved host, discovery by mDNS and subnet scan, pairing-code entry, pairing status/errors, and reconnect.
- **Remote controls:** directional/OK, Home/Back/Menu, Power, TV, volume/mute, text sending, and app/deep-link launch.
- **Remote text input:** ON/OFF preference with the gamepad/IME explanation; toggling reconnects the remote session.
- **Edit Channels & Genres:** genre/channel add, edit, delete and ordering; favorite toggles and favorite ordering; confirmations and validation.
- **Channel JSON:** load current, export, open a JSON file, edit, and apply after confirmation. Preserve v1 config validation and private channel storage.
- **Help/About:** picker navigation and remote-key mapping guidance.

## Architecture and data

- Native Android Views render Picker and Settings. UI state and focus behavior are managed in `MainActivity`.
- Android TV Remote v2 pairing, TLS sessions, reconnect, key/text/app-link operations, and tuning are handled by the copied protocol client under `app/src/main/java/com/xstream/remote/`.
- `ChannelStore` persists data atomically in app-private storage and seeds defaults only on first launch.
- Preserve the config shape `{ "version": 1, "genres": [...] }`, channel IDs, number fields, sort order, favorites, and favorite order.
- Keep private keys, paired credentials, and the peer fingerprint in app-private storage. Do not log or export them.
- Preserve application ID `com.stblab.gui`.

## Reference sources

These paths are in `/Users/bharath/gitprojects/android-mystb` and are read-only references:

- Picker and remote navigation: `web/app/page.tsx`
- Keypad: `web/app/components/VirtualKeypad.tsx`
- Channel/genre/favorite editing: `web/app/components/EditModal.tsx`
- JSON workflow: `web/app/components/JsonConfigModal.tsx`
- Channel data model and validation: `app/src/main/java/com/stblab/android/ChannelStore.kt`
- Pairing/setup behavior: `app/src/main/java/com/stblab/android/MainActivity.kt`
- Remote protocol: `app/src/main/java/com/xstream/remote/`

## Build

Open `stblab-gui` in Android Studio and build the `app` module, or run `./gradlew assembleDebug`. The debug APK is at `app/build/outputs/apk/debug/app-debug.apk`.
