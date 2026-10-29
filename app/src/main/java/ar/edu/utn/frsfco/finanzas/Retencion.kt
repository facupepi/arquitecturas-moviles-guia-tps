package ar.edu.utn.frsfco.finanzas

import java.util.Calendar
import java.util.Date
import java.util.TimeZone

/**
 * Reglas de retención, sin dependencias de Android para poder probarlas solas.
 *
 * Calcula la racha de días con gastos registrados, los logros que se desbloquean, el
 * resumen de la semana y cuánto falta para el próximo aviso programado.
 */
object Retencion {

    /** Logros que se pueden obtener. El orden es el que se muestra en la hoja de logros. */
    enum class Logro(val clave: String) {
        PRIMER_GASTO("primer_gasto"),
        RACHA_3("racha_3"),
        RACHA_7("racha_7"),
        RACHA_30("racha_30"),
        CINCUENTA_MOVIMIENTOS("cincuenta_movimientos");

        companion object {
            fun porClave(clave: String): Logro? = values().firstOrNull { it.clave == clave }
        }
    }

    /** Lo que se le cuenta a la persona al terminar la semana. */
    data class ResumenSemanal(
        val total: Double,
        val cantidad: Int,
        val categoriaPrincipal: String?,
        val porcentajePrincipal: Int,
        /** Variación porcentual frente a la semana anterior; nula si la anterior no tuvo gastos. */
        val variacion: Int?
    )

    const val MOVIMIENTOS_LOGRO = 50

    /**
     * Días en que la persona registró algo. Los gastos recurrentes que la aplicación carga sola
     * (con identificador de gasto fijo) no cuentan: no los registra ella.
     */
    fun diasRegistrados(gastos: List<Pair<Date, String>>, zona: TimeZone = TimeZone.getDefault()): Set<Int> =
        gastos.filter { it.second.isBlank() }.map { claveDia(it.first, zona) }.toSet()

