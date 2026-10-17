# 5ber eSIM

An Android app for talking **directly to a 5ber physical eSIM card** (or any SGP.22 eUICC) —
an independent replacement for the dead 5ber app. The card sits in the phone's own SIM slot;
the app reaches it through [Shizuku](https://shizuku.rikka.app/): no reader, no root, no PC.

Below the app there is also a lower-level path used for probing: **IccBr**, a tiny helper run
through adb as shell that injects APDUs straight into the running modem, driven from Termux by
`profilemgr.py`. It is the tool for the open question — the modem's **write window** (see
[below](#the-modem-write-window-open-question)).

## Why Shizuku?

The 5ber card's ARA-M access table only trusts the original 5ber app, which no longer exists,
and Android blocks ordinary apps from SIM APDU access (OMAPI rejects third-party
certificates on the orphaned card, and the hidden telephony APIs need shell/root). Shizuku —
free, no root, startable on-device through wireless debugging on Android 11+ — provides that
shell context: the app launches a tiny helper process through Shizuku which opens the card's
management applet on the live modem and pipes APDUs between it and the app.

That identity cannot be re-created: an APK only ever ships the *public* certificate — the
private signing key never leaves the developer's build system — so no re-signed build can
ever match the card's access rules. Shizuku is the only way in without a PC — and the same
trick, run over adb instead, is the `IccBr` helper below.

## Status

**Phase 1 — local card management** (verified live on a Redmi Note 15 Pro 5G, Android 16,
with a 5ber/ifree card):

- Phone-slot transport through Shizuku (a helper process on the running modem)
- eUICC session: MANAGE CHANNEL + applet select (5ber vendor applet, ISD-R fallback),
  ES10x STORE DATA framing, 61XX GET RESPONSE continuation, 120-byte segmentation
- ES10c operations: read EID, list profiles, enable / disable / delete profile, set nickname
  (enable / disable / delete verified on the card)
- Safety rails: can't delete the only/last profile, warnings before disabling or deleting
  the active profile
- On-screen log (with optional full APDU hex) for on-device debugging

**Phase 2 — profile download (SGP.22)** — verified end-to-end (Billion Connect,
`rsp.billionconnect.com`):

- QR scanner built in (CameraX + ZXing core, no Play Services) + manual code entry
- Full download flow: ES10b GetEUICCInfo1 + challenge → ES9+ initiateAuthentication →
  ES10b AuthenticateServer → ES9+ authenticateClient (profile preview + optional
  confirmation code) → ES10b PrepareDownload → ES9+ getBoundProfilePackage → segmented
  BF36 LoadBoundProfilePackage (42 segments in the live run) → result notification to the
  SM-DP+ → offer to enable the new profile
- Issues the live test surfaced, both fixed: the app was missing the INTERNET permission,
  and SM-DP+ TLS certificates chain to the GSMA RSP2 PKI that Android does not trust by
  default (see *SM-DP+ notes* below)

**Phase 3 — the modem write window** — active; see the section below.

## What you need

- The 5ber card, inserted in the phone's SIM slot.
- [Shizuku](https://shizuku.rikka.app/), started (Android 11+ can start it through wireless
  debugging — no PC needed).
- For the Termux probing path only: Termux + `adb` (wireless debugging).

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

Release copies land in `/sdcard/Download/` (e.g. `5ber-esim-0.2.4-release.apk`; the current
release is **0.2.4**).

## Use

**Read the card**

1. Start Shizuku.
2. Open the app → *Read card in phone slot (Shizuku)* → grant Shizuku and phone-state
   permissions.
3. Manage profiles: enable / disable / rename / delete.

**Add a profile**

1. *Add eSIM profile (QR / activation code)* → scan the provider's QR or paste the code.
2. Review the profile preview coming from the server; enter the confirmation code if the
   provider sent one.
3. The app downloads, installs and reports the result, then offers to enable the new profile.

After switching the active profile the app reconnects by itself: the card re-initializes
for a moment (the modem re-reads it as a fresh subscription). If card writes start getting
refused (`6F00`), toggle the built-in eSIM in Settings → SIM cards and retry — the app
hints this in the log (and see the next section).

## The modem write window (open question)

The modem intermittently refuses **writes** to this card while reads keep working: a write
comes back `6F00` ("no precise diagnosis"), or the logical channel cannot even be opened.
Toggling the **built-in eSIM** off/on under Settings → SIM cards resets the whole SIM stack
and writes work again. What is not known yet: **how long the window stays open, and what
closes it.**

`profilemgr.py probe` is the measuring tool (see *Probing from Termux* below): it sends a
deliberately-invalid, side-effect-free write (a padded `BF29`) every second and prints a
timeline. Toggle the built-in eSIM mid-run and watch the transitions:

```
[t+   0.6s] write -> 6F00                [BLOCKED (6F00)]
[t+  42.1s] write -> BF290380017F9000    [open (write path)]
[t+  95.7s] write -> 6F00                [BLOCKED (6F00)]
-- summary --
  write path open  t+  42.1s .. t+  95.7s  (~54s)
  96 probes: 53 open, 42 blocked, 1 no-session
```

*(That is the shape of the output — the actual window still needs measuring.)* Once it is
characterized, the app can drop the manual toggle: retry exactly when the window opens, or
re-create the reset itself.

## Probing from Termux (the adb path)

`tools/iccbr/` holds **IccBr**, a small Java helper run through adb as shell
(`app_process64`): it opens the card's management applet on the running modem through a live
Binder (so the framework's "client died" cleanup doesn't kill the channel) and then behaves
as a plain APDU terminal on stdin. `profilemgr.py` drives it over wireless debugging:

```sh
# one-time: build and deploy the helper
tools/iccbr/build.sh
adb push tools/iccbr/build/iccbr.jar /data/local/tmp/iccbr.jar

# use
python3 profilemgr.py list                      # profile list straight from the card
python3 profilemgr.py probe 120                 # write-window timeline (toggle mid-run)
python3 profilemgr.py nickname <iccid> test     # any ES10c op
```

Env: `ADB_DEVICE` picks a device (default: first in `adb devices`); `CARD_SUB` is the card's
subscription id. A SIM refresh re-enumerates the card as a brand-new subscription, so
`CARD_SUB` changes after every profile switch — the app log shows it (`slot: subId …`).
This path is a dev/probing aid; for day-to-day use the app is the easy way.

## Card quirks worth knowing

- Switching the active profile triggers a SIM refresh: the old logical channel dies with
  `6881` ("logical channel not supported") and the card re-enumerates as a new subscription
  (even the `subId` changes). The app detects this and reconnects automatically.
- The modem intermittently write-blocks this card with `6F00` — see *The modem write window*.
  Toggling the built-in eSIM off/on in Settings → SIM cards resets it; the app hints this.
- GetEID needs the `5C` tag selector form (`BF3E 03 5C 01 5A`) on this card; the bare form is
  refused.
- The card's management applet sits at the vendor AID `A0000005591010FFFFFFFF8900050500`;
  the standard ISD-R AID answers `6A82` when the card is in its factory state.

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
  card/    CardTransport — the interface EuiccCard talks to
  euicc/   EuiccCard — ES10x/ES10c protocol, ProfileInfo
  lpa/     SGP.22 download: ActivationCode, Es9 (ES9+), Sgp22 (ES10b), LpaSession
  shell/   SlotHelper — the Shizuku-launched app_process helper
  slot/    SlotTransport — client side of SlotHelper
  ui/      QrScanActivity — CameraX/ZXing QR scanner
  MainActivity.kt — UI
app/src/main/res/  layout, theme, launcher icon,
                   network_security_config.xml + bundled GSMA CI root (res/raw)
profilemgr.py — adb-driven CLI for low-level card work (see *Probing from Termux*)
tools/iccbr/  — IccBr.java + build script: the shell helper that CLI drives
```

## License

This project is licensed under the **GNU AGPL-3.0-only** — see [LICENSE](LICENSE).

The SGP.22 profile-download implementation follows lpac (`estkme-group/lpac`, also
AGPL-3.0) as its authoritative reference for message construction and the BF36
segmentation, so this project carries the same license. Other references consulted:
OpenEUICC / EasyEUICC (`gitea.angry.im/PeterCxy/OpenEUICC`) and GSMA SGP.22 v2.2.2.
