package work.portside.esim.util

import android.util.Log

/**
 * Tiny global log bus: everything interesting also shows up in the app's log pane,
 * which makes on-device debugging of card/reader issues much easier.
 */
object Logs {
    private const val TAG = "5ber"

    /** Set by the UI; receives every log line. */
    @Volatile
    var sink: ((String) -> Unit)? = null

    /** When true, raw APDU hex is written to the log. */
    @Volatile
    var apduLogging = false

    fun i(message: String) {
        Log.i(TAG, message)
        sink?.invoke(message)
    }

    fun apdu(direction: String, bytes: ByteArray) {
        if (apduLogging) i("APDU $direction ${Hex.encode(bytes)}")
    }
}
