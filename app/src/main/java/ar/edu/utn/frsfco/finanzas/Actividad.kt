package ar.edu.utn.frsfco.finanzas

import android.content.Context
import android.util.Log
import com.google.firebase.analytics.FirebaseAnalytics
import java.io.Serializable
import java.util.Date

/**
 * Racha y logros de la cuenta.
 *
 * Se calculan con los gastos que ya están en Firestore, así que no hace falta guardar nada
 * nuevo en la base ni cambiar sus reglas. En el teléfono solo quedan los logros ya
 * festejados y la mejor racha, para no repetir el aviso ni perder un logro cuando los
 * gastos viejos salen de la ventana que se consulta.
 */
object Actividad {

    /** Días hacia atrás que se consultan para la racha. */
    private const val VENTANA_DIAS = 60

    private const val PREFERENCIAS = "actividad"

    data class Estado(
        val racha: Int,
        val mejorRacha: Int,
        val obtenidos: List<String>,
        val nuevos: List<String>
    ) : Serializable

    private fun preferencias(c: Context) = c.getSharedPreferences(PREFERENCIAS, Context.MODE_PRIVATE)

    /**
     * Calcula la racha y los logros de la cuenta. Bloquea: consulta la base, así que
     * va en un hilo de fondo.
     */
    fun calcular(c: Context, uid: String, repo: GastosRepositorio = GastosRepositorio()): Estado {
        val hoy = Date()
        val desde = Retencion.inicioDelDia(Retencion.sumarDias(hoy, -VENTANA_DIAS))
        val gastos = repo.enRango(desde, Retencion.sumarDias(Retencion.inicioDelDia(hoy), 1))
        val dias = Retencion.diasRegistrados(gastos.map { it.fecha to it.fijoId })
        val prefs = preferencias(c)

        val racha = Retencion.racha(dias, hoy)
        val mejor = maxOf(Retencion.mejorRacha(dias), prefs.getInt("mejor_$uid", 0))
        val movimientos = repo.cantidadTotal() ?: gastos.size.toLong()

        val festejados = prefs.getStringSet("logros_$uid", emptySet()).orEmpty()
        val actuales = Retencion.logros(movimientos.toInt(), mejor).map { it.clave }.toSet() + festejados
        val nuevos = Retencion.nuevos(actuales.mapNotNull { Retencion.Logro.porClave(it) }.toSet(), festejados)
            .map { it.clave }

        prefs.edit()
            .putInt("mejor_$uid", mejor)
            .putStringSet("logros_$uid", actuales)
            .apply()

        // La racha queda como propiedad del usuario para segmentar campañas desde la consola.
        FirebaseAnalytics.getInstance(c).setUserProperty("racha", Retencion.tramoRacha(racha))
        Log.i(Avisos.ETIQUETA, "racha=$racha mejor=$mejor movimientos=$movimientos logros=${actuales.size} nuevos=$nuevos")

        val orden = Retencion.Logro.values().map { it.clave }
        return Estado(racha, mejor, actuales.sortedBy { orden.indexOf(it) }, nuevos)
    }

    /** Racha sin consultar logros, para personalizar los avisos programados. */
    fun rachaActual(repo: GastosRepositorio = GastosRepositorio()): Int {
        val hoy = Date()
        val gastos = repo.enRango(
            Retencion.inicioDelDia(Retencion.sumarDias(hoy, -VENTANA_DIAS)),
            Retencion.sumarDias(Retencion.inicioDelDia(hoy), 1)
        )
        return Retencion.racha(Retencion.diasRegistrados(gastos.map { it.fecha to it.fijoId }), hoy)
    }
}
