package work.portside.esim.lpa

import work.portside.esim.usb.CardException
import work.portside.esim.util.B64
import work.portside.esim.util.Ber
import work.portside.esim.util.Hex
import work.portside.esim.util.Tlv
import java.security.MessageDigest

/**
 * SGP.22 structure builders/parsers for the profile-download flow (ES10b requests and the
 * response layouts). Mirrors the GSMA reference LPA (lpac) so the wire format matches what
 * SM-DP+ servers and cards expect:
 *
 *  - AuthenticateServer       BF38 { ServerSigned1(30), ServerSignature1(5F37), CiPKId(04),
 *                                    ServerCertificate(30), ctxParams1 A0 { matchingId?,
 *                                    deviceInfo A1 { tac(80), deviceCapabilities A1 } } }
 *  - PrepareDownload          BF21 { smdpSigned2(30), smdpSignature2(5F37), [hashCc(04)], cert(30) }
 *  - LoadBoundProfilePackage  BF36 … segmented exactly like the reference LPA
 */
object Sgp22 {

    // ------------------------------------------------------------------ card requests

    fun getEuiccInfo1Request(): ByteArray = Tlv.encode(0xBF20, ByteArray(0))

    fun getEuiccChallengeRequest(): ByteArray = Tlv.encode(0xBF2E, ByteArray(0))

    /** Extracts the whole `BF20 …` TLV from the card's GetEuiccInfo1 response. */
    fun euiccInfo1(response: ByteArray): ByteArray {
        val node = Ber.find(response, 0xBF20)
            ?: throw CardException("Card did not return eUICC info (BF20)")
        return node.whole(response)
    }

    /** Extracts the 16-byte challenge from the card's GetEuiccChallenge response. */
    fun euiccChallenge(response: ByteArray): ByteArray {
        val root = Ber.find(response, 0xBF2E)
            ?: throw CardException("Card did not return a challenge (BF2E)")
        return Ber.find(response, 0x80, root.valueStart, root.end)?.value(response)
            ?: throw CardException("Card challenge response is malformed")
    }

    /** Binary transaction ID, taken from ServerSigned1's `[0]` field. */
    fun transactionIdFromServerSigned1(serverSigned1B64: String): ByteArray {
        val buf = B64.decode(serverSigned1B64)
        val signed1 = Ber.find(buf, 0x30) ?: throw Smdp("ServerSigned1 is not a SEQUENCE")
        return Ber.find(buf, 0x80, signed1.valueStart, signed1.end)?.value(buf)
            ?: throw Smdp("ServerSigned1 has no transaction ID")
    }

    fun authenticateServerRequest(
        serverSigned1B64: String,
        serverSignature1B64: String,
        euiccCiPKIdToBeUsedB64: String,
        serverCertificateB64: String,
        matchingId: String?,
    ): ByteArray {
        val signed1 = findWhole(serverSigned1B64, 0x30, "ServerSigned1")
        val signature1 = findWhole(serverSignature1B64, 0x5F37, "ServerSignature1")
        val ciPkId = findWhole(euiccCiPKIdToBeUsedB64, 0x04, "euiccCiPKIdToBeUsed")
        val certificate = findWhole(serverCertificateB64, 0x30, "ServerCertificate")

        val deviceInfo = Tlv.encode(
            0xA1,
            Tlv.encode(0x80, byteArrayOf(0x35, 0x29, 0x06, 0x11)) + // placeholder TAC, like the reference LPA
                    Tlv.encode(0xA1, ByteArray(0)),
        )
        val ctxParams1 = Tlv.encode(
            0xA0,
            (matchingId?.let { Tlv.encode(0x80, it.toByteArray(Charsets.UTF_8)) } ?: ByteArray(0)) + deviceInfo,
        )
        return Tlv.encode(0xBF38, signed1 + signature1 + ciPkId + certificate + ctxParams1)
    }

    /** True when SM-DP+ says a confirmation code (password) is needed for this download. */
    fun confirmationCodeRequired(smdpSigned2B64: String): Boolean {
        val buf = B64.decode(smdpSigned2B64)
        val signed2 = Ber.find(buf, 0x30) ?: return false
        val flag = Ber.find(buf, 0x01, signed2.valueStart, signed2.end) ?: return false
        return flag.valueLength > 0 && flag.value(buf).any { it.toInt() != 0 }
    }

