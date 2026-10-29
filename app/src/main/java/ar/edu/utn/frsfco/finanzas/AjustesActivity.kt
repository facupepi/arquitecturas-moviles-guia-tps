package ar.edu.utn.frsfco.finanzas

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import ar.edu.utn.frsfco.finanzas.databinding.ActivityAjustesBinding
import ar.edu.utn.frsfco.finanzas.databinding.FilaFijoBinding
import ar.edu.utn.frsfco.finanzas.databinding.FilaIngresoBinding
import ar.edu.utn.frsfco.finanzas.databinding.FilaMedioBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat

/**
 * Lo que no se toca todos los días.
 *
 * Junta el sueldo y demás ingresos, los gastos que se repiten solos y la copia de
 * los datos. También vive acá el cierre de sesión, que antes
 * estaba pegado al botón de categorías y se tocaba sin querer.
 */
class AjustesActivity : PantallaBase() {

    private lateinit var binding: ActivityAjustesBinding
    private val fijosRepo = FijosRepositorio()
    private val ingresosRepo = IngresosRepositorio()
    private val categoriasRepo = CategoriasRepositorio()
    private val mediosRepo = MediosRepositorio()
    private val gastosRepo = GastosRepositorio()
    private val acceso by lazy { AccesoGoogle(this) }
    private val escuchas = mutableListOf<ListenerRegistration>()

    private val formatoFecha = SimpleDateFormat("d 'de' MMMM", Idioma.español)

    private var fijos = listOf<GastoFijo>()
    private var categorias = listOf<Categoria>()
    private var medios = listOf<Medio>()
    private val ingresos = mutableListOf<Ingreso>()

    private companion object {
        const val CONFIRMAR_SALIDA = "confirmar_salida"
        const val CONFIRMAR_DEPURACION = "confirmar_depuracion"
    }

    private val pedirPermiso = registerForActivityResult(ActivityResultContracts.RequestPermission()) { concedido ->
        Avisos.evento(this, "permiso_notificaciones", "resultado", if (concedido) "concedido" else "rechazado")
        if (!concedido) abrirAjustesDelSistema()
        mostrarAvisos()
        Temas.sincronizar(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAjustesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prepararNavegacion(binding.navegacion.barraNavegacion, R.id.navAjustes)
        binding.btnNuevoFijo.setOnClickListener { abrirHojaFijo(null) }
        binding.btnNuevoIngreso.setOnClickListener { abrirHojaIngreso(null) }
        binding.btnNuevoMedio.setOnClickListener { abrirHojaMedio(null) }
        binding.btnExportar.setOnClickListener { exportar() }
        binding.btnSalir.setOnClickListener { confirmarSalida() }
        prepararAvisos()
        binding.btnPlanes.setOnClickListener { PlanesSheet().show(supportFragmentManager, "planes") }
        binding.btnAdministrar.setOnClickListener { abrirSuscripciones() }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Suscripcion.esPlus.collect { plus ->
                    binding.tvPlanEstado.setText(if (plus) R.string.plan_plus_ajustes else R.string.plan_gratuito)
                    binding.btnPlanes.visibility = if (plus) View.GONE else View.VISIBLE
                    binding.btnAdministrar.visibility = if (plus) View.VISIBLE else View.GONE
                }
            }
        }
        supportFragmentManager.setFragmentResultListener(PlanesSheet.RESULTADO, this) { _, datos ->
            datos.getString(HojaBase.MENSAJE)?.let { Snackbar.make(binding.root, it, Snackbar.LENGTH_LONG).show() }
        }
        binding.btnVincular.setOnClickListener { vincularConGoogle() }

        escucharHojas()

