package ar.edu.utn.frsfco.finanzas

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import ar.edu.utn.frsfco.finanzas.databinding.HojaGastoBinding
import java.util.Date

/**
 * Hoja para cargar un gasto, o para corregir uno ya cargado.
 *
 * Aparece sobre el resumen en lugar de abrir otra pantalla, para que anotar un gasto
 * cueste lo menos posible. Cuando recibe un gasto existente pasa a modo edición y
 * suma el botón de borrar.
 */
class GastoSheet : HojaBase() {

    private var _binding: HojaGastoBinding? = null
    private val binding get() = _binding!!
    private val repositorio = GastosRepositorio()

    private var categorias: List<Categoria> = emptyList()
    private var medios: List<Medio> = emptyList()
    private var gasto: Gasto? = null
    private var elegidaId: String? = null
    private var medioId: String? = null
    private var fecha: Date = Date()

    companion object {
        /** Nombre del canal por el que la hoja avisa qué hizo. */
        const val PEDIDO = "gasto"

        /** Gasto que el usuario borró, para poder reponerlo si se arrepiente. */
        const val BORRADO = "borrado"

        private const val CONFIRMAR_BORRADO = "confirmar_borrado"
        private const val ARG_CATEGORIAS = "categorias"
        private const val ARG_MEDIOS = "medios"
        private const val ARG_GASTO = "gasto"
        private const val ESTADO_ELEGIDA = "elegida"
        private const val ESTADO_MEDIO = "medio"
        private const val ESTADO_FECHA = "fecha"

        fun nuevo(categorias: List<Categoria>, medios: List<Medio>) =
            crear(null, categorias, medios)

        fun editar(gasto: Gasto, categorias: List<Categoria>, medios: List<Medio>) =
            crear(gasto, categorias, medios)

        private fun crear(
            gasto: Gasto?,
            categorias: List<Categoria>,
            medios: List<Medio>
        ) = GastoSheet().apply {
            arguments = bundleOf(
                ARG_CATEGORIAS to ArrayList(categorias),
                ARG_MEDIOS to ArrayList(medios),
                ARG_GASTO to gasto
            )
        }
    }

    override fun onCreate(estado: Bundle?) {
        super.onCreate(estado)
        categorias = argumento(ARG_CATEGORIAS, ArrayList::class.java)
            ?.filterIsInstance<Categoria>()
            .orEmpty()
        medios = argumento(ARG_MEDIOS, ArrayList::class.java)
            ?.filterIsInstance<Medio>()
            .orEmpty()
        gasto = argumento(ARG_GASTO, Gasto::class.java)

        // Después de un giro vale lo que el usuario había elegido; la primera vez, lo
        // que traía el gasto. En un gasto nuevo no queda ninguna categoría marcada:
        // antes se marcaba la primera y la gente cargaba todo en Comida sin darse cuenta.
        elegidaId =
            if (estado != null) estado.getString(ESTADO_ELEGIDA) else gasto?.categoriaId
        // El medio sí viene marcado de entrada: casi todos los gastos son del primero
        // de la lista y obligar a elegirlo cada vez sumaba un toque a la carga.
        medioId = when {
            estado != null -> estado.getString(ESTADO_MEDIO)
            gasto != null -> gasto?.medioId
            else -> medios.firstOrNull()?.id
        }
        fecha = guardado(estado, ESTADO_FECHA, Date::class.java) ?: gasto?.fecha ?: Date()
    }

    override fun onCreateView(inflador: LayoutInflater, contenedor: ViewGroup?, estado: Bundle?): View {
        _binding = HojaGastoBinding.inflate(inflador, contenedor, false)
        return binding.root
    }

