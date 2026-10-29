package ar.edu.utn.frsfco.finanzas

import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Parámetros que se pueden cambiar desde la consola de Firebase sin publicar una versión nueva.
 *
 * Como el límite real del plan gratuito es la cantidad diaria de lecturas, el tamaño del lote
 * del historial se puede reducir de forma remota si el consumo se acerca al tope. Además se
 * definen acá la oferta de planes, el plan destacado, el cupo gratuito de escaneos y si se
 * muestra el banner. Si no hay conexión o el servicio no responde, rigen los valores por defecto.
 */
object ParametrosRemotos {

    private const val CLAVE_TAMANO_LOTE = "tamano_lote_historial"
    private const val CLAVE_MOSTRAR_BANNER = "mostrar_banner"
    private const val CLAVE_PLAN_DESTACADO = "plan_destacado"
    private const val CLAVE_OFERTA = "oferta_plus"
    private const val CLAVE_ESCANEOS = "escaneos_gratis"
    private const val LOTE_POR_DEFECTO = 30L
    private const val LOTE_MINIMO = 10
    private const val LOTE_MAXIMO = 100

    private val remoto: FirebaseRemoteConfig by lazy { FirebaseRemoteConfig.getInstance() }

    private val _mostrarBanner = MutableStateFlow(true)

    /** Si el banner puede mostrarse. Se apaga desde la consola sin tocar la aplicación. */
    val mostrarBanner: StateFlow<Boolean> = _mostrarBanner.asStateFlow()

    /** Declara los valores por defecto y pide los vigentes. En depuración no espera entre consultas. */
    fun iniciar() {
        val espera = if (BuildConfig.DEBUG) 0L else 3600L
        remoto.setConfigSettingsAsync(
            FirebaseRemoteConfigSettings.Builder().setMinimumFetchIntervalInSeconds(espera).build()
        )
        remoto.setDefaultsAsync(
            mapOf(
                CLAVE_TAMANO_LOTE to LOTE_POR_DEFECTO,
                CLAVE_MOSTRAR_BANNER to true,
                CLAVE_PLAN_DESTACADO to "mensual",
                CLAVE_OFERTA to Acceso.VARIANTE_TRES_ESCALONES,
                CLAVE_ESCANEOS to Acceso.ESCANEOS_GRATIS_POR_DEFECTO.toLong()
            )
        )
        remoto.fetchAndActivate().addOnCompleteListener { _mostrarBanner.value = remoto.getBoolean(CLAVE_MOSTRAR_BANNER) }
    }

    /** Cantidad de movimientos que lee cada lote del historial, siempre dentro de un rango seguro. */
    fun tamanoLoteHistorial(): Int =
        remoto.getLong(CLAVE_TAMANO_LOTE).toInt().coerceIn(LOTE_MINIMO, LOTE_MAXIMO)

    /** Plan que se muestra resaltado en la hoja de planes: semanal, mensual o anual. */
    fun planDestacado(): String = remoto.getString(CLAVE_PLAN_DESTACADO).ifBlank { "mensual" }

    /** Variante de la oferta: los tres escalones o solo mes y año. */
    fun varianteOferta(): String = remoto.getString(CLAVE_OFERTA).ifBlank { Acceso.VARIANTE_TRES_ESCALONES }

    /** Escaneos de ticket por mes sin suscripción. */
    fun escaneosGratis(): Int = remoto.getLong(CLAVE_ESCANEOS).toInt().coerceIn(0, 50)
}
