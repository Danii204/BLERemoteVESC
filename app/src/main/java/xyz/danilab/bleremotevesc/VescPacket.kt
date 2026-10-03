package xyz.danilab.bleremotevesc

/**
 * Empaquetado minimo del protocolo serie del VESC.
 *
 * Trama corta (carga util < 256 bytes), que es la unica que necesita este mando:
 *
 *     0x02 | LEN | PAYLOAD[LEN] | CRC_HI | CRC_LO | 0x03
 *
 * El CRC es un CRC-16/XMODEM (polinomio 0x1021, valor inicial 0x0000) calculado
 * unicamente sobre PAYLOAD. Todos los enteros del protocolo van en big-endian.
 */
object VescPacket {

    // Identificadores de comando (enum COMM_PACKET_ID del firmware VESC).
    const val COMM_GET_VALUES = 4
    const val COMM_SET_DUTY = 5
    const val COMM_SET_CURRENT = 6
    const val COMM_SET_CURRENT_BRAKE = 7
    const val COMM_SET_RPM = 8
    const val COMM_LISP_REPL_CMD = 138

    /** CRC-16/XMODEM sobre [data]. */
    fun crc16(data: ByteArray): Int {
        var crc = 0
        for (byte in data) {
            crc = crc xor ((byte.toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1
                crc = crc and 0xFFFF
            }
        }
        return crc
    }

    /** Envuelve [payload] en una trama corta lista para enviar. */
    fun frame(payload: ByteArray): ByteArray {
        require(payload.size < 256) { "Solo se soportan tramas cortas" }
        val crc = crc16(payload)
        val out = ByteArray(payload.size + 5)
        out[0] = 0x02
        out[1] = payload.size.toByte()
        payload.copyInto(out, 2)
        out[payload.size + 2] = ((crc shr 8) and 0xFF).toByte()
        out[payload.size + 3] = (crc and 0xFF).toByte()
        out[payload.size + 4] = 0x03
        return out
    }

    private fun int32be(value: Int): ByteArray = byteArrayOf(
        ((value shr 24) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte(),
    )

    /**
     * COMM_SET_DUTY. [duty] en el rango -1.0 .. 1.0; el VESC espera el valor
     * escalado por 100000.
     */
    fun setDuty(duty: Float): ByteArray {
        val clamped = duty.coerceIn(-1f, 1f)
        val scaled = (clamped * 100000f).toInt()
        return frame(byteArrayOf(COMM_SET_DUTY.toByte()) + int32be(scaled))
    }

    /** COMM_SET_CURRENT. [amps] escalado por 1000. */
    fun setCurrent(amps: Float): ByteArray =
        frame(byteArrayOf(COMM_SET_CURRENT.toByte()) + int32be((amps * 1000f).toInt()))

    /**
     * Evalua una expresion LispBM en el VESC (firmware 6.00+). Si el
     * interprete esta parado, el firmware lo arranca solo. La cadena va
     * terminada en cero, como la espera el lado C.
     */
    fun lispRepl(codigo: String): ByteArray =
        frame(byteArrayOf(COMM_LISP_REPL_CMD.toByte()) + codigo.toByteArray(Charsets.US_ASCII) + 0)

    /** COMM_GET_VALUES, sin carga util mas alla del identificador. */
    fun getValues(): ByteArray = frame(byteArrayOf(COMM_GET_VALUES.toByte()))
}
