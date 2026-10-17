package work.portside.esim.util

import android.util.Base64

/** Base64 helpers for the ES9+ JSON layer (every ASN.1 value travels as padded Base64). */
object B64 {
    fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    fun decode(text: String): ByteArray = Base64.decode(text.trim(), Base64.DEFAULT)
}
