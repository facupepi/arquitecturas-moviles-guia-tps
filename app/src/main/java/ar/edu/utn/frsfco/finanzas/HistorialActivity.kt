package ar.edu.utn.frsfco.finanzas

import android.os.Bundle
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.os.BundleCompat
import androidx.recyclerview.widget.LinearLayoutManager
import ar.edu.utn.frsfco.finanzas.databinding.ActivityHistorialBinding
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.DocumentSnapshot

/**
 * Todos los gastos del tramo, sin recortar.
 *
 * El resumen muestra sólo los últimos y antes no había manera de llegar al resto:
 * los gastos viejos sumaban al total pero no se podían mirar ni corregir. Acá
 * además se pueden buscar por su detalle o por el nombre de la categoría.
 */
class HistorialActivity : PantallaBase() {

    companion object {
        const val EXTRA_PERIODO = "periodo"
        const val EXTRA_CATEGORIA = "categoria"
    }

    private lateinit var binding: ActivityHistorialBinding
    private val gastosRepo = GastosRepositorio()
    private val categoriasRepo = CategoriasRepositorio()
    private val mediosRepo = MediosRepositorio()
    private val escuchas = mutableListOf<ListenerRegistration>()

    private lateinit var adaptador: GastosAdapter
    private val gastos = mutableListOf<Gasto>()
    private var categorias = listOf<Categoria>()
    private var medios = listOf<Medio>()

    private lateinit var periodo: PrincipalActivity.Periodo
    private var categoriaFija: String? = null

    /** Tamaño del lote, fijado al abrir la pantalla para que no cambie entre una página y la siguiente. */
    private val loteHistorial = ParametrosRemotos.tamanoLoteHistorial()
    private var buscado = ""
    private var ultimoDocCursor: DocumentSnapshot? = null
    private var cargandoMas = false
    private var cargandoInicial = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistorialBinding.inflate(layoutInflater)
        setContentView(binding.root)

        periodo = runCatching {
            PrincipalActivity.Periodo.valueOf(intent.getStringExtra(EXTRA_PERIODO).orEmpty())
        }.getOrDefault(PrincipalActivity.Periodo.ESTE_MES)
        categoriaFija = intent.getStringExtra(EXTRA_CATEGORIA)

        Anuncios.instalar(this, binding.anuncios.root, null) { }
        binding.barra.setTitle(Tramos.rotulo(periodo))
        binding.barra.setNavigationOnClickListener { finish() }

        adaptador = GastosAdapter { gasto -> abrirEdicion(gasto) }
        binding.lista.layoutManager = LinearLayoutManager(this)
        binding.lista.adapter = adaptador

        binding.etBuscar.doAfterTextChanged { texto ->
            buscado = texto.trim().lowercase()
            refrescar()
        }

        binding.btnCargarMas.setOnClickListener {
            cargarSiguientePagina()
        }

