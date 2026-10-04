package xyz.danilab.bleremotevesc

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/**
 * Rosa de rumbo pequena: la flecha del sentido de marcha queda fija apuntando
 * arriba y el dial gira, con la N en rojo.
 */
class CompassView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Rumbo en grados (0 = norte). NaN = sin dato: el dial se atenua. */
    var rumbo = Float.NaN
        set(v) {
            field = v
            invalidate()
        }

    /** Rumbo antiguo (embarcacion parada): se dibuja atenuado. */
    var atenuado = false
        set(v) {
            field = v
            invalidate()
        }

    private var palette = Palette.CLARO
    private val d = resources.displayMetrics.density
    private val pAro = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * d }
    private val pMarca = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1.5f * d; strokeCap = Paint.Cap.ROUND }
    private val pNorte = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pBarco = Paint(Paint.ANTI_ALIAS_FLAG)
    private val camino = Path()

    fun applyPalette(p: Palette) {
        palette = p
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(cx, cy) - 2 * d
        val conDato = !rumbo.isNaN() && !atenuado

        pAro.color = palette.outlineVar
        c.drawCircle(cx, cy, r, pAro)

        c.save()
        if (!rumbo.isNaN()) c.rotate(-rumbo, cx, cy)
        pMarca.color = palette.onVar
        pMarca.alpha = if (conDato) 255 else 90
        for (i in 0 until 4) {
            if (i > 0) c.drawLine(cx, cy - r, cx, cy - r + 4 * d, pMarca)
            c.rotate(90f, cx, cy)
        }
        // Marca de norte: triangulo rojo en el borde del dial.
        pNorte.color = palette.spo
        pNorte.alpha = if (conDato) 255 else 90
        camino.reset()
        camino.moveTo(cx, cy - r - 1 * d)
        camino.lineTo(cx - 4.5f * d, cy - r + 7 * d)
        camino.lineTo(cx + 4.5f * d, cy - r + 7 * d)
        camino.close()
        c.drawPath(camino, pNorte)
        c.restore()

        // Embarcacion fija apuntando a proa.
        pBarco.color = palette.primary
        pBarco.alpha = if (conDato) 255 else 90
        camino.reset()
        camino.moveTo(cx, cy - r * 0.55f)
        camino.lineTo(cx + r * 0.32f, cy + r * 0.45f)
        camino.lineTo(cx, cy + r * 0.25f)
        camino.lineTo(cx - r * 0.32f, cy + r * 0.45f)
        camino.close()
        c.drawPath(camino, pBarco)
    }
}
