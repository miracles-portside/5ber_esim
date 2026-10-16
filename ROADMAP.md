# Roadmap

## Phase 1 — local profile management over USB reader ✅

- USB CCID T=0 transport (`usb/CcidReader.kt`)
- eUICC session + ES10x framing (`euicc/EuiccCard.kt`)
- EID, profile list, enable/disable/delete, nickname
- Demo mode fake card for hardware-less testing

**Blocked on hardware**: a USB CCID reader + the 5ber card to verify against the real chip.

## Phase 2 — add a new profile (download from SM-DP+)

This is the big one: full LPA download per GSMA SGP.22. Flow with an activation code
(`LPA:1$<smdp+>$<matchingId>[$<oid>]` or a QR):

1. **Parse activation code** (QR or typed): SM-DP+ FQDN + matching ID (+ optional OID).
2. **eUICC prep**
   - ES10b `GetEUICCInfo1` (BF20) — forwarded to the server.
   - ES10b `GetEUICCChallenge` (BF2E).
   - ES10c `GetEID` (BF3E) — already implemented.
3. **ES9+ `AuthenticateServer`** — HTTPS POST (JSON, base64 ASN.1 body) to
   `https://<smdp>:443/gsma/rsp2/es9plus/authenticateServer`.
   - Request: euiccChallenge + euiccInfo1 + matchingId (ASN.1 DER, base64).
   - Response: serverSigned1, serverSignature1, euiccCiPKIdToBeUsed, serverCertificate.
4. **ES10b `AuthenticateServer`** (BF38) — pass server data to the card; the *card* verifies
   the GSMA CI chain and returns euiccSigned1 + euiccSignature1.
5. **ES9+ `AuthenticateServer` (2nd call)** — send euiccSigned1/euiccSignature1 + LPA
   certificate chain + lpaSignature1 (LPA signs with its key; certs needed — check what
   lpac ships; GSMA CI roots are in SGP.22 spec).
   - Response: transactionId + profileMetadata for the confirmation step.
6. **ES9+ `GetBoundProfilePackage`** — returns the BoundProfilePackage (BF37) as a
   base64 string.
7. **ES10b `LoadBoundProfilePackage`** (BF36) — feed the package to the card in segments,
   relaying the card's responses per the standard until ProfileInstallationResult (BF37)
   comes back; handle rollback/notification cases.
8. **ES9+ `HandleNotification`** — report ProfileInstallationResult to the server.
9. **Enable** the new profile (existing ES10c code) + refresh handling.

Needed pieces:
- ASN.1 DER encode/decode for the SGP.22 structures (extend `util/Tlv.kt` into a small
  typed layer for the specific structures).
- HTTPS + JSON wrapper for ES9+ (OkHttp or plain HttpsURLConnection; no cert pinning
  needed beyond system trust + GSMA CI verification done by the card).
- ECDSA P-256 sign/verify via JCA (Android supports secp256r1).
- Certificate material: GSMA CI root certs + an LPA certificate/key pair that SM-DP+
  servers accept (research what lpac uses — `euicc/es9p.c`, and their cert handling).
- Testing needs a real activation code (single-use — get one when close).

## Phase 3 — polish

- Profile icons (tag 0x94) and richer EUICC info display
- Notifications: list / send / delete (ES10b) — useful after failed downloads
- SM-DS discovery (optional; EasyEUICC doesn't support it either)
- Telephony/OMAPI transport is *not* planned: blocked by ARA-M for this card on
  unrooted phones, readers are the supported path.

## Testing checklist (when the reader arrives)

- [ ] Reader enumerated, permission flow, ATR read
- [ ] MANAGE CHANNEL + ISD-R select on the real 5ber card
- [ ] EID read (compare with the EID 5ber showed, if recorded)
- [ ] Profile list matches reality; state flags correct
- [ ] Rename round-trip
- [ ] Enable/disable/delete behaviour + policy errors mapped correctly
- [ ] Re-insert card into phone; check it still registers
