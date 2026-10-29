package ar.edu.utn.frsfco.finanzas

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/**
 * Capa de integraciones con APIs públicas (TP7).
 *
 * Los datos de terceros no se guardan en Firestore: cambian solos y no son del usuario. Cada
 * consulta pasa por un caché local con vencimiento, de modo que sin red se muestra el último valor
 * conocido junto con su fecha.
 */

/** Cuántos pesos vale un dólar, de qué fuente salió y cuándo se publicó. */
data class Cotizacion(
    val pesosPorDolar: Double,
    val fuente: String,
    val publicada: String,
    val desdeCache: Boolean = false
)

/** Inflación mensual publicada, con el mes como aaaa-mm y el valor en porcentaje. */
data class InflacionMensual(val mes: String, val porcentaje: Double)

/** Cliente HTTP mínimo: sin bibliotecas extra, solo la del sistema y org.json. */
object HttpJson {
    private const val CONEXION_MS = 3000
    private const val LECTURA_MS = 5000

    fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "GET"
            c.connectTimeout = CONEXION_MS
            c.readTimeout = LECTURA_MS
            c.setRequestProperty("Accept", "application/json")
            val codigo = c.responseCode
            if (codigo !in 200..299) throw IOException("$url respondió $codigo")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}

/** Caché en el teléfono: guarda el texto de la respuesta y la hora en que se obtuvo. */
class CacheLocal(contexto: Context) {
    private val prefs = contexto.applicationContext.getSharedPreferences("integraciones", Context.MODE_PRIVATE)

    fun guardar(clave: String, texto: String) {
        prefs.edit().putString(clave, texto).putLong("$clave.t", System.currentTimeMillis()).apply()
    }

    /** Devuelve el texto guardado si no pasó más de [vigenciaMs], o `null`. Con [aunVencido] lo devuelve igual. */
    fun leer(clave: String, vigenciaMs: Long, aunVencido: Boolean = false): String? {
        val texto = prefs.getString(clave, null) ?: return null
        val edad = System.currentTimeMillis() - prefs.getLong("$clave.t", 0L)
        return if (aunVencido || edad <= vigenciaMs) texto else null
    }
}

/** Una fuente de datos que se puede consultar y de la que se sabe interpretar la respuesta. */
interface FuenteCotizacion {
    val nombre: String
    fun obtener(): Cotizacion
}

/** Fuente 1: DolarAPI, dólar oficial a la venta. */
class DolarApi(private val url: String = URL_OFICIAL) : FuenteCotizacion {
    override val nombre = "DolarAPI"

    override fun obtener(): Cotizacion = interpretar(HttpJson.get(url))

    companion object {
        const val URL_OFICIAL = "https://dolarapi.com/v1/dolares/oficial"

        fun interpretar(texto: String): Cotizacion {
            val j = JSONObject(texto)
            return Cotizacion(j.getDouble("venta"), "DolarAPI", j.getString("fechaActualizacion"))
        }
    }
}

/** Fuente 2, de respaldo: cotización del dólar publicada por el Banco Central. */
class BcraCotizaciones(private val url: String = URL_BCRA) : FuenteCotizacion {
    override val nombre = "BCRA"

    override fun obtener(): Cotizacion = interpretar(HttpJson.get(url))

    companion object {
        const val URL_BCRA = "https://api.bcra.gob.ar/estadisticascambiarias/v1.0/Cotizaciones"

        fun interpretar(texto: String): Cotizacion {
            val r = JSONObject(texto).getJSONObject("results")
            val detalle = r.getJSONArray("detalle")
            for (i in 0 until detalle.length()) {
                val o = detalle.getJSONObject(i)
                if (o.getString("codigoMoneda") == "USD") {
                    return Cotizacion(o.getDouble("tipoCotizacion"), "BCRA", r.getString("fecha"))
                }
            }
            throw IOException("La respuesta del BCRA no trae el dólar")
        }
    }
}

/**
 * Elige entre varias fuentes equivalentes. Misma política que el balanceador del TP3, pero para
 * un valor cualquiera: se prueba primero la fuente de más peso, cada una admite dos intentos y,
 * tras dos fallos seguidos, queda fuera 30 segundos y el pedido pasa a la siguiente.
 */
class ConmutadorDeFuentes<T>(
    private val fuentes: List<Pair<String, () -> T>>,
    private val corteMs: Long = 30_000L,
    private val reloj: () -> Long = System::currentTimeMillis
) {
    private val fallosSeguidos = HashMap<String, Int>()
    private val fueraHasta = HashMap<String, Long>()
    private val atendidos = HashMap<String, Int>()

    fun atendidosPor(nombre: String): Int = synchronized(this) { atendidos[nombre] ?: 0 }

    fun obtener(): Pair<String, T> {
        var ultimo: Exception? = null
        val ahora = reloj()
        val (activas, cortadas) = fuentes.partition { synchronized(this) { (fueraHasta[it.first] ?: 0L) <= ahora } }
        for ((nombre, pedir) in activas + cortadas) {
            for (intento in 1..2) {
                try {
                    val valor = pedir()
                    synchronized(this) {
                        fallosSeguidos[nombre] = 0
                        atendidos[nombre] = (atendidos[nombre] ?: 0) + 1
                    }
                    Log.i(ETIQUETA, "fuente=$nombre intento=$intento")
                    return nombre to valor
                } catch (e: Exception) {
                    ultimo = e
                    Log.w(ETIQUETA, "fuente=$nombre falló (intento $intento): ${e.message}")
                    val corta = synchronized(this) {
                        val n = (fallosSeguidos[nombre] ?: 0) + 1
                        fallosSeguidos[nombre] = n
                        val cortar = n >= 2 || e is SocketTimeoutException
                        if (cortar) fueraHasta[nombre] = reloj() + corteMs
                        cortar
                    }
                    if (corta) break
                }
            }
        }
        throw ultimo ?: IOException("No hay fuentes disponibles")
    }

    companion object {
        private const val ETIQUETA = "Integraciones"
    }
}