    fun prepareDownloadRequest(
        smdpSigned2B64: String,
        smdpSignature2B64: String,
        smdpCertificateB64: String,
        confirmationCode: String?,
    ): ByteArray {
        val signed2Buf = B64.decode(smdpSigned2B64)
        val signed2 = Ber.find(signed2Buf, 0x30) ?: throw Smdp("smdpSigned2 is not a SEQUENCE")
        val signature2 = findWhole(smdpSignature2B64, 0x5F37, "smdpSignature2")
        val certificate = findWhole(smdpCertificateB64, 0x30, "smdpCertificate")

        var body = signed2.whole(signed2Buf) + signature2

        if (confirmationCodeRequired(smdpSigned2B64)) {
            if (confirmationCode.isNullOrEmpty()) {
                throw Smdp("This profile requires the confirmation code (password) that came with the purchase")
            }
            val transactionId = Ber.find(signed2Buf, 0x80, signed2.valueStart, signed2.end)?.value(signed2Buf)
                ?: throw Smdp("smdpSigned2 has no transaction ID")
            body += Tlv.encode(0x04, hashCc(confirmationCode, transactionId))
        }
        body += certificate
        return Tlv.encode(0xBF21, body)
    }

    /** hashCc = SHA256( SHA256(confirmationCode) || transactionId ), per SGP.22. */
    private fun hashCc(confirmationCode: String, transactionId: ByteArray): ByteArray {
        val sha = MessageDigest.getInstance("SHA-256")
        val ccHash = sha.digest(confirmationCode.toByteArray(Charsets.UTF_8))
        sha.reset()
        sha.update(ccHash)
        sha.update(transactionId)
        return sha.digest()
    }

    fun cancelSessionRequest(transactionId: ByteArray, reason: Int): ByteArray = Tlv.encode(
        0xBF41,
        Tlv.encode(0x80, transactionId) + Tlv.encode(0x81, byteArrayOf(reason.toByte())),
    )

    fun retrieveNotificationRequest(seqNumber: Int): ByteArray = Tlv.encode(
        0xBF2B,
        Tlv.encode(0xA0, Tlv.encode(0x80, byteArrayOf(seqNumber.toByte()))),
    )

    /** The pending notification (BF37 ProfileInstallationResult or 30 otherSignedNotification) raw bytes. */
    fun notificationFromRetrieveResponse(response: ByteArray): ByteArray {
        val root = Ber.find(response, 0xBF2B) ?: throw CardException("Card has no notification to return")
        val a0 = Ber.find(response, 0xA0, root.valueStart, root.end)
            ?: throw CardException("Notification response is malformed")
        val inner = Ber.parse(response, a0.valueStart, a0.end)
            .firstOrNull { it.tag == 0xBF37 || it.tag == 0x30 }
            ?: throw CardException("Notification response is empty")
        return inner.whole(response)
    }

    fun notificationSentRequest(seqNumber: Int): ByteArray = Tlv.encode(
        0xBF30,
        Tlv.encode(0x80, byteArrayOf(seqNumber.toByte())),
    )

    // ------------------------------------------------------------------ profile installation result

    data class InstallResult(
        val seqNumber: Int?,
        val iccid: String?,
        val success: Boolean,
        val bppCommandId: Int?,
        val errorReason: Int?,
    ) {
        fun describeError(): String {
            val command = when (bppCommandId) {
                0 -> "initialise secure channel"
                1 -> "configure iSD-P"
                2, 3 -> "store metadata"
                4 -> "replace session keys"
                5 -> "load profile elements"
                else -> "unknown step"
            }
            val reason = when (errorReason) {
                1 -> "incorrect input values"
                2 -> "invalid signature"
                3 -> "invalid transaction ID"
                4 -> "unsupported certificate values"
                5 -> "unsupported remote operation"
                6 -> "unsupported profile class"
                7 -> "SCP03t structure error"
                8 -> "SCP03t security error"
                9 -> "a profile with this ICCID already exists on the card"
                10 -> "not enough memory for this profile"
                11 -> "installation was interrupted"
                12 -> "profile processing error in the card"
                13 -> "profile data mismatch"
                14 -> "invalid NAA key (test profile)"
                15 -> "blocked by the card's policy rules (PPR)"
                127 -> "unknown error"
                else -> "error ${errorReason ?: "?"}"
            }
            return "Profile installation failed while trying to $command: $reason"
        }
    }

