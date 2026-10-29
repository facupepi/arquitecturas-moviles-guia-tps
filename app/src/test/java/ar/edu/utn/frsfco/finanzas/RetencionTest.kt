package ar.edu.utn.frsfco.finanzas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Date
import java.util.TimeZone

class RetencionTest {

    private val zona = TimeZone.getTimeZone("America/Argentina/Buenos_Aires")

    private fun fecha(anio: Int, mes: Int, dia: Int, hora: Int = 12, minuto: Int = 0): Date =
        Calendar.getInstance(zona).apply { clear(); set(anio, mes - 1, dia, hora, minuto) }.time

    private fun dias(vararg d: Int): Set<Int> = d.map { 20261000 + it }.toSet()

    @Test
    fun laClaveDelDiaUsaLaZonaHorariaLocal() {
        // A las 23:30 de Argentina ya es el día siguiente en UTC; la clave sigue siendo la local.
        assertEquals(20261029, Retencion.claveDia(fecha(2026, 10, 29, 23, 30), zona))
    }

    @Test
    fun laRachaCuentaLosDiasSeguidosHastaHoy() {
        assertEquals(3, Retencion.racha(dias(27, 28, 29), fecha(2026, 10, 29), zona))
    }

    @Test
    fun siHoyNoHayGastosLaRachaSigueDesdeAyer() {
        assertEquals(2, Retencion.racha(dias(27, 28), fecha(2026, 10, 29), zona))
    }

    @Test
    fun unDiaCompletoSinGastosCortaLaRacha() {
        assertEquals(0, Retencion.racha(dias(25, 26, 27), fecha(2026, 10, 29), zona))
    }

    @Test
    fun laRachaAtraviesaElCambioDeMes() {
        val conSeptiembre = dias(1, 2) + setOf(20260929, 20260930)
        assertEquals(4, Retencion.racha(conSeptiembre, fecha(2026, 10, 2), zona))
    }

    @Test
    fun losGastosRecurrentesCargadosSolosNoCuentanParaLaRacha() {
        val registros = listOf(
            fecha(2026, 10, 29) to "",          // lo cargó la persona
            fecha(2026, 10, 28) to "alquiler",  // lo creó la aplicación
            fecha(2026, 10, 27) to "alquiler"
        )
        val dias = Retencion.diasRegistrados(registros, zona)
        assertEquals(setOf(20261029), dias)
        assertEquals(1, Retencion.racha(dias, fecha(2026, 10, 29), zona))
    }

    @Test
    fun laMejorRachaEsElTramoMasLargo() {
        assertEquals(4, Retencion.mejorRacha(dias(1, 2, 10, 11, 12, 13, 20), zona))
    }

    @Test
    fun losLogrosDependenDeLosMovimientosYDeLaMejorRacha() {
        assertTrue(Retencion.logros(0, 0).isEmpty())
        assertEquals(
            setOf(Retencion.Logro.PRIMER_GASTO, Retencion.Logro.RACHA_3, Retencion.Logro.RACHA_7),
            Retencion.logros(12, 8)
        )
        assertTrue(Retencion.Logro.CINCUENTA_MOVIMIENTOS in Retencion.logros(50, 1))
    }

    @Test
    fun soloSeFestejanLosLogrosQueNoSeHabianFestejado() {
        val actuales = setOf(Retencion.Logro.PRIMER_GASTO, Retencion.Logro.RACHA_3)
        assertEquals(listOf(Retencion.Logro.RACHA_3), Retencion.nuevos(actuales, setOf("primer_gasto")))
    }

    @Test
    fun elResumenSemanalIndicaLaCategoriaPrincipalYLaVariacion() {
        val semana = listOf("comida" to 6000.0, "transporte" to 2000.0, "comida" to 2000.0)
        val resumen = Retencion.resumenSemanal(semana, totalSemanaAnterior = 12500.0)!!
        assertEquals(10000.0, resumen.total, 0.001)
        assertEquals(3, resumen.cantidad)
        assertEquals("comida", resumen.categoriaPrincipal)
        assertEquals(80, resumen.porcentajePrincipal)
        assertEquals(-20, resumen.variacion)
    }

    @Test
    fun sinGastosEnLaSemanaNoHayResumenNiVariacionSinBase() {
        assertNull(Retencion.resumenSemanal(emptyList(), 5000.0))
        assertNull(Retencion.resumenSemanal(listOf("ocio" to 900.0), 0.0)!!.variacion)
    }

    @Test
    fun elRecordatorioEsperaHastaLaHoraDeHoyODeManana() {
        val antes = fecha(2026, 10, 29, 18, 0).time
        assertEquals(3 * 3600_000L, Retencion.esperaHasta(antes, 21, 0, zona))
        val despues = fecha(2026, 10, 29, 22, 0).time
        assertEquals(23 * 3600_000L, Retencion.esperaHasta(despues, 21, 0, zona))
    }

    @Test
    fun elResumenSeProgramaParaElDomingo() {
        // El 29/10/2026 es jueves: el domingo siguiente es el 1/11.
        val jueves = fecha(2026, 10, 29, 20, 0).time
        assertEquals(fecha(2026, 11, 1, 20, 0).time - jueves, Retencion.esperaHastaDomingo(jueves, 20, zona))
    }

    @Test
    fun elTramoDeRachaAgrupaParaSegmentar() {
        assertEquals("sin_racha", Retencion.tramoRacha(0))
        assertEquals("3_a_6", Retencion.tramoRacha(5))
        assertEquals("7_o_mas", Retencion.tramoRacha(12))
    }
}
