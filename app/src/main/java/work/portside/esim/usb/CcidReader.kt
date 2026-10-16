package work.portside.esim.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import work.portside.esim.util.Hex
import work.portside.esim.util.Logs

/**
 * USB CCID (chip/smart-card interface devices) transport, T=0.
 *
 * Talks the standard USB CCID protocol (DWG Smart-Card CCID rev 1.0/1.1) to contact
 * smart-card readers, so a 5ber card (or any SGP.22 eUICC) can be managed from the phone
 * through a reader — bypassing the ARA-M access control that blocks SIM-slot access
 * for unprivileged apps.
 */
class CcidReader private constructor(
    private val connection: UsbDeviceConnection,
    private val iface: UsbInterface,
    private val bulkOut: UsbEndpoint,
    private val bulkIn: UsbEndpoint,
    private val voltages: List<Int>,
    override val name: String,
) : CardTransport {

    companion object {
        private const val CCID_CLASS = 0x0B
        private const val SLOT = 0x00
        private const val TIMEOUT_MS = 5000

        // PC_to_RDR_* message types
        private const val MSG_ICC_POWER_ON = 0x62
        private const val MSG_ICC_POWER_OFF = 0x63
        private const val MSG_XFR_BLOCK = 0x6F

        // RDR_to_PC_DataBlock
        private const val MSG_DATA_BLOCK = 0x80

        // bError values
        private const val ERR_POWER_SELECT = 0x07
        private const val ERR_ICC_MUTE = 0xFE

        fun isCcidDevice(device: UsbDevice): Boolean {
            if (device.deviceClass == CCID_CLASS) return true
            for (i in 0 until device.interfaceCount) {
                if (device.getInterface(i).interfaceClass == CCID_CLASS) return true
            }
            return false
        }

        fun findReaders(manager: UsbManager): List<UsbDevice> =
            manager.deviceList.values.filter { isCcidDevice(it) }

        fun open(manager: UsbManager, device: UsbDevice): CcidReader {
            var iface: UsbInterface? = null
            for (i in 0 until device.interfaceCount) {
                val candidate = device.getInterface(i)
                if (candidate.interfaceClass == CCID_CLASS) {
                    iface = candidate
                    break
                }
            }
            if (iface == null && device.deviceClass == CCID_CLASS && device.interfaceCount > 0) {
                iface = device.getInterface(0)
            }
            val ccidIface = iface ?: throw CardException("Not a CCID reader")

            var epIn: UsbEndpoint? = null
            var epOut: UsbEndpoint? = null
            for (i in 0 until ccidIface.endpointCount) {
                val ep = ccidIface.getEndpoint(i)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.direction == UsbConstants.USB_DIR_IN) epIn = ep else epOut = ep
                }
            }
            if (epIn == null || epOut == null) throw CardException("Reader has no bulk endpoints")

            val conn = manager.openDevice(device) ?: throw CardException("Could not open USB device")
            if (!conn.claimInterface(ccidIface, true)) {
                conn.close()
                throw CardException("Could not claim the reader interface (is another app using it?)")
            }

            val product = device.productName?.takeIf { it.isNotBlank() } ?: "CCID reader"
            val label = "$product [%04X:%04X]".format(device.vendorId, device.productId)
            return CcidReader(conn, ccidIface, epOut, epIn, parseVoltages(conn.rawDescriptors), label)
        }

        /** Reads the CCID functional descriptor for supported voltages / auto features. */
        private fun parseVoltages(raw: ByteArray?): List<Int> {
            if (raw != null) {
                var i = 0
                while (i + 1 < raw.size) {
                    val len = raw[i].toInt() and 0xFF
                    if (len < 2) break
                    val type = raw[i + 1].toInt() and 0xFF
                    if (type == 0x21 && len >= 0x36 && i + 0x36 <= raw.size) {
                        val voltageSupport = raw[i + 5].toInt() and 0xFF
                        val features = (raw[i + 40].toInt() and 0xFF) or
                                ((raw[i + 41].toInt() and 0xFF) shl 8) or
                                ((raw[i + 42].toInt() and 0xFF) shl 16) or
                                ((raw[i + 43].toInt() and 0xFF) shl 24)
                        if (features and 0x08 != 0) return listOf(0) // automatic voltage
                        val list = ArrayList<Int>()
                        if (voltageSupport and 0x01 != 0) list.add(1) // 5.0 V
                        if (voltageSupport and 0x02 != 0) list.add(2) // 3.0 V
                        if (voltageSupport and 0x04 != 0) list.add(3) // 1.8 V
                        return if (list.isEmpty()) listOf(0, 2, 3) else list
                    }
                    i += len
                }
            }
            return listOf(0, 2, 3) // try auto, then 3 V, then 1.8 V
        }
    }

    private var sequence = 0
    private val header = ByteArray(10)
    private val receiveBuffer = ByteArray(65536)

    override fun powerOn(): ByteArray {
        skipPendingInput()
        var lastError: Exception? = null
        for (voltage in voltages) {
            try {
                val atr = command(MSG_ICC_POWER_ON, ByteArray(0), field7 = voltage)
                Logs.i("Reader: card powered on (voltage code $voltage), ATR ${Hex.encode(atr)}")
                return atr
            } catch (e: CcidError) {
                if (e.errorCode == ERR_POWER_SELECT) {
                    Logs.i("Reader: voltage code $voltage rejected, trying next")
                    powerOffQuietly()
                    lastError = e
                    continue
                }
                throw e
            }
        }
        throw CardException("Could not power on the card: ${lastError?.message ?: "unknown error"}")
    }

    override fun transmit(apdu: ByteArray): ByteArray {
        Logs.apdu(">", apdu)
        val response = command(MSG_XFR_BLOCK, apdu)
        Logs.apdu("<", response)
        return response
    }

    override fun powerOff() = powerOffQuietly()

    override fun close() {
        powerOffQuietly()
        try {
            connection.releaseInterface(iface)
        } catch (_: Exception) {
        }
        connection.close()
        Logs.i("Reader: connection closed")
    }

    private class CcidError(val errorCode: Int, message: String) : Exception(message)

    private fun powerOffQuietly() {
        try {
            command(MSG_ICC_POWER_OFF, ByteArray(0))
        } catch (_: Exception) {
        }
    }

    private fun command(type: Int, payload: ByteArray, field7: Int = 0, levelParameter: Int = 0): ByteArray {
        val seq = sequence++
        header[0] = type.toByte()
        header[1] = payload.size.toByte()
        header[2] = (payload.size shr 8).toByte()
        header[3] = (payload.size shr 16).toByte()
        header[4] = (payload.size shr 24).toByte()
        header[5] = SLOT.toByte()
        header[6] = seq.toByte()
        header[7] = field7.toByte()
        header[8] = levelParameter.toByte()
        header[9] = (levelParameter shr 8).toByte()

        writeRaw(header, 0, header.size)
        if (payload.isNotEmpty()) writeRaw(payload, 0, payload.size)

        while (true) {
            val block = readBlock(seq)
            if (block.commandStatus == 2) { // time extension requested
                Logs.i("Reader: time extension, still waiting for the card...")
                continue
            }
            if (block.error != 0) {
                if (block.error == ERR_ICC_MUTE) {
                    throw CardException("No card detected in the reader (or it is not seated properly)")
                }
                throw CcidError(block.error, "Reader error 0x%02X".format(block.error))
            }
            if (block.iccStatus != 0) throw CardException("Card is not active (status ${block.iccStatus})")
            return block.data
        }
    }

    private fun writeRaw(buffer: ByteArray, offset: Int, length: Int) {
        var sent = 0
        while (sent < length) {
            val chunk = minOf(bulkOut.maxPacketSize, length - sent)
            val written = connection.bulkTransfer(bulkOut, buffer, offset + sent, chunk, TIMEOUT_MS)
            if (written <= 0) throw CardException("USB write to the reader failed ($written)")
            sent += written
        }
    }

    private class CcidBlock(val iccStatus: Int, val commandStatus: Int, val error: Int, val data: ByteArray)

    private fun readBlock(expectedSeq: Int): CcidBlock {
        var got = readSome()
        var attempts = 5
        while (got < 10) {
            if (got < 0 && attempts-- <= 0) throw CardException("Timed out waiting for the reader")
            if (got in 0..9 && attempts-- <= 0) throw CardException("Short response from the reader ($got bytes)")
            got = readSome()
        }

        val type = receiveBuffer[0].toInt() and 0xFF
        val length = leInt(receiveBuffer, 1)
        val seq = receiveBuffer[6].toInt() and 0xFF
        val status = receiveBuffer[7].toInt() and 0xFF
        val error = receiveBuffer[8].toInt() and 0xFF

        if (type != MSG_DATA_BLOCK) throw CardException("Unexpected CCID response type 0x%02X".format(type))
        if (seq != expectedSeq) throw CardException("Reader sequence mismatch ($seq vs $expectedSeq)")
        if (length < 0 || length > receiveBuffer.size - 10) throw CardException("Reader reported impossible length $length")

        var have = got - 10
        while (have < length) {
            val read = connection.bulkTransfer(bulkIn, receiveBuffer, 10 + have, length - have, TIMEOUT_MS)
            if (read <= 0) throw CardException("USB read from the reader failed ($read)")
            have += read
        }

        val data = receiveBuffer.copyOfRange(10, 10 + length)
        return CcidBlock(
            iccStatus = status and 0x03,
            commandStatus = (status shr 6) and 0x03,
            error = error,
            data = data,
        )
    }

    private fun readSome(): Int =
        connection.bulkTransfer(bulkIn, receiveBuffer, receiveBuffer.size, TIMEOUT_MS)

    private fun skipPendingInput() {
        while (true) {
            val skipped = connection.bulkTransfer(bulkIn, receiveBuffer, receiveBuffer.size, 100)
            if (skipped <= 0) return
            Logs.i("Reader: skipped $skipped stale bytes")
        }
    }

    private fun leInt(buffer: ByteArray, offset: Int): Int =
        (buffer[offset].toInt() and 0xFF) or
                ((buffer[offset + 1].toInt() and 0xFF) shl 8) or
                ((buffer[offset + 2].toInt() and 0xFF) shl 16) or
                ((buffer[offset + 3].toInt() and 0xFF) shl 24)
}
