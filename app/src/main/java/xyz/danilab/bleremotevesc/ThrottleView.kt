package xyz.danilab.bleremotevesc

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.sign

/**
 * Acelerador vertical: arriba avante, abajo atras, enclavamiento en el centro.
 *
 * La posicion va de -100 a 100. Al soltar el dedo la palanca se queda donde
 * esta, como la de un motor fuera borda: no vuelve sola al centro.
 *
 * Dos protecciones contra acelerones sin querer:
 * - Solo se mueve agarrando el pulgar. Tocar el canal en otro punto no hace
 *   nada; antes un toque arriba saltaba directo al 100 %.
 * - Salir del centro cuesta mas que volver: hay que arrastrar mas alla de
 *   [breakout] para soltar el enclavamiento, y dentro de [deadzone] se vuelve
 *   a enclavar.
 */
class ThrottleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var deadzone = 8f
    /** Recorrido que hay que vencer para sacar la palanca del centro. */
    val breakout get() = deadzone + 12f
    var onChange: ((Float) -> Unit)? = null

    /**
     * Sin conexion con el VESC la palanca no se mueve: asi nunca queda
     * fuera del centro esperando a que se conecte. Se dibuja atenuada.
     */
    var habilitado = false
        set(v) {
            field = v
            if (!v) agarrado = false
            invalidate()
        }

    private var agarrado = false
    private var offsetAgarre = 0f

    var position = 0f
        private set

    private var palette = Palette.CLARO
    private val d = resources.displayMetrics.density
    private val thumbH = 56 * d
    private val inset = 8 * d

    private val pTrack = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pMid = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2 * d; strokeCap = Paint.Cap.ROUND }
    private val pKnob = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pGrip = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 4 * d; strokeCap = Paint.Cap.ROUND }
    private val pFlecha = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3 * d
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val flecha = android.graphics.Path()
    private val r = RectF()

    fun applyPalette(p: Palette) {
        palette = p
        invalidate()
    }

    /** Pone la palanca en una posicion sin notificar (p. ej. PARADA). */
    fun setPositionSilently(p: Float) {
        position = p
        invalidate()
    }

    private fun recorrido() = (height - 2 * inset - thumbH) / 2f

    private fun centroPulgar() = height / 2f - position / 100f * recorrido()

    /** Dibujo de slider Material 3 en vertical: canal tonal, relleno y tirador solido. */
    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val mid = h / 2f
        val radio = 28 * d

        r.set(0f, 0f, w, h)
        pTrack.color = palette.s2
        c.drawRoundRect(r, radio, radio, pTrack)

        if (position != 0f) {
            pFill.color = if (position > 0) palette.primaryC else palette.revC
            c.save()
            c.clipRect(0f, if (position > 0) 0f else mid, w, if (position > 0) mid else h)
            c.drawRoundRect(r, radio, radio, pFill)
            c.restore()
        }

        pMid.color = palette.outlineVar
        c.drawLine(18 * d, mid, w - 18 * d, mid, pMid)

        val fw = 12 * d
        val fh = 7 * d
        val margen = 24 * d
        dibujarFlecha(c, w / 2, margen, fw, fh, arriba = true, color = palette.primary, activa = position > 0)
        dibujarFlecha(c, w / 2, h - margen, fw, fh, arriba = false, color = palette.rev, activa = position < 0)

        val cy = centroPulgar()
        r.set(inset, cy - thumbH / 2, w - inset, cy + thumbH / 2)
        pKnob.color = palette.onSurface
        pKnob.alpha = if (habilitado) 255 else 60
        c.drawRoundRect(r, 20 * d, 20 * d, pKnob)

        pGrip.color = palette.surface
        pGrip.alpha = if (habilitado) 180 else 60
        c.drawLine(w / 2 - 14 * d, cy, w / 2 + 14 * d, cy, pGrip)
    }

    private fun dibujarFlecha(
        c: Canvas, cx: Float, cy: Float, fw: Float, fh: Float,
        arriba: Boolean, color: Int, activa: Boolean,
    ) {
        pFlecha.color = color
        pFlecha.alpha = when {
            !habilitado -> 50
            activa || position == 0f -> 255
            else -> 70
        }
        val dy = if (arriba) fh / 2 else -fh / 2
        flecha.reset()
        flecha.moveTo(cx - fw, cy + dy)
        flecha.lineTo(cx, cy - dy)
        flecha.lineTo(cx + fw, cy + dy)
        c.drawPath(flecha, pFlecha)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!habilitado) return true
                // Solo se agarra si el dedo cae sobre el pulgar (con algo de
                // margen para dedos mojados o guantes).
                val cy = centroPulgar()
                agarrado = abs(e.y - cy) <= thumbH * 0.8f
                if (agarrado) {
                    offsetAgarre = e.y - cy
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!agarrado) return true
                val y = e.y - offsetAgarre
                var p = ((height / 2f - y) / recorrido() * 100f).coerceIn(-100f, 100f)

                if (position == 0f) {
                    // Enclavado: no se suelta hasta pasar el umbral de salida.
                    if (abs(p) < breakout) return true
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                } else if (abs(p) <= deadzone || p.sign != position.sign) {
                    // Volviendo al centro, o cruzandolo de golpe: se enclava.
                    // Para pasar de avante a atras hay que soltar el centro otra vez.
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    p = 0f
                }

                if (p != position) {
                    position = p
                    invalidate()
                    onChange?.invoke(p)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                agarrado = false
                return true
            }
        }
        return super.onTouchEvent(e)
    }
}
