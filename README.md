# Radio Explorer

Native Android radio browser for phones, in the dark/red style of TV Explorer.
Uses the public [Radio Browser API](https://www.radio-browser.info/).

Search by station name, country or genre; save favorites locally. Results are ordered
by popularity and paginated, with no fixed total catalogue limit. Only stations marked
working by Radio Browser's last check are requested. Availability is not guaranteed.
Tap a station to open VLC (or another installed audio player). VLC handles background
playback, lock-screen controls and playback errors.

The app contacts Radio Browser and the selected station via your player. The API's
public listening counter is notified when you open a station. These services receive
your IP address. No additional analytics or advertising are included.

## Build

JDK 17, Android SDK platform 33 and build-tools 33.0.2:

```
ANDROID_SDK=/path/to/sdk bash build.sh
adb install -r out/radio-explorer.apk
```

Requires Android 6 or later. A local development signing key is generated at
`~/.android/radio-explorer-debug.jks`, using Android's conventional development-only
password. Keep your key to install subsequent updates; never publish private keys.

## Device smoke test

With the app installed, an unlocked phone, and Spain selected with no active filters:

```
ADB=/path/to/adb ANDROID_SERIAL=<device> python3 tests/smoke.py
```

Checks pagination, favorite persistence/removal, searches and country/genre filters.
It temporarily adds then removes one favorite and returns the country to Spain.
Live network access is required.

## Disclaimer and license

This app does not host or control broadcasts. Stations and metadata come from third
parties. The author accepts no responsibility for third-party content or its
availability. Users must comply with applicable law. Third-party services and
content remain subject to their own terms and rights.

MIT licensed; provided without warranty. Developed with AI assistance.
