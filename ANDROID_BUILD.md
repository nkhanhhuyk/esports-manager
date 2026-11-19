# Android build (Capacitor)

The Android app is the **same web build** wrapped by [Capacitor](https://capacitorjs.com/docs/android)
(8.6.x). Nothing is rewritten for Android: `npm run build:web` produces `dist/`, Capacitor copies it into
the native project and the APK ships those files. The app never loads a dev server or a remote URL at
runtime and needs no network connection to play.

| Item | Value |
| --- | --- |
| Package / application ID | `dev.uem.esportsmanager` (fictional reverse-domain ID chosen for this project) |
| App name | Esports Manager |
| Native project | `android/` (committed source — treat it as real code) |
| Capacitor config | `capacitor.config.ts` (`webDir: dist`, edge-to-edge WebView, safe-area CSS variables) |
| Plugins | `@capacitor/app` (system Back button, pause/background autosave), `@capacitor/filesystem` + `@capacitor/share` (save export through the Android share sheet) |
| minSdk / compileSdk / targetSdk | 24 (Android 7.0) / 36 / 36 |
| Permissions | `INTERNET` only (Capacitor template default; the game makes no network requests) |
| Saves on device | IndexedDB inside the app's WebView storage (persists across restarts; removed on uninstall — use *Save / Load → Export*, which opens the share sheet so you can keep the file in Files/Drive; *Import* uses the file picker) |

## Prerequisites

* Node.js 20+ and `npm install` in the repository root.
* JDK 21 (`java -version`).
* Android SDK with **platform 36**, **build-tools 35+/36**, and platform-tools. Either install Android Studio
  or the command-line tools:

```bash
# command-line SDK (Linux example)
mkdir -p ~/android-sdk/cmdline-tools && cd ~/android-sdk/cmdline-tools
curl -O https://dl.google.com/android/repository/commandlinetools-linux-<version>_latest.zip
unzip commandlinetools-linux-*_latest.zip && mv cmdline-tools latest
yes | latest/bin/sdkmanager --sdk_root=$HOME/android-sdk --licenses
latest/bin/sdkmanager --sdk_root=$HOME/android-sdk "platform-tools" "platforms;android-36" "build-tools;36.0.0"
export ANDROID_HOME=$HOME/android-sdk
```

Point Gradle at the SDK with `ANDROID_HOME` or `android/local.properties` (`sdk.dir=/path/to/sdk`;
this file is git-ignored).

## Build a debug APK

```bash
npm run android:debug-apk     # = build:web → cap sync android → ./gradlew assembleDebug
```

Output: **`android/app/build/outputs/apk/debug/app-debug.apk`** (signed with the Android debug key;
installable for testing, not for store publication). Windows: `cd android && gradlew.bat assembleDebug`.

Other scripts:

| Script | What it does |
| --- | --- |
| `npm run build:web` | Type-check + production web build into `dist/` |
| `npm run android:sync` | Build the web app and copy it + plugins into `android/` (`npx cap sync android`) |
| `npm run android:open` | Open `android/` in Android Studio |
| `npm run android:debug-apk` | Full debug APK build |
| `npm run android:install` | `adb install -r` the debug APK onto a connected device/emulator |

Always run `android:sync` (or `android:debug-apk`) after changing web code — the APK contains whatever was
last synced into `android/app/src/main/assets/public` (git-ignored, regenerated).

## Install and run

* Device: enable *Developer options → USB debugging*, connect, then `npm run android:install`
  (or copy the APK to the phone and open it; allow "install unknown apps" for the file manager).
* Emulator: Android Studio → Device Manager, start a device, then `npm run android:install`.

What to check on a device (manual test list): new career → advance a week and a season → *Save* to a
slot → close the app from Recents → reopen → *Load*; airplane mode on; rotate portrait/landscape; system
Back from a profile page returns to the previous screen and exits only from Home; send the app to the
background with unsaved progress and reopen (it autosaves on pause); export a save file.

## Release build and signing (separate, optional)

Never commit keystores or passwords. To produce a signed release APK (or an AAB for a store):

1. Create a keystore once, stored outside the repository:
   `keytool -genkeypair -v -keystore ~/keys/esports-manager.jks -alias uem -keyalg RSA -keysize 4096 -validity 10000`
2. Provide the secrets through environment variables or `~/.gradle/gradle.properties` (not the repo):
   `UEM_KEYSTORE=/home/you/keys/esports-manager.jks`, `UEM_KEYSTORE_PASSWORD=…`, `UEM_KEY_ALIAS=uem`,
   `UEM_KEY_PASSWORD=…`.
3. Add a `signingConfigs.release` block to `android/app/build.gradle` that reads those values
   (`System.getenv(...)` / `project.findProperty(...)`) and set `buildTypes.release.signingConfig`.
4. `cd android && ./gradlew assembleRelease` → `android/app/build/outputs/apk/release/app-release.apk`
   (or `./gradlew bundleRelease` → `app-release.aab`, which is an app bundle, **not** an APK).

Bump `versionCode` / `versionName` in `android/app/build.gradle` for each release.

## Build environment notes (this repository's CI-less build)

The debug APK in this project was built on Linux with JDK 21, Gradle 8.14.3 (wrapper), Android SDK
platform 36 and build-tools 35.0.1/36.0.0 installed via `sdkmanager`. Maven Central briefly answered
HTTP 429 (rate limit) on the first build; a user-level Gradle init script (`~/.gradle/init.d/`, not part of
the repo) that prefers Google's Maven Central mirror
(`https://maven-central.storage-download.googleapis.com/maven2/`) avoided it. The build machine had no
hardware virtualisation (`/dev/kvm`), so no emulator could be started there: the APK's contents (package,
SDK levels, permissions, bundled assets, absence of dev-server URLs, debug signature) were verified with
`aapt`/`apksigner`, and the identical web bundle was exercised in Chromium (desktop and Pixel 7 emulation,
offline file mode). On-device installation is the remaining manual step.
