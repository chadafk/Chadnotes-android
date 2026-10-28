#!/usr/bin/env bash
set -e

echo "=== Applying Chadnotes Branding & Enhancements ==="

# Locate build.gradle or build.gradle.kts automatically
GRADLE_FILE="app/build.gradle"
if [ -f "app/build.gradle.kts" ]; then
  GRADLE_FILE="app/build.gradle.kts"
fi

echo "Using build file: $GRADLE_FILE"

# 1. Change applicationId to com.chadnotes.xnotes
sed -i 's/applicationId = "com.xnotes"/applicationId = "com.chadnotes.xnotes"/g' "$GRADLE_FILE" || true
sed -i "s/applicationId 'com.xnotes'/applicationId 'com.chadnotes.xnotes'/g" "$GRADLE_FILE" || true

# 2. Make FileProvider authorities dynamic
if [ -f "app/src/main/AndroidManifest.xml" ]; then
  sed -i 's/android:authorities="com.xnotes/android:authorities="${applicationId}/g' app/src/main/AndroidManifest.xml || true
  sed -i 's/android:label="xnotes"/android:label="Chadnotes- A Xnotes Fork"/gi' app/src/main/AndroidManifest.xml || true
fi

# 3. Update strings.xml app name
if [ -f "app/src/main/res/values/strings.xml" ]; then
  sed -i 's/<string name="app_name">.*<\/string>/<string name="app_name">Chadnotes- A Xnotes Fork<\/string>/g' app/src/main/res/values/strings.xml || true
fi

# 4. Handle GigaChad Icons & mipmap densities
if command -v convert >/dev/null 2>&1 && [ -f "gigachad_icon_512.png" ]; then
  declare -A SIZES=( ["mdpi"]=48 ["hdpi"]=72 ["xhdpi"]=96 ["xxhdpi"]=144 ["xxxhdpi"]=192 )
  for DENSITY in "${!SIZES[@]}"; do
    SZ=${SIZES[$DENSITY]}
    TARGET_DIR="app/src/main/res/mipmap-${DENSITY}"
    mkdir -p "$TARGET_DIR"
    convert gigachad_icon_512.png -resize "${SZ}x${SZ}" "$TARGET_DIR/ic_launcher.png"
    convert gigachad_icon_512.png -resize "${SZ}x${SZ}" "$TARGET_DIR/ic_launcher_round.png"
    convert gigachad_icon_512.png -resize "${SZ}x${SZ}" "$TARGET_DIR/ic_launcher_foreground.png"
    rm -f "$TARGET_DIR/ic_launcher.webp" "$TARGET_DIR/ic_launcher_round.webp" "$TARGET_DIR/ic_launcher_foreground.webp"
  done
fi

rm -rf app/src/main/res/mipmap-anydpi-v26 app/src/main/res/mipmap-anydpi

# 5. Inject dependencies if not present
if ! grep -q "input-motionprediction" "$GRADLE_FILE"; then
  sed -i '/dependencies {/a \    implementation("androidx.graphics:graphics-core:1.0.2")\n    implementation("androidx.input:input-motionprediction:1.0.0")' "$GRADLE_FILE" || true
fi

# 6. Append R8 ProGuard keep rules
if [ -f "app/proguard-rules.pro" ]; then
  cat << 'EOF' >> app/proguard-rules.pro

-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-keep class com.xnotes.** { *; }
-dontwarn org.apache.**
-dontwarn com.tom_roush.**
-keep class com.tom_roush.** { *; }
EOF
fi

echo "=== Patch Applied Successfully ==="
