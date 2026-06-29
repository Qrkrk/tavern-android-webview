# Tavern (酒馆) — Minimal Android WebView Browser

[中文](README.md)

A full-screen WebView browser that loads `http://localhost:8000` (commonly used with [SillyTavern](https://github.com/SillyTavern/SillyTavern) and other AI frontends). Supports immersive fullscreen / normal dual modes, file downloads (including character card exports), and uploads — all without storage permissions.

## Tech Stack

| Item | Value |
|------|-------|
| Language | Kotlin |
| Layout | XML |
| minSdk | 24 (Android 7.0) |
| targetSdk | 34 (Android 14) |
| AGP | 8.2.0 |
| Gradle | 8.5 |
| Kotlin | 1.9.22 |

## Project Structure

```
tavern-android-webview/
├── build.gradle.kts                  # Project-level Gradle
├── settings.gradle.kts               # Module settings
├── gradle.properties                 # Gradle properties
├── gradle/wrapper/                   # Gradle Wrapper
├── app/
│   ├── build.gradle.kts              # App-level Gradle (dependencies, SDK versions)
│   ├── proguard-rules.pro            # ProGuard rules
│   └── src/main/
│       ├── AndroidManifest.xml       # Permissions, portrait, cleartext traffic
│       ├── java/com/localhost/tavern/
│       │   └── MainActivity.kt       # All core logic
│       └── res/
│           ├── layout/
│           │   └── activity_main.xml # Full-screen WebView layout
│           ├── values/               # themes.xml, strings.xml, colors.xml
│           └── mipmap-anydpi-v26/    # Adaptive icons (API 26+)
│               ├── ic_launcher.xml
│               └── ic_launcher_round.xml
```

## Features

- **Dual modes**: On first launch, a dialog lets you set the target URL and choose 🌐 Fullscreen (immersive, hides system bars) or 📱 Normal mode
- **Persistent URL**: Target URL saved via SharedPreferences; subsequent launches load directly without re-prompting
- **Long-press reset**: Long-press the WebView for 5 seconds to reconfigure the URL and switch modes
- **Blob downloads**: Intercepts `URL.createObjectURL` via JavaScript bridge to export blob content (e.g., character cards)
- **HTTP downloads**: System DownloadManager, saved to `Internal Storage/Download/`, no storage permissions required
- **File uploads**: System file picker via `ActivityResultLauncher`, no storage permissions required
- **Minimal permissions**: Only `INTERNET` + `ACCESS_NETWORK_STATE`, no storage permissions whatsoever

## Launcher Icon

Placeholder icons are included. Use Android Studio's **Image Asset Studio** with a 512×512 source image to auto-generate all densities:

1. Open the project in Android Studio
2. Right-click `app` → **New** → **Image Asset**
3. Set **Foreground Layer** to your 512×512 image, pick a solid **Background Layer**
4. Keep **Legacy** tab checked to generate PNGs for older devices
5. Click **Next** → **Finish**

For manual preparation: mdpi 48×48 / hdpi 72×72 / xhdpi 96×96 / xxhdpi 144×144 / xxxhdpi 192×192.

## Build & Run

1. Open the project root in **Android Studio**
2. Wait for Gradle Sync to complete
3. Connect a device or launch an emulator (API 24+)
4. Click **Run** or press `Shift+F10`

## Local Development

The app loads `http://127.0.0.1:8000` by default. Run a local web server:

```bash
# Python
python -m http.server 8000

# Node.js
npx serve -p 8000
```

Emulators can reach `localhost` out of the box. For physical devices, use USB port forwarding:

```bash
adb reverse tcp:8000 tcp:8000
```
