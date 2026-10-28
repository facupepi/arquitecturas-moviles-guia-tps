package ar.edu.utn.frsfco.finanzas

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.os.BundleCompat
import androidx.recyclerview.widget.LinearLayoutManager
import ar.edu.utn.frsfco.finanzas.databinding.ActivityPrincipalBinding
import ar.edu.utn.frsfco.finanzas.databinding.FilaDesgloseBinding
import ar.edu.utn.frsfco.finanzas.databinding.FilaLeyendaBinding
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.ListenerRegistration
import java.util.Calendar
import java.util.Date
import kotlin.math.roundToInt

/**
 * Resumen de gastos.
 *
 * Muestra cuánto se lleva gastado en el tramo de tiempo elegido, en qué se fue y los
 * últimos movimientos. Todo sale de Firestore y se actualiza solo cuando los datos
 * cambian. Si hay ingresos cargados, la cifra grande pasa a ser lo que queda.
 */
class PrincipalActivity : PantallaBase() {

    /** Tramos que se pueden mirar. El histórico completo es `TODO`. */
    enum class Periodo { ESTE_MES, MES_PASADO, ESTE_ANIO, TODO }

    private lateinit var binding: ActivityPrincipalBinding
    private val auth by lazy { FirebaseAuth.getInstance() }
    private val gastosRepo = GastosRepositorio()
    private val ingresosRepo = IngresosRepositorio()
    private val categoriasRepo = CategoriasRepositorio()
    private val mediosRepo = MediosRepositorio()
    private val fijosRepo = FijosRepositorio()

    private lateinit var adaptador: GastosAdapter
    private val escuchas = mutableListOf<ListenerRegistration>()
    private var escuchaGastos: ListenerRegistration? = null
    private var escuchaIngresos: ListenerRegistration? = null

    /** Cambia el filtro por categoría y redibuja con los gastos que ya llegaron. */
    private fun cambiarFiltro(categoria: String?) {
        filtroCategoria = categoria
        refrescar()
    }

    /**
     * Reactiva los datos del resumen según el período activo.
     *
     * Estrategia de escalabilidad del TP3: cada período escucha solo los gastos e ingresos que caen
     * en su rango de fechas, de modo que abrir la pantalla no descarga el historial completo.
     * Únicamente "Todo" necesita la colección entera, porque no tiene rango.
     */
    private fun reiniciarEscuchaGastos() {
        escuchaGastos?.remove()
        escuchas.remove(escuchaGastos)
        escuchaGastos = null
        gastosRepo.olvidarConfirmacion()
        escucharGastosLocal()
    }

    /** Si Firestore rechaza la escucha se deja de mostrar la carga y se avisa, en lugar de esperar para siempre. */
    private fun falloLaEscucha() {
        gastosCargados = periodo
        ingresosCargados = periodo
        avisar(R.string.error_movimientos)
        refrescar()
    }

    private fun escucharGastosLocal() {
        val (desde, hasta) = Tramos.rango(periodo)
        val nueva = if (desde != null && hasta != null) {
            gastosRepo.escucharEnRango(desde, hasta, alCambiar = { recibidos ->
                gastos.clear()
                gastos.addAll(recibidos)
                gastosCargados = periodo
                crearFijosQueFaltan()
                refrescar()
            }, alFallar = ::falloLaEscucha)
        } else {
            gastosRepo.escuchar(alFallar = ::falloLaEscucha) { recibidos ->
                gastos.clear()
                gastos.addAll(recibidos)
                gastosCargados = periodo
                crearFijosQueFaltan()
                refrescar()
            }
        }
        escuchaGastos = nueva
        escuchas += nueva
    }

    /** Ingresos del período: se escuchan por rango de fechas, igual que los gastos. */
    private fun reiniciarEscuchaIngresos() {
        escuchaIngresos?.remove()
        escuchas.remove(escuchaIngresos)
        escuchaIngresos = null
        val (desde, hasta) = Tramos.rango(periodo)
        val nueva = if (desde != null && hasta != null) {
            ingresosRepo.escucharEnRango(desde, hasta, alCambiar = { recibidos ->
                ingresos.clear()
                ingresos.addAll(recibidos)
                ingresosCargados = periodo
                refrescar()
            }, alFallar = ::falloLaEscucha)
        } else {
            ingresosRepo.escuchar(alFallar = ::falloLaEscucha) { recibidos ->
                ingresos.clear()
                ingresos.addAll(recibidos)
                ingresosCargados = periodo
                refrescar()
            }
        }
        escuchaIngresos = nueva
        escuchas += nueva
    }

