package ar.edu.utn.frsfco.finanzas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccesoTest {

    private val todos = listOf(Acceso.PAQUETE_ANUAL, Acceso.PAQUETE_SEMANAL, Acceso.PAQUETE_MENSUAL)

    @Test
    fun losPlanesSeOrdenanDeMenorAMayorDuracion() {
        assertEquals(
            listOf(Acceso.PAQUETE_SEMANAL, Acceso.PAQUETE_MENSUAL, Acceso.PAQUETE_ANUAL),
            Acceso.ordenar(todos, Acceso.VARIANTE_TRES_ESCALONES)
        )
    }

    @Test
    fun laVarianteMesYAnioNoOfreceElPlanSemanal() {
        assertEquals(
            listOf(Acceso.PAQUETE_MENSUAL, Acceso.PAQUETE_ANUAL),
            Acceso.ordenar(todos, Acceso.VARIANTE_MES_Y_ANIO)
        )
    }

    @Test
    fun siFaltaUnPaqueteSoloSeMuestranLosQueExisten() {
        assertEquals(listOf(Acceso.PAQUETE_MENSUAL), Acceso.ordenar(listOf(Acceso.PAQUETE_MENSUAL), "cualquiera"))
    }

    @Test
    fun elDestacadoSigueLoConfigurado() {
        val ofrecidos = Acceso.ordenar(todos, Acceso.VARIANTE_TRES_ESCALONES)
        assertEquals(Acceso.PAQUETE_ANUAL, Acceso.destacado(ofrecidos, "anual"))
        assertEquals(Acceso.PAQUETE_SEMANAL, Acceso.destacado(ofrecidos, "semanal"))
        assertEquals(Acceso.PAQUETE_MENSUAL, Acceso.destacado(ofrecidos, "mensual"))
    }

    @Test
    fun siElDestacadoNoSeOfreceSeResaltaElMensual() {
        val ofrecidos = Acceso.ordenar(todos, Acceso.VARIANTE_MES_Y_ANIO)
        assertEquals(Acceso.PAQUETE_MENSUAL, Acceso.destacado(ofrecidos, "semanal"))
        assertEquals(Acceso.PAQUETE_MENSUAL, Acceso.destacado(ofrecidos, "algo desconocido"))
    }

    @Test
    fun sinPaquetesNoHayDestacado() {
        assertNull(Acceso.destacado(emptyList(), "mensual"))
    }

    @Test
    fun elBotonSoloSubeCuandoHayAnuncios() {
        assertEquals(300, Acceso.margenBoton(300, 120, false))
        assertEquals(420, Acceso.margenBoton(300, 120, true))
    }

    @Test
    fun elGratuitoEscaneaHastaElCupoYPlusSinLimite() {
        assertTrue(Acceso.puedeEscanear(false, 0, 3))
        assertTrue(Acceso.puedeEscanear(false, 2, 3))
        assertFalse(Acceso.puedeEscanear(false, 3, 3))
        assertFalse(Acceso.puedeEscanear(false, 4, 3))
        assertTrue(Acceso.puedeEscanear(true, 99, 3))
    }

    @Test
    fun losEscaneosRestantesNuncaSonNegativos() {
        assertEquals(3, Acceso.escaneosRestantes(0, 3))
        assertEquals(1, Acceso.escaneosRestantes(2, 3))
        assertEquals(0, Acceso.escaneosRestantes(7, 3))
    }

    @Test
    fun elContadorSeReiniciaCadaMesYSeparaPorCuenta() {
        assertEquals("escaneos_abc_2026-10", Acceso.claveEscaneos("abc", 2026, 10))
        assertEquals("escaneos_abc_2026-01", Acceso.claveEscaneos("abc", 2026, 1))
        assertFalse(Acceso.claveEscaneos("abc", 2026, 10) == Acceso.claveEscaneos("xyz", 2026, 10))
        assertFalse(Acceso.claveEscaneos("abc", 2026, 10) == Acceso.claveEscaneos("abc", 2026, 11))
    }
}