/** Cotización del dólar con caché de 15 minutos y respaldo entre dos fuentes. */
class CotizacionRepositorio(private val cache: CacheLocal, fuentes: List<FuenteCotizacion> = FUENTES_POR_DEFECTO()) {

    private val conmutador = ConmutadorDeFuentes(fuentes.map { f -> f.nombre to { f.obtener() } })

    /** Bloquea; llamar desde un hilo de fondo. Si nada responde, devuelve lo último guardado o falla. */
    fun obtener(): Cotizacion {
        // Un dato reciente es un dato vigente: solo se avisa "guardado" cuando se usa uno vencido por falta de red.
        cache.leer(CLAVE, VIGENCIA_MS)?.let { return desdeTexto(it) }
        return try {
            val (_, c) = conmutador.obtener()
            cache.guardar(CLAVE, aTexto(c))
            c
        } catch (e: Exception) {
            val viejo = cache.leer(CLAVE, VIGENCIA_MS, aunVencido = true) ?: throw e
            desdeTexto(viejo).copy(desdeCache = true)
        }
    }

    companion object {
        private const val CLAVE = "cotizacion"
        private const val VIGENCIA_MS = 15 * 60 * 1000L

        fun FUENTES_POR_DEFECTO(): List<FuenteCotizacion> = listOf(DolarApi(), BcraCotizaciones())

        fun aTexto(c: Cotizacion) =
            JSONObject().put("v", c.pesosPorDolar).put("f", c.fuente).put("p", c.publicada).toString()

        fun desdeTexto(t: String): Cotizacion {
            val j = JSONObject(t)
            return Cotizacion(j.getDouble("v"), j.getString("f"), j.getString("p"))
        }
    }
}

/** Inflación mensual de ArgentinaDatos, con caché de 24 horas. */
class InflacionRepositorio(
    private val cache: CacheLocal,
    private val url: String = URL_INFLACION
) {
    /** Bloquea; llamar desde un hilo de fondo. Devuelve los últimos meses publicados, el más viejo primero. */
    fun ultimos(cantidad: Int): List<InflacionMensual> {
        val texto = cache.leer(CLAVE, VIGENCIA_MS) ?: try {
            // Se interpreta antes de guardar: una respuesta defectuosa no debe pisar el último dato bueno.
            HttpJson.get(url).also { interpretar(it); cache.guardar(CLAVE, it) }
        } catch (e: Exception) {
            cache.leer(CLAVE, VIGENCIA_MS, aunVencido = true) ?: throw e
        }
        return interpretar(texto).takeLast(cantidad)
    }

    companion object {
        const val URL_INFLACION = "https://api.argentinadatos.com/v1/finanzas/indices/inflacion"
        private const val CLAVE = "inflacion"
        private const val VIGENCIA_MS = 24 * 60 * 60 * 1000L

        fun interpretar(texto: String): List<InflacionMensual> {
            val a = JSONArray(texto)
            return (0 until a.length()).map {
                val o = a.getJSONObject(it)
                InflacionMensual(o.getString("fecha").take(7), o.getDouble("valor"))
            }.sortedBy { it.mes }
        }

        /** Inflación acumulada de varios meses seguidos, en porcentaje: se componen, no se suman. */
        fun acumulada(meses: List<InflacionMensual>): Double =
            (meses.fold(1.0) { acc, m -> acc * (1 + m.porcentaje / 100) } - 1) * 100
    }
}

/** Cálculos de la tarjeta de evolución del gasto. Sin Android, para poder probarlos. */
object Comparaciones {

    enum class Veredicto { DISMINUYO, MENOR_QUE_INFLACION, MAYOR_QUE_INFLACION }

    /** Variación porcentual del gasto, o `null` si falta alguno de los dos importes. */
    fun variacion(actual: Double, previo: Double): Double? =
        if (actual > 0.0 && previo > 0.0) (actual - previo) / previo * 100 else null

    /** Si el gasto bajó, creció menos que los precios o creció más que los precios. */
    fun veredicto(variacion: Double, inflacion: Double): Veredicto = when {
        variacion <= 0 -> Veredicto.DISMINUYO
        variacion <= inflacion -> Veredicto.MENOR_QUE_INFLACION
        else -> Veredicto.MAYOR_QUE_INFLACION
    }

    /** Inflación acumulada de los meses posteriores a [desde] y hasta [hasta] inclusive (formato aaaa-mm). */
    fun inflacionEntre(serie: List<InflacionMensual>, desde: String, hasta: String): Double? {
        val meses = serie.filter { it.mes > desde && it.mes <= hasta }
        return if (meses.isEmpty()) null else InflacionRepositorio.acumulada(meses)
    }
}
