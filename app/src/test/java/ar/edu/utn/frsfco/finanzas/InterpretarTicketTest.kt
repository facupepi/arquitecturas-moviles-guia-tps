package ar.edu.utn.frsfco.finanzas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InterpretarTicketTest {

    @Test
    fun `normaliza los formatos de importe argentinos`() {
        assertEquals(1234.5, InterpretarTicket.normalizar("1.234,50")!!, 0.0)
        assertEquals(1234.5, InterpretarTicket.normalizar("1234.50")!!, 0.0)
        assertEquals(1234.5, InterpretarTicket.normalizar("1 234,50")!!, 0.0)
        assertEquals(85.0, InterpretarTicket.normalizar("85,00")!!, 0.0)
        assertEquals(1234567.89, InterpretarTicket.normalizar("1.234.567,89")!!, 0.0)
    }

    @Test
    fun `toma el importe de la linea TOTAL y no el mayor`() {
        val ticket = """
            SUPERMERCADO EL TRIGAL
            CUIT 30-12345678-9
            Leche 2 x 1.250,00
            Pan 890,50
            SUBTOTAL 3.390,50
            DESCUENTO -300,00
            TOTAL 3.090,50
            EFECTIVO 5.000,00
        """.trimIndent()
        val lectura = InterpretarTicket.leer(ticket)
        assertEquals(3090.5, lectura.monto!!, 0.0)
        assertEquals("SUPERMERCADO EL TRIGAL", lectura.comercio)
    }

    @Test
    fun `si la cifra esta en la linea siguiente a TOTAL la encuentra`() {
        val lineas = listOf("Cafe 2.500,00", "TOTAL A PAGAR", "2.500,00")
        assertEquals(2500.0, InterpretarTicket.montoTotal(lineas)!!, 0.0)
    }

    @Test
    fun `sin linea TOTAL usa el mayor importe`() {
        val lineas = listOf("Item 100,00", "Item 4.500,00", "Item 250,00")
        assertEquals(4500.0, InterpretarTicket.montoTotal(lineas)!!, 0.0)
    }

    @Test
    fun `tolera el espacio y la coma doble que deja el reconocimiento`() {
        // Cadenas tal como las devolvió ML Kit en el emulador el 26/10/2026.
        assertEquals(listOf(7510.5), InterpretarTicket.importes("7.510,, 50"))
        assertEquals(listOf(1250.0), InterpretarTicket.importes("2 x 1.250, 00"))
        assertEquals(listOf(7210.5), InterpretarTicket.importes("TOTAL 7.210, 50"))
    }

    @Test
    fun `reconstruye los renglones cuando rotulos e importes salen en bloques separados`() {
        // Posiciones y orden de bloques como en la lectura real del ticket: primero todos los rótulos
        // (columna izquierda) y después todos los importes (columna derecha).
        val rotulos = listOf("Pan lactal", "SUBTOTAL", "TOTAL", "EFECTIVO")
        val importes = listOf("890,50", "7.510,, 50", "7.210, 50", "10.000, 00")
        val lineas = rotulos.mapIndexed { i, t -> LineaOcr(t, 20, 100 + i * 50, 140 + i * 50) } +
            importes.mapIndexed { i, t -> LineaOcr(t, 600, 102 + i * 50, 142 + i * 50) }
        val filas = InterpretarTicket.reconstruirFilas(lineas)
        assertEquals(4, filas.size)
        assertEquals("TOTAL 7.210, 50", filas[2])
        assertEquals(7210.5, InterpretarTicket.leerFilas(filas, "").monto!!, 0.0)
    }

    // Renglones tal como los reconstruyó ML Kit en el emulador, de cuatro tickets argentinos reales fotografiados.

    @Test
    fun `ticket real de Easy con total en el formato con punto de miles`() {
        val filas = listOf(
            "VA RESPONSABLE INSCRIPT0 AD: 04/10/199b",
            "29/12/2024 15:43:49 NRO.COMP: 08353-00124170 npilab",
            "NRO.TIENDA: 3106 NRO.CAJA: 4 PACK",
            "FACTURA B", "Consumidor Final",
            "CAJA PLASTICA PARA ZAPATOS X3 (21,00)",
            "4x9.900,00 / 7791231103174 39.600,00",
            "SUBTOTAL SIN DESCUENTOS 64.585,00",
            "TOTAL 64.585,00",
            "IVA Contenido 11.208,97"
        )
        val lectura = InterpretarTicket.leerFilas(filas, "")
        assertEquals(64585.0, lectura.monto!!, 0.0)
        // Ninguna línea de las primeras es un comercio: se deja vacío en lugar de proponer una fiscal.
        assertNull(lectura.comercio)
    }

    @Test
    fun `ticket real de Carrefour con cantidades de cuatro decimales`() {
        val filas = listOf(
            "2-CARREFOUR-", "VICENTE LOPEZ.", "Fecha 17/03/19 Hora 10: 48:36",
            "7798088960363 158.00", "2,0000 K 120,000D", "7796989008542 240.00",
            "RECARGO VISA 2 CUOTAS CON", "TOTAL 355,37", "Suma de sus pagos 355,37"
        )
        val lectura = InterpretarTicket.leerFilas(filas, "")
        assertEquals(355.37, lectura.monto!!, 0.0)
        assertEquals("2-CARREFOUR-", lectura.comercio)
    }

    @Test
    fun `ticket real con total y decimales con punto`() {
        val filas = listOf(
            "ANDRADE NAHUEL HORACIO", "C.U.I.T. Nro 23-33086889-9", "Fecha 20-05-17 Hora : 23137:45",
            "EMPANADA CAPRESE (21.00) 29.00", "2.000 / 39.00", "AQ PERA 500 CC (21.00) 78.00",
            "TOTAL 255.00", "PAGO 255.00"
        )
        val lectura = InterpretarTicket.leerFilas(filas, "")
        assertEquals(255.0, lectura.monto!!, 0.0)
        assertEquals("ANDRADE NAHUEL HORACIO", lectura.comercio)
    }

    @Test
    fun `ticket real con signo de pesos y espacio dentro del importe`() {
        val filas = listOf(
            "015 SAN NARTIN", "FACTURA B (Cod.006)", "Fecha 05/11/24 Hora 16:05:47",
            "1X 1500,00 (21.00S)-[16.37%] 1500.00",
            "SU8TOTAL SIN DESCUENTOS $ 2690,00",
            "TOTAL 2690. 00",
            "Pago EFECTIVO $ 2690,00"
        )
        assertEquals(2690.0, InterpretarTicket.leerFilas(filas, "").monto!!, 0.0)
    }

    @Test
    fun `una fecha no es un importe`() {
        // Líneas de un ticket real fotografiado (Viena, 2007) leídas por ML Kit el 26/10/2026.
        assertEquals(emptyList<Double>(), InterpretarTicket.importes("03.08./ 7/B/00042"))
        assertEquals(emptyList<Double>(), InterpretarTicket.importes("Rn. 0017981 23.10.11 4"))
        assertEquals(listOf(1250.0), InterpretarTicket.importes("Total 1.250,00"))
    }

    @Test
    fun `un ticket con todos los importes en cero no propone nada`() {
        val filas = listOf("16:24 Tisch 22/0", "03.08./ 7/B/00042", "1x WASSER", "0.00 EUR 0.00 EUR", "0.00 EUR",
            "10% MWSt. von 0.00 EUR 0.00 EUR")
        assertNull(InterpretarTicket.leerFilas(filas, "").monto)
    }

    @Test
    fun `un texto sin importes no propone nada`() {
        assertNull(InterpretarTicket.leer("Gracias por su compra\nVuelva pronto").monto)
    }

    @Test
    fun `el comercio no es una linea fiscal`() {
        val lineas = listOf("FACTURA B", "CUIT 30-1", "KIOSCO LA ESQUINA", "Total 100,00")
        assertEquals("KIOSCO LA ESQUINA", InterpretarTicket.comercio(lineas))
    }
}
