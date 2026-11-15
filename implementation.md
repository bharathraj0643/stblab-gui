# StbLab GUI Implementation Plan

## Project boundary

All implementation work for this task belongs in `/Users/bharath/gitprojects/stblab-gui`. This repository is the product and code destination. Create the complete application here, including Android project files, native UI, app services, assets, and documentation.

`/Users/bharath/gitprojects/android-mystb` is a read-only reference project. Inspect it to understand current behavior and reuse its feature requirements as guidance, but do not implement changes there and do not treat it as the destination project. The source paths listed below identify reference material only.

## Objective

Build a single, self-contained Android TV GUI app in `stblab-gui`. Its launcher opens directly to the native **Picker**. A visible **Settings** button opens the home for all other features: server management, Airtel pairing/discovery, remote settings, channel/genre/favorite editing, JSON configuration, and help.

Replace the web picker UI with native Android UI components. Preserve the current UX: the same actions, button meanings, directional navigation, selection/tuning behavior, ordering, feedback, and predictable Back paths. The app UI must not render through WebView or depend on a browser/Next.js runtime.

## Reference feature inventory

The reference project already contains the relevant behavior and service patterns:

- Its Android activity shows server status, LAN address, Airtel discovery and pairing, reconnect, and the remote text-input setting.
- Its foreground host service uses Ktor on port 8080, retries failures, and starts at boot.
- Its Remote v2 client and protocol sources implement pairing, TLS control, credential storage, reconnect, key/text/app-link commands, and tuning.
- Its channel store persists genres, channels, favorites, and JSON configuration in app-private storage.
- Its route layer exposes remote and channel operations, and serves a separately built web picker.
- Its web picker implements genres, paginated channel rows, favorites, direct dialing, keypad, telemetry, tuning, and physical remote key handling.
- Its edit dialog implements genre/channel CRUD, favorites, and ordering. Its JSON dialog implements load, export, file import, edit, validation, and apply.

Use these as behavioral references while implementing equivalent native features in `stblab-gui`. Do not couple the new application to the reference project's build or source tree at runtime.

## UX contract

### Top-level navigation

1. App launch always opens **Picker**.
2. Picker has a visible **Settings** button in a consistent position.
3. Settings is the only top-level home for setup and maintenance features.
4. Back from a Settings subsection returns to Settings; Back from Settings returns to Picker. Back from Picker follows normal Android TV behavior and never unexpectedly opens Settings or interrupts a tune.
5. OK/Enter activates the focused item. Back closes a dialog or navigates one level up.

### Picker

- Show a **Genre** column with channel counts. Add a virtual **⭐ Favorites** genre first whenever favorites exist. Selecting a genre restores its last selected channel position.
- Show a **Channel** column with dummy number, channel name, and redirect number. Preserve the 11-channel page size, page count, previous/next page actions, and cyclic selection behavior.
- Show connection, power, volume/mute, current app, and last-tuned telemetry.
- Keep **NumPad** collapsed by default. When opened, show 0–9, CH ▲, CH ▼, Favorites, Recall, and OK. CH ▲/▼ act on the currently focused genre or channel list. OK tunes the selection or resolves an active dial buffer. Favorites selects the Favorites genre. Recall selects and tunes the previous channel from the two-entry tune history.
- Support direct dialing from physical remote digits and the virtual pad. Preserve the three-digit maximum, dummy-number-first then redirect-number lookup, Favorites-first resolution, match/progress feedback, 3-second timeout, and shorter completed three-digit delay.
- Physical CH+/CH− immediately changes selection and shows a preview/countdown; fast-tune after 3 seconds without another navigation action. Any other key cancels the pending auto-tune.
- Preserve tuning feedback, last-tuned display, transient errors, and picker selection on failure.
- Preserve physical remote navigation and key mappings: directional, OK/Enter, digits, CH+/CH−, Previous/Recall (keycode 229), and Favorites (keycode 177). The `e` edit shortcut may be omitted if it conflicts with native text entry; Settings is the supported native route to editing.
- Keep the existing three-area hierarchy (genres, channels, status/actions) and recognizable visual priority, adapting dimensions and focus indication to Android TV.

