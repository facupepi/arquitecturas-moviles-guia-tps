package ar.edu.utn.frsfco.finanzas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ComparacionesTest {

    private val serie = listOf(
        InflacionMensual("2026-07", 2.1), InflacionMensual("2026-08", 1.7), InflacionMensual("2026-09", 1.9)
    )

    @Test
    fun `la variacion se calcula contra el periodo previo`() {
        assertEquals(25.0, Comparaciones.variacion(5000.0, 4000.0)!!, 0.0001)
        assertEquals(-25.0, Comparaciones.variacion(3000.0, 4000.0)!!, 0.0001)
    }

    @Test
    fun `sin importe en alguno de los dos periodos no hay variacion`() {
        assertNull(Comparaciones.variacion(0.0, 4000.0))
        assertNull(Comparaciones.variacion(5000.0, 0.0))
    }

    @Test
    fun `el veredicto distingue baja, suba menor y suba mayor que la inflacion`() {
        assertEquals(Comparaciones.Veredicto.DISMINUYO, Comparaciones.veredicto(-25.0, 1.9))
        assertEquals(Comparaciones.Veredicto.DISMINUYO, Comparaciones.veredicto(0.0, 1.9))
        assertEquals(Comparaciones.Veredicto.MENOR_QUE_INFLACION, Comparaciones.veredicto(1.5, 1.9))
        assertEquals(Comparaciones.Veredicto.MENOR_QUE_INFLACION, Comparaciones.veredicto(1.9, 1.9))
        assertEquals(Comparaciones.Veredicto.MAYOR_QUE_INFLACION, Comparaciones.veredicto(66.7, 1.9))
    }

    @Test
    fun `la inflacion entre dos meses excluye el inicial e incluye el final`() {
        // De julio a septiembre se componen agosto y septiembre.
        val esperada = ((1.017 * 1.019) - 1) * 100
        assertEquals(esperada, Comparaciones.inflacionEntre(serie, "2026-07", "2026-09")!!, 0.0001)
        assertEquals(1.9, Comparaciones.inflacionEntre(serie, "2026-08", "2026-09")!!, 0.0001)
    }

    @Test
    fun `sin meses posteriores no hay inflacion para comparar`() {
        assertNull(Comparaciones.inflacionEntre(serie, "2026-09", "2026-09"))
        assertNull(Comparaciones.inflacionEntre(serie, "2026-10", "2026-12"))
    }
}