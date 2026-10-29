package ar.edu.utn.frsfco.finanzas

/**
 * Reglas de acceso de la versión gratuita y de la versión Plus.
 *
 * Todo lo que no depende de Android vive acá para poder probarlo sin un teléfono.
 */
object Acceso {

    /** Escaneos de ticket sin costo por mes mientras no haya suscripción. */
    const val ESCANEOS_GRATIS_POR_DEFECTO = 3

    /** Identificadores de los paquetes de la oferta, iguales a los de RevenueCat. */
    const val PAQUETE_SEMANAL = "\$rc_weekly"
    const val PAQUETE_MENSUAL = "\$rc_monthly"
    const val PAQUETE_ANUAL = "\$rc_annual"

    /** Cantidad de paquetes que debe tener la oferta completa. */
    private val ORDEN = listOf(PAQUETE_SEMANAL, PAQUETE_MENSUAL, PAQUETE_ANUAL)

    /**
     * Devuelve los paquetes en el orden en que se muestran (semana, mes, año) y deja
     * solo los de la variante pedida. Con "mes_y_anio" el plan semanal no se ofrece.
     */
    fun ordenar(ids: List<String>, variante: String): List<String> {
        val permitidos = if (variante == VARIANTE_MES_Y_ANIO) {
            ORDEN.filter { it != PAQUETE_SEMANAL }
        } else {
            ORDEN
        }
        return permitidos.filter { it in ids }
    }

    /**
     * Paquete que se muestra resaltado. Si el configurado no está entre los que se
     * ofrecen, se resalta el mensual y, en última instancia, el primero.
     */
    fun destacado(ofrecidos: List<String>, configurado: String): String? {
        val buscado = when (configurado) {
            "semanal" -> PAQUETE_SEMANAL
            "anual" -> PAQUETE_ANUAL
            else -> PAQUETE_MENSUAL
        }
        return when {
            buscado in ofrecidos -> buscado
            PAQUETE_MENSUAL in ofrecidos -> PAQUETE_MENSUAL
            else -> ofrecidos.firstOrNull()
        }
    }

    /**
     * Margen inferior del botón flotante. Con el bloque de anuncios visible hay que
     * subirlo su alto, o el botón taparía el banner.
     */
    fun margenBoton(base: Int, altoAnuncios: Int, hayAnuncios: Boolean): Int =
        if (hayAnuncios) base + altoAnuncios else base

    /** Clave del contador mensual de escaneos: usuario y mes, para que cada mes arranque en cero. */
    fun claveEscaneos(uid: String, anio: Int, mes: Int): String =
        "escaneos_%s_%04d-%02d".format(uid, anio, mes)

    /** Un usuario con suscripción activa no tiene tope; el gratuito, hasta el cupo configurado. */
    fun puedeEscanear(esPlus: Boolean, usados: Int, cupo: Int): Boolean =
        esPlus || usados < cupo

    /** Cuántos escaneos le quedan sin pagar; nunca negativo. */
    fun escaneosRestantes(usados: Int, cupo: Int): Int = (cupo - usados).coerceAtLeast(0)

    const val VARIANTE_TRES_ESCALONES = "tres_escalones"
    const val VARIANTE_MES_Y_ANIO = "mes_y_anio"
}