    private val gastos = mutableListOf<Gasto>()
    private val ingresos = mutableListOf<Ingreso>()
    private var categorias = listOf<Categoria>()
    private var medios = listOf<Medio>()
    private var fijos = listOf<GastoFijo>()

    private var periodo = Periodo.ESTE_MES

    /** Período para el que ya llegó la primera respuesta de gastos e ingresos; mientras no llega se muestra la carga. */
    private var gastosCargados: Periodo? = null
    private var ingresosCargados: Periodo? = null
    private var filtroCategoria: String? = null

    private companion object {
        /** Cuántos gastos se listan acá; el resto se ve en el historial. */
        const val MAXIMO_LISTA = 10

        const val ESTADO_PERIODO = "periodo"
        const val ESTADO_FILTRO = "filtro"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPrincipalBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // El tramo elegido y el filtro por categoría no viven en ninguna vista, así
        // que al girar la pantalla se perdían y el resumen volvía al mes en curso.
        savedInstanceState?.let { estado ->
            periodo = Periodo.valueOf(estado.getString(ESTADO_PERIODO, Periodo.ESTE_MES.name))
            filtroCategoria = estado.getString(ESTADO_FILTRO)
        }

        mostrarCuenta()

        adaptador = GastosAdapter { gasto -> abrirEdicion(gasto) }
        binding.lista.layoutManager = LinearLayoutManager(this)
        binding.lista.adapter = adaptador

        prepararNavegacion(binding.navegacion.barraNavegacion, R.id.navResumen)
        encogerBotonAlBajar()
        binding.btnAgregar.setOnClickListener { abrirCarga() }
        binding.btnVerTodos.setOnClickListener { abrirHistorial() }
        binding.btnQuitarFiltro.setOnClickListener { cambiarFiltro(null) }

        binding.grupoPeriodo.setOnCheckedStateChangeListener { _, elegidos ->
            val nuevo = when (elegidos.firstOrNull()) {
                R.id.chipMesPasado -> Periodo.MES_PASADO
                R.id.chipEsteAnio -> Periodo.ESTE_ANIO
                R.id.chipTodo -> Periodo.TODO
                else -> Periodo.ESTE_MES
            }
            if (nuevo != periodo) {
                periodo = nuevo
                reiniciarEscuchaGastos()
                reiniciarEscuchaIngresos()
                refrescar()
            }
        }

        escucharHoja()

        // La primera vez que entra el usuario se crean las categorías y los medios
        // de pago de arranque, para que pueda registrar un gasto sin configurar nada.
        categoriasRepo.sembrarSiHaceFalta()
        mediosRepo.sembrarSiHaceFalta()
    }

