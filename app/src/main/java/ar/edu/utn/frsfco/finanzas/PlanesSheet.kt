package ar.edu.utn.frsfco.finanzas

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.lifecycleScope
import ar.edu.utn.frsfco.finanzas.databinding.HojaPlanesBinding
import com.google.android.material.card.MaterialCardView
import com.google.android.material.snackbar.Snackbar
import com.revenuecat.purchases.Package
import kotlinx.coroutines.launch

/**
 * Hoja con los planes de la suscripción Plus.
 *
 * Las tarjetas se arman con la oferta que devuelve la tienda: los precios y las duraciones
 * se definen en la consola y no en la aplicación. El plan destacado y la variante de la
 * oferta vienen de los parámetros remotos, de modo que se pueden probar otras combinaciones
 * sin publicar una versión nueva.
 */
class PlanesSheet : HojaBase() {

    private var _binding: HojaPlanesBinding? = null
    private val binding get() = _binding!!

    private val tarjetas = mutableMapOf<String, MaterialCardView>()
    private var elegido: Package? = null

    override fun onCreateView(inflater: LayoutInflater, contenedor: ViewGroup?, estado: Bundle?): View {
        _binding = HojaPlanesBinding.inflate(inflater, contenedor, false)
        return binding.root
    }

    override fun onViewCreated(vista: View, estado: Bundle?) {
        super.onViewCreated(vista, estado)
        binding.btnContinuar.setOnClickListener { elegido?.let { comprar(it) } }
        if (estado == null) Embudo.registrar(requireContext(), Embudo.PLANES_VISTOS)
        cargar()
    }

    private fun cargar() {
        if (!Suscripcion.disponible) {
            binding.tvEstado.setText(R.string.planes_sin_tienda)
            binding.btnContinuar.visibility = View.GONE
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            Suscripcion.refrescar()
            if (Suscripcion.esPlus.value) {
                mostrarPlusActivo()
                return@launch
            }
            try {
                val paquetes = Suscripcion.planes()
                val ids = Acceso.ordenar(paquetes.map { it.identifier }, ParametrosRemotos.varianteOferta())
                val destacado = Acceso.destacado(ids, ParametrosRemotos.planDestacado())
                binding.listaPlanes.removeAllViews()
                tarjetas.clear()
                ids.forEach { id ->
                    val paquete = paquetes.first { it.identifier == id }
                    binding.listaPlanes.addView(tarjeta(paquete, id == destacado))
                }
                binding.tvEstado.visibility = if (ids.isEmpty()) View.VISIBLE else View.GONE
                if (ids.isEmpty()) binding.tvEstado.setText(R.string.planes_vacio)
                paquetes.firstOrNull { it.identifier == destacado }?.let { elegir(it) }
            } catch (e: Exception) {
                binding.tvEstado.setText(R.string.planes_error)
            }
        }
    }

    private fun mostrarPlusActivo() {
        binding.listaPlanes.removeAllViews()
        binding.btnContinuar.visibility = View.GONE
        binding.tvEstado.visibility = View.VISIBLE
        binding.tvEstado.setText(R.string.plus_activo)
    }

    private fun tarjeta(paquete: Package, destacado: Boolean): View {
        val contexto = requireContext()
        val densidad = resources.displayMetrics.density
        fun dp(valor: Int) = (valor * densidad).toInt()

        val card = MaterialCardView(contexto).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(10) }
            radius = dp(16).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = ContextCompat.getColor(contexto, R.color.borde)
            setCardBackgroundColor(ContextCompat.getColor(contexto, R.color.superficie))
            isClickable = true
            isFocusable = true
            tag = "plan_${paquete.identifier}"
        }
        val fila = LinearLayout(contexto).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }
        val textos = LinearLayout(contexto).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val titulo = TextView(contexto).apply {
            text = nombreDelPlan(paquete)
            textSize = 16f
            setTextColor(ContextCompat.getColor(contexto, R.color.texto))
            typeface = ResourcesCompat.getFont(contexto, R.font.poppins_semibold)
        }
        textos.addView(titulo)
        if (destacado) {
            textos.addView(TextView(contexto).apply {
                setText(R.string.plan_recomendado)
                textSize = 12f
                setTextColor(ContextCompat.getColor(contexto, R.color.verde))
                typeface = ResourcesCompat.getFont(contexto, R.font.poppins_medium)
            })
        }
        val precio = TextView(contexto).apply {
            text = paquete.product.price.formatted
            textSize = 17f
            setTextColor(ContextCompat.getColor(contexto, R.color.texto))
            typeface = ResourcesCompat.getFont(contexto, R.font.poppins_semibold)
        }
        fila.addView(textos)
        fila.addView(precio)
        card.addView(fila)
        card.setOnClickListener { elegir(paquete) }
        tarjetas[paquete.identifier] = card
        return card
    }

    /** Marca el plan tocado. La compra recién empieza al tocar Continuar. */
    private fun elegir(paquete: Package) {
        elegido = paquete
        val contexto = requireContext()
        val densidad = resources.displayMetrics.density
        tarjetas.forEach { (id, card) ->
            val activo = id == paquete.identifier
            card.strokeWidth = ((if (activo) 2 else 1) * densidad).toInt()
            card.strokeColor = ContextCompat.getColor(contexto, if (activo) R.color.verde else R.color.borde)
            card.setCardBackgroundColor(ContextCompat.getColor(contexto, if (activo) R.color.verde_suave else R.color.superficie))
        }
        binding.btnContinuar.isEnabled = true
    }

    private fun nombreDelPlan(paquete: Package): String = when (paquete.identifier) {
        Acceso.PAQUETE_SEMANAL -> getString(R.string.plan_semanal)
        Acceso.PAQUETE_MENSUAL -> getString(R.string.plan_mensual)
        Acceso.PAQUETE_ANUAL -> getString(R.string.plan_anual)
        else -> paquete.product.title
    }

    private fun comprar(paquete: Package) {
        val plan = paquete.identifier.removePrefix("\$rc_")
        Embudo.registrar(requireContext(), Embudo.PLAN_ELEGIDO, plan)
        Embudo.registrar(requireContext(), Embudo.COMPRA_INICIADA, plan)
        viewLifecycleOwner.lifecycleScope.launch {
            when (val resultado = Suscripcion.comprar(requireActivity(), paquete)) {
                ResultadoCompra.Exitosa -> {
                    Embudo.registrar(requireContext(), Embudo.COMPRA_OK, plan)
                    terminar(RESULTADO, Bundle().apply { putString(MENSAJE, getString(R.string.compra_ok)) })
                }
                ResultadoCompra.Cancelada -> {
                    Embudo.registrar(requireContext(), Embudo.COMPRA_CANCELADA, plan)
                    Snackbar.make(binding.root, R.string.compra_cancelada, Snackbar.LENGTH_SHORT).show()
                }
                is ResultadoCompra.Fallida ->
                    Snackbar.make(binding.root, getString(R.string.compra_fallida, resultado.motivo), Snackbar.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val RESULTADO = "planes"
    }
}