    override fun onViewCreated(vista: View, estado: Bundle?) {
        super.onViewCreated(vista, estado)

        CampoMonto.preparar(binding.etMonto)
        armarCategorias()
        armarMedios()
        mostrarFecha()
        if (estado == null) completarSiEsEdicion() else tituloYBorrado()

        binding.btnFecha.setOnClickListener { Fechas.elegir(this, fecha, ::cambiarFecha) }
        Fechas.reconectar(this, ::cambiarFecha)

        binding.btnGuardar.setOnClickListener { guardar() }
        binding.btnBorrar.setOnClickListener { confirmarBorrado() }
        childFragmentManager.setFragmentResultListener(
            CONFIRMAR_BORRADO, viewLifecycleOwner
        ) { _, _ -> borrar() }

        // En un gasto nuevo lo primero es el monto, así que el teclado sube solo.
        // Al editar no: el teclado tapaba el botón de borrar y las categorías.
        // ADJUST_RESIZE figura como obsoleto desde API 30, pero en una hoja como
        // esta sigue siendo la forma directa de que el teclado empuje el contenido.
        val esNuevo = gasto == null
        if (esNuevo && estado == null) binding.etMonto.requestFocus()
        dialog?.window?.setSoftInputMode(
            (if (esNuevo) WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
            else WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN) or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
    }

    /**
     * Copia el gasto a los campos. Sólo la primera vez: después de un giro los campos
     * ya vuelven con lo que el usuario había escrito y volver a copiarlos lo borraría.
     */
    private fun completarSiEsEdicion() {
        val g = gasto ?: return
        tituloYBorrado()
        CampoMonto.escribir(binding.etMonto, g.monto)
        binding.etDetalle.setText(g.detalle)
    }

    /** El título y el botón de borrar dependen del modo, no de lo que se haya escrito. */
    private fun tituloYBorrado() {
        if (gasto == null) return
        binding.tvTitulo.setText(R.string.editar_gasto)
        binding.btnBorrar.visibility = View.VISIBLE
    }

    private fun cambiarFecha(elegida: Date) {
        fecha = elegida
        mostrarFecha()
    }

    private fun mostrarFecha() {
        binding.btnFecha.text = Fechas.nombrar(requireContext(), fecha)
    }

    private fun armarCategorias() =
        Fichas.armar(binding.grupoCategorias, categorias, elegidaId) {
            elegidaId = it.id
            Fichas.repintar(binding.grupoCategorias, categorias, elegidaId)
        }

    private fun armarMedios() =
        Fichas.armar(binding.grupoMedios, medios, medioId) {
            medioId = it.id
            Fichas.repintar(binding.grupoMedios, medios, medioId)
        }

    private fun guardar() {
        val monto = Plata.leer(binding.etMonto.text.toString())
        val aviso = CampoMonto.error(monto)
        if (monto == null || aviso != null) {
            binding.etMonto.error = getString(aviso ?: R.string.monto_invalido)
            return
        }
        val categoria = categorias.firstOrNull { it.id == elegidaId }
        if (categoria == null) {
            avisar(R.string.elegi_categoria)
            return
        }

        val existente = gasto
        val armado = Gasto(
            id = existente?.id.orEmpty(),
            monto = monto,
            categoriaId = categoria.id,
            detalle = binding.etDetalle.text.toString(),
            fecha = fecha,
            medioId = medioId.orEmpty(),
            fijoId = existente?.fijoId.orEmpty()
        )

        // Firestore guarda primero en el teléfono, así que la hoja se cierra en el
        // acto. Esperar la confirmación del servidor la dejaba trabada sin señal.
        if (existente == null) repositorio.agregar(armado) else repositorio.actualizar(armado)

        ocultarTeclado()
        terminar(
            PEDIDO,
            bundleOf(
                MENSAJE to
                    if (existente == null) R.string.gasto_guardado else R.string.gasto_actualizado
            )
        )
    }

    private fun confirmarBorrado() {
        if (gasto == null) return
        Confirmacion.pedir(
            pedido = CONFIRMAR_BORRADO,
            titulo = getString(R.string.borrar_gasto),
            aviso = R.string.borrar_gasto_aviso,
            accion = R.string.borrar
        ).show(childFragmentManager, CONFIRMAR_BORRADO)
    }

    private fun borrar() {
        val existente = gasto ?: return
        // Si lo creó un recurrente, se anota antes de borrar para que no se genere de nuevo solo.
        if (existente.fijoId.isNotBlank() && FijosRepositorio.esDeEsteMes(existente.fecha, java.util.Calendar.getInstance())) {
            FijosRepositorio.omitirAhora(existente.fijoId)
            FijosRepositorio().omitirEsteMes(existente.fijoId)
        }
        repositorio.borrar(existente.id)
        ocultarTeclado()
        terminar(PEDIDO, bundleOf(MENSAJE to R.string.gasto_borrado, BORRADO to existente))
    }

    private fun ocultarTeclado() {
        val servicio = requireContext().getSystemService(InputMethodManager::class.java)
        servicio?.hideSoftInputFromWindow(binding.root.windowToken, 0)
    }

    private fun avisar(texto: Int) {
        Toast.makeText(requireContext(), texto, Toast.LENGTH_SHORT).show()
    }

    override fun onSaveInstanceState(estado: Bundle) {
        super.onSaveInstanceState(estado)
        // El monto y el detalle los guarda cada campo de texto por su cuenta; la
        // categoría y la fecha no viven en ninguna vista, así que van acá.
        estado.putString(ESTADO_ELEGIDA, elegidaId)
        estado.putString(ESTADO_MEDIO, medioId)
        estado.putSerializable(ESTADO_FECHA, fecha)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
