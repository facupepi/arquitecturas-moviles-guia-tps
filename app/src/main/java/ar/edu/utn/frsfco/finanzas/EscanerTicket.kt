package ar.edu.utn.frsfco.finanzas

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/** Lo que se pudo leer de un ticket. Ambos datos son una propuesta: el usuario los corrige. */
data class LecturaTicket(val monto: Double?, val comercio: String?, val textoCompleto: String)

/**
 * Lee un ticket con ML Kit (TP7). El reconocimiento corre en el teléfono: la imagen no sale del equipo.
 */
object EscanerTicket {

    /** Reconoce el texto de la imagen y propone monto y comercio. [alTerminar] vuelve en el hilo principal. */
    fun leer(contexto: Context, imagen: Uri, alTerminar: (Result<LecturaTicket>) -> Unit) {
        val reconocedor = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val entrada = InputImage.fromFilePath(contexto, imagen)
            reconocedor.process(entrada)
                .addOnSuccessListener { texto ->
                    // ML Kit agrupa en bloques: en un ticket los rótulos y los importes salen en bloques
                    // distintos. Con la posición de cada línea se vuelve a armar cada renglón del ticket.
                    val lineas = texto.textBlocks.flatMap { it.lines }.mapNotNull { l ->
                        l.boundingBox?.let { LineaOcr(l.text, it.left, it.top, it.bottom) }
                    }
                    val filas = InterpretarTicket.reconstruirFilas(lineas).ifEmpty { texto.text.lines() }
                    val lectura = InterpretarTicket.leerFilas(filas, texto.text)
                    // Solo se registra cuántas filas se leyeron: el contenido del ticket es un dato del usuario.
                    Log.i("EscanerTicket", "filas=${filas.size}")
                    alTerminar(Result.success(lectura))
                }
                .addOnFailureListener { alTerminar(Result.failure(it)) }
                .addOnCompleteListener { reconocedor.close() }
        } catch (e: Exception) {
            reconocedor.close()
            alTerminar(Result.failure(e))
        }
    }
}

/** Una línea de texto reconocida, con el rectángulo que ocupa en la imagen. */
data class LineaOcr(val texto: String, val izquierda: Int, val arriba: Int, val abajo: Int)

/** Interpreta el texto que devolvió el reconocimiento. Es lógica pura, sin Android, para poder probarla. */
object InterpretarTicket {

    // Importes con coma o punto decimal de dos cifras, con o sin separador de miles: 1.234,50 / 1234.50 / 85,00
    private val importe = Regex("""\d{1,3}(?:[.\s]\d{3})+[.,]\d{2}(?!\d)|\d+[.,]\d{2}(?!\d)""")

    fun leer(texto: String): LecturaTicket =
        leerFilas(texto.lines().map { it.trim() }.filter { it.isNotEmpty() }, texto)

    fun leerFilas(filas: List<String>, textoCompleto: String): LecturaTicket {
        val limpias = filas.map { it.trim() }.filter { it.isNotEmpty() }
        return LecturaTicket(montoTotal(limpias), comercio(limpias), textoCompleto)
    }

    /**
     * Junta las líneas que están a la misma altura en un solo renglón, de izquierda a derecha. Así el
     * rótulo "TOTAL" vuelve a quedar al lado de su importe aunque el reconocimiento los haya separado.
     */
    fun reconstruirFilas(lineas: List<LineaOcr>): List<String> {
        val filas = mutableListOf<MutableList<LineaOcr>>()
        for (l in lineas.sortedBy { (it.arriba + it.abajo) / 2 }) {
            val centro = (l.arriba + l.abajo) / 2
            val alto = (l.abajo - l.arriba).coerceAtLeast(1)
            val fila = filas.lastOrNull()
            val centroFila = fila?.let { f -> f.sumOf { (it.arriba + it.abajo) / 2 } / f.size }
            if (fila != null && centroFila != null && kotlin.math.abs(centro - centroFila) <= alto / 2) fila += l
            else filas += mutableListOf(l)
        }
        return filas.map { f -> f.sortedBy { it.izquierda }.joinToString(" ") { it.texto } }
    }

    /**
     * El reconocimiento a veces deja un espacio tras el separador decimal ("1.250, 00") o lo duplica
     * ("7.510,, 50"). Se normaliza antes de buscar importes.
     */
    private fun limpiar(linea: String) =
        linea.replace(Regex("([.,]){2,}"), "$1").replace(Regex("([.,])\\s+(\\d{2})\\b"), "$1$2")

    /**
     * Prefiere el importe de la línea "TOTAL" (o el de la siguiente, porque el reconocimiento a veces
     * separa la palabra de la cifra). Sin esa línea, toma el mayor importe del ticket.
     */
    fun montoTotal(lineas: List<String>): Double? {
        for ((i, l) in lineas.withIndex()) {
            val u = l.uppercase()
            if ("TOTAL" in u && "SUBTOTAL" !in u && "SUB TOTAL" !in u) {
                val enLinea = importes(l)
                if (enLinea.isNotEmpty()) return enLinea.max()
                val siguiente = lineas.getOrNull(i + 1)?.let { importes(it) }.orEmpty()
                if (siguiente.isNotEmpty()) return siguiente.max()
            }
        }
        return lineas.flatMap { importes(it) }.maxOrNull()
    }

    /**
     * Los importes de una línea, sin las fechas ("03.08." o "23.10.11" también tienen forma de importe,
     * pero van seguidas de otro separador) y sin los ceros, que no son un gasto a proponer.
     */
    fun importes(linea: String): List<Double> {
        val limpia = limpiar(linea)
        return importe.findAll(limpia)
            .filter { m -> limpia.getOrNull(m.range.last + 1) !in setOf('.', '/', '-') }
            .mapNotNull { normalizar(it.value) }
            .filter { it > 0.0 }
            .toList()
    }

    /** Convierte "1.234,50", "1234.50" o "1 234,50" en 1234.5. El último separador seguido de dos cifras es el decimal. */
    fun normalizar(texto: String): Double? {
        val limpio = texto.replace(" ", "")
        val posicion = limpio.lastIndexOfAny(charArrayOf(',', '.'))
        if (posicion < 0 || limpio.length - posicion - 1 != 2) return limpio.toDoubleOrNull()
        val entera = limpio.substring(0, posicion).replace(".", "").replace(",", "")
        return "$entera.${limpio.substring(posicion + 1)}".toDoubleOrNull()
    }

    // Palabras de las líneas fiscales y de datos del comprobante, que nunca son el nombre del comercio.
    private val noEsComercio = listOf(
        "CUIT", "FACTURA", "TICKET", "IVA", "RESPONSABLE", "INSCRIPT", "NRO", "FECHA", "ACTIVIDAD", "CONSUMIDOR"
    )

    /**
     * El nombre del comercio suele ser la primera línea con más letras que cifras y sin palabras fiscales.
     * Si ninguna cumple se deja vacío: es preferible no proponer nada a proponer una línea equivocada.
     */
    fun comercio(lineas: List<String>): String? =
        lineas.take(4).firstOrNull { l ->
            val letras = l.count { it.isLetter() }
            val cifras = l.count { it.isDigit() }
            letras >= 4 && letras > cifras && noEsComercio.none { it in l.uppercase() }
        }?.take(40)
}
