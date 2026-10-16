# 5ber eSIM

Android app for managing eSIM profiles on a 5ber physical SIM card.

## Status

Fresh scaffold — builds a minimal app (title screen + placeholder action).
No SIM/eSIM functionality yet.

## Stack

- Gradle 8.14.3, AGP 8.13.0, Kotlin 2.2.20
- compileSdk / targetSdk 36, minSdk 24
- `work.portside.esim`

## Build

```sh
./gradlew assembleDebug
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`

Notes for Termux: `gradle.properties` sets
`android.aapt2FromMavenOverride` to the prebuilt aapt2 at
`~/termux-android-studio/prebuilt/aapt2`, matching the other local projects.

## Layout

```
app/src/main/java/work/portside/esim/   Kotlin sources
app/src/main/res/                       resources, theme, launcher icon
```