    /** Identifica un día del calendario como aaaammdd, en la zona horaria indicada. */
    fun claveDia(fecha: Date, zona: TimeZone = TimeZone.getDefault()): Int {
        val c = Calendar.getInstance(zona).apply { time = fecha }
        return c.get(Calendar.YEAR) * 10000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH)
    }

    private fun diaAnterior(fecha: Date, zona: TimeZone): Date =
        Calendar.getInstance(zona).apply { time = fecha; add(Calendar.DAY_OF_MONTH, -1) }.time

    /**
     * Días seguidos con al menos un gasto, contando hacia atrás desde hoy.
     *
     * Si hoy todavía no se registró nada, la racha sigue viva desde ayer: se corta recién
     * cuando pasa un día completo sin registros.
     */
    fun racha(dias: Set<Int>, hoy: Date, zona: TimeZone = TimeZone.getDefault()): Int {
        var cursor = if (claveDia(hoy, zona) in dias) hoy else diaAnterior(hoy, zona)
        var cuenta = 0
        while (claveDia(cursor, zona) in dias) {
            cuenta++
            cursor = diaAnterior(cursor, zona)
        }
        return cuenta
    }

    /** La racha más larga dentro de los días registrados. */
    fun mejorRacha(dias: Set<Int>, zona: TimeZone = TimeZone.getDefault()): Int {
        var mejor = 0
        for (dia in dias) {
            val fecha = Calendar.getInstance(zona).apply {
                clear()
                set(dia / 10000, (dia / 100) % 100 - 1, dia % 100, 12, 0)
            }.time
            // Solo se cuenta desde el primer día de cada tramo, para no recorrerlo varias veces.
            if (claveDia(diaAnterior(fecha, zona), zona) in dias) continue
            var cursor = fecha
            var largo = 0
            while (claveDia(cursor, zona) in dias) {
                largo++
                cursor = Calendar.getInstance(zona).apply { time = cursor; add(Calendar.DAY_OF_MONTH, 1) }.time
            }
            mejor = maxOf(mejor, largo)
        }
        return mejor
    }

    /** Logros que corresponden a la actividad registrada. */
    fun logros(movimientos: Int, mejorRacha: Int): Set<Logro> = buildSet {
        if (movimientos >= 1) add(Logro.PRIMER_GASTO)
        if (mejorRacha >= 3) add(Logro.RACHA_3)
        if (mejorRacha >= 7) add(Logro.RACHA_7)
        if (mejorRacha >= 30) add(Logro.RACHA_30)
        if (movimientos >= MOVIMIENTOS_LOGRO) add(Logro.CINCUENTA_MOVIMIENTOS)
    }

    /** Los logros obtenidos ahora que todavía no se habían festejado. */
    fun nuevos(actuales: Set<Logro>, festejados: Set<String>): List<Logro> =
        actuales.filter { it.clave !in festejados }.sortedBy { it.ordinal }

    /** Tramo de la racha que se guarda como propiedad del usuario para segmentar campañas. */
    fun tramoRacha(racha: Int): String = when {
        racha == 0 -> "sin_racha"
        racha < 3 -> "1_a_2"
        racha < 7 -> "3_a_6"
        else -> "7_o_mas"
    }

    /**
     * Arma el resumen de la semana a partir de los montos por categoría.
     *
     * Devuelve nulo si en la semana no hubo gastos: en ese caso no se envía nada.
     */
    fun resumenSemanal(
        semana: List<Pair<String, Double>>,
        totalSemanaAnterior: Double
    ): ResumenSemanal? {
        if (semana.isEmpty()) return null
        val total = semana.sumOf { it.second }
        if (total <= 0.0) return null
        val principal = semana.groupBy { it.first }
            .mapValues { (_, v) -> v.sumOf { it.second } }
            .maxByOrNull { it.value }
        val porcentaje = principal?.let { Math.round(it.value * 100 / total).toInt() } ?: 0
        val variacion = if (totalSemanaAnterior > 0.0) {
            Math.round((total - totalSemanaAnterior) * 100 / totalSemanaAnterior).toInt()
        } else null
        return ResumenSemanal(total, semana.size, principal?.key, porcentaje, variacion)
    }

    /** Milisegundos hasta la próxima vez que el reloj marque esa hora. */
    fun esperaHasta(ahora: Long, hora: Int, minuto: Int, zona: TimeZone = TimeZone.getDefault()): Long {
        val objetivo = Calendar.getInstance(zona).apply {
            timeInMillis = ahora
            set(Calendar.HOUR_OF_DAY, hora)
            set(Calendar.MINUTE, minuto)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= ahora) add(Calendar.DAY_OF_MONTH, 1)
        }
        return objetivo.timeInMillis - ahora
    }

    /** Milisegundos hasta el próximo domingo a la hora indicada. */
    fun esperaHastaDomingo(ahora: Long, hora: Int, zona: TimeZone = TimeZone.getDefault()): Long {
        val objetivo = Calendar.getInstance(zona).apply {
            timeInMillis = ahora
            set(Calendar.HOUR_OF_DAY, hora)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            while (get(Calendar.DAY_OF_WEEK) != Calendar.SUNDAY || timeInMillis <= ahora) {
                add(Calendar.DAY_OF_MONTH, 1)
            }
        }
        return objetivo.timeInMillis - ahora
    }

    /** Inicio del día de la fecha dada: las 0 h. */
    fun inicioDelDia(fecha: Date, zona: TimeZone = TimeZone.getDefault()): Date =
        Calendar.getInstance(zona).apply {
            time = fecha
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.time

    /** Corre una fecha la cantidad de días indicada, hacia adelante o hacia atrás. */
    fun sumarDias(fecha: Date, dias: Int, zona: TimeZone = TimeZone.getDefault()): Date =
        Calendar.getInstance(zona).apply { time = fecha; add(Calendar.DAY_OF_MONTH, dias) }.time
}
