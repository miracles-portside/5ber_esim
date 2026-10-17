package work.portside.esim.euicc

import work.portside.esim.usb.CardException
import work.portside.esim.usb.CardTransport
import work.portside.esim.util.Hex
import work.portside.esim.util.Logs
import work.portside.esim.util.Tlv
import java.io.ByteArrayOutputStream

data class ProfileInfo(
    val iccid: String,
    val isdpAid: String,
    val state: Int, // 0 = disabled, 1 = enabled (SGP.22 ProfileState)
    val nickname: String?,
    val serviceProviderName: String?,
    val profileName: String?,
    val profileClass: Int, // 0 = test, 1 = provisioning, 2 = operational
) {
    val isEnabled: Boolean get() = state == 1

    val displayName: String
        get() = listOfNotNull(
            nickname?.takeIf { it.isNotBlank() },
            profileName?.takeIf { it.isNotBlank() },
            serviceProviderName?.takeIf { it.isNotBlank() },
        ).firstOrNull() ?: "Profile"
}

/**
 * Talks to an eUICC (the 5ber card) through its management applet using the SGP.22 ES10c
 * command set. All commands are BER-TLV payloads wrapped in STORE DATA APDUs on a logical
 * channel, exactly as the standard requires.
 *
 * @param refreshOnSwitch request a REFRESH from the card after enable/disable. Needed when
 *        the card is live in the phone (so the modem re-reads it), pointless otherwise.
 */
class EuiccCard(private val transport: CardTransport, private val refreshOnSwitch: Boolean = false) {

    companion object {
        /**
         * Management-applet AIDs to try, in order. Real 5ber/ifree-lineage cards do **not**
         * carry the standard ISD-R AID — theirs is the vendor one (second entry).
         */
        private val AID_CANDIDATES = listOf(
            Hex.decode("A0000005591010FFFFFFFF8900050500"), // 5ber / ifree vendor management applet
            Hex.decode("A0000005591010FFFFFFFF8900000100"), // standard ISD-R (other cards)
        )

        /** Max DER bytes per STORE DATA APDU; matches common LPA behaviour. */
        private const val ES10X_MSS = 120

        private const val SW1_MORE_DATA = 0x61
    }

    var atr: ByteArray = ByteArray(0)
        private set

    private var channel = 0
    private var connected = false

    fun connect() {
        if (connected) return
        atr = transport.powerOn()
        if (transport.handlesSession) {
            // The transport (phone slot) already opened the channel and selected the applet.
            connected = true
            Logs.i("Card: connected via ${transport.name}")
            return
        }
        channel = tryOpenLogicalChannel()
        selectManager()
        connected = true
        Logs.i("Card: connected on logical channel $channel")
    }

    fun disconnect() {
        if (!connected) return
        runCatching { transport.powerOff() }
        runCatching { transport.close() }
        connected = false
        Logs.i("Card: disconnected")
    }

    /** EID as 32 hex digits. Not every card exposes it — callers should tolerate failure. */
    fun getEid(): String {
        val response = es10x(Tlv.encode(0xBF3E, Tlv.encode(0x5C, byteArrayOf(0x5A))))
        val root = Tlv.parse(response).firstOrNull { it.tag == 0xBF3E }
            ?: throw CardException("Card did not return an EID")
        // The EID travels as plain hex digits, not as swapped BCD like the ICCID.
        return root.find(0x5A)?.let { Hex.encode(it.value) }
            ?: throw CardException("Card did not return an EID")
    }

    fun getProfiles(): List<ProfileInfo> {
        val response = es10x(Tlv.encode(0xBF2D, ByteArray(0)))
        val root = Tlv.parse(response).firstOrNull { it.tag == 0xBF2D }
            ?: throw CardException("Bad profile list response from the card")
        val listOk = root.find(0xA0)
            ?: root.find(0xA1)?.let { throw CardException("Card refused to list profiles (error ${it.asInt()})") }
            ?: throw CardException("No profile list in the card's response")

        return listOk.children().filter { it.tag == 0xE3 }.map { entry ->
            ProfileInfo(
                iccid = entry.find(0x5A)?.let { Hex.bcdDigits(it.value) } ?: "",
                isdpAid = entry.find(0x4F)?.let { Hex.encode(it.value) } ?: "",
                state = entry.find(0x9F70)?.asInt() ?: -1,
                nickname = entry.find(0x90)?.asText(),
                serviceProviderName = entry.find(0x91)?.asText(),
                profileName = entry.find(0x92)?.asText(),
                profileClass = entry.find(0x95)?.asInt() ?: -1,
            )
        }
    }

    fun enableProfile(iccid: String) = profileOperation(0xBF31, iccid)

    fun disableProfile(iccid: String) = profileOperation(0xBF32, iccid)

    fun deleteProfile(iccid: String) = profileOperation(0xBF33, iccid)

    /** Sends one ES10b request (same STORE DATA framing as ES10c) and returns the raw response. */
    fun es10b(request: ByteArray): ByteArray = es10x(request)

    fun setNickname(iccid: String, nickname: String) {
        val request = Tlv.encode(
            0xBF29,
            Tlv.encode(0x5A, Hex.digitsToBcd(iccid)) +
                    Tlv.encode(0x90, nickname.toByteArray(Charsets.UTF_8)),
        )
        checkResult(es10x(request), 0xBF29)
    }

