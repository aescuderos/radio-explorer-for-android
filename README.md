# Radio Explorer

Native Android radio browser for phones, in the dark/red style of TV Explorer.
Uses the public [Radio Browser API](https://www.radio-browser.info/).

Search by station name, country or genre; save favorites locally. Results are ordered
by popularity and paginated, with no fixed total catalogue limit. More results load
automatically as you scroll. Searches and country/genre filters are sent to Radio
Browser and apply to its full matching catalogue, not just loaded results. Stations
marked unavailable by Radio Browser's last check and invalid stream URLs are excluded. In the favorites
view, the search and filters apply only to locally saved favorites.
The outlined heart adds a favorite; the filled heart removes it. Only stations marked
working by Radio Browser's last check are requested. Availability is not guaranteed.
Tap a station to play it in the app using LibVLC; no separate player is required.
Playback continues in the background with notification and lock-screen media controls.
The app stops a live stream when paused and reconnects when resumed.

The app contacts Radio Browser and the selected station via your player. The API's
public listening counter is notified when you open a station. These services receive
your IP address. No additional analytics or advertising are included.

## Build

JDK 17, Android SDK platform 33, build-tools 33.0.2, curl, unzip and zip.
Download the declared LibVLC artifact from Maven Central before building:

```
mkdir -p deps
curl -fL -o deps/libvlc-all-3.6.2.aar https://repo.maven.apache.org/maven2/org/videolan/android/libvlc-all/3.6.2/libvlc-all-3.6.2.aar
ANDROID_SDK=/path/to/sdk bash build.sh
adb install -r out/radio-explorer.apk
```

Requires Android 6 or later. A local development signing key is generated at
`~/.android/radio-explorer-debug.jks`, using Android's conventional development-only
password. Keep your key to install subsequent updates; never publish private keys.
The build checks the AAR's SHA-256 before use. LibVLC is packaged as separate
native shared libraries for arm64, armv7, x86 and x86_64; the APK is significantly larger.

## Device smoke test

With the app installed, an unlocked phone, and Spain selected with no active filters:

```
ADB=/path/to/adb ANDROID_SERIAL=<device> python3 tests/smoke.py
```

Checks pagination, favorite persistence/removal, searches and country/genre filters.
It temporarily adds then removes one favorite and returns the country to Spain.
Live network access is required.

With Spain selected, `python3 tests/playback_smoke.py` starts Cadena 100, checks
internal and background playback, pauses and resumes through media controls, then
stops. Both tests accept `ADB` and `ANDROID_SERIAL` as above.

## Disclaimer and license

This app does not host or control broadcasts. Stations and metadata come from third
parties. The author accepts no responsibility for third-party content or its
availability. Users must comply with applicable law. Third-party services and
content remain subject to their own terms and rights.

Radio Explorer's own code is MIT licensed; provided without warranty. Developed
with AI assistance. It uses unmodified [LibVLC for Android 3.6.2](https://code.videolan.org/videolan/vlc-android),
copyright VideoLAN and contributors, distributed separately under the LGPL 2.1 or later.
The LGPL text is included in `assets/licenses/LGPL-2.1.txt` and in the app's Info dialog.
The [published Android binding sources](https://repo.maven.apache.org/maven2/org/videolan/android/libvlc-all/3.6.2/libvlc-all-3.6.2-sources.jar)
and [VLC source](https://code.videolan.org/videolan/vlc) are available from their upstream authors.
Before distributing an APK, verify the corresponding source and redistribution obligations for the
exact LibVLC build and its included native dependencies.
