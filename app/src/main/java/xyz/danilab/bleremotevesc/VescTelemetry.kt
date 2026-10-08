package xyz.danilab.bleremotevesc

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reensambla tramas del protocolo VESC a partir de trozos arbitrarios.
 *
 * El modulo BLE trabaja con MTU 23, asi que cada notificacion trae como mucho
 * 20 bytes: una respuesta a COMM_GET_VALUES (~75 bytes) llega partida en
 * cuatro o cinco notificaciones. Este parser acumula, localiza el inicio de
 * trama, espera a tenerla entera, comprueba el CRC y entrega solo el payload.
 */
class VescFrameParser(private val onPayload: (ByteArray) -> Unit) {

    private var buf = ByteArray(0)

    fun reset() {
        buf = ByteArray(0)
    }

    fun feed(chunk: ByteArray) {
        buf += chunk
        while (true) {
            if (!extraer()) break
        }
        // Basura sin inicio valido no debe crecer sin limite.
        if (buf.size > 1024) buf = ByteArray(0)
    }

    /** Devuelve true si ha consumido algo y conviene seguir intentando. */
    private fun extraer(): Boolean {
        val inicio = buf.indexOfFirst { it == 0x02.toByte() || it == 0x03.toByte() }
        if (inicio < 0) {
            buf = ByteArray(0)
            return false
        }
        if (inicio > 0) buf = buf.copyOfRange(inicio, buf.size)

        val corta = buf[0] == 0x02.toByte()
        val cabecera = if (corta) 2 else 3
        if (buf.size < cabecera) return false
        val len = if (corta) {
            buf[1].toInt() and 0xFF
        } else {
            ((buf[1].toInt() and 0xFF) shl 8) or (buf[2].toInt() and 0xFF)
        }
        if (len == 0 || len > 512) {
            buf = buf.copyOfRange(1, buf.size)
            return true
        }
        val total = cabecera + len + 3
        if (buf.size < total) return false

        val payload = buf.copyOfRange(cabecera, cabecera + len)
        val crc = ((buf[cabecera + len].toInt() and 0xFF) shl 8) or
            (buf[cabecera + len + 1].toInt() and 0xFF)
        val fin = buf[total - 1]

        if (fin == 0x03.toByte() && crc == VescPacket.crc16(payload)) {
            buf = buf.copyOfRange(total, buf.size)
            onPayload(payload)
        } else {
            // Falso inicio de trama: saltamos un byte y seguimos buscando.
            buf = buf.copyOfRange(1, buf.size)
        }
        return true
    }
}

/** Lo que interesa de la respuesta a COMM_GET_VALUES. */
data class VescValues(
    val tempFet: Float,
    val motorCurrent: Float,
    val inputCurrent: Float,
    val duty: Float,
    val rpm: Int,
    val vIn: Float,
    val fault: Int,
)

object VescTelemetry {

    /**
     * Interpreta la respuesta a COMM_GET_VALUES (firmware 5.x a 7.x). Todos
     * los campos van en big-endian y escalados por potencias de 10.
     */
    fun parseValues(p: ByteArray): VescValues? {
        if (p.isEmpty() || p[0].toInt() != VescPacket.COMM_GET_VALUES || p.size < 54) return null
        val b = ByteBuffer.wrap(p).order(ByteOrder.BIG_ENDIAN)
        return VescValues(
            tempFet = b.getShort(1) / 10f,
            motorCurrent = b.getInt(5) / 100f,
            inputCurrent = b.getInt(9) / 100f,
            duty = b.getShort(21) / 1000f,
            rpm = b.getInt(23),
            vIn = b.getShort(27) / 10f,
            fault = p[53].toInt(),
        )
    }

    /**
     * Tension en reposo de una celda Li-ion NCM frente a estado de carga, con
     * los mismos extremos que la BMS Daly del pack: 0 % = 3,00 V y
     * 100 % = 4,15 V por celda. Bajo carga la tension cae, asi que el
     * porcentaje baja al dar gas y se recupera al soltar.
     */
    private val curvaOcv = floatArrayOf(
        3.00f, 0f, 3.30f, 5f, 3.45f, 10f, 3.55f, 20f, 3.62f, 30f, 3.68f, 40f,
        3.74f, 50f, 3.81f, 60f, 3.88f, 70f, 3.96f, 80f, 4.05f, 90f, 4.15f, 100f,
    )

    fun bateriaPorcentaje(vPack: Float, celdas: Int): Int {
        val v = vPack / celdas
        if (v <= curvaOcv[0]) return 0
        var i = 2
        while (i < curvaOcv.size) {
            val v1 = curvaOcv[i]
            if (v <= v1) {
                val v0 = curvaOcv[i - 2]
                val p0 = curvaOcv[i - 1]
                val p1 = curvaOcv[i + 1]
                return (p0 + (v - v0) / (v1 - v0) * (p1 - p0)).toInt()
            }
            i += 2
        }
        return 100
    }
}
