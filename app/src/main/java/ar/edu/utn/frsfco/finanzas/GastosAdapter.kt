package ar.edu.utn.frsfco.finanzas

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import ar.edu.utn.frsfco.finanzas.databinding.ItemGastoBinding
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date

/**
 * Muestra la lista de gastos, uno por fila.
 *
 * Recibe las categorías y los medios de pago aparte porque ahora los define el
 * usuario, así que el color, el ícono y el nombre se resuelven al dibujar la fila.
 */
class GastosAdapter(
    private val alTocar: (Gasto) -> Unit
) : RecyclerView.Adapter<GastosAdapter.Fila>() {

    private val gastos = mutableListOf<Gasto>()
    private var categorias = mapOf<String, Categoria>()
    private var medios = mapOf<String, Medio>()
    private var soloEsteMes = true

    private val formatoCorto = SimpleDateFormat("d 'de' MMMM", Idioma.español)
    private val formatoConAnio = SimpleDateFormat("d 'de' MMMM 'de' yyyy", Idioma.español)

    /**
     * @param soloEsteMes cuando la lista abarca varios meses hay que escribir el año,
     * porque "14 de marzo" a secas no dice de cuándo es.
     */
    fun mostrar(
        nuevos: List<Gasto>,
        porId: Map<String, Categoria>,
        mediosPorId: Map<String, Medio>,
        soloEsteMes: Boolean = true
    ) {
        gastos.clear()
        gastos.addAll(nuevos)
        categorias = porId
        medios = mediosPorId
        this.soloEsteMes = soloEsteMes
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(padre: ViewGroup, tipo: Int): Fila {
        val binding = ItemGastoBinding.inflate(LayoutInflater.from(padre.context), padre, false)
        return Fila(binding)
    }

    override fun onBindViewHolder(fila: Fila, posicion: Int) = fila.mostrar(gastos[posicion])

    override fun getItemCount() = gastos.size

    inner class Fila(private val binding: ItemGastoBinding) : RecyclerView.ViewHolder(binding.root) {

        fun mostrar(gasto: Gasto) {
            // Si la categoría fue borrada, el gasto se sigue viendo con una de reserva.
            val categoria = categorias[gasto.categoriaId] ?: Categoria.desconocida()

            binding.icono.setImageResource(categoria.iconoDibujable())
            binding.icono.imageTintList = ColorStateList.valueOf(categoria.colorEntero())
            binding.fondoIcono.setCardBackgroundColor(categoria.colorSuave())

            binding.tvCategoria.text = categoria.nombre
            binding.tvDetalle.text = armarApoyo(gasto)
            binding.tvMonto.text = Plata.formatear(gasto.monto)

            // El medio de pago se muestra siempre: antes sólo aparecía en los gastos
            // con tarjeta y en el resto la fila quedaba sin decir cómo se pagó.
            binding.tvMedio.text = (medios[gasto.medioId] ?: Medio.desconocido()).nombre
            binding.tvMedio.setTextColor(
                ContextCompat.getColor(binding.root.context, R.color.texto_suave)
            )

            binding.root.setOnClickListener { alTocar(gasto) }
        }

        /** Cuándo fue, y el detalle si el usuario escribió uno. */
        private fun armarApoyo(gasto: Gasto): String {
            val cuando = cuandoFue(gasto.fecha)
            return if (gasto.detalle.isBlank()) cuando else "$cuando, ${gasto.detalle}"
        }

        /** Hoy y ayer se nombran así; el resto lleva la fecha. */
        private fun cuandoFue(fecha: Date): String = when (diasDesde(fecha)) {
            0 -> binding.root.context.getString(R.string.hoy)
            1 -> binding.root.context.getString(R.string.ayer)
            else -> if (soloEsteMes) formatoCorto.format(fecha) else formatoConAnio.format(fecha)
        }

        private fun diasDesde(fecha: Date): Int {
            fun aMedianoche(d: Date) = Calendar.getInstance().apply {
                time = d
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val unDia = 24 * 60 * 60 * 1000L
            val diferencia = aMedianoche(Date()).timeInMillis - aMedianoche(fecha).timeInMillis
            return (diferencia / unDia).toInt()
        }
    }
}
