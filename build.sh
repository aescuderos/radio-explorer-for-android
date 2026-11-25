#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
SDK=${ANDROID_SDK:?Set ANDROID_SDK to your Android SDK directory}
BT="$SDK/build-tools/33.0.2"
JAR="$SDK/platforms/android-33/android.jar"
KS=${KEYSTORE:-$HOME/.android/radio-explorer-debug.jks}
if [ ! -f "$KS" ]; then
    mkdir -p "$(dirname "$KS")"
    keytool -genkeypair -keystore "$KS" -alias radio -keyalg RSA -keysize 2048 \
        -validity 10000 -dname "CN=Radio Explorer Debug" -storepass android -keypass android
fi
mkdir -p out
rm -rf out/cls
mkdir -p out/cls
"$BT/aapt2" compile --dir res -o out/res.zip
"$BT/aapt2" link -o out/base.apk -I "$JAR" --manifest AndroidManifest.xml \
    --min-sdk-version 23 --target-sdk-version 33 --version-code 1 --version-name 1.0 out/res.zip
javac --release 8 -Xlint:unchecked -cp "$JAR" -d out/cls src/com/radioexplorer/*.java
"$BT/d8" --lib "$JAR" --min-api 23 --output out out/cls/com/radioexplorer/*.class
(cd out && zip -q base.apk classes.dex)
"$BT/zipalign" -f 4 out/base.apk out/aligned.apk
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --out out/radio-explorer.apk out/aligned.apk
echo "Built out/radio-explorer.apk"
