package ar.edu.utn.frsfco.finanzas

import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings

/**
 * Parámetros que se pueden cambiar desde la consola de Firebase sin publicar una versión nueva.
 *
 * Como el límite real del plan gratuito es la cantidad diaria de lecturas, el tamaño del lote
 * del historial se puede reducir de forma remota si el consumo se acerca al tope. Si no hay
 * conexión o el servicio no responde, rigen los valores por defecto.
 */
object ParametrosRemotos {

    private const val CLAVE_TAMANO_LOTE = "tamano_lote_historial"
    private const val LOTE_POR_DEFECTO = 30L
    private const val LOTE_MINIMO = 10
    private const val LOTE_MAXIMO = 100

    private val remoto: FirebaseRemoteConfig by lazy { FirebaseRemoteConfig.getInstance() }

    /** Declara los valores por defecto y pide los vigentes, a lo sumo una vez por hora. */
    fun iniciar() {
        remoto.setConfigSettingsAsync(
            FirebaseRemoteConfigSettings.Builder().setMinimumFetchIntervalInSeconds(3600).build()
        )
        remoto.setDefaultsAsync(mapOf(CLAVE_TAMANO_LOTE to LOTE_POR_DEFECTO))
        remoto.fetchAndActivate()
    }

    /** Cantidad de movimientos que lee cada lote del historial, siempre dentro de un rango seguro. */
    fun tamanoLoteHistorial(): Int =
        remoto.getLong(CLAVE_TAMANO_LOTE).toInt().coerceIn(LOTE_MINIMO, LOTE_MAXIMO)
}
