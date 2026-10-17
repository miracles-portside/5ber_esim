# 5ber eSIM

An Android app for talking **directly to a 5ber physical eSIM card** (or any SGP.22 eUICC) —
an independent replacement for the dead 5ber app. Two ways to reach the card:

- **In the phone's SIM slot, via [Shizuku](https://shizuku.rikka.app/)** — no reader, no root,
  no PC. This is the everyday path.
- **Over a USB CCID smart-card reader** — fully self-contained, works even where Shizuku
  can't run.

## Why Shizuku?

The 5ber card's ARA-M access table only trusts the original 5ber app, which no longer exists,
and Android blocks ordinary apps from SIM APDU access (OMAPI rejects third-party
certificates on the orphaned card, and the hidden telephony APIs need shell/root). Shizuku —
free, no root, startable on-device through wireless debugging on Android 11+ — provides that
shell context: the app launches a tiny helper process through Shizuku which opens the card's
management applet on the live modem and pipes APDUs between it and the app.

That identity cannot be re-created: an APK only ever ships the *public* certificate — the
private signing key never leaves the developer's build system — so no re-signed build can
ever match the card's access rules. Shizuku (or the reader) is the only way in.

A USB CCID reader talks ISO 7816 to the chip directly, bypassing ARA-M completely — no
Shizuku required. This is the standard recovery route for orphaned cards.

## Status

**Phase 1 — local card management** (builds and runs; verified live on a Redmi Note 15 Pro 5G,
Android 16, with a 5ber/ifree card):

- Phone-slot transport through Shizuku (a helper process on the running modem)
- USB CCID (T=0) transport for contact smart-card readers
- eUICC session: MANAGE CHANNEL + applet select (5ber vendor applet, ISD-R fallback),
  ES10x STORE DATA framing, 61XX GET RESPONSE continuation, 120-byte segmentation
- ES10c operations: read EID, list profiles, enable / disable / delete profile, set nickname
  (enable / disable / delete verified on the card)
- Safety rails: can't delete the only/last profile, warnings before disabling or deleting
  the active profile
- On-screen log (with optional full APDU hex) for on-device debugging
- **Demo mode** to exercise the whole UI + protocol code without any hardware

**Phase 2 — profile download (SGP.22)** — working; first real eSIM install verified
end-to-end on the phone slot (Billion Connect, `rsp.billionconnect.com`):

- QR scanner built in (CameraX + ZXing core, no Play Services) + manual code entry
- Full download flow: ES10b GetEUICCInfo1 + challenge → ES9+ initiateAuthentication →
  ES10b AuthenticateServer → ES9+ authenticateClient (profile preview + optional
  confirmation code) → ES10b PrepareDownload → ES9+ getBoundProfilePackage → segmented
  BF36 LoadBoundProfilePackage (42 segments in the live run) → result notification to the
  SM-DP+ → offer to enable the new profile
- Issues the live test surfaced, both fixed: the app was missing the INTERNET permission,
  and SM-DP+ TLS certificates chain to the GSMA RSP2 PKI that Android does not trust by
  default (see *SM-DP+ notes* below)

## Hardware you need

- **Phone-slot path: nothing extra.** Just the 5ber card, the phone, and Shizuku.
- **Reader path (optional):** a USB CCID smart-card reader, T=0. Known-good: Hirsch
  SCR3310 v2.0, uTrust 2700R/F, HID OMNIKEY 3021/3121, 9eSIM / ESTKme readers. Cheap generic
  "SIM card reader writers" usually work but are hit-or-miss.
  - A USB-C OTG adapter (for the phone).
  - A nano-SIM → standard SIM adapter if the reader takes full-size SIMs (many include one).

## Build

```sh
./build-release.sh           # release build + copy to /sdcard/Download (the usual flow)
./gradlew assembleRelease    # signed release APK only
./gradlew assembleDebug      # debug APK (dev only)
```

- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`
- Release APK: `app/build/outputs/apk/release/app-release.apk` — signed with the project's
  release keystore. The credentials live in `keystore.properties` and `keystore/*.jks`,
  both git-ignored: **back them up**, or you won't be able to update-install over an
  existing release build.

Release copies land in `/sdcard/Download/` (e.g. `5ber-esim-0.2.3-release.apk`; the current
release is **0.2.3**).

## Use

**Read the card in the phone's SIM slot (recommended)**

1. Install [Shizuku](https://shizuku.rikka.app/) and start it (on Android 11+ you can pair it
   through wireless debugging — no PC needed).
2. Open the app → *Read card in phone slot (Shizuku)* → grant Shizuku and phone-state
   permissions.
3. Manage profiles: enable / disable / rename / delete.

**Over a USB reader**

1. Put the 5ber card in the reader, plug the reader into the phone.
2. Open the app → *Connect / Read card* → grant USB permission.
3. Manage profiles.

**Add a profile**

1. *Add eSIM profile (QR / activation code)* → scan the provider's QR or paste the code.
2. Review the profile preview coming from the server; enter the confirmation code if the
   provider sent one.
3. The app downloads, installs and reports the result, then offers to enable the new profile.

After switching the active profile the app reconnects by itself: the card re-initializes
for a moment (the modem re-reads it as a fresh subscription). If card writes start getting
refused (`6F00`), toggle the built-in eSIM in Settings → SIM cards and retry — the app
hints this in the log.

*Demo mode* runs the entire flow against a fake card, no reader needed.

## Card quirks worth knowing

- Switching the active profile triggers a SIM refresh: the old logical channel dies with
  `6881` ("logical channel not supported") and the card re-enumerates as a new subscription
  (even the `subId` changes). The app detects this and reconnects automatically.
- The modem occasionally write-blocks this card (`6F00` a few ms into a write). Toggling the
  built-in eSIM off/on in Settings → SIM cards resets the card's write protection; the app
  hints this in the log.
- GetEID needs the `5C` tag selector form (`BF3E 03 5C 01 5A`) on this card; the bare form is
  refused.

## SM-DP+ (server) notes

- **TLS**: SM-DP+ endpoints are commonly secured with GSMA CI (SGP.22) certificates — e.g.
  `rsp.billionconnect.com` chains to `GSM Association - RSP2 Root CI1`, which is *not* in
  Android's public trust store. The app bundles that root (`res/raw/gsma_rsp2_root_ci1.pem`)
  as an extra trust anchor via `network_security_config.xml`, keeping the system store for
  providers that use public CAs (Android's own LPA bundles the GSMA roots too).
  For reference, lpac skips TLS verification entirely — the eUICC authenticates the server
  end-to-end by signature (`serverSigned1` is verified by the card); we do both.
- `handleNotification` may be answered with an empty 2xx body — that means success. If
  reporting fails, the notification stays queued on the card; the provider's portal may show
  the profile as "not installed" until it is delivered.
- The profile preview shown before installation comes from `profileMetadata`; some servers
  omit it, in which case the app just asks for a plain confirm.

## Stack

Gradle 8.14.3, AGP 8.13.0, Kotlin 2.2.20, compileSdk/targetSdk 36, minSdk 24, package
`work.portside.esim`. UI is plain AppCompat views; QR scanning via CameraX 1.4 + ZXing core
3.5 (no Google Play Services); APDU access through Shizuku 13.1.5.
Termux note: if the bundled aapt2 can't run on your device, set
`android.aapt2FromMavenOverride` (e.g. to `~/termux-android-studio/prebuilt/aapt2`) in your
global `~/.gradle/gradle.properties` — deliberately not in this repo, so the project stays
clonable anywhere.

## Layout

```
app/src/main/java/work/portside/esim/
  util/    Hex, TLV/BER codecs, base64, log bus
  usb/     CardTransport, USB CCID reader driver, demo (fake) card
  euicc/   EuiccCard — ES10x/ES10c protocol, ProfileInfo
  lpa/     SGP.22 download: ActivationCode, Es9 (ES9+), Sgp22 (ES10b), LpaSession
  shell/   SlotHelper — the Shizuku-launched app_process helper
  slot/    SlotTransport — client side of SlotHelper
  ui/      QrScanActivity — CameraX/ZXing QR scanner
  MainActivity.kt — UI
app/src/main/res/  layout, theme, launcher icon, USB attach filter,
                   network_security_config.xml + bundled GSMA CI root (res/raw)
profilemgr.py — adb-driven CLI for the same card operations (dev aid)
```

## License

This project is licensed under the **GNU AGPL-3.0-only** — see [LICENSE](LICENSE).

The SGP.22 profile-download implementation follows lpac (`estkme-group/lpac`, also
AGPL-3.0) as its authoritative reference for message construction and the BF36
segmentation, so this project carries the same license. Other references consulted:
OpenEUICC / EasyEUICC (`gitea.angry.im/PeterCxy/OpenEUICC`) and GSMA SGP.22 v2.2.2.