    /** Parses the card's response to the final LoadBoundProfilePackage segment (BF37). */
    fun parseInstallResult(response: ByteArray): InstallResult {
        val bf37 = Ber.find(response, 0xBF37)
            ?: throw CardException("Card did not return a profile installation result")
        val bf27 = Ber.find(response, 0xBF27, bf37.valueStart, bf37.end)
            ?: throw CardException("Installation result has no data")
        val bf2f = Ber.find(response, 0xBF2F, bf27.valueStart, bf27.end)

        val seq = bf2f?.let { Ber.find(response, 0x80, it.valueStart, it.end) }
            ?.let { node -> node.value(response).fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) } }
        val iccid = bf2f?.let { Ber.find(response, 0x5A, it.valueStart, it.end) }
            ?.let { Hex.bcdDigits(it.value(response)) }

        val a2 = Ber.find(response, 0xA2, bf27.valueStart, bf27.end)
            ?: throw CardException("Installation result has no final result")
        val final = Ber.parse(response, a2.valueStart, a2.end).firstOrNull()
            ?: throw CardException("Installation result is empty")

        return when (final.tag) {
            0xA0 -> InstallResult(seq, iccid, success = true, bppCommandId = null, errorReason = null)
            else -> {
                var commandId: Int? = null
                var errorReason: Int? = null
                for (child in Ber.parse(response, final.valueStart, final.end)) {
                    when (child.tag) {
                        0x80 -> commandId = toInt(child.value(response))
                        0x81 -> errorReason = toInt(child.value(response))
                    }
                }
                InstallResult(seq, iccid, success = false, bppCommandId = commandId, errorReason = errorReason)
            }
        }
    }

    // ------------------------------------------------------------------ BoundProfilePackage segmentation

    /**
     * Splits a BoundProfilePackage into the sequence of LoadBoundProfilePackage segments the
     * card receives — byte-for-byte the reference-LPA strategy: the first command carries the
     * BF36 header plus the InitialiseSecureChannel element, then one command per structural
     * element (with sequences of profile elements sent one entry at a time).
     */
    fun bppSegments(boundProfilePackageB64: String): List<ByteArray> {
        val bpp = B64.decode(boundProfilePackageB64)
        val root = Ber.find(bpp, 0xBF36)
            ?: throw Smdp("The server's BoundProfilePackage is malformed (no BF36)")

        val out = ArrayList<ByteArray>()

        fun add(from: Int, to: Int) = out.add(bpp.copyOfRange(from, to))

        val isc = Ber.find(bpp, 0xBF23, root.valueStart, root.end)
            ?: throw Smdp("The BoundProfilePackage has no InitialiseSecureChannel element")
        add(root.start, isc.end)

        val first = Ber.find(bpp, 0xA0, root.valueStart, root.end)
            ?: throw Smdp("The BoundProfilePackage has no profile-element sequence")
        add(first.start, first.end)

        val second = Ber.find(bpp, 0xA1, root.valueStart, root.end)
            ?: throw Smdp("The BoundProfilePackage has no second element sequence")
        add(second.start, second.valueStart) // header first…
        for (child in Ber.parse(bpp, second.valueStart, second.end)) {
            add(child.start, child.end) // …then one command per element
        }

        Ber.find(bpp, 0xA2, root.valueStart, root.end)?.let { add(it.start, it.end) }

        val last = Ber.find(bpp, 0xA3, root.valueStart, root.end)
            ?: throw Smdp("The BoundProfilePackage has no final element sequence")
        add(last.start, last.valueStart)
        for (child in Ber.parse(bpp, last.valueStart, last.end)) {
            add(child.start, child.end)
        }

        return out
    }

    // ------------------------------------------------------------------ profile metadata (preview)

    data class ProfileMeta(val iccid: String?, val profileName: String?, val serviceProviderName: String?) {
        val displayName: String
            get() = listOfNotNull(
                profileName?.takeIf { it.isNotBlank() },
                serviceProviderName?.takeIf { it.isNotBlank() },
            ).firstOrNull() ?: "New profile"
    }

    fun profileMetadata(profileMetadataB64: String): ProfileMeta? {
        val buf = B64.decode(profileMetadataB64)
        val root = Ber.find(buf, 0xBF25) ?: return null
        val iccid = Ber.find(buf, 0x5A, root.valueStart, root.end)?.let { Hex.bcdDigits(it.value(buf)) }
        val name = Ber.find(buf, 0x92, root.valueStart, root.end)?.let { String(it.value(buf), Charsets.UTF_8) }
        val spn = Ber.find(buf, 0x91, root.valueStart, root.end)?.let { String(it.value(buf), Charsets.UTF_8) }
        return ProfileMeta(iccid, name, spn)
    }

    // ------------------------------------------------------------------ helpers

    private fun findWhole(b64: String, tag: Int, what: String): ByteArray {
        val buf = B64.decode(b64)
        val node = Ber.find(buf, tag) ?: throw Smdp("$what is malformed (no %02X tag)".format(tag))
        return node.whole(buf)
    }

    private fun toInt(bytes: ByteArray): Int {
        var r = 0
        for (b in bytes) r = (r shl 8) or (b.toInt() and 0xFF)
        return r
    }

    /** Errors coming from malformed server data. */
    class Smdp(message: String) : Exception(message)
}
