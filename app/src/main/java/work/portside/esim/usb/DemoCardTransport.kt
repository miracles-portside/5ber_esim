package work.portside.esim.usb

import work.portside.esim.util.Hex
import work.portside.esim.util.Tlv
import java.io.ByteArrayOutputStream

/**
 * Fake eUICC used to exercise the app (UI, protocol code, TLV parsing) without a reader.
 * It answers just enough of the ES10c command set for the operations this app performs.
 */
class DemoCardTransport : CardTransport {

    override val name = "DEMO card (no hardware)"

    private data class FakeProfile(
        val iccid: String,
        val aid: String,
        var state: Int, // 0 = disabled, 1 = enabled
        var nickname: String?,
        val serviceProviderName: String?,
        val profileName: String,
        val profileClass: Int,
    )

    private val profiles = mutableListOf(
        FakeProfile(
            iccid = "89490000000000001234",
            aid = "A0000005591010FFFFFFFF8900000100",
            state = 1,
            nickname = "Travel A",
            serviceProviderName = "SampleTel",
            profileName = "Sample Operator A",
            profileClass = 2,
        ),
        FakeProfile(
            iccid = "89490000000000005678",
            aid = "A0000005591010FFFFFFFF8900000200",
            state = 0,
            nickname = null,
            serviceProviderName = "SampleTel",
            profileName = "Sample Operator B",
            profileClass = 2,
        ),
    )

    private val eid = "89049021003000000000000000001234"

    override fun powerOn(): ByteArray = Hex.decode("3B9F96803FC7A08031E073F6211357A5590105")

    override fun transmit(apdu: ByteArray): ByteArray {
        Thread.sleep(120) // pretend the card is doing work
        if (apdu.size < 5) return sw(0x6A, 0x86)
        return when (apdu[1].toInt() and 0xFF) {
            0x70 -> byteArrayOf(0x01, 0x90.toByte(), 0x00) // MANAGE CHANNEL -> channel 1
            0xA4 -> sw(0x90, 0x00)                          // SELECT ISD-R
            0xC0 -> sw(0x90, 0x00)                          // GET RESPONSE
            0xE2 -> handleEs10x(apduData(apdu))
            else -> sw(0x6D, 0x00)
        }
    }

    override fun powerOff() {}

    override fun close() {}

    private fun handleEs10x(data: ByteArray): ByteArray {
        return when (peekTag(data)) {
            0xBF3E -> Tlv.encode(0xBF3E, Tlv.encode(0x5A, Hex.digitsToBcd(eid))) + sw(0x90, 0x00)
            0xBF2D -> buildProfileList()
            0xBF31 -> changeState(data, 0xBF31, enable = true)
            0xBF32 -> changeState(data, 0xBF32, enable = false)
            0xBF33 -> delete(data)
            0xBF29 -> nickname(data)
            else -> sw(0x6D, 0x00)
        }
    }

    private fun buildProfileList(): ByteArray {
        val entries = ByteArrayOutputStream()
        for (p in profiles) {
            val entry = ByteArrayOutputStream()
            entry.write(Tlv.encode(0x5A, Hex.digitsToBcd(p.iccid)))
            entry.write(Tlv.encode(0x4F, Hex.decode(p.aid)))
            entry.write(Tlv.encode(0x9F70, byteArrayOf(p.state.toByte())))
            p.nickname?.let { entry.write(Tlv.encode(0x90, it.toByteArray(Charsets.UTF_8))) }
            p.serviceProviderName?.let { entry.write(Tlv.encode(0x91, it.toByteArray(Charsets.UTF_8))) }
            entry.write(Tlv.encode(0x92, p.profileName.toByteArray(Charsets.UTF_8)))
            entry.write(Tlv.encode(0x95, byteArrayOf(p.profileClass.toByte())))
            entries.write(Tlv.encode(0xE3, entry.toByteArray()))
        }
        val listOk = Tlv.encode(0xA0, entries.toByteArray())
        return Tlv.encode(0xBF2D, listOk) + sw(0x90, 0x00)
    }

    private fun changeState(data: ByteArray, responseTag: Int, enable: Boolean): ByteArray {
        val iccid = iccidOf(data) ?: return operationResult(responseTag, 1)
        val profile = profiles.firstOrNull { it.iccid == iccid } ?: return operationResult(responseTag, 1)
        if (enable) {
            profiles.forEach { it.state = 0 }
            profile.state = 1
        } else {
            profile.state = 0
        }
        return operationResult(responseTag, 0)
    }

    private fun delete(data: ByteArray): ByteArray {
        val iccid = iccidOf(data) ?: return operationResult(0xBF33, 1)
        val profile = profiles.firstOrNull { it.iccid == iccid } ?: return operationResult(0xBF33, 1)
        profiles.remove(profile)
        return operationResult(0xBF33, 0)
    }

    private fun nickname(data: ByteArray): ByteArray {
        val iccid = iccidOf(data) ?: return operationResult(0xBF29, 1)
        val profile = profiles.firstOrNull { it.iccid == iccid } ?: return operationResult(0xBF29, 1)
        profile.nickname = Tlv.parse(data).firstOrNull { it.tag == 0xBF29 }
            ?.find(0x90)?.asText()
        return operationResult(0xBF29, 0)
    }

    private fun iccidOf(data: ByteArray): String? {
        val root = Tlv.parse(data).firstOrNull() ?: return null
        val node = root.find(0x5A) ?: root.find(0xA0)?.find(0x5A) ?: return null
        return Hex.bcdDigits(node.value)
    }

    private fun operationResult(tag: Int, code: Int): ByteArray =
        Tlv.encode(tag, Tlv.encode(0x80, byteArrayOf(code.toByte()))) + sw(0x90, 0x00)

    private fun peekTag(data: ByteArray): Int {
        if (data.isEmpty()) return -1
        val first = data[0].toInt() and 0xFF
        if (first and 0x1F == 0x1F && data.size >= 2) {
            return (first shl 8) or (data[1].toInt() and 0xFF)
        }
        return first
    }

    private fun apduData(apdu: ByteArray): ByteArray {
        val lc = apdu[4].toInt() and 0xFF
        if (lc == 0 || 5 + lc > apdu.size) return ByteArray(0)
        return apdu.copyOfRange(5, 5 + lc)
    }

    private fun sw(s1: Int, s2: Int) = byteArrayOf(s1.toByte(), s2.toByte())
}
