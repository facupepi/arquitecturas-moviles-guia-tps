package ar.edu.utn.frsfco.finanzas

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import ar.edu.utn.frsfco.finanzas.databinding.HojaIngresoBinding
import java.util.Date

/**
 * Hoja para registrar un ingreso o corregir uno ya registrado.
 *
 * La fecha se puede cambiar porque el sueldo entra un día y uno lo anota otro, igual
 * que pasa con los gastos.
 */
class IngresoSheet : HojaBase() {

    private var _binding: HojaIngresoBinding? = null
    private val binding get() = _binding!!
    private val repositorio = IngresosRepositorio()

    private var ingreso: Ingreso? = null
    private var fecha: Date = Date()

    companion object {
        const val PEDIDO = "ingreso"

        private const val ARG_INGRESO = "ingreso"
        private const val ESTADO_FECHA = "fecha"

        fun abrir(ingreso: Ingreso?) = IngresoSheet().apply {
            arguments = bundleOf(ARG_INGRESO to ingreso)
        }
    }

    override fun onCreate(estado: Bundle?) {
        super.onCreate(estado)
        ingreso = argumento(ARG_INGRESO, Ingreso::class.java)
        fecha = guardado(estado, ESTADO_FECHA, Date::class.java) ?: ingreso?.fecha ?: Date()
    }

    override fun onCreateView(inflador: LayoutInflater, contenedor: ViewGroup?, estado: Bundle?): View {
        _binding = HojaIngresoBinding.inflate(inflador, contenedor, false)
        return binding.root
    }

    override fun onViewCreated(vista: View, estado: Bundle?) {
        super.onViewCreated(vista, estado)

        CampoMonto.preparar(binding.etMonto)
        binding.tvTitulo.setText(
            if (ingreso == null) R.string.nuevo_ingreso else R.string.editar_ingreso
        )
        // Los campos de texto se rellenan una sola vez: después de un giro vuelven
        // solos con lo que el usuario haya escrito.
        if (estado == null) {
            ingreso?.let {
                CampoMonto.escribir(binding.etMonto, it.monto)
                binding.etDetalle.setText(it.detalle)
            }
        }

        mostrarFecha()
        binding.btnFecha.setOnClickListener { Fechas.elegir(this, fecha, ::cambiarFecha) }
        Fechas.reconectar(this, ::cambiarFecha)
        binding.btnGuardar.setOnClickListener { guardar() }
    }

    private fun cambiarFecha(elegida: Date) {
        fecha = elegida
        mostrarFecha()
    }

    private fun mostrarFecha() {
        binding.btnFecha.text = Fechas.nombrar(requireContext(), fecha)
    }

    private fun guardar() {
        val monto = Plata.leer(binding.etMonto.text.toString())
        val aviso = CampoMonto.error(monto)
        if (monto == null || aviso != null) {
            binding.etMonto.error = getString(aviso ?: R.string.monto_invalido)
            return
        }
        val existente = ingreso
        val armado = Ingreso(
            id = existente?.id.orEmpty(),
            monto = monto,
            detalle = binding.etDetalle.text.toString(),
            fecha = fecha
        )
        if (existente == null) repositorio.agregar(armado) else repositorio.actualizar(armado)
        terminar(PEDIDO, bundleOf(MENSAJE to R.string.ingreso_guardado))
    }

    override fun onSaveInstanceState(estado: Bundle) {
        super.onSaveInstanceState(estado)
        estado.putSerializable(ESTADO_FECHA, fecha)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
