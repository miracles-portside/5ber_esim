package work.portside.esim.util

/**
 * BER/DER node codec that keeps **byte positions**. The SGP.22 download flow needs to slice
 * the original bytes of a BoundProfilePackage exactly the way the GSMA reference LPAs do
 * (segment boundaries are byte offsets into the server blob), so a decoded tree is not
 * enough — we need the raw layout.
 */
object Ber {

    class Node(val tag: Int, val start: Int, val valueStart: Int, val end: Int) {
        val valueLength: Int get() = end - valueStart

        /** The whole TLV (tag + length + value). */
        fun whole(buf: ByteArray): ByteArray = buf.copyOfRange(start, end)

        /** Only the value bytes. */
        fun value(buf: ByteArray): ByteArray = buf.copyOfRange(valueStart, end)
    }

    /** Parses the top-level nodes in [from, to). Stops quietly at the first malformed node. */
    fun parse(buf: ByteArray, from: Int = 0, to: Int = buf.size): List<Node> {
        val out = ArrayList<Node>()
        var i = from
        while (i < to) {
            if (i + 1 >= to) break
            val start = i

            var tag = buf[i].toInt() and 0xFF
            i++
            if (tag and 0x1F == 0x1F) {
                var n = 0
                while (true) {
                    if (i >= to) return out
                    val b = buf[i].toInt() and 0xFF
                    i++
                    tag = (tag shl 8) or b
                    n++
                    if (b and 0x80 == 0 || n >= 3) break
                }
            }

            if (i >= to) break
            var len = buf[i].toInt() and 0xFF
            i++
            if (len and 0x80 != 0) {
                val n = len and 0x7F
                if (n > 3) break
                len = 0
                repeat(n) {
                    if (i >= to) return out
                    len = (len shl 8) or (buf[i].toInt() and 0xFF)
                    i++
                }
            }

            if (len < 0 || i + len > to) break
            out.add(Node(tag, start, i, i + len))
            i += len
        }
        return out
    }

    /** First sibling with [tag] in [from, to), or null. */
    fun find(buf: ByteArray, tag: Int, from: Int = 0, to: Int = buf.size): Node? =
        parse(buf, from, to).firstOrNull { it.tag == tag }
}
