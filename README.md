# PdfCraft for Android

A native Android port of **[PdfCraft](https://github.com/storytold/pdfcraft)** with **full desktop-like control**, designed for Android Laptop, tablets, Chromebooks, and foldable devices.

PdfCraft on Android embeds the genuine 35-crate desktop PDF engine and egui-based desktop workbench directly inside a hardware-accelerated OpenGL ES 3.0 surface, paired with full hardware mouse, keyboard, and stylus input support.

---

## Key Features

- **Full Desktop Workbench & Engine**:
  - The complete desktop interface (`pdfcraft-ui-egui`) and multi-threaded Rust engine (`pdfcraft-engine`) compiled for 64-bit Android (`arm64-v8a` and `x86_64`).
  - 16KB ELF page-aligned shared libraries compliant with Android 15 and Android 16.
  - Hardware-accelerated OpenGL ES 3.0 rendering (`egui_glow`) at 60/120 Hz.

- **Desktop-Grade Pointer & Mouse Controls**:
  - Hardware mouse hover (`ACTION_HOVER_MOVE`) with dynamic OS cursor shape changes (pointing hand, text I-beam, grab, resize, crosshair, etc.).
  - Left, right, and middle mouse button support, including context menus on right-click.
  - Stylus barrel-button right-click and two-finger tap right-click for touchscreens.
  - Smooth vertical & horizontal scrolling via mouse wheel and trackpad, plus `Ctrl + Scroll` canvas zooming.

- **Desktop Keyboard & Shortcuts**:
  - Full hardware keyboard mapping for all desktop shortcuts:
    - `Ctrl + O`: Open document via Android Scoped Storage (SAF)
    - `Ctrl + S` / `Ctrl + Shift + S`: In-place save and Save As
    - `Ctrl + K`: Command Palette
    - `Ctrl + F`: Search & Find
    - `Ctrl + Z` / `Ctrl + Y` (or `Ctrl + Shift + Z`): Undo / Redo
    - `F1`–`F12`, arrows, `Delete`, `Escape`, `Tab`, etc.
  - Android IME soft keyboard integration for text and form fields.

- **Floating Desktop Control Bar**:
  - A collapsible Compose overlay providing one-tap access to sticky `Ctrl`, `Shift`, `Alt`, and `Right-Click` modifiers for touch and stylus usage.
  - Quick action buttons for Command Palette, Open, Save, Undo, Redo, Find, Share, Keyboard toggle, and UI scaling (75%–200%).

- **Android System Integration**:
  - **Scoped Storage (SAF)**: `ACTION_OPEN_DOCUMENT`, `ACTION_OPEN_MULTIPLE_DOCUMENTS`, `ACTION_CREATE_DOCUMENT`, and `ACTION_OPEN_DOCUMENT_TREE` wired into PdfCraft's asynchronous file pickers.
  - **Bi-directional SAF Write-Back**: Saving a document (`Ctrl+S`) writes back directly to the source `content://` URI without creating duplicate files.
  - **Intents**: Open and view PDFs directly from file managers, email, and share sheets (`ACTION_VIEW`, `ACTION_EDIT`, `ACTION_SEND`, `ACTION_SEND_MULTIPLE`).
  - **System Printing**: Native `PrintManager` integration receiving PdfCraft's composed print jobs (Booklet, Poster, Multiple per sheet, Grayscale).
  - **Window Resizing**: Full freeform multi-window resizing support (`android:resizeableActivity="true"`).

- **Reliability & Crash Safety**:
  - Built-in [`CrashReporter`](app/src/main/kotlin/com/andrewnoonan/pdfcraft/platform/CrashReporter.kt) capturing both JVM uncaught exceptions and Rust native panics.
  - Dedicated copyable crash screen for quick diagnostic reporting without needing `adb logcat`.

---

## Project Structure

```
.
├── app/                  # Android application (Kotlin, Jetpack Compose, GLSurfaceView)
│   ├── src/main/         # Android manifest, UI components, bridge interfaces, storage managers
│   └── src/test/         # Unit, Robolectric (SDK 34), and Compose UI tests
├── rust-bridge/          # JNI + OpenGL ES 3.0 bridge connecting Kotlin to PdfCraft
│   ├── src/lib.rs        # JNI exports, glow painter, and event translators
│   └── shims/            # Android platform shims for arboard (clipboard) and rfd (file pickers)
├── pdfcraft/             # PdfCraft source tree (35 crates, models, rendering, tools)
└── scripts/
    └── build-android-rust.sh  # Cross-compilation script for aarch64 and x86_64
```

---

## Building from Source

### Prerequisites

- **Android SDK**: API Level 36 (`platforms;android-36`, `build-tools;36.1.0`, `ndk;29.0.14206865`, `cmake;3.31.6`).
- **Rust Toolchain**: Rust 1.85+ with Android targets:
  ```bash
  rustup target add aarch64-linux-android x86_64-linux-android
  ```
- **Java**: JDK 17+.

### 1. Cross-Compile the Native Libraries

Run the cross-compilation script to build `libpdfcraft_android.so` for both `arm64-v8a` and `x86_64`:

```bash
bash scripts/build-android-rust.sh
```

This compiles release libraries, enforces 16KB page alignment, validates exported JNI symbols, and verifies zero unresolved strong dependencies.

### 2. Build the Android APK (or App Bundle for Google Play)

**Debug APK** (for direct device installation):
```bash
./gradlew assembleDebug
```
The output APK will be generated at:
`app/build/outputs/apk/debug/PdfCraft-debug.apk`

**Release App Bundle (AAB)** (for Google Play Store upload):
```bash
./gradlew bundleRelease
```
The output signed bundle will be generated at:
`app/build/outputs/bundle/release/PdfCraft-release.aab`

---

## Google Play Store & CI/CD Deployment

The repository includes an automated GitHub Actions release workflow at [`.github/workflows/play-store-release.yml`](.github/workflows/play-store-release.yml) that builds, signs, and deploys release App Bundles directly to Google Play.

### 1. Initial Google Play Setup (One-time)

Google Play's API requires that a new app be registered and have its **initial App Bundle uploaded manually once** via the [Google Play Console](https://play.google.com/console):

1. In Google Play Console, click **Create app** (App name: `PdfCraft`, Package: `com.andrewnoonan.pdfcraft`).
2. Go to **Testing > Internal testing** (or Closed testing), click **Create new release**, and upload the generated `PdfCraft-release.aab`.
3. Complete the required Store Listing declarations (Privacy Policy, Content Rating, Target Audience).

### 2. Automated Publishing via GitHub Actions

To enable automated deployments from GitHub Actions:

1. **Google Cloud Service Account**:
   - In Google Play Console, navigate to **API access** and link a Google Cloud Project.
   - Create a Service Account with the **Release manager** role.
   - Generate and download a JSON key for the service account.
2. **Configure GitHub Repository Secrets**:
   - `PLAY_SERVICE_ACCOUNT_JSON`: Contents of the Google Play Service Account JSON key.
   - `KEYSTORE_BASE64`: (Optional) Base64-encoded upload keystore (defaults to `app/keystore/pdfcraft.jks`).
   - `KEYSTORE_PASSWORD`: Keystore password (defaults to `pdfcraft_store_2026`).
   - `KEY_ALIAS`: Key alias (defaults to `pdfcraft`).
   - `KEY_PASSWORD`: Key password (defaults to `pdfcraft_store_2026`).

3. **Triggering a Release**:
   - **Automatic**: Push a git tag (e.g. `git tag v0.17.1 && git push origin v0.17.1`).
   - **Manual**: Go to **Actions > Google Play Store Release > Run workflow**, select the target track (`internal`, `alpha`, `beta`, `production`), and click **Run workflow**.

---

## Testing

Run all test suites across Rust and Android:

```bash
# 1. Run headless engine and bridge tests in Rust
cargo test --manifest-path rust-bridge/Cargo.toml --lib

# 2. Run all JVM, Robolectric, and Compose UI tests in Android
./gradlew testDebugUnitTest
```

---

## License

PdfCraft for Android is licensed under either the [MIT License](LICENSE-MIT) or the [Apache License, Version 2.0](LICENSE-APACHE), matching upstream PdfCraft.