    private fun profileOperation(tag: Int, iccid: String) {
        val identifier = Tlv.encode(0x5A, Hex.digitsToBcd(iccid))
        val body = if (tag == 0xBF33) {
            // DeleteProfile: plain choice, no refresh flag
            identifier
        } else {
            // Enable/DisableProfile: choice + refreshFlag. Live in the phone we ask the card
            // to send a REFRESH, so the modem re-reads it and the switch takes effect at once.
            val flag = if (refreshOnSwitch) 0xFF.toByte() else 0x00
            Tlv.encode(0xA0, identifier + Tlv.encode(0x81, byteArrayOf(flag)))
        }
        checkResult(es10x(Tlv.encode(tag, body)), tag)
    }

    private fun checkResult(response: ByteArray, tag: Int) {
        val root = Tlv.parse(response).firstOrNull { it.tag == tag }
            ?: throw CardException("Missing response for %04X".format(tag))
        val code = root.find(0x80)?.asInt() ?: throw CardException("No result code in the card's response")
        if (code != 0) throw CardException(resultText(code))
    }

    private fun resultText(code: Int): String = when (code) {
        1 -> "the profile was not found on the card (check the ICCID)"
        2 -> "the card says the profile is in the wrong state for this operation"
        3 -> "the operation is blocked by the card's policy rules (ppr)"
        4 -> "the card is busy with a re-enable; retry in a moment"
        5 -> "the card is busy with another command; retry in a moment"
        127 -> "the card did not understand the request"
        else -> "the card returned error code $code"
    }

    private fun tryOpenLogicalChannel(): Int {
        return try {
            val response = transport.transmit(buildApdu(0x00, 0x70, 0x00, 0x00, null, 1))
            if (response.size >= 3 && (response[response.size - 2].toInt() and 0xF0) == 0x90) {
                response[0].toInt() and 0xFF
            } else {
                Logs.i("Card: MANAGE CHANNEL not supported, using the basic channel")
                0
            }
        } catch (e: Exception) {
            Logs.i("Card: MANAGE CHANNEL failed (${e.message}), using the basic channel")
            0
        }
    }

    private fun selectManager() {
        var lastSw = "??"
        for (aid in AID_CANDIDATES) {
            val response = transport.transmit(buildApdu(channel and 0x0F, 0xA4, 0x04, 0x00, aid, null))
            val sw = sw1(response)
            if (sw == 0x90 || sw == 0x61) return
            lastSw = swHex(response)
            Logs.i("Card: select ${Hex.encode(aid)} failed (SW $lastSw)")
        }
        throw CardException("Could not select the eUICC management applet (SW $lastSw)")
    }

    /** Sends a DER request through ES10x STORE DATA, chunked per the standard, collecting all data. */
    private fun es10x(request: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var offset = 0
        var seq = 0
        while (offset < request.size) {
            val n = minOf(ES10X_MSS, request.size - offset)
            val last = offset + n == request.size
            val apdu = buildApdu(
                cla = 0x80 or (channel and 0x0F),
                ins = 0xE2,
                p1 = if (last) 0x91 else 0x11,
                p2 = seq,
                data = request.copyOfRange(offset, offset + n),
                le = null,
            )
            out.write(transmitWithContinuation(apdu))
            offset += n
            seq++
        }
        return out.toByteArray()
    }

    /** Sends one APDU and follows 61XX ("more data") with GET RESPONSE until the card is done. */
    private fun transmitWithContinuation(apdu: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var response = transport.transmit(apdu)
        while (true) {
            out.write(responseData(response))
            if (sw1(response) == SW1_MORE_DATA) {
                val le = response[response.size - 1].toInt() and 0xFF
                response = transport.transmit(buildApdu(0x80 or (channel and 0x0F), 0xC0, 0x00, 0x00, null, le))
                continue
            }
            break
        }
        if ((sw1(response) and 0xF0) != 0x90) {
            // 0x91 = success with a pending proactive command (the modem handles FETCH/REFRESH).
            throw CardException("Card error: SW ${swHex(response)}")
        }
        return out.toByteArray()
    }

    private fun buildApdu(cla: Int, ins: Int, p1: Int, p2: Int, data: ByteArray?, le: Int?): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(cla)
        out.write(ins)
        out.write(p1)
        out.write(p2)
        if (data != null && data.isNotEmpty()) {
            if (data.size < 0x100) {
                out.write(data.size)
            } else {
                out.write(0)
                out.write((data.size shr 8) and 0xFF)
                out.write(data.size and 0xFF)
            }
            out.write(data, 0, data.size)
        }
        if (le != null) out.write(le and 0xFF)
        return out.toByteArray()
    }

    private fun responseData(response: ByteArray): ByteArray {
        if (response.size < 2) throw CardException("Short APDU response from the card")
        return response.copyOfRange(0, response.size - 2)
    }

    private fun sw1(response: ByteArray): Int {
        if (response.size < 2) throw CardException("Short APDU response from the card")
        return response[response.size - 2].toInt() and 0xFF
    }

    private fun swHex(response: ByteArray): String {
        if (response.size < 2) return "??"
        return Hex.encode(response.copyOfRange(response.size - 2, response.size))
    }
}
