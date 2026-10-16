package work.portside.esim.util

object Hex {
    private const val DIGITS = "0123456789ABCDEF"

    fun encode(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(DIGITS[v ushr 4]).append(DIGITS[v and 0x0F])
        }
        return sb.toString()
    }

    fun decode(s: String): ByteArray {
        val clean = s.filter { !it.isWhitespace() && it != ':' }
        require(clean.length % 2 == 0) { "Invalid hex string" }
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            out[i] = ((Character.digit(clean[i * 2], 16) shl 4) or Character.digit(clean[i * 2 + 1], 16)).toByte()
        }
        return out
    }

    /** GSM BCD (low nibble first) to display digits, e.g. ICCID / EID. */
    fun bcdDigits(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(DIGITS[b.toInt() and 0x0F])
            sb.append(DIGITS[(b.toInt() shr 4) and 0x0F])
        }
        return sb.toString()
    }

    /** Digits to GSM BCD bytes (low nibble first); pads odd length with F. */
    fun digitsToBcd(digits: String): ByteArray {
        val d = digits.replace(" ", "").uppercase()
        val padded = if (d.length % 2 == 0) d else d + "F"
        val out = ByteArray(padded.length / 2)
        for (i in out.indices) {
            val lo = Character.digit(padded[i * 2], 16)
            val hi = Character.digit(padded[i * 2 + 1], 16)
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    /** Groups a long digit string for display: 8949 0000 ... */
    fun grouped(s: String, size: Int = 4): String = s.chunked(size).joinToString(" ")
}
