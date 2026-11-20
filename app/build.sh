#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
: "${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT to an Android SDK with platform 35 and build-tools 35.0.0}"
: "${JAVA_HOME:?Set JAVA_HOME to JDK 17 or newer}"
# Optional overrides: HICAR_BUILD_TOOLS, HICAR_PLATFORM_JAR.
# Without them the documented 35.0.0 / android-35 pair is used when present,
# otherwise the newest installed build-tools and platform are picked.
bt="${HICAR_BUILD_TOOLS:-$ANDROID_SDK_ROOT/build-tools/35.0.0}"
if [ ! -x "$bt/aapt2" ] && [ ! -x "$bt/aapt2.exe" ]; then
    bt=""
    for candidate in $(ls -1 "$ANDROID_SDK_ROOT/build-tools" 2>/dev/null | sort -V -r); do
        if [ -x "$ANDROID_SDK_ROOT/build-tools/$candidate/aapt2" ] || [ -x "$ANDROID_SDK_ROOT/build-tools/$candidate/aapt2.exe" ]; then
            bt="$ANDROID_SDK_ROOT/build-tools/$candidate"; break
        fi
    done
    [ -n "$bt" ] || { echo "No usable build-tools found under $ANDROID_SDK_ROOT/build-tools" >&2; exit 1; }
    echo "build-tools: using $bt" >&2
fi
platform="${HICAR_PLATFORM_JAR:-$ANDROID_SDK_ROOT/platforms/android-35/android.jar}"
if [ ! -f "$platform" ]; then
    platform=""
    for candidate in $(ls -1 "$ANDROID_SDK_ROOT/platforms" 2>/dev/null | sort -V -r); do
        if [ -f "$ANDROID_SDK_ROOT/platforms/$candidate/android.jar" ]; then
            platform="$ANDROID_SDK_ROOT/platforms/$candidate/android.jar"; break
        fi
    done
    [ -n "$platform" ] || { echo "No android.jar found under $ANDROID_SDK_ROOT/platforms" >&2; exit 1; }
    echo "platform: using $platform" >&2
fi
version=$("${PYTHON:-python3}" -c 'import xml.etree.ElementTree as E; print(E.parse("AndroidManifest.xml").getroot().get("{http://schemas.android.com/apk/res/android}versionName"))')
# Windows SDK installs ship .exe/.bat wrappers; POSIX hosts ship bare names.
tool() {
    for suffix in "" ".exe" ".bat" ".cmd"; do
        if [ -f "$bt/$1$suffix" ]; then printf '%s' "$bt/$1$suffix"; return 0; fi
    done
    printf '%s' "$bt/$1"
}
AAPT2=$(tool aapt2); D8=$(tool d8); ZIPALIGN=$(tool zipalign); APKSIGNER=$(tool apksigner); KEYTOOL=$(tool keytool)
if [ ! -x "$KEYTOOL" ] && [ -x "$JAVA_HOME/bin/keytool" ]; then KEYTOOL="$JAVA_HOME/bin/keytool"; fi
JAVAC="$JAVA_HOME/bin/javac"
if [ ! -x "$JAVAC" ] && [ -x "$JAVAC.exe" ]; then JAVAC="$JAVAC.exe"; fi
for abi in armeabi-v7a arm64-v8a; do
    if [ ! -f "native-libs/lib/$abi/libcarlink_hichain.so" ]; then
        echo "Missing $abi native library. Follow docs/BUILD.md to build native-auth first." >&2
        exit 1
    fi
done
mkdir -p build/classes build/dex
rm -rf build/classes/*
"$AAPT2" link -I "$platform" --manifest AndroidManifest.xml -A assets -o build/base.apk
"$JAVAC" -encoding UTF-8 -source 8 -target 8 -nowarn -bootclasspath "$platform:$bt/core-lambda-stubs.jar" -d build/classes src/local/hicarlab/*.java
rm -f build/classes.jar
# `zip` is missing on some hosts (Windows Git Bash), so the helper script is used.
"${PYTHON:-python3}" zipadd.py create build/classes.jar build/classes
"$D8" --min-api 26 --lib "$platform" --output build/dex build/classes.jar
cp build/base.apk build/unsigned.apk
"${PYTHON:-python3}" zipadd.py add build/unsigned.apk native-libs/lib lib/
"${PYTHON:-python3}" zipadd.py add build/unsigned.apk build/dex
"$ZIPALIGN" -f -P 16 4 build/unsigned.apk build/aligned.apk
if [ -n "${HICAR_KEYSTORE:-}" ]; then
    : "${HICAR_STORE_PASSWORD:?Set HICAR_STORE_PASSWORD for your release key}"
    : "${HICAR_KEY_PASSWORD:?Set HICAR_KEY_PASSWORD for your release key}"
    : "${HICAR_KEY_ALIAS:?Set HICAR_KEY_ALIAS for your release key}"
    export HICAR_STORE_PASSWORD HICAR_KEY_PASSWORD
    apk="build/HiCarLab-$version.apk"
    "$APKSIGNER" sign --ks "$HICAR_KEYSTORE" --ks-key-alias "$HICAR_KEY_ALIAS" --ks-pass env:HICAR_STORE_PASSWORD --key-pass env:HICAR_KEY_PASSWORD --out "$apk" build/aligned.apk
else
    # Development key only. Never commit it or use it for public releases.
    mkdir -p ../.local-signing
    if [ ! -f ../.local-signing/development.keystore ]; then
        "$KEYTOOL" -genkeypair -keystore ../.local-signing/development.keystore -alias development -storepass android -keypass android -keyalg RSA -keysize 2048 -validity 3650 -dname 'CN=HiCar Receiver Development'
    fi
    apk="build/HiCarLab-$version-development.apk"
    "$APKSIGNER" sign --ks ../.local-signing/development.keystore --ks-key-alias development --ks-pass pass:android --key-pass pass:android --out "$apk" build/aligned.apk
fi
"$APKSIGNER" verify "$apk"
"$ZIPALIGN" -c -P 16 4 "$apk"
echo "Built: $apk"