        // Quien está probando sin cuenta tiene que poder pasarse a una de verdad
        // desde acá. Antes la única salida era cerrar sesión, y ahí perdía todo.
        binding.tarjetaInvitado.visibility =
            if (esInvitado()) View.VISIBLE else View.GONE
    }

    /**
     * Queda a la espera de lo que decidan las hojas y la pregunta de salida.
     *
     * Pasa por el canal de resultados y no por una función suelta para que la
     * respuesta llegue igual cuando la pantalla se rehizo mientras estaban abiertas.
     */
    private fun escucharHojas() {
        listOf(IngresoSheet.PEDIDO, FijoSheet.PEDIDO, MedioSheet.PEDIDO).forEach { pedido ->
            supportFragmentManager.setFragmentResultListener(pedido, this) { _, datos ->
                avisar(datos.getInt(HojaBase.MENSAJE))
            }
        }
        supportFragmentManager.setFragmentResultListener(CONFIRMAR_SALIDA, this) { _, _ ->
            cerrarSesion()
        }
        supportFragmentManager.setFragmentResultListener(CONFIRMAR_DEPURACION, this) { _, datos ->
            val ids = datos.getStringArrayList("ids") ?: return@setFragmentResultListener
            gastosRepo.borrarLote(ids) {
                avisar(R.string.movimientos_depurados)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // El permiso puede haber cambiado desde los ajustes del sistema.
        mostrarAvisos()
        escuchas += fijosRepo.escuchar { recibidos ->
            fijos = recibidos
            dibujarFijos()
        }
        escuchas += categoriasRepo.escuchar { recibidas ->
            categorias = recibidas
            dibujarFijos()
        }
        escuchas += ingresosRepo.escucharUltimos { recibidos ->
            ingresos.clear()
            ingresos.addAll(recibidos)
            dibujarIngresos()
        }
        escuchas += mediosRepo.escuchar { recibidos ->
            medios = recibidos
            dibujarMedios()
        }
    }

    override fun onStop() {
        super.onStop()
        escuchas.forEach { it.remove() }
        escuchas.clear()
    }

    // ---------------------------------------------------------------- ingresos

    private fun dibujarIngresos() {
        binding.listaIngresos.removeAllViews()
        binding.vacioIngresos.visibility = if (ingresos.isEmpty()) View.VISIBLE else View.GONE

        ingresos.take(12).forEach { ingreso ->
            val fila = FilaIngresoBinding.inflate(
                LayoutInflater.from(this), binding.listaIngresos, false
            )
            fila.tvDetalle.text = ingreso.detalle.ifBlank { getString(R.string.ingreso) }
            fila.tvFecha.text = formatoFecha.format(ingreso.fecha)
            fila.tvMonto.text = Plata.formatear(ingreso.monto)
            fila.root.setOnClickListener { abrirHojaIngreso(ingreso) }
            fila.btnBorrar.setOnClickListener {
                confirmarBorrado(ingreso.detalle.ifBlank { getString(R.string.ingreso) }, R.string.borrar_ingreso_aviso) {
                    ingresosRepo.borrar(ingreso.id)
                    avisar(R.string.ingreso_borrado)
                }
            }
            binding.listaIngresos.addView(fila.root)
        }
    }

    /** Igual que en Categorías: borrar es destructivo, así que se pide confirmación y el botón va en rojo. */
    private fun confirmarBorrado(titulo: String, aviso: Int, accion: () -> Unit) {
        val dialogo = MaterialAlertDialogBuilder(this)
            .setTitle(titulo)
            .setMessage(aviso)
            .setNegativeButton(R.string.cancelar, null)
            .setPositiveButton(R.string.borrar) { _, _ -> accion() }
            .show()
        dialogo.getButton(AlertDialog.BUTTON_POSITIVE)
            .setTextColor(ContextCompat.getColor(this, R.color.rojo))
    }

    private fun abrirHojaIngreso(ingreso: Ingreso?) {
        IngresoSheet.abrir(ingreso).show(supportFragmentManager, IngresoSheet.PEDIDO)
    }

    // --------------------------------------------------------- medios de pago

    private fun dibujarMedios() {
        binding.listaMedios.removeAllViews()
        binding.vacioMedios.visibility = if (medios.isEmpty()) View.VISIBLE else View.GONE

        medios.forEach { medio ->
            val fila = FilaMedioBinding.inflate(
                LayoutInflater.from(this), binding.listaMedios, false
            )
            fila.icono.setImageResource(medio.iconoDibujable())
            fila.icono.imageTintList = ColorStateList.valueOf(medio.colorEntero())
            fila.fondoIcono.setCardBackgroundColor(medio.colorSuave())
            fila.tvNombre.text = medio.nombre
            fila.root.setOnClickListener { abrirHojaMedio(medio) }
            fila.btnBorrar.setOnClickListener {
                confirmarBorrado(medio.nombre, R.string.borrar_medio_aviso) {
                    mediosRepo.borrar(medio.id)
                    avisar(R.string.medio_borrado)
                }
            }
            binding.listaMedios.addView(fila.root)
        }
    }

    private fun abrirHojaMedio(medio: Medio?) {
        MedioSheet.abrir(medio, medios.size).show(supportFragmentManager, MedioSheet.PEDIDO)
    }

    // ----------------------------------------------------------- gastos fijos

    private fun dibujarFijos() {
        binding.listaFijos.removeAllViews()
        binding.vacioFijos.visibility = if (fijos.isEmpty()) View.VISIBLE else View.GONE
        val porId = categorias.associateBy { it.id }

        fijos.forEach { fijo ->
            val fila = FilaFijoBinding.inflate(LayoutInflater.from(this), binding.listaFijos, false)
            val categoria = porId[fijo.categoriaId] ?: Categoria.desconocida()
            fila.icono.setImageResource(categoria.iconoDibujable())
            fila.icono.imageTintList =
                ColorStateList.valueOf(categoria.colorEntero())
            fila.fondoIcono.setCardBackgroundColor(categoria.colorSuave())
            fila.tvNombre.text = fijo.nombre
            fila.tvDia.text = getString(R.string.todos_los_dias, fijo.diaDelMes)
            fila.tvMonto.text = Plata.formatear(fijo.monto)
            fila.root.setOnClickListener { abrirHojaFijo(fijo) }
            fila.btnBorrar.setOnClickListener {
                confirmarBorrado(fijo.nombre, R.string.borrar_fijo_aviso) {
                    fijosRepo.borrar(fijo.id)
                    avisar(R.string.fijo_borrado)
                }
            }
            binding.listaFijos.addView(fila.root)
        }
    }

    private fun abrirHojaFijo(fijo: GastoFijo?) {
        if (categorias.isEmpty()) {
            avisar(R.string.sin_categorias)
            return
        }
        FijoSheet.abrir(fijo, categorias).show(supportFragmentManager, FijoSheet.PEDIDO)
    }

    // -------------------------------------------------------------- exportar

    /**
     * Consulta bajo demanda todos los gastos, arma una planilla y la manda a donde el usuario quiera.
     * Al no escuchar la colección en tiempo real desde el inicio de la pantalla, evita lecturas masivas.
     */
    /** Una suscripción se cancela en la tienda: se abre la página de suscripciones de Google Play. */
    private fun abrirSuscripciones() {
        val direccion = "https://play.google.com/store/account/subscriptions?package=$packageName"
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(direccion)))
        } catch (e: Exception) {
            Snackbar.make(binding.root, R.string.administrar_sin_tienda, Snackbar.LENGTH_LONG).show()
        }
    }

    private fun exportar() {
        binding.btnExportar.isEnabled = false
        gastosRepo.obtenerTodos(
            alCompletar = { lista ->
                binding.btnExportar.isEnabled = true
                if (lista.isEmpty()) {
                    avisar(R.string.sin_gastos_exportar)
                    return@obtenerTodos
                }
                compartirPlanilla(lista)
            },
            alFallar = {
                binding.btnExportar.isEnabled = true
                avisar(R.string.sin_gastos_exportar)
            }
        )
    }

    private fun compartirPlanilla(lista: List<Gasto>) {
        val porId = categorias.associateBy { it.id }
        val mediosPorId = medios.associateBy { it.id }
        val formato = SimpleDateFormat("yyyy-MM-dd", Idioma.español)
        val planilla = buildString {
            appendLine("fecha;monto;categoria;medio;detalle")
            lista.forEach { gasto ->
                val categoria = porId[gasto.categoriaId]?.nombre ?: ""
                val medio = mediosPorId[gasto.medioId]?.nombre.orEmpty()
                // El punto y coma separa columnas, así que no puede quedar en el texto.
                val detalle = gasto.detalle.replace(';', ',')
                appendLine(
                    "${formato.format(gasto.fecha)};${paraPlanilla(gasto.monto)};" +
                        "$categoria;$medio;$detalle"
                )
            }
        }

        val envio = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.exportar_asunto))
            putExtra(Intent.EXTRA_TEXT, planilla)
        }
        startActivity(Intent.createChooser(envio, getString(R.string.exportar)))

        if (lista.isNotEmpty()) {
            Confirmacion.pedir(
                pedido = CONFIRMAR_DEPURACION,
                titulo = getString(R.string.depurar_titulo),
                aviso = R.string.depurar_aviso,
                accion = R.string.btn_depurar,
                datos = androidx.core.os.bundleOf("ids" to ArrayList(lista.map { it.id }))
            ).show(supportFragmentManager, CONFIRMAR_DEPURACION)
        }
    }

    /**
     * Escribe el importe como lo espera una planilla en español, con coma decimal y
     * sin decimales cuando son cero. Sin esto salía "6500.0" y quedaba como texto.
     */
    private fun paraPlanilla(monto: Double): String =
        if (monto % 1.0 == 0.0) monto.toLong().toString()
        else String.format(Idioma.español, "%.2f", monto)

    // ------------------------------------------------------- entrar con cuenta

    private fun esInvitado() = FirebaseAuth.getInstance().currentUser?.isAnonymous == true

    /** Suma una cuenta de Google a la sesión de invitado, conservando lo cargado. */
    private fun vincularConGoogle() {
        mostrarEsperaVinculo(true)
        lifecycleScope.launch {
            try {
                val resultado = acceso.entrar()
                avisar(
                    if (resultado == AccesoGoogle.Resultado.YA_EXISTIA) {
                        R.string.cuenta_ya_existia
                    } else {
                        R.string.cuenta_vinculada
                    }
                )
                // La pantalla anterior tiene que volver a leer, ahora con otro usuario.
                startActivity(
                    Intent(this@AjustesActivity, PrincipalActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
                finish()
            } catch (e: GetCredentialCancellationException) {
                mostrarEsperaVinculo(false)
            } catch (e: Exception) {
                mostrarEsperaVinculo(false)
                avisar(R.string.error_entrada)
            }
        }
    }

    private fun mostrarEsperaVinculo(esperando: Boolean) {
        binding.progresoVincular.visibility = if (esperando) View.VISIBLE else View.GONE
        binding.btnVincular.isEnabled = !esperando
        binding.btnVincular.text = if (esperando) "" else getString(R.string.btn_entrar)
        binding.btnVincular.icon = if (esperando) {
            null
        } else {
            ContextCompat.getDrawable(this, R.drawable.ic_google)
        }
    }

    // ----------------------------------------------------------- cerrar sesión

    /** Interruptores de cada aviso. Cada cambio reprograma las tareas en el teléfono. */
    private fun prepararAvisos() {
        binding.swRecordatorio.setOnCheckedChangeListener { _, activo ->
            if (activo != Avisos.recordatorioActivo(this)) Avisos.guardarRecordatorio(this, activo)
            binding.btnHorario.isEnabled = activo
        }
        binding.swResumen.setOnCheckedChangeListener { _, activo ->
            if (activo != Avisos.resumenActivo(this)) Avisos.guardarResumen(this, activo)
        }
        binding.swInactividad.setOnCheckedChangeListener { _, activo ->
            if (activo != Avisos.inactividadActiva(this)) {
                Avisos.guardarInactividad(this, activo)
                if (activo) Avisos.reprogramarInactividad(this)
            }
        }
        binding.swNovedades.setOnCheckedChangeListener { _, activo ->
            if (activo != Avisos.novedadesActivas(this)) Avisos.guardarNovedades(this, activo)
        }
        binding.btnHorario.setOnClickListener { elegirHorario() }
        binding.btnActivarAvisos.setOnClickListener {
            if (android.os.Build.VERSION.SDK_INT >= 33 && !Avisos.permisoYaPedido(this)) {
                Avisos.marcarPermisoPedido(this)
                pedirPermiso.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                abrirAjustesDelSistema()
            }
        }
    }

    private fun mostrarAvisos() {
        binding.bloqueBloqueadas.visibility = if (Avisos.permitidas(this)) View.GONE else View.VISIBLE
        binding.swRecordatorio.isChecked = Avisos.recordatorioActivo(this)
        binding.swResumen.isChecked = Avisos.resumenActivo(this)
        binding.swInactividad.isChecked = Avisos.inactividadActiva(this)
        binding.swNovedades.isChecked = Avisos.novedadesActivas(this)
        binding.btnHorario.isEnabled = Avisos.recordatorioActivo(this)
        binding.tvRecordatorioDetalle.text = getString(
            R.string.aviso_recordatorio_detalle,
            String.format(Idioma.español, "%02d:%02d", Avisos.hora(this), Avisos.minuto(this))
        )
        binding.tvInactividadDetalle.text = getString(R.string.aviso_inactividad_detalle, ParametrosRemotos.diasInactividad())
    }

    private fun elegirHorario() {
        val selector = MaterialTimePicker.Builder()
            .setTimeFormat(TimeFormat.CLOCK_24H)
            .setHour(Avisos.hora(this))
            .setMinute(Avisos.minuto(this))
            .setTitleText(R.string.aviso_horario_titulo)
            .build()
        selector.addOnPositiveButtonClickListener {
            Avisos.guardarHorario(this, selector.hour, selector.minute)
            mostrarAvisos()
        }
        selector.show(supportFragmentManager, "horario")
    }

    /** Si la persona ya rechazó el permiso, el sistema no lo vuelve a pedir: se abren sus ajustes. */
    private fun abrirAjustesDelSistema() {
        val intento = Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
        try {
            startActivity(intento)
        } catch (e: Exception) {
            startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    private fun confirmarSalida() {
        // Al invitado hay que avisarle que se lleva los datos puestos.
        Confirmacion.pedir(
            pedido = CONFIRMAR_SALIDA,
            titulo = getString(R.string.btn_salir),
            aviso = if (esInvitado()) R.string.salir_invitado_aviso else R.string.salir_aviso,
            accion = R.string.btn_salir
        ).show(supportFragmentManager, CONFIRMAR_SALIDA)
    }

    private fun cerrarSesion() {
        escuchas.forEach { it.remove() }
        escuchas.clear()
        lifecycleScope.launch {
            Suscripcion.olvidar()
            Avisos.cancelarTodo(this@AjustesActivity)
            FirebaseAuth.getInstance().signOut()
            startActivity(
                Intent(this@AjustesActivity, LoginActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
            finish()
        }
    }

    private fun avisar(mensaje: Int) {
        Snackbar.make(binding.root, mensaje, Snackbar.LENGTH_SHORT).show()
    }
}
