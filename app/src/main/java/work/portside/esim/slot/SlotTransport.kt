package work.portside.esim.slot

import android.content.Context
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess
import work.portside.esim.usb.CardException
import work.portside.esim.usb.CardTransport
import work.portside.esim.util.Hex
import work.portside.esim.util.Logs
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.TimeUnit

/**
 * Talks to the card **in the phone's SIM slot**, no USB reader and no root: the helper
 * (`work.portside.esim.shell.SlotHelper`) is started through Shizuku, which runs it with the
 * `shell` identity, and the helper opens the logical channel to the card for us.
 *
 * The transport handles the whole card session itself ([handlesSession] = true): APDUs are
 * sent exactly as built (full C-APDU hex) on the channel the helper opened.
 */
class SlotTransport(context: Context, private val subId: Int, private val slotIndex: Int = 0) : CardTransport {

    override val name = "phone slot (Shizuku, subId=$subId)"
    override val handlesSession = true

    private val apkPath: String = context.packageCodePath
    private var process: ShizukuRemoteProcess? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null

    override fun powerOn(): ByteArray {
        if (!Shizuku.pingBinder()) throw CardException("Shizuku is not running")
        val cmd = "CLASSPATH='$apkPath' exec /system/bin/app_process64 /system/bin work.portside.esim.shell.SlotHelper $subId $slotIndex"
        Logs.i("slot: starting helper (subId=$subId, slot=$slotIndex)")
        val proc = newProcess(arrayOf("/system/bin/sh", "-c", cmd))
        process = proc
        val r = proc.inputStream.bufferedReader()
        val w = proc.outputStream.bufferedWriter()
        reader = r
        writer = w

        var chan = -1
        var last = ""
        while (true) {
            val line = r.readLine() ?: break
            last = line
            if (line.startsWith("[=] CHANNEL=")) {
                chan = line.substringAfter("CHANNEL=").trim().toIntOrNull() ?: -1
                break
            }
            if (line.startsWith("[i]") || line.startsWith("[!]")) Logs.i("slot: $line")
        }
        if (chan < 0) {
            close()
            throw CardException("Could not open a channel on this card (helper said: $last)")
        }
        Logs.i("slot: channel $chan open (via ${if (last.isNotEmpty()) "helper" else "?"})")
        return ByteArray(0)
    }

    override fun transmit(apdu: ByteArray): ByteArray {
        val w = writer ?: throw CardException("Slot transport is not open")
        val r = reader ?: throw CardException("Slot transport is not open")
        Logs.apdu(">>", apdu)
        w.write("RAW " + Hex.encode(apdu))
        w.newLine()
        w.flush()
        while (true) {
            val line = r.readLine() ?: throw CardException("Card helper stopped unexpectedly")
            when {
                line.startsWith("=>") -> {
                    val response = Hex.decode(line.substringAfter("=>"))
                    Logs.apdu("<<", response)
                    return response
                }
                line.startsWith("ERR") -> throw CardException("Card helper error: " + line.removePrefix("ERR").trim())
                line.isNotBlank() -> Logs.i("slot: $line")
            }
        }
    }

    override fun powerOff() {
        // The card stays powered by the phone; nothing to do.
    }

    override fun close() {
        runCatching {
            writer?.write("quit")
            writer?.newLine()
            writer?.flush()
        }
        runCatching { process?.waitFor(2, TimeUnit.SECONDS) }
        runCatching { process?.destroy() }
        process = null
        reader = null
        writer = null
    }

    companion object {
        /**
         * Shizuku's process API (`Shizuku.newProcess`) is private in the 13.x api artifact, so
         * reach it reflectively. This is how apps have always launched shell helpers.
         */
        private fun newProcess(cmd: Array<String>): ShizukuRemoteProcess {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java,
            )
            method.isAccessible = true
            try {
                return method.invoke(null, cmd, null, null) as ShizukuRemoteProcess
            } catch (e: java.lang.reflect.InvocationTargetException) {
                throw (e.cause ?: e)
            }
        }
    }
}
