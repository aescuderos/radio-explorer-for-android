#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
SDK=${ANDROID_SDK:?Set ANDROID_SDK to your Android SDK directory}
BT="$SDK/build-tools/33.0.2"
JAR="$SDK/platforms/android-33/android.jar"
AAR=deps/libvlc-all-3.6.2.aar
if [ ! -f "$AAR" ]; then
    echo "Missing $AAR; see README.md for dependency installation." >&2
    exit 1
fi
echo "cf6042f4a0c520a85f850e73b7dd8f68b70aff1923364f2b16d87f30f48168bd  $AAR" | sha256sum -c
KS=${KEYSTORE:-$HOME/.android/radio-explorer-debug.jks}
if [ ! -f "$KS" ]; then
    mkdir -p "$(dirname "$KS")"
    keytool -genkeypair -keystore "$KS" -alias radio -keyalg RSA -keysize 2048 \
        -validity 10000 -dname "CN=Radio Explorer Debug" -storepass android -keypass android
fi
mkdir -p out
rm -rf out/cls
mkdir -p out/cls out/gen
unzip -o -q "$AAR" classes.jar 'jni/*' 'assets/*' -d out/libvlc
mkdir -p out/libvlc/assets/licenses
cp assets/licenses/LGPL-2.1.txt out/libvlc/assets/licenses/
"$BT/aapt2" compile --dir res -o out/res.zip
"$BT/aapt2" link -o out/base.apk -I "$JAR" --manifest AndroidManifest.xml \
    --min-sdk-version 23 --target-sdk-version 33 --version-code 4 --version-name 1.3 --java out/gen -A out/libvlc/assets out/res.zip
javac --release 8 -Xlint:unchecked -cp "$JAR:out/libvlc/classes.jar" -d out/cls \
    out/gen/com/radioexplorer/R.java src/com/radioexplorer/*.java
"$BT/d8" --lib "$JAR" --min-api 23 --output out out/cls/com/radioexplorer/*.class out/libvlc/classes.jar
mkdir -p out/lib
cp -r out/libvlc/jni/. out/lib/
(cd out && zip -q -r base.apk classes.dex lib)
"$BT/zipalign" -f 4 out/base.apk out/aligned.apk
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --out out/radio-explorer.apk out/aligned.apk
echo "Built out/radio-explorer.apk"
