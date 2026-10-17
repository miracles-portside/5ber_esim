# Roadmap

## Phase 1 — card management from the phone slot ✅

- Transport: Shizuku → `app_process` helper that opens the card's management applet (vendor
  AID `A0000005591010FFFFFFFF8900050500`) on the live modem, with a live-binder logical
  channel so the framework's "client died" cleanup can't kill it
- ES10x framing: STORE DATA, 61XX GET RESPONSE continuation, 120-byte segmentation
- ES10c: EID, profile list, enable / disable / delete / nickname — verified on the card
- Safety rails + on-screen APDU log

## Phase 2 — profile download (SGP.22) ✅

Verified end-to-end: Billion Connect installed on the card, notification delivered, profile
enabled and serving.

- QR / activation-code parsing; ES10b GetEUICCInfo1 + challenge → ES9+ initiateAuthentication
  → ES10b AuthenticateServer → ES9+ authenticateClient (preview + confirmation code) →
  ES10b PrepareDownload → ES9+ getBoundProfilePackage → segmented BF36 LoadBoundProfilePackage
  (42 segments live) → ProfileInstallationResult → ES9+ handleNotification
- Fixes the live run required: INTERNET permission; GSMA RSP2 root bundled as trust anchor;
  auto-reconnect after the SIM refresh (`6881`); empty-body notification replies

## Phase 3 — the modem write window (active)

The modem intermittently refuses writes (`6F00`) to the card while reads stay fine; toggling
the built-in eSIM resets the stack and writes open again. Open questions:

- [ ] **Measure the window**: after the toggle, how many seconds do writes stay open?
      Tool: `profilemgr.py probe` + `tools/iccbr` (the Termux/adb path)
- [ ] **Find the closure trigger**: time, a later APDU, or the framework re-arming a filter?
- [ ] **Recover without the manual toggle**: e.g. time retries to the window, or re-create
      the stack reset from the shell side (Shizuku)
- [ ] Fold the result into the app (better hint / automatic retry)

## Phase 4 — polish

- Profile icons (tag 0x94) and richer EUICC info display
- Notifications: list / send / delete (ES10b) — to replay notifications left queued by
  failed downloads
- SM-DS discovery (optional; EasyEUICC doesn't support it either)
- Telephony/OMAPI transport is *not* planned: blocked by ARA-M for this card on unrooted
  phones; the Shizuku shell path is the supported route.