    /**
     * Queda a la espera de lo que decida la hoja de gastos.
     *
     * Pasa por el canal de resultados y no por una función suelta para que el aviso
     * llegue igual cuando la pantalla se rehizo mientras la hoja estaba abierta.
     */
    private fun escucharHoja() {
        supportFragmentManager.setFragmentResultListener(
            GastoSheet.PEDIDO, this
        ) { _, datos ->
            val borrado = BundleCompat.getSerializable(datos, GastoSheet.BORRADO, Gasto::class.java)
            if (borrado != null) {
                avisarConDeshacer(borrado)
            } else {
                avisar(datos.getInt(HojaBase.MENSAJE))
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(ESTADO_PERIODO, periodo.name)
        outState.putString(ESTADO_FILTRO, filtroCategoria)
    }

    override fun onStart() {
        super.onStart()
        escuchas += categoriasRepo.escuchar { recibidas ->
            categorias = recibidas
            refrescar()
        }
        // Desde el primer cuadro se ve el estado de carga, no la tarjeta a medio armar.
        refrescar()
        reiniciarEscuchaGastos()
        reiniciarEscuchaIngresos()
        escuchas += mediosRepo.escuchar { recibidos ->
            medios = recibidos
            refrescar()
        }
        escuchas += fijosRepo.escuchar { recibidos ->
            fijos = recibidos
            crearFijosQueFaltan()
        }
    }

    override fun onStop() {
        super.onStop()
        // Sin esto las escuchas seguirían consumiendo datos con la pantalla cerrada.
        escuchas.forEach { it.remove() }
        escuchas.clear()
        escuchaGastos = null
        escuchaIngresos = null
    }

    /**
     * Mientras se recorre la lista, el botón se achica y deja de tapar el contenido;
     * al volver arriba recupera el texto. Extendido todo el tiempo se comía la
     * leyenda del gráfico.
     */
    private fun encogerBotonAlBajar() {
        binding.desplazable.setOnScrollChangeListener { _, _, y, _, anterior ->
            if (y > anterior && y > 40) binding.btnAgregar.shrink()
            if (y < anterior) binding.btnAgregar.extend()
        }
    }

    /** El alquiler y las expensas se cargan solos cuando llega su día. */
    private fun crearFijosQueFaltan() {
        // La comprobación de materialización compara contra gastos del mes actual.
        // En Mes pasado la lista visible no los contiene y podría duplicarlos.
        // Tampoco antes de que el servidor confirme los gastos: la lista vacía de una
        // instalación nueva haría crear de nuevo los fijos que ya están guardados.
        if (periodo == Periodo.MES_PASADO || fijos.isEmpty() || !gastosRepo.confirmadoPorServidor) return
        FijosRepositorio.materializar(fijos, gastos, gastosRepo)
    }

    private fun mostrarCuenta() {
        val usuario = auth.currentUser
        val nombre = usuario?.displayName?.substringBefore(' ')
        binding.tvNombre.text = if (nombre.isNullOrBlank()) {
            getString(R.string.titulo_resumen)
        } else {
            getString(R.string.saludo, nombre)
        }
        binding.tvCuenta.text = usuario?.email.orEmpty()
    }

    /** Lo que se dibuja: los totales del período y los movimientos que lo componen. */
    private class Vista(
        val total: Double,
        val entrado: Double,
        val cantidad: Int,
        val porCategoria: List<TotalCategoria>,
        val recientes: List<Gasto>
    )

    private fun construirVista(): Vista? {
        // Hasta que Firestore responde por primera vez no se dibuja nada: un vacío falso parpadeaba al entrar.
        if (gastosCargados != periodo || ingresosCargados != periodo) return null
        val delTramo = gastos.filter { Tramos.entra(it.fecha, periodo) }
        val entrado = ingresos.filter { Tramos.entra(it.fecha, periodo) }.sumOf { it.monto }
        val porCategoria = delTramo.groupBy { it.categoriaId }
            .map { (id, suyos) -> TotalCategoria(id, suyos.sumOf { it.monto }, suyos.size) }
        return Vista(delTramo.sumOf { it.monto }, entrado, delTramo.size, porCategoria, delTramo)
    }

    private fun refrescar() {
        val porId = categorias.associateBy { it.id }
        val vista = construirVista()
        if (vista == null) {
            // Esperando la primera respuesta de Firestore: se indica la carga y no se muestra nada a medias.
            binding.tvRotulo.setText(Tramos.rotulo(periodo))
            binding.tvTotal.text = "…"
            binding.tvCantidad.setText(R.string.cargando_resumen)
            binding.bloqueDesglose.visibility = View.GONE
            binding.vacio.visibility = View.GONE
            binding.tarjetaLista.visibility = View.GONE
            binding.tituloLista.visibility = View.GONE
            binding.bloqueGrafico.visibility = View.GONE
            return
        }

        mostrarCifra(vista.total, vista.entrado, vista.cantidad)
        mostrarDesglose(vista.total, vista.entrado)

        val visibles = filtroCategoria?.let { id -> vista.recientes.filter { it.categoriaId == id } }
            ?: vista.recientes
        val cantidadVisible = filtroCategoria?.let { id ->
            vista.porCategoria.firstOrNull { it.categoriaId == id }?.cantidad ?: 0
        } ?: vista.cantidad
        mostrarLista(visibles, cantidadVisible, porId, medios.associateBy { it.id })

        val hay = vista.cantidad > 0
        binding.vacio.visibility = if (hay) View.GONE else View.VISIBLE
        binding.tarjetaLista.visibility = if (hay) View.VISIBLE else View.GONE
        binding.tituloLista.visibility = if (hay) View.VISIBLE else View.GONE
        binding.bloqueGrafico.visibility = if (hay) View.VISIBLE else View.GONE

        if (hay) dibujarGrafico(vista.porCategoria, porId, vista.total) else explicarVacio(vista.cantidad)
    }

    /**
     * Con ingresos cargados el número grande es el saldo; sin ellos, lo gastado.
     * Preguntar cuánto queda fue lo primero que hicieron todos los que la probaron,
     * y la aplicación sólo sabía contestar cuánto se había ido.
     */
    private fun mostrarCifra(total: Double, entrado: Double, cuantos: Int) {
        val hayIngresos = entrado > 0
        binding.tvRotulo.setText(
            if (hayIngresos) R.string.saldo_disponible else Tramos.rotulo(periodo)
        )
        binding.tvTotal.text = Plata.formatear(if (hayIngresos) entrado - total else total)
        binding.tvCantidad.text =
            resources.getQuantityString(R.plurals.cantidad_gastos, cuantos, cuantos)
    }

    /**
     * El desglose de la tarjeta, una línea por concepto.
     *
     * Antes era un solo renglón con los importes separados por puntos medios y costaba
     * leerlo de un vistazo; ahora cada dato tiene su ícono y su renglón. El reparto por
     * medio de pago se ve en el historial, donde cada movimiento dice con qué se pagó.
     */
    private fun mostrarDesglose(total: Double, entrado: Double) {
        binding.bloqueDesglose.visibility = if (entrado > 0) View.VISIBLE else View.GONE
        if (entrado <= 0) return

        llenarFila(binding.filaIngresos, R.drawable.ic_entra, R.string.rotulo_ingresos, entrado)
        llenarFila(binding.filaGastos, R.drawable.ic_sale, R.string.rotulo_gastos, total)
    }

    private fun llenarFila(fila: FilaDesgloseBinding, icono: Int, concepto: Int, monto: Double) {
        fila.icono.setImageResource(icono)
        fila.tvConcepto.setText(concepto)
        fila.tvMonto.text = Plata.formatear(monto)
    }

    private fun mostrarLista(
        visibles: List<Gasto>,
        cantidadVisible: Int,
        porId: Map<String, Categoria>,
        mediosPorId: Map<String, Medio>
    ) {
        adaptador.mostrar(
            visibles.take(MAXIMO_LISTA), porId, mediosPorId, periodo == Periodo.ESTE_MES
        )

        val filtrada = filtroCategoria?.let { porId[it] }
        binding.tvTituloLista.text = filtrada?.nombre?.uppercase()
            ?: getString(R.string.ultimos_gastos)
        binding.btnQuitarFiltro.visibility = if (filtrada != null) View.VISIBLE else View.GONE

        val hayMas = cantidadVisible > MAXIMO_LISTA
        binding.btnVerTodos.visibility = if (hayMas) View.VISIBLE else View.GONE
        if (hayMas) {
            binding.btnVerTodos.text =
                resources.getQuantityString(R.plurals.ver_todos, cantidadVisible, cantidadVisible)
        }
    }

    /** Reparte el total del tramo entre las categorías y arma el anillo con su leyenda. */
    private fun dibujarGrafico(totales: List<TotalCategoria>, porId: Map<String, Categoria>, total: Double) {
        val porCategoria = totales
            .map { (porId[it.categoriaId] ?: Categoria.desconocida()) to it.total }
            .sortedByDescending { it.second }

        binding.grafico.mostrar(
            porCategoria.map { (cat, monto) -> GraficoAnillo.Porcion(cat.colorEntero(), monto) }
        )

        // Van todas: si alguna quedara afuera, su porción del anillo no tendría explicación.
        binding.leyenda.removeAllViews()
        porCategoria.forEach { (cat, monto) ->
            val fila = FilaLeyendaBinding.inflate(LayoutInflater.from(this), binding.leyenda, false)
            val punto = fila.punto.background.mutate()
            DrawableCompat.setTint(punto, cat.colorEntero())
            fila.punto.background = punto
            fila.tvNombre.text = cat.nombre
            fila.tvPorcentaje.text = getString(
                R.string.porcentaje, (monto / total * 100).roundToInt()
            )
            fila.tvMonto.text = Plata.formatear(monto)

            // Tocar una categoría deja en la lista sólo sus gastos.
            if (cat.id.isNotBlank()) {
                fila.root.setOnClickListener {
                    cambiarFiltro(if (filtroCategoria == cat.id) null else cat.id)
                }
            }
            if (cat.id == filtroCategoria) {
                fila.root.backgroundTintList = ColorStateList.valueOf(cat.colorSuave())
            }
            binding.leyenda.addView(fila.root)
        }
    }

    /** El aviso cambia según sea la primera vez o un tramo sin movimientos. */
    private fun explicarVacio(cantidad: Int) {
        // Mes pasado solo carga ese mes: que esté vacío no significa que la cuenta lo esté.
        val recienEmpieza = cantidad == 0 && periodo != Periodo.MES_PASADO
        binding.tvVacioTitulo.setText(
            if (recienEmpieza) R.string.chanchito_titulo else R.string.sin_periodo_titulo
        )
        binding.tvVacioBajada.setText(
            if (recienEmpieza) R.string.chanchito_bajada else R.string.sin_periodo_bajada
        )
    }

    private fun abrirCarga() {
        if (medios.isEmpty()) {
            avisar(R.string.sin_medios_aviso)
            return
        }
        GastoSheet.nuevo(categorias, medios).show(supportFragmentManager, GastoSheet.PEDIDO)
    }

    private fun abrirEdicion(gasto: Gasto) {
        GastoSheet.editar(gasto, categorias, medios).show(supportFragmentManager, GastoSheet.PEDIDO)
    }

    private fun abrirHistorial() {
        startActivity(
            Intent(this, HistorialActivity::class.java)
                .putExtra(HistorialActivity.EXTRA_PERIODO, periodo.name)
                .putExtra(HistorialActivity.EXTRA_CATEGORIA, filtroCategoria)
        )
    }

    private fun avisar(mensaje: Int) {
        Snackbar.make(binding.root, mensaje, Snackbar.LENGTH_SHORT)
            .setAnchorView(binding.btnAgregar)
            .show()
    }

    /** Borrar no se puede deshacer desde el servidor, así que se repone el documento. */
    private fun avisarConDeshacer(borrado: Gasto) {
        Snackbar.make(binding.root, R.string.gasto_borrado, Snackbar.LENGTH_LONG)
            .setAnchorView(binding.btnAgregar)
            .setAction(R.string.deshacer) {
                gastosRepo.restaurar(borrado)
            }
            .setActionTextColor(ContextCompat.getColor(this, R.color.verde_claro))
            .show()
    }
}

/** Decide qué entra en cada tramo de tiempo y cómo se llama. */
object Tramos {

    fun inicioDeMes(fecha: Date = Date()): Date {
        val c = Calendar.getInstance().apply {
            time = fecha
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return c.time
    }

    /**
     * Devuelve el rango [desde, hasta) correspondiente al período para consultar en Firestore.
     * Si un extremo es null, no tiene cota inferior o superior en la consulta.
     */
    fun rango(periodo: PrincipalActivity.Periodo): Pair<Date?, Date?> = when (periodo) {
        PrincipalActivity.Periodo.ESTE_MES -> {
            val inicio = inicioDeMes()
            val fin = Calendar.getInstance().apply {
                time = inicio
                add(Calendar.MONTH, 1)
            }.time
            Pair(inicio, fin)
        }
        PrincipalActivity.Periodo.MES_PASADO -> {
            val inicioMesPasado = Calendar.getInstance().apply {
                add(Calendar.MONTH, -1)
                set(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.time
            Pair(inicioMesPasado, inicioDeMes())
        }
        PrincipalActivity.Periodo.ESTE_ANIO -> {
            val inicioAnio = Calendar.getInstance().apply {
                set(Calendar.DAY_OF_YEAR, 1)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.time
            val inicioProximoAnio = Calendar.getInstance().apply {
                time = inicioAnio
                add(Calendar.YEAR, 1)
            }.time
            Pair(inicioAnio, inicioProximoAnio)
        }
        PrincipalActivity.Periodo.TODO -> Pair(null, null)
    }

    fun entra(fecha: Date, periodo: PrincipalActivity.Periodo): Boolean {
        val cuando = Calendar.getInstance().apply { time = fecha }
        val hoy = Calendar.getInstance()
        return when (periodo) {
            PrincipalActivity.Periodo.ESTE_MES -> mismoMes(cuando, hoy)
            PrincipalActivity.Periodo.MES_PASADO -> mismoMes(
                cuando, Calendar.getInstance().apply { add(Calendar.MONTH, -1) }
            )
            PrincipalActivity.Periodo.ESTE_ANIO ->
                cuando.get(Calendar.YEAR) == hoy.get(Calendar.YEAR)
            PrincipalActivity.Periodo.TODO -> true
        }
    }

    fun rotulo(periodo: PrincipalActivity.Periodo): Int = when (periodo) {
        PrincipalActivity.Periodo.ESTE_MES -> R.string.gastado_este_mes
        PrincipalActivity.Periodo.MES_PASADO -> R.string.gastado_mes_pasado
        PrincipalActivity.Periodo.ESTE_ANIO -> R.string.gastado_este_anio
        PrincipalActivity.Periodo.TODO -> R.string.gastado_total
    }

    private fun mismoMes(una: Calendar, otra: Calendar) =
        una.get(Calendar.YEAR) == otra.get(Calendar.YEAR) &&
            una.get(Calendar.MONTH) == otra.get(Calendar.MONTH)
}

/** Total y cantidad de gastos de una categoría dentro del período que se está mirando. */
data class TotalCategoria(val categoriaId: String, val total: Double, val cantidad: Int)
