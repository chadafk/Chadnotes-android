#!/usr/bin/env bash
set -e

echo "=== Applying Chadnotes - A Xnotes Fork Branding & Enhancements ==="

# 1. Change applicationId to com.chadnotes.xnotes while keeping namespace = com.xnotes
sed -i 's/applicationId = "com.xnotes"/applicationId = "com.chadnotes.xnotes"/g' app/build.gradle.kts

# 2. Ensure FileProvider or other authorities in AndroidManifest use ${applicationId}
sed -i 's/android:authorities="com.xnotes/android:authorities="${applicationId}/g' app/src/main/AndroidManifest.xml || true

# 3. Rename the app to "Chadnotes- A Xnotes Fork" across strings.xml and AndroidManifest.xml
if [ -f "app/src/main/res/values/strings.xml" ]; then
  sed -i 's/<string name="app_name">.*<\/string>/<string name="app_name">Chadnotes- A Xnotes Fork<\/string>/g' app/src/main/res/values/strings.xml
fi
sed -i 's/android:label="xnotes"/android:label="Chadnotes- A Xnotes Fork"/gi' app/src/main/AndroidManifest.xml || true

# 4. Fetch high-res monochrome GigaChad portrait if available, or use bundled gigachad_icon_512.png,
# and convert into all mipmap directories + remove overriding vector adaptive icons so the PNG icon shows everywhere
if command -v convert >/dev/null 2>&1; then
  curl -L -s --fail -A "Mozilla/5.0" "https://upload.wikimedia.org/wikipedia/commons/3/34/Gigachad.jpg" -o /tmp/gigachad_raw.jpg || true
  if [ -s /tmp/gigachad_raw.jpg ]; then
    echo "Downloaded Wikimedia GigaChad portrait; generating mipmap icons..."
    convert /tmp/gigachad_raw.jpg -gravity North -crop 500x500+0+20 +repage -colorspace Gray -contrast-stretch 2%x2% gigachad_icon_512.png || true
  fi

  declare -A SIZES=( ["mdpi"]=48 ["hdpi"]=72 ["xhdpi"]=96 ["xxhdpi"]=144 ["xxxhdpi"]=192 )
  for DENSITY in "${!SIZES[@]}"; do
    SZ=${SIZES[$DENSITY]}
    TARGET_DIR="app/src/main/res/mipmap-${DENSITY}"
    mkdir -p "$TARGET_DIR"
    convert gigachad_icon_512.png -resize "${SZ}x${SZ}" "$TARGET_DIR/ic_launcher.png"
    convert gigachad_icon_512.png -resize "${SZ}x${SZ}" "$TARGET_DIR/ic_launcher_round.png"
    convert gigachad_icon_512.png -resize "${SZ}x${SZ}" "$TARGET_DIR/ic_launcher_foreground.png"
    # Remove any .webp launcher files that might conflict with .png in the same mipmap folder
    rm -f "$TARGET_DIR/ic_launcher.webp" "$TARGET_DIR/ic_launcher_round.webp" "$TARGET_DIR/ic_launcher_foreground.webp"
  done
fi

# Remove vector adaptive icon XMLs if they override our GigaChad bitmap mipmaps
rm -rf app/src/main/res/mipmap-anydpi-v26 app/src/main/res/mipmap-anydpi

# 5. Inject Low-Latency & MotionPrediction dependencies into app/build.gradle.kts if not already present
if ! grep -q "input-motionprediction" app/build.gradle.kts; then
  sed -i '/dependencies {/a \    implementation("androidx.graphics:graphics-core:1.0.2")\n    implementation("androidx.input:input-motionprediction:1.0.0")' app/build.gradle.kts
fi

# 6. Append safe R8 ProGuard keep rules for JNI Tree-sitter and PDFBox
cat << 'EOF' >> app/proguard-rules.pro

# --- Chadnotes R8 Optimization & JNI / PDF Protection Rules ---
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-keep class com.xnotes.** { *; }
-dontwarn org.apache.**
-dontwarn com.tom_roush.**
-keep class com.tom_roush.** { *; }
EOF

echo "=== Chadnotes Patch Complete ==="