### Settings

All non-picker features live under Settings, using a focusable list and native subpages/dialogs:

1. **Server:** live/offline/starting/retrying state, LAN URL and port 8080, errors, and server actions. Retain **STOP SERVER & EXIT** semantics where the target platform supports them. Explain that hosting serves external LAN browser clients; the native Picker does not load that web page.
2. **Airtel box pairing:** host/IP field, saved host, **FIND AIRTEL BOX**, discovery results, **PAIR WITH AIRTEL**, six-character code, **CONFIRM PAIRING CODE**, pairing status/errors, and **RECONNECT**. Preserve mDNS/subnet discovery and focus transfer from a selected result to pairing. Keep credentials and certificate fingerprint private.
3. **Remote controls:** provide native directional/OK, Home/Back/Menu, Power, TV, volume/mute, text-send, and app/deep-link launch controls backed by the Remote v2 client.
4. **Remote text input:** preserve the ON/OFF setting and gamepad/IME compatibility explanation. Toggling reconnects the remote session.
5. **Edit Channels & Genres:** preserve genre add/edit/delete and reordering; channel add/edit/delete and reordering; favorite toggles and dedicated Favorites reordering. Confirm destructive deletes and show validation/errors.
6. **Channel JSON:** native load-current, export-current, choose JSON file, editable JSON area, cancel, and apply actions. Confirm replacement; importing must not alter pairing. Preserve `{ "version": 1, "genres": [...] }`, validation, and private persistence.
7. **Help/About:** make picker navigation hints and remote-key mapping guidance available; show app/server version and LAN URL when available.

## Architecture for `stblab-gui`

- Create a complete Android application project in this repository. Keep Gradle wrapper, Android module(s), source, resources, and project-specific documentation here.
- Implement all app screens with native Android components. Do not use WebView or a browser wrapper for the GUI.
- Use one UI toolkit consistently. Prefer Compose for TV if it meets the target firmware and focus requirements; otherwise use native Views. Toolkit choice must not change the UX contract.
- Separate UI from protocol, persistence, and hosting. Define repositories/services for the Android TV Remote v2 client, discovery/pairing, channel storage, and embedded HTTP server.
- Add Picker and Settings state holders/view models. Picker state owns genre/channel/page selection, per-genre positions, focus area, dial buffer/timers, tune history, pending channel-step timer, telemetry, loading state, and visible errors. Settings state owns server status, pairing/discovery, text-input preference, editing, and JSON workflows.
- Use lifecycle-aware coroutines/flows for state and polling. Preserve the 3-second remote-state polling behavior, avoid duplicate pollers across screen transitions, and cancel timers/work when their owner is cleared.
- Native views call repositories directly; they must not call the app's own loopback HTTP server for internal operations.
- Implement native file import/export through Android document APIs. Validate imports and write channel data atomically.
- Keep an HTTP API/server as an independent service if external LAN clients remain in scope. Preserve route contracts and JSON shapes used by the reference client unless a versioned migration is designed. The server must not be used as the native GUI transport.
- Store channel data, private keys, pairing credentials, and fingerprints in app-private storage. Never log or export secrets.

## Data and behavior invariants

- Seed defaults only on a fresh install; preserve user data on upgrades.
- Preserve channel config version/shape, stable IDs, sort order, dummy number, redirect number, favorite flag, and favorite order.
- Favorites is a virtual picker genre, not a persisted genre. Preserve favorite-first picker display and lookup.
- Preserve tune behavior: selecting a channel and confirming tunes it; numeric input searches dummy number before redirect number; tune sends the redirect number and channel name; recall uses recent tune history.
- Preserve existing Remote v2 capabilities and limits. The reference supports short key presses only; do not expose unsupported hold/release behavior.
- Preserve per-install protocol identity, paired-device state, fingerprint checks, and private storage.

## Implementation phases

### Phase 1: initialize the destination project

