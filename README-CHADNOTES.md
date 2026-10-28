# 🗿 Chadnotes - A Xnotes Fork (Drop-in GitHub Bundle)

This archive contains everything needed to transform your `xnotes-android` fork on GitHub into **Chadnotes- A Xnotes Fork** with the **GigaChad** launcher icon and compile a signed, R8-optimized Release APK in the cloud using GitHub Actions.

## What's Inside This ZIP
1. **`.github/workflows/build-apk.yml`**: Cloud build pipeline that installs JDK 17, Android NDK `27.0.12077973`, and CMake `3.22.1`, runs `apply-chadnotes-patch.sh`, compiles & signs `Chadnotes-A-Xnotes-Fork.apk`, and publishes it directly to **GitHub Releases** and **Actions Artifacts**.
2. **`apply-chadnotes-patch.sh`**: Automatically rebrands `applicationId` to `com.chadnotes.xnotes`, sets the app name to **`Chadnotes- A Xnotes Fork`**, installs the **GigaChad** icon across all `mipmap-*` folders, and adds the Jetpack Low-Latency & MotionPrediction dependencies.
3. **`gigachad_icon_512.png` & `app/src/main/res/mipmap-*/ic_launcher*.png`**: Pre-rendered GigaChad launcher icons across `mdpi`, `hdpi`, `xhdpi`, `xxhdpi`, and `xxxhdpi` densities.
4. **`app/src/main/java/com/xnotes/canvas/ChadnotesSuite.kt`**: Unified Kotlin source module containing Stylus Button & Double-Tap Remapping, Parametric & Cubic Bézier Pressure Curves, Scribble-to-Erase, and `MotionEventPredictor` + `CanvasFrontBufferedRenderer`.

## How to Upload to Your GitHub Fork & Get Your APK
1. Extract this `.zip` file on your device.
2. Open your `xnotes-android` fork on GitHub in **`github.dev`** (press `.` on your keyboard or replace `github.com` with `github.dev` in the URL).
3. Drag and drop the extracted folders/files (`.github`, `app`, `apply-chadnotes-patch.sh`, `gigachad_icon_512.png`) directly into the root of your repository in the `github.dev` file explorer.
4. Click the **Source Control** icon on the left sidebar, enter a commit message like `Add Chadnotes branding and cloud APK builder`, and click **Commit & Push**.
5. Open the **Actions** tab (or **Releases** section) in your GitHub fork—within ~5 minutes, download and install **`Chadnotes-A-Xnotes-Fork.apk`**!
