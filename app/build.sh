#!/bin/bash
# Builds Preheat.apk without gradle: aapt2 (manifest) -> javac -> d8 -> zipalign ->
# apksigner. Same toolchain as cantest/, which produced a verified installable APK.
set -eu
ROOT=/Users/vitalizavala/Work/tmp/box/preheat
SDK=/Users/vitalizavala/Library/Android/sdk
BT=$SDK/build-tools/35.0.1
PLATFORM=$SDK/platforms/android-33/android.jar
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

cd "$ROOT"
rm -rf build && mkdir -p build/compiled build/dex build/apk

echo "==> javac"
# Ошибки javac не глотаем: раньше сборка падала на лямбде, но печатала BUILD OK
# и выпускала APK без MainActivity. set -e не спасает, потому что ошибка уходит
# в stderr и код возврата javac терялся за пайпом.
if ! "$JAVA_HOME/bin/javac" -source 8 -target 8 -bootclasspath "$PLATFORM" \
      -d build/compiled $(find src -name '*.java') 2>build/javac.log; then
  echo "JAVAC FAILED:"
  grep -v -e 'bootstrap class path' -e 'source value 8 is obsolete' \
          -e 'target value 8 is obsolete' -e 'To suppress warnings' build/javac.log >&2 || true
  grep 'error:' build/javac.log >&2 || true
  exit 1
fi

echo "==> d8"
"$BT/d8" --lib "$PLATFORM" --min-api 26 --output build/dex \
  $(find build/compiled -name '*.class') 2>&1 | tail -3

echo "==> aapt2"
"$BT/aapt2" link -I "$PLATFORM" --manifest src/AndroidManifest.xml \
  -o build/apk/base.apk 2>&1 | tail -5

echo "==> package"
cd build/apk
cp ../dex/classes.dex .
zip -q -X base.apk classes.dex
cd "$ROOT"

echo "==> sign"
KS=$ROOT/preheat.keystore
[ -f "$KS" ] || keytool -genkeypair -v -keystore "$KS" -storepass android -keypass android \
  -alias preheat -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Preheat" >/dev/null 2>&1

"$BT/zipalign" -f 4 build/apk/base.apk build/apk/aligned.apk
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --out Preheat.apk build/apk/aligned.apk 2>&1 | grep -v -e WARNING -e 'native-access' || true

echo
echo "BUILD OK -> $ROOT/Preheat.apk"
ls -l Preheat.apk
unzip -l Preheat.apk | tail -6
"$BT/apksigner" verify -v Preheat.apk 2>/dev/null | head -3