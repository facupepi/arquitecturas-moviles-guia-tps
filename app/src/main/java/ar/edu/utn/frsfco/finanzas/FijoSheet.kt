package ar.edu.utn.frsfco.finanzas

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import ar.edu.utn.frsfco.finanzas.databinding.HojaFijoBinding
import com.google.android.material.chip.Chip

/**
 * Hoja para dar de alta un gasto que se repite todos los meses, o corregir uno.
 *
 * Recibe las categorías con las que se dibujan las fichas: la hoja no las consulta
 * por su cuenta porque la pantalla que la abre ya las tiene escuchando.
 */
class FijoSheet : HojaBase() {

    private var _binding: HojaFijoBinding? = null
    private val binding get() = _binding!!
    private val repositorio = FijosRepositorio()

    private var fijo: GastoFijo? = null
    private var categorias: List<Categoria> = emptyList()
    private var elegidaId: String? = null

    companion object {
        const val PEDIDO = "fijo"

        private const val ARG_FIJO = "fijo"
        private const val ARG_CATEGORIAS = "categorias"
        private const val ESTADO_ELEGIDA = "elegida"

        fun abrir(fijo: GastoFijo?, categorias: List<Categoria>) = FijoSheet().apply {
            arguments = bundleOf(
                ARG_FIJO to fijo,
                ARG_CATEGORIAS to ArrayList(categorias)
            )
        }
    }

    override fun onCreate(estado: Bundle?) {
        super.onCreate(estado)
        fijo = argumento(ARG_FIJO, GastoFijo::class.java)
        categorias = argumento(ARG_CATEGORIAS, ArrayList::class.java)
            ?.filterIsInstance<Categoria>()
            .orEmpty()
        elegidaId = if (estado != null) estado.getString(ESTADO_ELEGIDA) else fijo?.categoriaId
    }

    override fun onCreateView(inflador: LayoutInflater, contenedor: ViewGroup?, estado: Bundle?): View {
        _binding = HojaFijoBinding.inflate(inflador, contenedor, false)
        return binding.root
    }

    override fun onViewCreated(vista: View, estado: Bundle?) {
        super.onViewCreated(vista, estado)

        CampoMonto.preparar(binding.etMonto)
        binding.tvTitulo.setText(if (fijo == null) R.string.nuevo_fijo else R.string.editar_fijo)
        // Los campos de texto se rellenan una sola vez: después de un giro vuelven
        // solos con lo que el usuario haya escrito.
        if (estado == null) {
            fijo?.let {
                binding.etNombre.setText(it.nombre)
                CampoMonto.escribir(binding.etMonto, it.monto)
                binding.etDia.setText(it.diaDelMes.toString())
            }
        }

        armarFichas()
        binding.btnGuardar.setOnClickListener { guardar() }
    }

    /** Una ficha por categoría, con la del gasto ya marcada si se está corrigiendo. */
    private fun armarFichas() {
        binding.grupoCategorias.removeAllViews()
        categorias.forEach { cat ->
            val ficha = Chip(requireContext())
            ficha.text = cat.nombre
            ficha.isCheckable = true
            ficha.isChecked = cat.id == elegidaId
            ficha.chipIcon = ContextCompat.getDrawable(requireContext(), cat.iconoDibujable())
            ficha.chipIconTint = ColorStateList.valueOf(cat.colorEntero())
            ficha.setOnClickListener { elegidaId = cat.id }
            binding.grupoCategorias.addView(ficha)
        }
    }

    /**
     * Valida lo escrito y guarda. Cuando algo falta deja el aviso puesto en el campo
     * que corresponde y la hoja queda abierta para corregirlo.
     */
    private fun guardar() {
        val nombre = binding.etNombre.text.toString().trim()
        val monto = Plata.leer(binding.etMonto.text.toString())
        val dia = binding.etDia.text.toString().toIntOrNull()
        val categoria = categorias.firstOrNull { it.id == elegidaId }

        when {
            nombre.isBlank() -> binding.etNombre.error = getString(R.string.nombre_vacio)
            monto == null || CampoMonto.error(monto) != null ->
                binding.etMonto.error = getString(CampoMonto.error(monto) ?: R.string.monto_invalido)
            dia == null || dia !in 1..31 -> binding.etDia.error = getString(R.string.dia_invalido)
            categoria == null ->
                Toast.makeText(requireContext(), R.string.elegi_categoria, Toast.LENGTH_SHORT).show()
            else -> {
                repositorio.guardar(
                    (fijo ?: GastoFijo()).copy(
                        nombre = nombre,
                        monto = monto,
                        categoriaId = categoria.id,
                        diaDelMes = dia
                    )
                )
                terminar(PEDIDO, bundleOf(MENSAJE to R.string.fijo_guardado))
            }
        }
    }

    override fun onSaveInstanceState(estado: Bundle) {
        super.onSaveInstanceState(estado)
        estado.putString(ESTADO_ELEGIDA, elegidaId)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
