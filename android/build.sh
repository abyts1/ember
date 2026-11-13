#!/usr/bin/env bash
# Build Ember's APK with the bare Android build-tools (no Gradle, no AndroidX).
# Needs: JDK 17+, $ANDROID_HOME with platforms;android-35 and build-tools;35.0.0.
set -euo pipefail
cd "$(dirname "$0")"

SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}
BT=$SDK/build-tools/${BUILD_TOOLS:-35.0.0}
JAR=$SDK/platforms/android-${PLATFORM:-35}/android.jar
KS=${KEYSTORE:-$HOME/.android/ember-release.jks}
OUT=build

for f in "$BT/aapt2" "$BT/d8" "$BT/apksigner" "$BT/zipalign" "$JAR"; do
  [[ -e $f ]] || { echo "missing $f — install it with sdkmanager" >&2; exit 1; }
done

rm -rf "$OUT" && mkdir -p "$OUT"/{res,gen,classes,dex}

"$BT/aapt2" compile --dir res -o "$OUT/res.zip"
"$BT/aapt2" link -o "$OUT/base.apk" -I "$JAR" --manifest AndroidManifest.xml \
  --java "$OUT/gen" --min-sdk-version 29 --target-sdk-version 35 "$OUT/res.zip"

javac --release 11 -nowarn -Xlint:-options -encoding UTF-8 -classpath "$JAR" -d "$OUT/classes" \
  $(find src "$OUT/gen" -name '*.java')

"$BT/d8" --release --min-api 29 --lib "$JAR" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')

cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -qj ../unsigned.apk classes.dex)
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

if [[ ! -f $KS ]]; then
  echo "creating signing key at $KS (back it up: updates must be signed with the same key)"
  mkdir -p "$(dirname "$KS")"
  keytool -genkeypair -keystore "$KS" -alias ember -keyalg RSA -keysize 4096 -validity 10000 \
    -storepass "${KS_PASS:-android}" -keypass "${KS_PASS:-android}" -dname "CN=Ember" >/dev/null
fi
"$BT/apksigner" sign --ks "$KS" --ks-key-alias ember --ks-pass "pass:${KS_PASS:-android}" \
  --out "$OUT/ember.apk" "$OUT/aligned.apk"
"$BT/apksigner" verify "$OUT/ember.apk"
ls -l "$OUT/ember.apk"
