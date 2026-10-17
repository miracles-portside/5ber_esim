package work.portside.esim.lpa

/**
 * SGP.22 activation code: `1$<SM-DP+ address>$<matchingId>[$<OID>][$<CC-required>]`, as found
 * in the QR code (usually with an `LPA:` prefix).
 */
data class ActivationCode(
    val smdp: String,
    val matchingId: String?,
    val oid: String?,
    val confirmationCodeRequired: Boolean,
    val raw: String,
) {
    companion object {
        fun parse(input: String): ActivationCode {
            var s = input.trim()
            if (s.regionMatches(0, "LPA:", 0, 4, ignoreCase = true)) s = s.substring(4).trim()

            val parts = s.split('$')
            if (parts.isEmpty() || parts[0].trim() != "1") {
                throw IllegalArgumentException("Not an eSIM activation code — it should look like LPA:1\$server\$code")
            }
            val smdp = parts.getOrNull(1)?.trim().orEmpty()
            if (smdp.isBlank()) throw IllegalArgumentException("The code does not contain an SM-DP+ server address")
            val matching = parts.getOrNull(2)?.trim()?.takeIf { it.isNotEmpty() }
            val oid = parts.getOrNull(3)?.trim()?.takeIf { it.isNotEmpty() }
            val cc = parts.getOrNull(4)?.trim() == "1"
            return ActivationCode(smdp, matching, oid, cc, s)
        }
    }
}
