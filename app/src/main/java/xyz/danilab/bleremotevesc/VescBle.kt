package xyz.danilab.bleremotevesc

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.UUID

/**
 * Cliente BLE para el modulo "VESC BLE UART" del Flipsky FSESC 75100 Pro.
 *
 * El modulo expone el Nordic UART Service (NUS) y hace de puente transparente
 * hacia el puerto UART del VESC, de modo que basta con escribir tramas del
 * protocolo VESC en la caracteristica TX.
 */
@SuppressLint("MissingPermission")
class VescBle(
    private val context: Context,
    private val onState: (State, String) -> Unit,
    private val onRx: (ByteArray) -> Unit = {},
) {

    enum class State { IDLE, SCANNING, CONNECTING, CONNECTED, ERROR }

    companion object {
        val NUS_SERVICE: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        /** El movil escribe aqui; el modulo lo vuelca al UART del VESC. */
        val NUS_TX: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        /** El modulo notifica aqui lo que responde el VESC. */
        val NUS_RX: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val SCAN_TIMEOUT_MS = 15000L
    }

    private val main = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter

    private var gatt: BluetoothGatt? = null
    private var txChar: BluetoothGattCharacteristic? = null
    private var scanning = false

    /**
     * Solo interesa el ultimo valor de consigna: si una escritura sigue en
     * curso, la siguiente sustituye a la pendiente en vez de encolarse. Las
     * peticiones (telemetria) van aparte y no se pisan entre si.
     */
    private var pending: ByteArray? = null
    private val peticiones = ArrayDeque<Trozo>()
    private var writeBusy = false

    /** Un pedazo de una peticion; [ultimo] marca el final de su trama. */
    private class Trozo(val datos: ByteArray, val ultimo: Boolean)

    /**
     * Bytes utiles por escritura: MTU - 3. No se negocia un MTU mayor: Android
     * no admite dos operaciones GATT a la vez y pedirlo mientras se escribe el
     * CCCD o una consigna haria fallar una de las dos. Con 20 bytes y troceado
     * basta de sobra.
     */
    private var tamTrozo = 20

    /**
     * Mientras se envia una trama troceada no puede colarse una consigna en
     * medio: el modulo vuelca los bytes al UART tal cual llegan y el VESC
     * veria una trama corrupta.
     */
    private var enMitadDeTrama = false

    var state: State = State.IDLE
        private set

    private fun setState(s: State, msg: String) {
        state = s
        main.post { onState(s, msg) }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!scanning) return
            stopScan()
            setState(State.CONNECTING, "Conectando a ${result.device.address}...")
            gatt = result.device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            setState(State.ERROR, "Fallo del escaneo BLE (código $errorCode)")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                setState(State.CONNECTING, "Conectado, buscando servicios...")
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                cleanup()
                setState(State.IDLE, "Desconectado (estado $status)")
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(NUS_SERVICE)
            if (service == null) {
                setState(State.ERROR, "El dispositivo no expone el Nordic UART Service")
                disconnect()
                return
            }
            txChar = service.getCharacteristic(NUS_TX)
            if (txChar == null) {
                setState(State.ERROR, "No se encontró la característica TX")
                disconnect()
                return
            }
            val cccd = service.getCharacteristic(NUS_RX)?.let { rx ->
                g.setCharacteristicNotification(rx, true)
                rx.getDescriptor(CCCD)
            }
            if (cccd != null) {
                // Hasta que termine esta escritura no se puede mandar nada mas:
                // Android solo admite una operacion GATT a la vez.
                writeCccd(g, cccd)
            } else {
                setState(State.CONNECTED, "Conectado al VESC")
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (d.uuid == CCCD) {
                // Intervalo equilibrado (30-50 ms) en vez del rapido (~11-22 ms):
                // basta para mandar consigna a 10 Hz y la radio duerme mas.
                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED)
                setState(State.CONNECTED, "Conectado al VESC")
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) tamTrozo = (mtu - 3).coerceAtLeast(20)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writeBusy = false
            flush()
        }

        @Deprecated("Ruta para API < 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            c.value?.let { v -> main.post { onRx(v) } }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            main.post { onRx(value) }
        }
    }

    private fun writeCccd(g: BluetoothGatt, d: BluetoothGattDescriptor) {
        val enable = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(d, enable)
        } else {
            @Suppress("DEPRECATION")
            d.value = enable
            @Suppress("DEPRECATION")
            g.writeDescriptor(d)
        }
    }

    /** Busca el primer dispositivo que anuncie el NUS y se conecta a el. */
    fun scanAndConnect() {
        val scanner = adapter?.bluetoothLeScanner
        if (adapter == null || !adapter.isEnabled || scanner == null) {
            setState(State.ERROR, "Bluetooth apagado")
            return
        }
        if (scanning) return
        scanning = true
        setState(State.SCANNING, "Buscando VESC BLE UART...")
        val filter = ScanFilter.Builder().setServiceUuid(android.os.ParcelUuid(NUS_SERVICE)).build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(listOf(filter), settings, scanCallback)
        main.postDelayed({
            if (scanning) {
                stopScan()
                setState(State.IDLE, "No se encontró ningún VESC")
            }
        }, SCAN_TIMEOUT_MS)
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        adapter?.bluetoothLeScanner?.stopScan(scanCallback)
    }

    fun disconnect() {
        stopScan()
        gatt?.disconnect()
        gatt?.close()
        cleanup()
        setState(State.IDLE, "Desconectado")
    }

    private fun cleanup() {
        gatt = null
        txChar = null
        pending = null
        peticiones.clear()
        writeBusy = false
        enMitadDeTrama = false
        tamTrozo = 20
    }

    /** Encola [data]; si ya habia algo pendiente sin enviar, lo sustituye. */
    fun send(data: ByteArray) {
        if (state != State.CONNECTED) return
        pending = data
        flush()
    }

    /**
     * Encola una peticion puntual (telemetria, orden LispBM...). Si no cabe
     * en una escritura se trocea; los trozos salen seguidos.
     */
    fun request(data: ByteArray) {
        if (state != State.CONNECTED) return
        val n = (data.size + tamTrozo - 1) / tamTrozo
        if (peticiones.size + n > 32) return
        for (i in 0 until n) {
            val ini = i * tamTrozo
            peticiones.addLast(Trozo(data.copyOfRange(ini, minOf(ini + tamTrozo, data.size)), i == n - 1))
        }
        flush()
    }

    private fun flush() {
        if (writeBusy) return
        val g = gatt ?: return
        val c = txChar ?: return

        val trozo: Trozo?
        val data: ByteArray
        if (enMitadDeTrama || pending == null) {
            trozo = peticiones.removeFirstOrNull() ?: return
            data = trozo.datos
        } else {
            trozo = null
            data = pending!!
            pending = null
        }

        writeBusy = true
        val type = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(c, data, type) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                c.writeType = type
                c.value = data
                g.writeCharacteristic(c)
            }
        }

        if (ok) {
            if (trozo != null) enMitadDeTrama = !trozo.ultimo
            return
        }

        // La pila BLE estaba ocupada (otra operacion en curso o buffer lleno).
        // Se devuelve lo que se iba a mandar a su sitio y se reintenta en
        // breve; tirarlo corromperia una trama troceada a medias.
        writeBusy = false
        if (trozo != null) {
            peticiones.addFirst(trozo)
        } else if (pending == null) {
            pending = data
        }
        main.postDelayed({ flush() }, 15)
    }

}
