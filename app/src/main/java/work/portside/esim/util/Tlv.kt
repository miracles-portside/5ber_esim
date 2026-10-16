package work.portside.esim.util

/**
 * Minimal DER/BER TLV codec, sufficient for the SGP.22 (ES10x) structures used by this app.
 * Multi-byte tags (e.g. BF2D, 9F70) and long-form lengths are supported.
 */
class Tlv(val tag: Int, val value: ByteArray) {

    fun children(): List<Tlv> = parse(value)

    fun find(tag: Int): Tlv? = children().firstOrNull { it.tag == tag }

    fun asInt(): Int {
        var r = 0
        for (b in value) r = (r shl 8) or (b.toInt() and 0xFF)
        return r
    }

    fun asText(): String = String(value, Charsets.UTF_8)

    companion object {
        fun parse(data: ByteArray): List<Tlv> {
            val out = ArrayList<Tlv>()
            var i = 0
            while (i < data.size) {
                if (i + 1 >= data.size) break

                var tag = data[i].toInt() and 0xFF
                i++
                if (tag and 0x1F == 0x1F) {
                    var extra = 0
                    while (true) {
                        if (i >= data.size) return out
                        val b = data[i].toInt() and 0xFF
                        i++
                        tag = (tag shl 8) or b
                        extra++
                        if (b and 0x80 == 0 || extra >= 3) break
                    }
                }

                if (i >= data.size) break
                var length = data[i].toInt() and 0xFF
                i++
                if (length and 0x80 != 0) {
                    val n = length and 0x7F
                    if (n > 3) break
                    length = 0
                    repeat(n) {
                        if (i >= data.size) return out
                        length = (length shl 8) or (data[i].toInt() and 0xFF)
                        i++
                    }
                }

                if (length < 0 || i + length > data.size) break
                out.add(Tlv(tag, data.copyOfRange(i, i + length)))
                i += length
            }
            return out
        }

        fun encode(tag: Int, value: ByteArray): ByteArray = tagBytes(tag) + lengthBytes(value.size) + value

        private fun tagBytes(tag: Int): ByteArray = when {
            tag <= 0xFF -> byteArrayOf(tag.toByte())
            tag <= 0xFFFF -> byteArrayOf((tag ushr 8).toByte(), tag.toByte())
            tag <= 0xFFFFFF -> byteArrayOf((tag ushr 16).toByte(), (tag ushr 8).toByte(), tag.toByte())
            else -> byteArrayOf((tag ushr 24).toByte(), (tag ushr 16).toByte(), (tag ushr 8).toByte(), tag.toByte())
        }

        private fun lengthBytes(len: Int): ByteArray = when {
            len < 0x80 -> byteArrayOf(len.toByte())
            len < 0x100 -> byteArrayOf(0x81.toByte(), len.toByte())
            len < 0x10000 -> byteArrayOf(0x82.toByte(), (len ushr 8).toByte(), len.toByte())
            else -> byteArrayOf(0x83.toByte(), (len ushr 16).toByte(), (len ushr 8).toByte(), len.toByte())
        }
    }
}
