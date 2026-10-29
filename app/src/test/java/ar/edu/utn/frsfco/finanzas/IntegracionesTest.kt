package ar.edu.utn.frsfco.finanzas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Pruebas de la capa de integraciones. Las respuestas de ejemplo son recortes de lo que devolvieron
 * las APIs reales el 26/10/2026; la prueba contra los servicios en vivo se hace en el emulador.
 */
class IntegracionesTest {

    @Test
    fun `interpreta la respuesta de DolarAPI`() {
        val texto = """{"moneda":"USD","casa":"oficial","nombre":"Oficial","compra":1510,"venta":1560,"fechaActualizacion":"2026-10-26T18:00:00.000Z"}"""
        val c = DolarApi.interpretar(texto)
        assertEquals(1560.0, c.pesosPorDolar, 0.0)
        assertEquals("DolarAPI", c.fuente)
        assertEquals("2026-10-26", c.publicada.take(10))
    }

    @Test
    fun `interpreta la respuesta del BCRA y elige el dolar entre las monedas`() {
        val texto = """{"status":200,"results":{"fecha":"2026-10-26","detalle":[
            {"codigoMoneda":"ARS","descripcion":"PESO","tipoPase":0.00064900,"tipoCotizacion":0.0},
            {"codigoMoneda":"AUD","descripcion":"DOLAR AUSTRALIA","tipoPase":0.7025,"tipoCotizacion":1082.5525},
            {"codigoMoneda":"USD","descripcion":"DOLAR E.E.U.U.","tipoPase":0.0,"tipoCotizacion":1541.0}]}}"""
        val c = BcraCotizaciones.interpretar(texto)
        assertEquals(1541.0, c.pesosPorDolar, 0.0)
        assertEquals("BCRA", c.fuente)
    }

    @Test(expected = IOException::class)
    fun `el BCRA sin dolar es un error`() {
        BcraCotizaciones.interpretar("""{"results":{"fecha":"2026-10-26","detalle":[]}}""")
    }

    @Test
    fun `ordena la inflacion por mes y toma los ultimos`() {
        val texto = """[{"fecha":"2026-09-30","valor":1.9},{"fecha":"2026-07-31","valor":2.1},{"fecha":"2026-08-31","valor":1.7}]"""
        val meses = InflacionRepositorio.interpretar(texto)
        assertEquals(listOf("2026-07", "2026-08", "2026-09"), meses.map { it.mes })
        assertEquals(1.9, meses.last().porcentaje, 0.0)
    }

    @Test
    fun `la inflacion acumulada se compone y no se suma`() {
        val meses = listOf(InflacionMensual("2026-07", 2.1), InflacionMensual("2026-08", 1.7), InflacionMensual("2026-09", 1.9))
        val acumulada = InflacionRepositorio.acumulada(meses)
        assertEquals(5.81, acumulada, 0.01)
        assertTrue(acumulada > 2.1 + 1.7 + 1.9)
    }

    @Test
    fun `el conmutador usa la primera fuente si responde`() {
        val c = ConmutadorDeFuentes(listOf("a" to { 1 }, "b" to { 2 }))
        assertEquals("a" to 1, c.obtener())
    }

    @Test
    fun `el conmutador pasa a la segunda fuente cuando la primera falla`() {
        val c = ConmutadorDeFuentes(listOf<Pair<String, () -> Int>>("a" to { throw IOException("caída") }, "b" to { 2 }))
        assertEquals("b" to 2, c.obtener())
    }

    @Test
    fun `una fuente caida queda fuera 30 segundos y se vuelve a probar despues`() {
        var ahora = 0L
        var pedidosA = 0
        var arriba = false
        val c = ConmutadorDeFuentes(
            listOf<Pair<String, () -> Int>>(
                "a" to { pedidosA++; if (arriba) 1 else throw IOException("caída") },
                "b" to { 2 }
            ),
            corteMs = 30_000L, reloj = { ahora }
        )
        assertEquals("b", c.obtener().first)
        val pedidosTrasCaer = pedidosA
        assertEquals(2, pedidosTrasCaer)

        // Mientras está cortada ni se la consulta.
        ahora = 10_000
        assertEquals("b", c.obtener().first)
        assertEquals(pedidosTrasCaer, pedidosA)

        // Pasado el corte y con el servicio recuperado, vuelve a atender.
        ahora = 31_000
        arriba = true
        assertEquals("a", c.obtener().first)
    }

    @Test(expected = IOException::class)
    fun `si ninguna fuente responde falla`() {
        ConmutadorDeFuentes(listOf<Pair<String, () -> Int>>("a" to { throw IOException("x") }, "b" to { throw IOException("y") })).obtener()
    }

    @Test
    fun `una cotizacion guardada se recupera igual`() {
        val original = Cotizacion(1560.0, "DolarAPI", "2026-10-26T18:00:00.000Z")
        val vuelta = CotizacionRepositorio.desdeTexto(CotizacionRepositorio.aTexto(original))
        assertEquals(original, vuelta)
    }

    @Test
    fun `una respuesta de inflacion invalida no se interpreta como serie`() {
        // El repositorio interpreta antes de guardar: con esta respuesta lanza y el dato bueno anterior no se pisa.
        val fallo = runCatching { InflacionRepositorio.interpretar("<html>error</html>") }
        assertTrue(fallo.isFailure)
    }
}
