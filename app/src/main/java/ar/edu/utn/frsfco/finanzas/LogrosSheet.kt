package ar.edu.utn.frsfco.finanzas

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.os.bundleOf
import ar.edu.utn.frsfco.finanzas.databinding.HojaLogrosBinding

/**
 * Hoja con la racha actual y los logros, obtenidos y pendientes.
 *
 * Mostrar también los pendientes indica qué falta para el próximo, que es lo que invita
 * a volver al día siguiente.
 */
class LogrosSheet : HojaBase() {

    private var _binding: HojaLogrosBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, contenedor: ViewGroup?, estado: Bundle?): View {
        _binding = HojaLogrosBinding.inflate(inflater, contenedor, false)
        return binding.root
    }

    override fun onViewCreated(vista: View, estado: Bundle?) {
        super.onViewCreated(vista, estado)
        val actividad = argumento(ESTADO, Actividad.Estado::class.java) ?: return
        binding.tvRacha.text = if (actividad.racha > 0) {
            resources.getQuantityString(R.plurals.racha_dias, actividad.racha, actividad.racha)
        } else {
            getString(R.string.racha_cero)
        }
        binding.tvMejor.text = resources.getQuantityString(R.plurals.racha_mejor, actividad.mejorRacha, actividad.mejorRacha)
        Retencion.Logro.values().forEach { logro ->
            binding.listaLogros.addView(fila(logro, logro.clave in actividad.obtenidos))
        }
    }

    private fun fila(logro: Retencion.Logro, obtenido: Boolean): View {
        val c = requireContext()
        val densidad = resources.displayMetrics.density
        fun dp(v: Int) = (v * densidad).toInt()

        val fila = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
            alpha = if (obtenido) 1f else 0.55f
        }
        val icono = ImageView(c).apply {
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(14) }
            setImageResource(if (obtenido) R.drawable.ic_logro else R.drawable.ic_logro_pendiente)
            background = ContextCompat.getDrawable(c, R.drawable.fondo_circulo)
            backgroundTintList = ContextCompat.getColorStateList(c, if (obtenido) R.color.verde_suave else R.color.borde)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val textos = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        textos.addView(TextView(c).apply {
            text = getString(titulo(logro))
            textSize = 15f
            setTextColor(ContextCompat.getColor(c, R.color.texto))
            typeface = ResourcesCompat.getFont(c, R.font.poppins_semibold)
        })
        textos.addView(TextView(c).apply {
            text = getString(if (obtenido) R.string.logro_obtenido else detalle(logro))
            textSize = 13f
            setTextColor(ContextCompat.getColor(c, if (obtenido) R.color.verde else R.color.texto_suave))
        })
        fila.addView(icono)
        fila.addView(textos)
        return fila
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ESTADO = "estado"

        fun nueva(estado: Actividad.Estado) = LogrosSheet().apply { arguments = bundleOf(ESTADO to estado) }

        fun titulo(logro: Retencion.Logro): Int = when (logro) {
            Retencion.Logro.PRIMER_GASTO -> R.string.logro_primer_gasto
            Retencion.Logro.RACHA_3 -> R.string.logro_racha_3
            Retencion.Logro.RACHA_7 -> R.string.logro_racha_7
            Retencion.Logro.RACHA_30 -> R.string.logro_racha_30
            Retencion.Logro.CINCUENTA_MOVIMIENTOS -> R.string.logro_cincuenta
        }

        fun detalle(logro: Retencion.Logro): Int = when (logro) {
            Retencion.Logro.PRIMER_GASTO -> R.string.logro_primer_gasto_detalle
            Retencion.Logro.RACHA_3 -> R.string.logro_racha_3_detalle
            Retencion.Logro.RACHA_7 -> R.string.logro_racha_7_detalle
            Retencion.Logro.RACHA_30 -> R.string.logro_racha_30_detalle
            Retencion.Logro.CINCUENTA_MOVIMIENTOS -> R.string.logro_cincuenta_detalle
        }
    }
}
