package work.portside.esim.card

/** A smart card we can talk to. Implemented by the phone-slot (Shizuku) transport. */
interface CardTransport {
    val name: String

    /**
     * True when the transport itself opens the card's application session (e.g. the phone-slot
     * transport opens the logical channel through the framework). When false, EuiccCard
     * performs MANAGE CHANNEL + SELECT itself.
     */
    val handlesSession: Boolean get() = false

    /** Powers the card up; returns the ATR. */
    fun powerOn(): ByteArray

    /** Sends one raw APDU; returns the raw response including SW1 SW2. */
    fun transmit(apdu: ByteArray): ByteArray

    fun powerOff()

    fun close()
}

class CardException(message: String) : Exception(message)
