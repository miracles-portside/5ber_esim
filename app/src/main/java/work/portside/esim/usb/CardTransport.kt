package work.portside.esim.usb

/** A smart card we can talk to. Implemented by the USB CCID reader, faked by demo mode. */
interface CardTransport {
    val name: String

    /** Powers the card up; returns the ATR. */
    fun powerOn(): ByteArray

    /** Sends one raw APDU; returns the raw response including SW1 SW2. */
    fun transmit(apdu: ByteArray): ByteArray

    fun powerOff()

    fun close()
}

class CardException(message: String) : Exception(message)