- Inspect reference screens and source to record actual controls, focus transitions, remote key handling, edge cases, and API/data contracts.
- Set up the Android application project and build configuration inside `stblab-gui` only.
- Select the UI toolkit and minimum Android/TV target based on the intended device.
- Create the native app shell with Picker as the launcher destination and a Settings entry point.
- Write a control mapping that pairs each reference action with its native control and focus/back path.

### Phase 2: implement native Picker

- Implement genre/channel columns, page controls, focus/selection states, virtual Favorites genre, per-genre position memory, telemetry, and status messages.
- Implement directional navigation matching the reference, including genre/channel transitions and virtual keypad focus behavior.
- Implement physical remote mappings, keypad actions, direct dialing, channel-step preview/auto-tune, tune action, tune history, Favorites shortcut, and Recall.
- Connect the UI to the new app's repository and protocol layer.

### Phase 3: implement Settings and supporting services

- Implement server lifecycle/status and LAN URL within the new project.
- Implement device discovery, pairing, code entry, reconnect, status/errors, and private credential persistence.
- Implement remote text-input preference and explanation.
- Implement native genre/channel/favorite editing with matching mutation, ordering, validation, and confirmation behavior.
- Implement JSON load/export/import/edit/apply with Android document APIs and the existing v1 schema.
- Add Help/About and consistent Back behavior.

### Phase 4: preserve external compatibility where required

- Decide whether LAN-hosted browser access is part of the product requirements. Keep the HTTP server by default because it is an existing host feature.
- If retained, implement/port the API and optional web assets inside `stblab-gui` as an external client surface only. The Android application UI must remain native and independent from those assets.
- Include license notices for any protocol source adapted from the reference or other upstream projects. Do not create a runtime dependency on `android-mystb`.

### Phase 5: migration and release readiness

- Decide the application ID and signing strategy for the new destination. If upgrading an existing installed app is required, verify package ID and signing compatibility before promising in-place data/credential migration.
- Verify fresh install and upgrade behavior, server start/retry/boot, pairing/reconnect, JSON import/export, CRUD/reordering, favorites, tuning, numeric dial, remote key mappings, and navigation restoration.
- Validate on the target BPL TV and Airtel box: focus readability, remote key delivery, network loss/recovery, standby behavior, document picker, and external browser reachability if hosting is retained.
- Write build/run instructions in `stblab-gui` describing the native Picker as the app's primary experience.

## Acceptance criteria

- All implementation files and build outputs originate from `/Users/bharath/gitprojects/stblab-gui`; `android-mystb` remains reference-only.
- Launch opens the native Picker directly. No HTML/React/WebView is used to render app screens.
- Every picker feature and action remains available with matching button meaning and remote navigation behavior.
- Settings is the sole route to pairing, server controls, remote controls and text-input setting, editing, favorites management, JSON configuration, and help/about.
- Settings screens have predictable TV focus, confirm, and Back behavior; Back from Settings returns to Picker.
- Channel, genre, favorites, ordering, tune, direct dial, recall, telemetry, and remote-control behavior match the reference.
- Channel data and pairing credentials are stored privately and survive supported upgrades.
- If LAN hosting remains enabled, external clients retain the stable API contracts and server status is visible in Settings.

## Reference sources (read-only)

These files are in `/Users/bharath/gitprojects/android-mystb` and are for behavioral/technical reference only:

- Picker and navigation: `web/app/page.tsx`
- Virtual keypad: `web/app/components/VirtualKeypad.tsx`
- Channel/genre/favorite editor: `web/app/components/EditModal.tsx`
- JSON workflow: `web/app/components/JsonConfigModal.tsx`
- API contracts: `app/src/main/java/com/stblab/android/ApiRoutes.kt` and `web/lib/api.ts`
- Channel persistence/validation: `app/src/main/java/com/stblab/android/ChannelStore.kt`
- Pairing/setup behavior: `app/src/main/java/com/stblab/android/MainActivity.kt`
- Host lifecycle: `app/src/main/java/com/stblab/android/HostService.kt` and `app/src/main/java/com/stblab/android/BootReceiver.kt`
- Remote protocol: `app/src/main/java/com/xstream/remote/`