        // La hoja avisa por acá y no por una función suelta: así el aviso llega
        // igual cuando la pantalla se rehizo mientras la hoja estaba abierta.
        supportFragmentManager.setFragmentResultListener(
            GastoSheet.PEDIDO, this
        ) { _, datos ->
            val borrado = BundleCompat.getSerializable(datos, GastoSheet.BORRADO, Gasto::class.java)
            if (borrado != null) {
                avisarConDeshacer(borrado)
                if (periodo != PrincipalActivity.Periodo.ESTE_MES) {
                    gastos.removeAll { it.id == borrado.id }
                    refrescar()
                }
            } else {
                avisar(datos.getInt(HojaBase.MENSAJE))
                if (periodo != PrincipalActivity.Periodo.ESTE_MES) {
                    cargarPrimeraPagina()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        escuchas += categoriasRepo.escuchar { recibidas ->
            categorias = recibidas
            refrescar()
        }
        // Estrategia de escalabilidad del TP3:
        // 1. ESTE_MES, con o sin categoría fija: escucha en tiempo real acotada al mes en curso.
        // 2. MES_PASADO, ESTE_ANIO y TODO, con o sin categoría: consulta paginada en el servidor
        //    con rango estricto de fechas y cursor (startAfter), evitando traer datos fuera de período
        //    y permitiendo explorar miles de movimientos con el botón 'Cargar más'.
        val cat = categoriaFija
        if (cat != null && periodo == PrincipalActivity.Periodo.ESTE_MES) {
            binding.btnCargarMas.visibility = View.GONE
            val (desde, hasta) = Tramos.rango(periodo)
            escuchas += gastosRepo.escucharPorCategoriaEnRango(
                cat, requireNotNull(desde), requireNotNull(hasta)
            ) { recibidos ->
                gastos.clear()
                gastos.addAll(recibidos)
                refrescar()
            }
        } else if (cat == null && periodo == PrincipalActivity.Periodo.ESTE_MES) {
            binding.btnCargarMas.visibility = View.GONE
            val (desde, hasta) = Tramos.rango(periodo)
            escuchas += gastosRepo.escucharEnRango(
                requireNotNull(desde), requireNotNull(hasta)
            ) { recibidos ->
                gastos.clear()
                gastos.addAll(recibidos)
                refrescar()
            }
        } else {
            cargarPrimeraPagina()
        }
        escuchas += mediosRepo.escuchar { recibidos ->
            medios = recibidos
            refrescar()
        }
    }

    override fun onStop() {
        super.onStop()
        escuchas.forEach { it.remove() }
        escuchas.clear()
    }

    /**
     * Carga el primer lote de 30 movimientos históricos mediante consulta acotada por período
     * y categoría en el servidor de Firestore, admitiendo paginación por cursor.
     */
    private fun cargarPrimeraPagina() {
        val (desde, hasta) = Tramos.rango(periodo)
        ultimoDocCursor = null
        cargandoInicial = true
        binding.btnCargarMas.isEnabled = false
        gastosRepo.consultarPaginado(
            desde = desde,
            hasta = hasta,
            categoriaId = categoriaFija,
            limite = loteHistorial.toLong(),
            ultimoDoc = null,
            alCompletar = { recibidos, nuevoCursor ->
            if (isFinishing || isDestroyed) return@consultarPaginado
            gastos.clear()
            gastos.addAll(recibidos)
            ultimoDocCursor = nuevoCursor
            cargandoInicial = false
            binding.btnCargarMas.isEnabled = true
            binding.btnCargarMas.visibility = if (recibidos.size >= loteHistorial) View.VISIBLE else View.GONE
            refrescar()
        },
            alFallar = {
            if (isFinishing || isDestroyed) return@consultarPaginado
            cargandoInicial = false
            binding.btnCargarMas.isEnabled = true
            binding.btnCargarMas.visibility = View.GONE
            refrescar()
            avisar(R.string.error_cargar_historial)
        }
        )
    }

    /**
     * Obtiene el siguiente lote de 30 registros continuando desde el último DocumentSnapshot (cursor),
     * manteniendo el rango de fechas y filtro de categoría.
     */
    private fun cargarSiguientePagina() {
        val cursor = ultimoDocCursor ?: return
        if (cargandoMas) return
        cargandoMas = true
        binding.btnCargarMas.isEnabled = false
        val (desde, hasta) = Tramos.rango(periodo)
        gastosRepo.consultarPaginado(
            desde = desde,
            hasta = hasta,
            categoriaId = categoriaFija,
            limite = loteHistorial.toLong(),
            ultimoDoc = cursor,
            alCompletar = { recibidos, nuevoCursor ->
            if (isFinishing || isDestroyed) return@consultarPaginado
            cargandoMas = false
            binding.btnCargarMas.isEnabled = true
            gastos.addAll(recibidos.filterNot { nuevo -> gastos.any { it.id == nuevo.id } })
            ultimoDocCursor = nuevoCursor
            binding.btnCargarMas.visibility = if (recibidos.size >= loteHistorial) View.VISIBLE else View.GONE
            refrescar()
        },
            alFallar = {
            if (isFinishing || isDestroyed) return@consultarPaginado
            cargandoMas = false
            binding.btnCargarMas.isEnabled = true
            avisar(R.string.error_cargar_historial)
        }
        )
    }

    private fun refrescar() {
        val porId = categorias.associateBy { it.id }
        val visibles = gastos
            .filter { Tramos.entra(it.fecha, periodo) }
            .filter { categoriaFija == null || it.categoriaId == categoriaFija }
            .filter { coincide(it, porId) }

        adaptador.mostrar(visibles, porId, medios.associateBy { it.id }, soloEsteMes = false)

        binding.tvResumen.text = resources.getQuantityString(
            R.plurals.historial_resumen,
            visibles.size,
            visibles.size,
            Plata.formatear(visibles.sumOf { it.monto })
        )
        binding.vacio.visibility = if (!cargandoInicial && visibles.isEmpty()) View.VISIBLE else View.GONE
    }

    /** Busca en el detalle que escribió el usuario y en el nombre de la categoría. */
    private fun coincide(gasto: Gasto, porId: Map<String, Categoria>): Boolean {
        if (buscado.isBlank()) return true
        val categoria = porId[gasto.categoriaId]?.nombre.orEmpty()
        return gasto.detalle.lowercase().contains(buscado) ||
            categoria.lowercase().contains(buscado)
    }

    private fun abrirEdicion(gasto: Gasto) {
        GastoSheet.editar(gasto, categorias, medios).show(supportFragmentManager, GastoSheet.PEDIDO)
    }

    private fun avisar(mensaje: Int) {
        Snackbar.make(binding.root, mensaje, Snackbar.LENGTH_SHORT).show()
    }

    /** Borrar no se puede deshacer desde el servidor, así que se repone el documento y se reincorpora a la lista. */
    private fun avisarConDeshacer(borrado: Gasto) {
        Snackbar.make(binding.root, R.string.gasto_borrado, Snackbar.LENGTH_LONG)
            .setAction(R.string.deshacer) {
                gastosRepo.restaurar(borrado)
                val entraEnPeriodo = Tramos.entra(borrado.fecha, periodo)
                val coincideCategoria = categoriaFija == null || borrado.categoriaId == categoriaFija
                if (entraEnPeriodo && coincideCategoria && gastos.none { it.id == borrado.id }) {
                    gastos.add(borrado)
                    gastos.sortByDescending { it.fecha }
                    refrescar()
                }
            }
            .setActionTextColor(ContextCompat.getColor(this, R.color.verde_claro))
            .show()
    }
}

/** Atajo para escuchar los cambios de un campo de texto sin escribir el watcher entero. */
private fun android.widget.EditText.doAfterTextChanged(alCambiar: (String) -> Unit) {
    addTextChangedListener(object : android.text.TextWatcher {
        override fun afterTextChanged(s: android.text.Editable?) = alCambiar(s?.toString().orEmpty())
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
    })
}
