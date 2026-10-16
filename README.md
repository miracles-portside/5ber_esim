# 5ber eSIM

An Android app for talking **directly to a 5ber physical eSIM card** (or any SGP.22 eUICC)
through a USB smart-card reader — an independent replacement for the dead 5ber app.

## Why a reader?

The 5ber app could manage the card because the card's ARA-M access table only trusts the
5ber app (which no longer exists), and Android blocks apps from SIM APDU access anyway.
A USB CCID reader talks ISO 7816 to the chip directly, bypassing ARA-M completely — no
root required. This is the standard recovery route for orphaned 5ber / esim.me cards.

## Status

Phase 1 — **local profile management over a USB reader** (builds and installs):

- USB CCID (T=0) transport for contact smart-card readers
- eUICC session: MANAGE CHANNEL + ISD-R select, ES10x STORE DATA framing,
  61XX GET RESPONSE continuation, 120-byte segmentation
- ES10c operations: read EID, list profiles, enable / disable / delete profile, set nickname
- Safety rails: can't delete the only/last profile, warnings before disabling or
  deleting the active profile
- On-screen log (with optional full APDU hex) for on-device debugging
- **Demo mode** to exercise the whole UI + protocol code without any hardware

Phase 2 — profile download from SM-DP+ (activation code / QR): see `ROADMAP.md`.

## Hardware you need

- A **USB CCID smart-card reader**, T=0. Known-good: Hirsch SCR3310 v2.0, uTrust 2700R/F,
  HID OMNIKEY 3021/3121, 9eSIM / ESTKme readers. Cheap generic "SIM card reader writers"
  usually work but are hit-or-miss.
- A USB-C OTG adapter (for the phone).
- A nano-SIM → standard SIM adapter if the reader takes full-size SIMs (many include one).

## Build

```sh
./gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

For the current build, a copy is also at `~/storage/downloads/5ber_esim-debug.apk`.

## Use

1. Put the 5ber card in the reader, plug the reader into the phone.
2. Open the app → *Connect / Read card* → grant USB permission.
3. Manage profiles. Put the card back in the phone and restart the phone if you
   switched the active profile.

*Demo mode* runs the entire flow against a fake card, no reader needed.

## Stack

Gradle 8.14.3, AGP 8.13.0, Kotlin 2.2.20, compileSdk/targetSdk 36, minSdk 24,
package `work.portside.esim`. Termux note: `gradle.properties` points
`android.aapt2FromMavenOverride` at the prebuilt aapt2 in `~/termux-android-studio`.

## Layout

```
app/src/main/java/work/portside/esim/
  util/    Hex, TLV codec, log bus
  usb/     CardTransport, USB CCID reader driver, demo (fake) card
  euicc/   EuiccCard — ES10x/ES10c protocol, ProfileInfo
  MainActivity.kt — UI
app/src/main/res/  layout, theme, launcher icon, USB attach filter
```

Protocol references consulted while writing this (no code copied):
lpac (`estkme-group/lpac`), OpenEUICC/EasyEUICC (`gitea.angry.im/PeterCxy/OpenEUICC`),
GSMA SGP.22 v2.2.2.
