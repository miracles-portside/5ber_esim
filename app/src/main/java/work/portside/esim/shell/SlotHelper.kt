package work.portside.esim.shell

import android.os.Binder
import android.os.IBinder
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Runs **inside Shizuku's server process** (uid `shell`) via `app_process64`, started from the
 * app through `Shizuku.newProcess`. It opens a logical channel to the removable card's
 * management applet with a live Binder (so the framework does not auto-close the channel) and
 * then acts as a tiny APDU terminal on stdin/stdout:
 *
 *   input : "RAW <full-apdu-hex>" | "quit"
 *   output: "[=] CHANNEL=<n>" once ready, then "=> <response-hex>" (or "ERR <message>")
 *
 * Plain Java/Kotlin with no Android app dependencies, so `app_process64` can load it straight
 * from the app's APK classpath.
 */
object SlotHelper {

    private val AID_CANDIDATES = listOf(
        "A0000005591010FFFFFFFF8900050500", // 5ber / ifree vendor management applet
        "A0000005591010FFFFFFFF8900000100", // standard ISD-R (other cards)
    )

    /** Keeps the framework channel-open binder alive for the lifetime of this process. */
    @JvmStatic
    private var binderKeepAlive: Binder? = null

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            run(args)
        } catch (t: Throwable) {
            println("[!] fatal: $t")
            t.printStackTrace(System.out)
        }
        System.out.flush()
    }

    private fun run(args: Array<String>) {
        val subId = args.firstOrNull()?.toIntOrNull() ?: 22
        val slotIndex = args.getOrNull(1)?.toIntOrNull() ?: 0

        val phone = try {
            Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
                .invoke(null, "phone") as? IBinder
        } catch (t: Throwable) {
            null
        }
        if (phone == null) {
            println("[!] no phone binder")
            return
        }
        val stub = Class.forName("com.android.internal.telephony.ITelephony\$Stub")
        val telephony = stub.getMethod("asInterface", Class.forName("android.os.IBinder")).invoke(null, phone)
        val reqClass = Class.forName("com.android.internal.telephony.IccLogicalChannelRequest")

        var openM: Method? = null
        var txM: Method? = null
        for (m in telephony.javaClass.methods) {
            when {
                m.name == "iccOpenLogicalChannel" && m.parameterCount == 1 &&
                        m.parameterTypes[0] == reqClass -> openM = m
                m.name == "iccTransmitApduLogicalChannel" && m.parameterCount == 8 -> txM = m
            }
        }
        if (openM == null || txM == null) {
            println("[!] ITelephony methods not found")
            return
        }

        var channel = -1
        var usedAid = ""
        var lastStatus = -999L
        for (aid in AID_CANDIDATES) {
            try {
                val req = reqClass.getDeclaredConstructor().newInstance()
                setField(req, "subId", subId)
                setField(req, "slotIndex", slotIndex)
                setField(req, "portIndex", 0)
                setField(req, "callingPackage", "com.android.shell")
                setField(req, "aid", aid)
                setField(req, "p2", 0)
                setField(req, "channel", -1)
                val binder = Binder()
                binderKeepAlive = binder
                setField(req, "binder", binder)

                val resp = openM.invoke(telephony, req)
                val ch = num(resp, "getChannel")
                lastStatus = num(resp, "getStatus")
                println("[i] open aid=$aid channel=$ch status=$lastStatus")
                if (ch >= 0) {
                    channel = ch.toInt()
                    usedAid = aid
                    break
                }
            } catch (t: Throwable) {
                println("[i] open aid=$aid failed: ${t.cause ?: t}")
            }
        }

        println("[=] CHANNEL=$channel")
        println("[=] AID=$usedAid")
        System.out.flush()
        if (channel < 0) {
            println("[!] no channel (last status $lastStatus)")
            return
        }

        val input = BufferedReader(InputStreamReader(System.`in`))
        while (true) {
            val line = (input.readLine() ?: break).trim()
            if (line.isEmpty()) continue
            if (line == "quit" || line == "close") break
            try {
                val hex = if (line.startsWith("RAW ")) line.substring(4).trim() else line
                val apdu = hexToBytes(hex)
                require(apdu.size >= 5) { "short APDU" }
                val cla = apdu[0].toInt() and 0xFF
                val ins = apdu[1].toInt() and 0xFF
                val p1 = apdu[2].toInt() and 0xFF
                val p2 = apdu[3].toInt() and 0xFF
                val p3 = apdu[4].toInt() and 0xFF
                val data = if (apdu.size > 5) toHex(apdu, 5, minOf(5 + p3, apdu.size)) else ""

                var response = txM.invoke(telephony, subId, channel, cla, ins, p1, p2, p3, data) as? String ?: ""
                println("=> $response")
                // Follow T=0-style "61 xx: more data" with GET RESPONSE on the same channel.
                // ("91 xx" is left alone: that is a pending proactive command, handled by the modem.)
                while (response.length >= 4 && response.substring(response.length - 4, response.length - 2) == "61") {
                    val le = Integer.parseInt(response.substring(response.length - 2), 16)
                    response = txM.invoke(telephony, subId, channel, cla, 0xC0, 0, 0, le, "") as? String ?: ""
                    println("=> $response")
                }
            } catch (t: Throwable) {
                println("ERR ${t.cause ?: t}")
            }
            System.out.flush()
        }

        try {
            val closeM = telephony.javaClass.methods
                .firstOrNull { it.name == "iccCloseLogicalChannel" && it.parameterCount == 2 }
            closeM?.invoke(telephony, subId, channel)
        } catch (_: Throwable) {
        }
        println("[i] done")
        System.out.flush()
    }

    private fun setField(o: Any, name: String, value: Any) {
        val f: Field = o.javaClass.getField(name)
        f.set(o, value)
    }

    private fun num(o: Any?, getter: String): Long = try {
        if (o == null) -999 else (o.javaClass.getMethod(getter).invoke(o) as Number).toLong()
    } catch (_: Throwable) {
        -999
    }

    private fun hexToBytes(s: String): ByteArray {
        val clean = s.filter { !it.isWhitespace() }
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            out[i] = ((Character.digit(clean[i * 2], 16) shl 4) or Character.digit(clean[i * 2 + 1], 16)).toByte()
        }
        return out
    }

    private fun toHex(b: ByteArray, from: Int, to: Int): String {
        val sb = StringBuilder()
        for (i in from until to) sb.append(String.format("%02x", b[i]))
        return sb.toString()
    }
}
