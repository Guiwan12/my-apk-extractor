# My APK Extractor

A clean-room Android app that lists your installed apps and extracts their APK
files to a folder you choose. Kotlin + Jetpack Compose. This is your own code —
written from an *understanding* of how a typical extractor works, not copied from
any existing app.

## What it does

- Lists every installed app (name, package, version, size, icon)
- Toggles between user apps and system apps
- Search by name or package
- Tap an app for a detail sheet (version, size, min/target SDK, install/update
  dates, and the full list of declared permissions)
- From the detail sheet: **Extract** the APK, **Share** it to another app, or
  **save the icon as a PNG**
- Long-press to multi-select, then extract many at once
- **Sort** by name, size, or last-updated
- Saves to any folder you pick (Downloads, SD card, etc.) via the Storage Access
  Framework — no storage permission needed
- Handles **split APKs**: multi-part apps are bundled into a single `.apks` zip
  (base + splits) so they're reinstallable

## How to build

1. Open the folder in **Android Studio** (Hedgehog or newer).
2. Let it sync Gradle (it downloads the Gradle wrapper and dependencies the first
   time — needs internet).
3. Plug in a phone with USB debugging on, or start an emulator, and hit **Run**.

The Gradle wrapper (`gradlew` + `gradle/wrapper/gradle-wrapper.jar`) is included,
so you can also build from a terminal: `./gradlew assembleDebug` produces
`app/build/outputs/apk/debug/app-debug.apk`.

Requirements: Android Studio with the Android SDK, and a device/emulator on
**Android 8.0 (API 26) or newer**. The code targets Kotlin 2.0.21 / AGP 8.5.2.

## Build it in the cloud (no local setup)

`.github/workflows/build.yml` builds a debug APK automatically on GitHub's
servers. Push this project to a GitHub repo and, under the **Actions** tab, the
build runs on its own. The finished APK shows up two ways:

- as a downloadable **artifact** on the workflow run, and
- attached to a **"latest" prerelease** under the repo's **Releases** page
  (easiest to download on a phone).

A debug APK is signed with Android's debug key — fine for sideloading onto your
own device. For a Play Store build you'd add a release signing config.

## The five moving parts (and where they live)

The whole thing is small. These are the pieces every APK extractor is built on:

| Piece | What it does | File |
|---|---|---|
| **List apps** | `PackageManager.getInstalledApplications(GET_META_DATA)` returns every app. `QUERY_ALL_PACKAGES` in the manifest is what makes it return *everything* on Android 11+. | `AppRepository.kt` |
| **Read metadata** | `loadLabel()` → name, `loadIcon()` → icon, `getPackageInfo()` → version. System vs user is `flags and FLAG_SYSTEM`. | `AppRepository.kt` |
| **Find the APK on disk** | `ApplicationInfo.publicSourceDir` is the literal path to the installed base APK; `splitSourceDirs` holds any split parts. `File(path).length()` is the size. | `AppRepository.kt` |
| **Extract** | Ask for a destination folder (`ACTION_OPEN_DOCUMENT_TREE`), persist the URI, then stream-copy the APK file(s) into it through the content resolver. | `ApkExtractor.kt` |
| **UI** | A searchable, multi-select list with a user/system toggle and an extract button. | `MainActivity.kt` + `MainViewModel.kt` |

## File map

```
app/src/main/
  AndroidManifest.xml          QUERY_ALL_PACKAGES + the launcher activity
  java/com/example/apkextractor/
    AppInfo.kt                 the data model for one app
    AppRepository.kt           reads the installed-app list from PackageManager
    ApkExtractor.kt            SAF copy logic (single APK + split-bundle zip)
    MainViewModel.kt           state + filtering + the extract coroutine
    MainActivity.kt            the Compose UI
  res/...                      icon, theme, strings
```

## Notes & next steps

- **Permissions on the Play Store:** `QUERY_ALL_PACKAGES` is a restricted
  permission. For sideloading / personal use it's fine. To publish, Google wants
  a declared reason — an "app management" tool like this is an accepted use.
- **Ideas to extend:** extract the icon as a PNG, share an APK via a share sheet,
  sort by size/date, show the app's requested permissions on a detail screen,
  dark theme (swap `lightColorScheme()` for a dynamic/dark one).
- Everything runs the heavy work (icon loading, file copy) off the main thread in
  the ViewModel — keep it that way or the UI will jank with a few hundred apps.
