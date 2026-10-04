package xyz.danilab.bleremotevesc

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.slider.Slider
import com.google.android.material.materialswitch.MaterialSwitch
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * BLERemoteVESC: mando Bluetooth para un motor con controlador VESC en un pato de
 * pesca. Acelerador vertical por corriente (par), modos de potencia,
 * bateria y potencia leidas del VESC, y velocidad del GPS del telefono.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        /** Energia nominal del pack: 10S8P, 40 Ah x 3,6 V x 10. */
        private const val BATTERY_WH = 1440f
        /**
         * Constante de tiempo del promedio de potencia que alimenta la
         * estimacion de autonomia. La lectura instantanea baila demasiado;
         * con ~20 s la estimacion responde a un cambio de ritmo sin saltar.
         */
        private const val AUTONOMIA_TAU_S = 20f
        /** Por debajo de esto se considera parado y no se estima. */
        private const val AUTONOMIA_MIN_W = 10f
        /**
         * Ritmo de envio con el motor en uso: consigna a 10 Hz y telemetria a
         * 5 Hz. El VESC corta a 1 s sin consigna.
         */
        private const val TX_ACTIVO_MS = 100L
        private const val TELEM_CADA_ACTIVO = 2
        /**
         * Ritmo en reposo (palanca en el centro y motor sin empuje): consigna a
         * 4 Hz, de sobra dentro del timeout de 1 s, y telemetria a 1 Hz. La
         * radio y la CPU se despiertan menos veces.
         */
        private const val TX_REPOSO_MS = 250L
        private const val TELEM_CADA_REPOSO = 4
        /** Tras soltar gas se sigue a ritmo activo este tiempo. */
        private const val ACTIVO_TRAS_SOLTAR_MS = 3000L
        /** Sin conexion el bucle solo vigila datos viejos. */
        private const val TX_DESCONECTADO_MS = 1000L

        /** Sin tocar la pantalla este tiempo, se atenua (si esta activado). */
        private const val ATENUAR_TRAS_MS = 30_000L
        /** Brillo de la pantalla atenuada, 0..1. Se sigue leyendo de cerca. */
        private const val BRILLO_ATENUADO = 0.08f
        /** Sin telemetria durante este tiempo, la bateria se da por desconocida. */
        private const val TELEMETRY_STALE_MS = 3000L
        private const val BATTERY_CELLS = 10

        /**
         * Dos tonos con las bobinas del motor. foc-beep solo suena con el
         * motor parado, que es como esta justo al conectar.
         */
        private const val BEEP_LISP =
            "(progn (foc-beep 1200 0.15 3.0) (sleep 0.25) (foc-beep 1600 0.15 3.0))"
        /**
         * Mientras suena no se manda consigna: un COMM_SET_CURRENT, aunque sea
         * de 0 A, toma el control del motor y cortaria el pitido. Incluye el
         * tiempo que tarda el interprete LispBM en arrancar la primera vez.
         * Supera el timeout de 1 s del VESC, pero solo ocurre justo al
         * conectar, con el acelerador en cero.
         */
        private const val BEEP_SILENCIO_MS = 2000L

        private const val GPS_ACCURACY_MAX_M = 15f
        private const val GPS_SPEED_ACCURACY_MAX_MS = 1.0f
        private const val GPS_WINDOW = 5
        private const val GPS_MIN_SAMPLES = 3
        private const val GPS_SPEED_FLOOR_KMH = 1.0f
        private const val GPS_STALE_MS = 5000L
        /** Por debajo de esta velocidad el rumbo GPS no es fiable. */
        private const val RUMBO_GPS_MIN_KMH = 2f
    }

    /**
     * Modos de potencia, como Eco/Crucero/Sport de los motores comerciales.
     * Cada modo guarda su propio tope de corriente y su suavizado: el 100 %
     * de la palanca significa cosas distintas segun el modo.
     */
    private enum class ModoPot(val id: String, val nombre: String, val maxDef: Float, val rampaDef: Float) {
        ECO("eco", "Eco", 6f, 15f),
        CRUCERO("crucero", "Crucero", 12f, 10f),
        SPORT("sport", "Sport", 25f, 5f),
    }

    private var modoPot = ModoPot.CRUCERO

    // Ajustes del acelerador, editables desde el panel y persistidos.
    private var currentMax = 15f
    private var currentMin = 1.5f
    private var expo = 2.0f
    private var deadzone = 8f
    /** Segundos para ir de 0 a 100 % de corriente. Bajar no se limita. */
    private var rampaS = 15f
    /** Correccion sumada a la tension que lee el VESC (el firmware 75_300_R2 lee desfasado). */
    private var vOffset = 0f
    private var ultimoVIn = 0f
    /** Potencia suavizada que sale de la bateria, W. */
    private var potencia = 0f
    /** Potencia promediada para la autonomia; 0 = sin promedio todavia. */
    private var potenciaMedia = 0f
    private var ultimoPct = 0

    private lateinit var prefs: SharedPreferences
    private var palette = Palette.OSCURO

    private lateinit var drawer: DrawerLayout
    private lateinit var throttle: ThrottleView
    private lateinit var ble: VescBle
    private val handler = Handler(Looper.getMainLooper())

    private var currentTarget = 0f
    /** Posicion de la palanca para mostrar: 0..100 sobre el recorrido util, con signo. */
    private var palancaPct = 0f
    private var currentSent = 0f
    private var tick = 0
    /** Periodo real del ultimo ciclo, para la rampa. */
    private var periodoMs = TX_REPOSO_MS
    private var ultimoEmpujeMs = 0L
    private var ultimoToqueMs = 0L
    private var atenuada = false
    private var gpsActivado = true
    private var atenuarActivado = true
    private var gpsEscuchando = false
    private var silencioHasta = 0L
    private var pitidoAlConectar = true
    private var cortarAlSalir = true
    private var ultimaTelemetriaMs = 0L

    private val parser = VescFrameParser { payload ->
        val id = payload[0].toInt() and 0xFF
        if (id == 135) {
            // COMM_LISP_PRINT: lo que el interprete LispBM devuelve o imprime.
            Log.i("BLERemoteVESC", "LISP: " + String(payload, 1, payload.size - 1, Charsets.US_ASCII).trimEnd('\u0000'))
        } else if (id != VescPacket.COMM_GET_VALUES) {
            Log.i("BLERemoteVESC", "paquete id=$id len=${payload.size}")
        }
        VescTelemetry.parseValues(payload)?.let { mostrarTelemetria(it) }
    }

    private fun tv(id: Int) = findViewById<TextView>(id)

    private val txRunnable = object : Runnable {
        override fun run() {
            val ahora = SystemClock.elapsedRealtime()
            val conectado = ble.state == VescBle.State.CONNECTED
            if (currentTarget != 0f || currentSent != 0f) ultimoEmpujeMs = ahora
            val activo = conectado && ahora - ultimoEmpujeMs < ACTIVO_TRAS_SOLTAR_MS

            avanzarRampa()
            if (conectado && ahora >= silencioHasta) {
                ble.send(VescPacket.setCurrent(currentSent))
                val cada = if (activo) TELEM_CADA_ACTIVO else TELEM_CADA_REPOSO
                if (++tick % cada == 0) ble.request(VescPacket.getValues())
            }
            comprobarDatosViejos()
            comprobarAtenuado(ahora)

            periodoMs = when {
                !conectado -> TX_DESCONECTADO_MS
                activo -> TX_ACTIVO_MS
                else -> TX_REPOSO_MS
            }
            handler.postDelayed(this, periodoMs)
        }
    }

    /** Si el bucle va a ritmo lento, lo despierta ya para no meter retardo. */
    private fun despertarBucle() {
        if (periodoMs != TX_ACTIVO_MS) {
            handler.removeCallbacks(txRunnable)
            handler.post(txRunnable)
        }
    }

    private val permisos = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { resultado ->
        if (resultado.values.all { it }) iniciarGps()
        else tv(R.id.txtEstado).text = "Faltan permisos de Bluetooth o ubicación"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("mando", MODE_PRIVATE)
        modoPot = ModoPot.values().firstOrNull { it.id == prefs.getString("modo_pot", null) } ?: ModoPot.CRUCERO
        currentMax = prefs.getFloat("max_${modoPot.id}", modoPot.maxDef)
        currentMin = prefs.getFloat("min", currentMin)
        expo = prefs.getFloat("expo", expo)
        deadzone = prefs.getFloat("dz", deadzone)
        rampaS = prefs.getFloat("rampa_${modoPot.id}", modoPot.rampaDef)
        vOffset = prefs.getFloat("voff", vOffset)
        pitidoAlConectar = prefs.getBoolean("pitido", true)
        gpsActivado = prefs.getBoolean("gps", true)
        atenuarActivado = prefs.getBoolean("atenuar", true)
        cortarAlSalir = prefs.getBoolean("corte", true)

        drawer = findViewById(R.id.drawer)
        throttle = findViewById(R.id.throttle)
        throttle.deadzone = deadzone
        throttle.onChange = { aplicarConsigna(it) }

        ble = VescBle(
            context = this,
            onState = { estado, mensaje -> onEstadoBle(estado, mensaje) },
            onRx = { parser.feed(it) },
        )

        findViewById<ImageView>(R.id.btnMenu).setOnClickListener { drawer.openDrawer(Gravity.START) }
        findViewById<View>(R.id.btnParada).setOnClickListener { pararMotor() }
        tv(R.id.btnConectar).setOnClickListener {
            if (ble.state == VescBle.State.CONNECTED) ble.disconnect() else pedirPermisosYConectar()
        }

        tv(R.id.mOscuro).setOnClickListener { setModo(Palette.OSCURO) }
        tv(R.id.mClaro).setOnClickListener { setModo(Palette.CLARO) }
        tv(R.id.mAmoled).setOnClickListener { setModo(Palette.AMOLED) }

        enlazarAjuste(R.id.sMax, R.id.rMax, currentMax) { currentMax = it; "Corriente máxima (${modoPot.nombre})" to "%.0f A".format(it) }
        enlazarAjuste(R.id.sMin, R.id.rMin, currentMin) { currentMin = it; "Primer escalón" to "%.1f A".format(it) }
        enlazarAjuste(R.id.sExp, R.id.rExp, expo) { expo = it; "Curva (expo)" to "%.1f".format(it) }
        enlazarAjuste(R.id.sDz, R.id.rDz, deadzone) {
            deadzone = it; throttle.deadzone = it; "Zona muerta" to "%.0f %%".format(it)
        }
        enlazarAjuste(R.id.sRamp, R.id.rRamp, rampaS) { rampaS = it; "Suavizado (${modoPot.nombre})" to "%.0f s".format(it) }
        enlazarAjuste(R.id.sVoff, R.id.rVoff, vOffset) {
            vOffset = it
            if (ultimoVIn > 0f) pintarBateria()
            "Corrección de tensión" to "%+.1f V".format(it)
        }

        tv(R.id.mpEco).setOnClickListener { setModoPot(ModoPot.ECO) }
        tv(R.id.mpCrucero).setOnClickListener { setModoPot(ModoPot.CRUCERO) }
        tv(R.id.mpSport).setOnClickListener { setModoPot(ModoPot.SPORT) }

        findViewById<MaterialSwitch>(R.id.swPitido).apply {
            isChecked = pitidoAlConectar
            setOnCheckedChangeListener { _, on ->
                pitidoAlConectar = on
                prefs.edit().putBoolean("pitido", on).apply()
            }
        }
        findViewById<MaterialSwitch>(R.id.swCorte).apply {
            isChecked = cortarAlSalir
            setOnCheckedChangeListener { _, on ->
                cortarAlSalir = on
                prefs.edit().putBoolean("corte", on).apply()
                pintarAvisoCorte()
            }
        }
        pintarAvisoCorte()

        findViewById<MaterialSwitch>(R.id.swGps).apply {
            isChecked = gpsActivado
            setOnCheckedChangeListener { _, on ->
                gpsActivado = on
                prefs.edit().putBoolean("gps", on).apply()
                if (on) iniciarGps() else pararGps("GPS desactivado")
            }
        }
        findViewById<MaterialSwitch>(R.id.swAtenuar).apply {
            isChecked = atenuarActivado
            setOnCheckedChangeListener { _, on ->
                atenuarActivado = on
                prefs.edit().putBoolean("atenuar", on).apply()
                if (!on) restaurarBrillo()
            }
        }

        prepararSecciones()
        prepararActualizaciones()
        tv(R.id.aWeb).setOnClickListener { abrirWeb("https://www.danilab.xyz") }
        tv(R.id.aRepo).setOnClickListener { abrirWeb("https://github.com/${Updater.REPO}") }

        setModo(Palette.byId(prefs.getString("modo", null)))
        onEstadoBle(VescBle.State.IDLE, "Desconectado")
        pedirPermisos()
        ultimoToqueMs = SystemClock.elapsedRealtime()
        handler.post(txRunnable)
    }

    // --- Ajustes ----------------------------------------------------------

    private val claves = mapOf(R.id.sMin to "min", R.id.sExp to "expo", R.id.sDz to "dz", R.id.sVoff to "voff")

    /** El tope y el suavizado se guardan por modo; el resto es comun. */
    private fun clave(sliderId: Int) = when (sliderId) {
        R.id.sMax -> "max_${modoPot.id}"
        R.id.sRamp -> "rampa_${modoPot.id}"
        else -> claves.getValue(sliderId)
    }

    private fun enlazarAjuste(sliderId: Int, rowId: Int, inicial: Float, aplicar: (Float) -> Pair<String, String>) {
        val s = findViewById<Slider>(sliderId)
        val row = tv(rowId)
        s.value = inicial.coerceIn(s.valueFrom, s.valueTo)
        fun pinta(v: Float) {
            val (nombre, valor) = aplicar(v)
            row.text = "$nombre:  $valor"
        }
        pinta(s.value)
        s.addOnChangeListener { _, v, _ ->
            pinta(v)
            prefs.edit().putFloat(clave(sliderId), v).apply()
            aplicarConsigna(throttle.position)
        }
    }

    /**
     * Cambia de modo. Se puede hacer en marcha: si el nuevo tope es menor, el
     * empuje baja al momento; si es mayor, sube con el suavizado del modo.
     */
    private fun setModoPot(m: ModoPot) {
        if (m != modoPot) {
            modoPot = m
            prefs.edit().putString("modo_pot", m.id).apply()
            val max = prefs.getFloat("max_${m.id}", m.maxDef)
            val rampa = prefs.getFloat("rampa_${m.id}", m.rampaDef)
            findViewById<Slider>(R.id.sMax).value = max.coerceIn(5f, 50f)
            findViewById<Slider>(R.id.sRamp).value = rampa.coerceIn(1f, 20f)
            currentMax = max
            rampaS = rampa
            // Si el valor coincide con el del modo anterior el slider no avisa:
            // se reescriben las etiquetas a mano para que lleven el modo nuevo.
            tv(R.id.rMax).text = "Corriente máxima (${m.nombre}):  %.0f A".format(max)
            tv(R.id.rRamp).text = "Suavizado (${m.nombre}):  %.0f s".format(rampa)
            aplicarConsigna(throttle.position)
        }
        pintarModosPot()
    }

    /** Colores propios de cada modo: Eco verde, Crucero naranja, Sport rojo. */
    private fun colorModo(m: ModoPot) = when (m) {
        ModoPot.ECO -> palette.eco to palette.ecoC
        ModoPot.CRUCERO -> palette.cru to palette.cruC
        ModoPot.SPORT -> palette.spo to palette.spoC
    }

    private fun pintarModosPot() {
        pintarSegmentado(
            R.id.modosPot, listOf(R.id.mpDiv1, R.id.mpDiv2),
            listOf(R.id.mpEco to ModoPot.ECO, R.id.mpCrucero to ModoPot.CRUCERO, R.id.mpSport to ModoPot.SPORT)
                .map { (id, m) -> Triple(id, m == modoPot, colorModo(m)) },
        )
    }

    /**
     * Boton segmentado de Material 3: contorno de pildora, separadores, y el
     * elegido relleno con su color y una marca de verificacion.
     */
    private fun pintarSegmentado(contenedor: Int, divisores: List<Int>, botones: List<Triple<Int, Boolean, Pair<Int, Int>>>) {
        val p = palette
        val d = resources.displayMetrics.density
        findViewById<View>(contenedor).apply {
            background = GradientDrawable().apply {
                cornerRadius = 20 * d
                setStroke(d.roundToInt(), p.outline)
            }
            clipToOutline = true
        }
        divisores.forEach { findViewById<View>(it).setBackgroundColor(p.outline) }
        val n = botones.size
        botones.forEachIndexed { i, (id, activo, colores) ->
            val (fuerte, suave) = colores
            val r = 20 * d
            val radios = when (i) {
                0 -> floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
                n - 1 -> floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
                else -> FloatArray(8)
            }
            tv(id).apply {
                setTextColor(if (activo) p.onSurface else p.onSurface)
                val fondo = GradientDrawable().apply {
                    cornerRadii = radios
                    setColor(if (activo) suave else 0)
                }
                background = RippleDrawable(ColorStateList.valueOf((fuerte and 0x00FFFFFF) or 0x33000000), fondo, null)
                // La marca va pegada al texto, centrada con el, como en Material 3.
                val etiqueta = text.toString().removePrefix("\u2713 ").removePrefix("  ")
                if (activo) {
                    val ic = ContextCompat.getDrawable(context, R.drawable.ic_check)!!.mutate()
                    ic.setTint(fuerte)
                    val px = (18 * d).roundToInt()
                    ic.setBounds(0, 0, px, px)
                    val sp = android.text.SpannableString("  $etiqueta")
                    sp.setSpan(android.text.style.ImageSpan(ic, android.text.style.DynamicDrawableSpan.ALIGN_CENTER), 0, 1, 0)
                    text = sp
                    setTextColor(if (p.claro) fuerte else p.onSurface)
                } else {
                    text = etiqueta
                }
            }
        }
    }

    // --- Menu: secciones plegables ------------------------------------------

    private class Seccion(val cabecera: Int, val icono: Int, val titulo: Int, val sub: Int, val flecha: Int, val cuerpo: Int)

    private val secciones by lazy {
        listOf(
            Seccion(R.id.hApariencia, R.id.iApariencia, R.id.tApariencia, R.id.uApariencia, R.id.cApariencia, R.id.bApariencia),
            Seccion(R.id.hAcelerador, R.id.iAcelerador, R.id.tAcelerador, R.id.uAcelerador, R.id.cAcelerador, R.id.bAcelerador),
            Seccion(R.id.hBateria, R.id.iBateria, R.id.tBateria, R.id.uBateria, R.id.cBateria, R.id.bBateria),
            Seccion(R.id.hAhorro, R.id.iAhorro, R.id.tAhorro, R.id.uAhorro, R.id.cAhorro, R.id.bAhorro),
            Seccion(R.id.hSeguridad, R.id.iSeguridad, R.id.tSeguridad, R.id.uSeguridad, R.id.cSeguridad, R.id.bSeguridad),
            Seccion(R.id.hUpd, R.id.iUpd, R.id.tUpd, R.id.uUpd, R.id.cUpd, R.id.bUpd),
            Seccion(R.id.hAcerca, R.id.iAcerca, R.id.tAcerca, R.id.uAcerca, R.id.cAcerca, R.id.bAcerca),
        )
    }

    /** Filas del menu que se despliegan al tocarlas, como en los Ajustes de Android. */
    private fun prepararSecciones() {
        secciones.forEach { k ->
            findViewById<View>(k.cabecera).setOnClickListener {
                val cuerpo = findViewById<View>(k.cuerpo)
                val abrir = cuerpo.visibility != View.VISIBLE
                cuerpo.visibility = if (abrir) View.VISIBLE else View.GONE
                findViewById<View>(k.flecha).animate().rotation(if (abrir) 180f else 0f).setDuration(150).start()
            }
        }
    }

    private fun abrirWeb(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            // Sin navegador: no hay nada que hacer.
        }
    }

    // --- Actualizaciones ----------------------------------------------------

    private val updater by lazy { Updater(this) }
    private var releasePendiente: Updater.Release? = null
    private val esBeta by lazy { updater.versionInstalada.contains("beta") }

    private fun prepararActualizaciones() {
        tv(R.id.pVersion).text = versionLegible()
        tv(R.id.txtUpd).text = "Versión instalada: ${versionLegible()}"
        findViewById<MaterialSwitch>(R.id.swAutoUpd).apply {
            isChecked = prefs.getBoolean("auto_upd", true)
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("auto_upd", on).apply() }
        }
        tv(R.id.btnBuscarUpd).setOnClickListener { buscarActualizacion(manual = true) }
        tv(R.id.btnInstalarUpd).setOnClickListener { instalarActualizacion() }

        // Comprobacion automatica como mucho cada 12 h, al abrir la app.
        val hace = System.currentTimeMillis() - prefs.getLong("upd_ultima", 0L)
        if (prefs.getBoolean("auto_upd", true) && hace > 12 * 3600_000L) buscarActualizacion(manual = false)
    }

    /** "0.1.0-beta" -> "0.1.0 · Beta 1"; "0.1.0-beta.3" -> "0.1.0 · Beta 3". */
    private fun versionLegible(): String {
        val v = updater.versionInstalada
        val base = v.substringBefore("-")
        val pre = v.substringAfter("-", "")
        return if (pre.startsWith("beta")) "$base · Beta ${pre.substringAfter(".", "1")}" else base
    }

    private fun buscarActualizacion(manual: Boolean) {
        if (manual) tv(R.id.txtUpd).text = "Buscando..."
        updater.buscar(incluirBetas = esBeta) { r ->
            prefs.edit().putLong("upd_ultima", System.currentTimeMillis()).apply()
            when (r) {
                is Updater.Resultado.Disponible -> {
                    releasePendiente = r.release
                    tv(R.id.txtUpd).text = "Nueva versión disponible: ${r.release.nombre}"
                    tv(R.id.btnInstalarUpd).apply {
                        text = "Actualizar a ${r.release.tag.removePrefix("v")}"
                        visibility = View.VISIBLE
                    }
                    // Punto de aviso en el boton del menu.
                    findViewById<View>(R.id.dotUpd).visibility = View.VISIBLE
                }
                Updater.Resultado.AlDia -> if (manual) tv(R.id.txtUpd).text = "Tienes la última versión (${versionLegible()})"
                is Updater.Resultado.Error -> if (manual) tv(R.id.txtUpd).text = r.motivo
            }
        }
    }

    private fun instalarActualizacion() {
        val rel = releasePendiente ?: return
        if (currentTarget != 0f || currentSent != 0f) {
            tv(R.id.txtUpd).text = "Para el motor antes de actualizar."
            return
        }
        tv(R.id.txtUpd).text = "Descargando..."
        updater.descargarEInstalar(
            rel,
            progreso = { tv(R.id.txtUpd).text = "Descargando... $it %" },
            alTerminar = { err -> tv(R.id.txtUpd).text = err ?: "Abriendo el instalador..." },
        )
    }

    // --- Apariencia -------------------------------------------------------

    private fun redondo(fill: Int, stroke: Int?, radioDp: Float) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = radioDp * resources.displayMetrics.density
        if (stroke != null) setStroke((resources.displayMetrics.density).roundToInt(), stroke)
    }

    /** Fondo con efecto de pulsacion (ripple) de Material. */
    private fun pulsable(fill: Int, radioDp: Float, ripple: Int = palette.onSurface) = RippleDrawable(
        ColorStateList.valueOf((ripple and 0x00FFFFFF) or 0x1F000000),
        redondo(fill, null, radioDp),
        null,
    )

    private fun tintar(id: Int, color: Int) {
        findViewById<ImageView>(id).imageTintList = ColorStateList.valueOf(color)
    }

    private fun setModo(p: Palette) {
        palette = p
        prefs.edit().putString("modo", p.id).apply()

        window.statusBarColor = p.surface
        window.navigationBarColor = p.surface
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = p.claro
            isAppearanceLightNavigationBars = p.claro
        }

        // Pantalla principal
        findViewById<View>(R.id.root).setBackgroundColor(p.surface)
        tv(R.id.txtTitulo).setTextColor(p.onSurface)
        findViewById<ImageView>(R.id.btnMenu).apply {
            imageTintList = ColorStateList.valueOf(p.onSurface)
            background = pulsable(0, 24f)
        }
        findViewById<View>(R.id.dotUpd).background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(p.error)
        }
        listOf(R.id.cardBat, R.id.cardRumbo, R.id.cardCmd, R.id.cardPow, R.id.cardSpd).forEach {
            findViewById<View>(it).background = redondo(p.s1, null, 20f)
        }
        listOf(R.id.icBat, R.id.icRumbo, R.id.icCmd, R.id.icPow, R.id.icSpd).forEach { tintar(it, p.onVar) }
        listOf(R.id.txtBat, R.id.txtRumbo, R.id.txtPow, R.id.txtVelocidad).forEach { tv(it).setTextColor(p.onSurface) }
        tv(R.id.lblRumbo).setTextColor(p.onVar)
        tv(R.id.txtRumboSub).setTextColor(p.onVar)
        findViewById<CompassView>(R.id.compass).applyPalette(p)
        listOf(
            R.id.lblBat, R.id.lblCmd, R.id.lblPow, R.id.lblSpd, R.id.uBat, R.id.uCmd, R.id.uPow, R.id.uSpd,
            R.id.txtVolt, R.id.txtAutonomia, R.id.txtAmpBat, R.id.txtFixGps,
        ).forEach { tv(it).setTextColor(p.onVar) }
        findViewById<ProgressBar>(R.id.barBat).apply {
            progressTintList = ColorStateList.valueOf(p.ok)
            progressBackgroundTintList = ColorStateList.valueOf(p.s3)
        }
        findViewById<View>(R.id.btnParada).background = pulsable(p.stopBg, 32f, p.onStop)
        tintar(R.id.icStop, p.onStop)
        tv(R.id.txtStop).setTextColor(p.onStop)
        throttle.applyPalette(p)

        // Menu lateral
        findViewById<ScrollView>(R.id.panel).background = GradientDrawable().apply {
            setColor(p.s1)
            val r = 16 * resources.displayMetrics.density
            cornerRadii = floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
        }
        tv(R.id.pTitle).setTextColor(p.onSurface)
        tv(R.id.pVersion).setTextColor(p.onVar)
        findViewById<View>(R.id.cardConn).background = redondo(p.s2, null, 20f)
        tv(R.id.txtEstado).setTextColor(p.onSurface)
        secciones.forEach { k ->
            findViewById<View>(k.cabecera).background = pulsable(0, 28f)
            tintar(k.icono, p.onVar)
            tintar(k.flecha, p.onVar)
            tv(k.titulo).setTextColor(p.onSurface)
            tv(k.sub).setTextColor(p.onVar)
        }
        listOf(R.id.rMax, R.id.rMin, R.id.rExp, R.id.rDz, R.id.rRamp, R.id.rVoff, R.id.aNombre, R.id.aAutor)
            .forEach { tv(it).setTextColor(p.onSurface) }
        listOf(
            R.id.rModoHint, R.id.rRampHint, R.id.rVoffHint, R.id.rEnergiaHint, R.id.rSeg1, R.id.rCorteHint,
            R.id.txtUpd, R.id.aDesc, R.id.aLic,
        ).forEach { tv(it).setTextColor(p.onVar) }
        listOf(R.id.aWeb, R.id.aRepo).forEach { tv(it).setTextColor(p.primary) }
        tv(R.id.btnBuscarUpd).apply { setTextColor(p.onSurface); background = pulsable(p.s3, 20f) }
        tv(R.id.btnInstalarUpd).apply { setTextColor(p.onPrimary); background = pulsable(p.primary, 20f, p.onPrimary) }

        pintarSegmentado(
            R.id.seg, listOf(R.id.segDiv1, R.id.segDiv2),
            listOf(R.id.mClaro to Palette.CLARO, R.id.mOscuro to Palette.OSCURO, R.id.mAmoled to Palette.AMOLED)
                .map { (id, m) -> Triple(id, m.id == p.id, p.primary to p.primaryC) },
        )

        val tint = ColorStateList.valueOf(p.primary)
        listOf(R.id.sMax, R.id.sMin, R.id.sExp, R.id.sDz, R.id.sRamp, R.id.sVoff).forEach {
            findViewById<Slider>(it).apply {
                trackActiveTintList = tint
                thumbTintList = tint
                trackInactiveTintList = ColorStateList.valueOf(p.primaryC)
                tickActiveTintList = ColorStateList.valueOf(0)
                tickInactiveTintList = ColorStateList.valueOf(0)
            }
        }

        // Interruptores Material 3
        val on = intArrayOf(android.R.attr.state_checked)
        val off = intArrayOf()
        listOf(R.id.swPitido, R.id.swCorte, R.id.swGps, R.id.swAtenuar, R.id.swAutoUpd).forEach {
            findViewById<MaterialSwitch>(it).apply {
                setTextColor(p.onSurface)
                thumbTintList = ColorStateList(arrayOf(on, off), intArrayOf(p.onPrimary, p.outline))
                trackTintList = ColorStateList(arrayOf(on, off), intArrayOf(p.primary, p.s3))
                trackDecorationTintList = ColorStateList(arrayOf(on, off), intArrayOf(0, p.outline))
            }
        }

        pintarModosPot()
        pintarConexion(ble.state == VescBle.State.CONNECTED)
        pintarConsigna()
    }

    private fun pintarConexion(conectado: Boolean) {
        val p = palette
        // Chip de la barra superior
        findViewById<View>(R.id.chipLink).background =
            if (conectado) redondo(p.okC, null, 8f) else redondo(0, p.outlineVar, 8f)
        findViewById<ImageView>(R.id.icLink).apply {
            setImageResource(if (conectado) R.drawable.ic_bt_on else R.drawable.ic_bt_off)
            imageTintList = ColorStateList.valueOf(if (conectado) p.ok else p.onVar)
        }
        tv(R.id.txtLink).apply {
            text = if (conectado) "Conectado" else "Sin conexión"
            setTextColor(if (conectado) p.onSurface else p.onVar)
        }
        // Tarjeta del menu
        findViewById<ImageView>(R.id.icConn).apply {
            setImageResource(if (conectado) R.drawable.ic_bt_on else R.drawable.ic_bt_off)
            imageTintList = ColorStateList.valueOf(if (conectado) p.ok else p.onVar)
        }
        tv(R.id.btnConectar).apply {
            text = if (conectado) "Desconectar" else "Conectar"
            if (conectado) {
                setTextColor(p.onSurface); background = pulsable(p.s3, 20f)
            } else {
                setTextColor(p.onPrimary); background = pulsable(p.primary, 20f, p.onPrimary)
            }
        }
    }

    private fun pintarAvisoCorte() {
        tv(R.id.rCorteHint).text = if (cortarAlSalir) {
            "Si cambias de app o apagas la pantalla, el motor se para."
        } else {
            "El motor sigue con la app en segundo plano. Si Android la pausa, " +
                "el VESC deja de recibir órdenes y para solo en 1 s."
        }
    }

    private fun onEstadoBle(estado: VescBle.State, mensaje: String) {
        tv(R.id.txtEstado).text = mensaje
        val conectado = estado == VescBle.State.CONNECTED
        pintarConexion(conectado)
        throttle.habilitado = conectado
        // La pantalla solo se fuerza encendida mientras hay motor que manejar.
        if (conectado) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            restaurarBrillo()
        }
        if (conectado && pitidoAlConectar) {
            silencioHasta = SystemClock.elapsedRealtime() + BEEP_SILENCIO_MS
            Log.i("BLERemoteVESC", "enviando pitido")
            ble.request(VescPacket.lispRepl(BEEP_LISP))
        }
        if (!conectado) {
            pararMotor()
            parser.reset()
            ultimaTelemetriaMs = 0L
            limpiarBateria("Sin datos")
        }
    }

    // --- Acelerador -------------------------------------------------------

    private fun aplicarConsigna(pos: Float) {
        if (abs(pos) <= deadzone) {
            currentTarget = 0f
            palancaPct = 0f
        } else {
            val t = (abs(pos) - deadzone) / (100f - deadzone)
            currentTarget = sign(pos) * (currentMin + t.pow(expo) * (currentMax - currentMin))
            // Lo que se ensena es la posicion de la palanca, no la corriente:
            // al VESC se le manda par (amperios), pero al piloto le basta con
            // saber cuanto acelerador lleva. Minimo 1 % para que no marque 0
            // con el motor empujando.
            palancaPct = sign(pos) * (t * 100f).coerceAtLeast(1f)
        }
        pintarConsigna()
        if (currentTarget != 0f) despertarBucle()
    }

    private fun pintarConsigna() {
        val txt = tv(R.id.txtCmd)
        txt.text = abs(palancaPct).roundToInt().toString()
        txt.setTextColor(
            when {
                palancaPct > 0f -> palette.primary
                palancaPct < 0f -> palette.rev
                else -> palette.onSurface
            }
        )
    }

    /**
     * Suavizado de aceleracion: subir de par va limitado a [rampaS] segundos
     * de 0 a 100 %, aunque la palanca se lleve al tope de golpe. Bajar es
     * inmediato: soltar gas o PARADA nunca deben tener retardo.
     */
    private fun avanzarRampa() {
        if (abs(currentTarget) <= abs(currentSent) || currentTarget.sign != currentSent.sign) {
            currentSent = currentTarget
            return
        }
        val paso = currentMax / rampaS * (periodoMs / 1000f)
        val delta = currentTarget - currentSent
        currentSent += if (abs(delta) <= paso) delta else paso * delta.sign
    }

    private fun pararMotor() {
        throttle.setPositionSilently(0f)
        currentTarget = 0f
        currentSent = 0f
        palancaPct = 0f
        pintarConsigna()
        if (ble.state == VescBle.State.CONNECTED) ble.send(VescPacket.setCurrent(0f))
    }

    // --- Telemetria del VESC ----------------------------------------------

    private fun mostrarTelemetria(v: VescValues) {
        ultimaTelemetriaMs = SystemClock.elapsedRealtime()
        ultimoVIn = v.vIn
        pintarBateria()

        // Potencia que sale de la bateria = tension de bus x corriente de
        // entrada, las dos tal como las da el VESC. No se suaviza en la app:
        // el firmware ya entrega la corriente promediada desde la ultima
        // lectura. Negativa = regeneracion.
        potencia = (v.vIn + vOffset) * v.inputCurrent
        tv(R.id.txtPow).text = potencia.roundToInt().toString()
        actualizarAutonomia()
        tv(R.id.txtAmpBat).text = "%.1f A batería".format(v.inputCurrent)
    }

    private fun pintarBateria() {
        val v = ultimoVIn + vOffset
        val pct = VescTelemetry.bateriaPorcentaje(v, BATTERY_CELLS)
        ultimoPct = pct
        tv(R.id.txtBat).text = pct.toString()
        findViewById<ProgressBar>(R.id.barBat).progress = pct
        tv(R.id.txtVolt).text = "%.1f V · %dS".format(v, BATTERY_CELLS)
    }

    /**
     * Autonomia = energia que queda / potencia media reciente. La energia
     * sale del porcentaje, que se calcula por tension: con el motor tirando
     * la tension cae y la estimacion sale algo pesimista.
     */
    private var ultimaMuestraPotMs = 0L

    private fun actualizarAutonomia() {
        val ahora = SystemClock.elapsedRealtime()
        val dt = if (ultimaMuestraPotMs == 0L) 0.2f else ((ahora - ultimaMuestraPotMs) / 1000f).coerceIn(0.05f, 2f)
        ultimaMuestraPotMs = ahora
        val w = potencia.coerceAtLeast(0f)
        potenciaMedia = if (potenciaMedia == 0f) w else potenciaMedia + dt / AUTONOMIA_TAU_S * (w - potenciaMedia)

        val txt = tv(R.id.txtAutonomia)
        if (potenciaMedia < AUTONOMIA_MIN_W) {
            txt.text = "Autonomía: parado"
            return
        }
        val horas = BATTERY_WH * ultimoPct / 100f / potenciaMedia
        val h = horas.toInt()
        val min = ((horas - h) * 60f).roundToInt()
        txt.text = when {
            horas >= 20f -> "Autonomía: +20 h"
            h == 0 -> "Autonomía: ≈ %d min".format(min)
            else -> "Autonomía: ≈ %d h %02d min".format(h, min)
        }
    }

    private fun limpiarBateria(motivo: String) {
        ultimoVIn = 0f
        potencia = 0f
        potenciaMedia = 0f
        tv(R.id.txtAutonomia).text = ""
        tv(R.id.txtPow).text = "--"
        tv(R.id.txtAmpBat).text = "Sin datos"
        tv(R.id.txtBat).text = "--"
        findViewById<ProgressBar>(R.id.barBat).progress = 0
        tv(R.id.txtVolt).text = motivo
    }

    private fun comprobarDatosViejos() {
        val ahora = SystemClock.elapsedRealtime()
        if (ultimaTelemetriaMs != 0L && ahora - ultimaTelemetriaMs > TELEMETRY_STALE_MS) {
            ultimaTelemetriaMs = 0L
            limpiarBateria("Sin respuesta del VESC")
        }
        if (muestras.isNotEmpty() && ultimoFixMs != 0L && ahora - ultimoFixMs > GPS_STALE_MS) {
            limpiarVelocidad("Sin fix reciente")
        }
    }

    // --- Permisos ---------------------------------------------------------

    private fun permisosNecesarios(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun tienePermisos(): Boolean = permisosNecesarios().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun pedirPermisos() {
        if (tienePermisos()) iniciarGps() else permisos.launch(permisosNecesarios())
    }

    private fun pedirPermisosYConectar() {
        if (!tienePermisos()) {
            permisos.launch(permisosNecesarios())
            return
        }
        ble.scanAndConnect()
    }

    // --- GPS del telefono -------------------------------------------------

    private val muestras = ArrayDeque<Float>()
    private var ultimoFixMs = 0L

    private fun limpiarVelocidad(motivo: String) {
        muestras.clear()
        velocidadKmh = 0f
        tv(R.id.txtVelocidad).text = "--.-"
        tv(R.id.txtFixGps).text = motivo
        pintarRumbo()
    }

    /** Solo se aceptan medidas con precision declarada suficiente. */
    private val gpsListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val precision = if (location.hasAccuracy()) location.accuracy else Float.MAX_VALUE
            if (precision > GPS_ACCURACY_MAX_M) {
                limpiarVelocidad("GPS ±${precision.roundToInt()} m")
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && location.hasSpeedAccuracy() &&
                location.speedAccuracyMetersPerSecond > GPS_SPEED_ACCURACY_MAX_MS
            ) {
                limpiarVelocidad("Poco fiable")
                return
            }
            if (!location.hasSpeed()) {
                limpiarVelocidad("Sin velocidad")
                return
            }

            ultimoFixMs = SystemClock.elapsedRealtime()
            muestras.addLast(location.speed * 3.6f)
            while (muestras.size > GPS_WINDOW) muestras.removeFirst()

            if (muestras.size < GPS_MIN_SAMPLES) {
                tv(R.id.txtVelocidad).text = "--.-"
                tv(R.id.txtFixGps).text = "Estabilizando"
                return
            }
            val media = muestras.average().toFloat()
            velocidadKmh = media
            if (location.hasBearing() && media >= RUMBO_GPS_MIN_KMH) nuevoRumboGps(location.bearing)
            pintarRumbo()
            tv(R.id.txtVelocidad).text = "%.1f".format(if (media < GPS_SPEED_FLOOR_KMH) 0f else media)
            tv(R.id.txtFixGps).text = "GPS ±${precision.roundToInt()} m"
        }

        @Deprecated("Requerido en API < 29")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        override fun onProviderDisabled(provider: String) = limpiarVelocidad("GPS apagado")
        override fun onProviderEnabled(provider: String) = Unit
    }

    /**
     * El GPS es, despues de la pantalla, lo que mas bateria gasta. Solo se
     * escucha con la app en primer plano y si el usuario no lo ha apagado.
     */
    @SuppressLint("MissingPermission")
    private fun iniciarGps() {
        if (!gpsActivado) {
            limpiarVelocidad("GPS desactivado")
            return
        }
        if (gpsEscuchando || !tienePermisos()) return
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            limpiarVelocidad("GPS apagado")
            return
        }
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, gpsListener)
        gpsEscuchando = true
        limpiarVelocidad("Esperando GPS")
    }

    private fun pararGps(motivo: String) {
        if (gpsEscuchando) {
            (getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(gpsListener)
            gpsEscuchando = false
        }
        limpiarVelocidad(motivo)
    }

    // --- Rumbo -------------------------------------------------------------

    /*
     * Rumbo sobre el fondo, del GPS. En un pato se va sentado mirando hacia
     * atras, asi que la brujula del movil marcaria lo contrario: el GPS mide
     * hacia donde se desplaza la embarcacion de verdad. Solo es fiable en
     * marcha; parado se muestra el ultimo rumbo, atenuado.
     */
    private var velocidadKmh = 0f
    /** Rumbo suavizado como vector unitario, para no saltar entre 359 y 0. */
    private var rumboSin = Float.NaN
    private var rumboCos = Float.NaN
    private var rumboGpsMs = 0L

    private fun nuevoRumboGps(grados: Float) {
        val rad = Math.toRadians(grados.toDouble())
        val sn = sin(rad).toFloat()
        val cs = cos(rad).toFloat()
        if (rumboSin.isNaN() || SystemClock.elapsedRealtime() - rumboGpsMs > 10_000) {
            rumboSin = sn; rumboCos = cs
        } else {
            rumboSin += 0.4f * (sn - rumboSin)
            rumboCos += 0.4f * (cs - rumboCos)
        }
        rumboGpsMs = SystemClock.elapsedRealtime()
    }

    private fun cardinal(g: Int) = arrayOf("N", "NE", "E", "SE", "S", "SO", "O", "NO")[((g + 22) % 360) / 45]

    private fun pintarRumbo() {
        val compas = findViewById<CompassView>(R.id.compass)
        if (rumboSin.isNaN()) {
            compas.rumbo = Float.NaN
            tv(R.id.txtRumbo).text = "--"
            tv(R.id.txtRumboSub).text = if (gpsActivado) "Sin datos" else "GPS desactivado"
            return
        }
        val grados = (Math.toDegrees(atan2(rumboSin, rumboCos).toDouble()).toFloat() + 360f) % 360f
        val g = grados.roundToInt() % 360
        val enMarcha = velocidadKmh >= RUMBO_GPS_MIN_KMH &&
            SystemClock.elapsedRealtime() - rumboGpsMs < 3000
        compas.rumbo = grados
        compas.atenuado = !enMarcha
        tv(R.id.txtRumbo).text = "$g°"
        tv(R.id.txtRumbo).alpha = if (enMarcha) 1f else 0.5f
        tv(R.id.txtRumboSub).text = if (enMarcha) cardinal(g) else "${cardinal(g)} · último"
    }

    // --- Atenuado de pantalla ---------------------------------------------

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        ultimoToqueMs = SystemClock.elapsedRealtime()
        if (atenuada) restaurarBrillo()
        return super.dispatchTouchEvent(ev)
    }

    /**
     * Con la pantalla forzada encendida, bajar el brillo es lo que mas
     * ahorra. Cualquier toque la devuelve al brillo normal.
     */
    private fun comprobarAtenuado(ahora: Long) {
        val encendidaForzada = ble.state == VescBle.State.CONNECTED
        if (atenuarActivado && encendidaForzada && !atenuada && ahora - ultimoToqueMs > ATENUAR_TRAS_MS) {
            window.attributes = window.attributes.apply { screenBrightness = BRILLO_ATENUADO }
            atenuada = true
        }
    }

    private fun restaurarBrillo() {
        if (!atenuada) return
        window.attributes = window.attributes.apply {
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        atenuada = false
    }

    // --- Ciclo de vida ----------------------------------------------------

    override fun onStart() {
        super.onStart()
        iniciarGps()
    }

    override fun onStop() {
        super.onStop()
        // En segundo plano no se ve la velocidad: el GPS se apaga.
        pararGps("Esperando GPS")
    }

    override fun onPause() {
        super.onPause()
        // Por defecto, si la app pasa a segundo plano se corta la traccion.
        // Desactivable desde ajustes.
        if (cortarAlSalir) pararMotor()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(txRunnable)
        pararGps("")
        ble.disconnect()
    }
}
