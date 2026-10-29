package ar.edu.utn.frsfco.finanzas

import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.AggregateSource
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Guarda y lee los gastos en Firestore.
 *
 *     usuarios/{uid}/gastos/{gasto}
 *
 * Cada usuario tiene su propia colección, colgada de su identificador, de modo que
 * las reglas de seguridad puedan limitar el acceso a los datos propios.
 *
 * Ninguna escritura espera la confirmación del servidor. Firestore guarda primero en
 * el teléfono y sincroniza cuando puede, así que esperar dejaba la pantalla trabada
 * sin señal aunque el dato ya estuviera guardado.
 */
class GastosRepositorio {

    private val base = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    /**
     * Indica si la última lista recibida ya viene confirmada por el servidor. Mientras no lo
     * está, la lista puede estar vacía o incompleta (instalación nueva, sin copia local), y
     * crear los gastos fijos con ella duplicaría los que ya existen.
     */
    @Volatile
    var confirmadoPorServidor = false
        private set

    fun olvidarConfirmacion() {
        confirmadoPorServidor = false
    }

    private fun coleccion() = base
        .collection("usuarios")
        .document(auth.currentUser?.uid ?: error("no hay sesión abierta"))
        .collection("gastos")

    fun agregar(gasto: Gasto) {
        coleccion().add(aMapa(gasto))
    }

    /** Reemplaza los datos de un gasto que el usuario corrigió. */
    fun actualizar(gasto: Gasto) {
        coleccion().document(gasto.id).set(aMapa(gasto))
    }

    fun borrar(id: String) {
        coleccion().document(id).delete()
    }

    /** Vuelve a guardar un gasto recién borrado, con su mismo identificador. */
    fun restaurar(gasto: Gasto) {
        coleccion().document(gasto.id).set(aMapa(gasto))
    }

    /** Consulta puntual de todos los gastos bajo demanda para exportación, sin escuchas activas. */
    fun obtenerTodos(
        alCompletar: (List<Gasto>) -> Unit,
        alFallar: (Exception) -> Unit = {}
    ) {
        coleccion()
            .orderBy("fecha", Query.Direction.DESCENDING)
            .get()
            .addOnSuccessListener { documentos ->
                alCompletar(documentos.map { doc -> mapear(doc) })
            }
            .addOnFailureListener { error ->
                alFallar(error)
            }
    }

    /** Borra un lote de gastos por identificador mediante batch write, para depurar historial exportado. */
    fun borrarLote(ids: List<String>, alCompletar: () -> Unit = {}) {
        if (ids.isEmpty()) {
            alCompletar()
            return
        }
        val lotes = ids.chunked(500)
        fun procesar(indice: Int) {
            if (indice >= lotes.size) {
                alCompletar()
                return
            }
            val lote = base.batch()
            lotes[indice].forEach { id ->
                lote.delete(coleccion().document(id))
            }
            lote.commit()
                .addOnSuccessListener { procesar(indice + 1) }
                .addOnFailureListener { alCompletar() }
        }
        procesar(0)
    }

    /**
     * Escucha los gastos y avisa cada vez que cambian (consulta histórica sin filtro).
     *
     * Firestore mantiene una copia local, así que la lista aparece al instante y se
     * actualiza sola cuando termina de sincronizar. Devuelve el registro para poder
     * cortar la escucha cuando la pantalla deja de verse.
     */
    fun escuchar(alFallar: () -> Unit = {}, alCambiar: (List<Gasto>) -> Unit): ListenerRegistration =
        coleccion()
            .orderBy("fecha", Query.Direction.DESCENDING)
            .addSnapshotListener { documentos, error ->
                if (error != null || documentos == null) {
                    if (error != null) alFallar()
                    return@addSnapshotListener
                }
                confirmadoPorServidor = !documentos.metadata.isFromCache
                alCambiar(documentos.map { doc -> mapear(doc) })
            }

    /**
     * Suma lo gastado en [desde, hasta) con una consulta puntual por rango de fechas, que usa solo el
     * índice automático de fecha y no agrega índices a la base. Sirve para comparar períodos (TP7).
     * Bloquea; llamar desde un hilo de fondo.
     */
    fun totalEnRango(desde: Date, hasta: Date): Double {
        val consulta = coleccion()
            .whereGreaterThanOrEqualTo("fecha", desde)
            .whereLessThan("fecha", hasta)
            .get()
        return Tasks.await(consulta, 10, TimeUnit.SECONDS).sumOf { it.getDouble("monto") ?: 0.0 }
    }

    /** Escucha los gastos del intervalo [desde, hasta), sin traer períodos ajenos. */
    fun escucharEnRango(
        desde: Date,
        hasta: Date,
        alFallar: () -> Unit = {},
        alCambiar: (List<Gasto>) -> Unit
    ): ListenerRegistration =
        coleccion()
            .whereGreaterThanOrEqualTo("fecha", desde)
            .whereLessThan("fecha", hasta)
            .orderBy("fecha", Query.Direction.DESCENDING)
            .addSnapshotListener { documentos, error ->
                if (error != null || documentos == null) {
                    if (error != null) alFallar()
                    return@addSnapshotListener
                }
                confirmadoPorServidor = !documentos.metadata.isFromCache
                alCambiar(documentos.map { doc -> mapear(doc) })
            }

    /** Escucha en tiempo real una categoría dentro de [desde, hasta). */
    fun escucharPorCategoriaEnRango(
        categoriaId: String,
        desde: Date,
        hasta: Date,
        alFallar: () -> Unit = {},
        alCambiar: (List<Gasto>) -> Unit
    ): ListenerRegistration =
        coleccion()
            .whereEqualTo("categoria", categoriaId)
            .whereGreaterThanOrEqualTo("fecha", desde)
            .whereLessThan("fecha", hasta)
            .orderBy("fecha", Query.Direction.DESCENDING)
            .addSnapshotListener { documentos, error ->
                if (error != null || documentos == null) {
                    if (error != null) alFallar()
                    return@addSnapshotListener
                }
                alCambiar(documentos.map { doc -> mapear(doc) })
            }

    /**
     * Consulta una página acotada de movimientos históricos ordenados por fecha descendente,
     * admitiendo opcionalmente cotas de fecha (desde/hasta) y filtro de categoría.
     *
     * Si incluye categoría, aprovecha el índice compuesto (categoria ASC, fecha DESC).
     * Si incluye rango temporal, acota las lecturas exclusivamente al período solicitado
     * en el servidor de Firestore sin descargar movimientos ajenos.
     */
    fun consultarPaginado(
        desde: Date? = null,
        hasta: Date? = null,
        categoriaId: String? = null,
        limite: Long = 30,
        ultimoDoc: DocumentSnapshot? = null,
        alCompletar: (List<Gasto>, DocumentSnapshot?) -> Unit,
        alFallar: (Exception) -> Unit = {}
    ) {
        var consulta: Query = coleccion()
        if (categoriaId != null) {
            consulta = consulta.whereEqualTo("categoria", categoriaId)
        }
        if (desde != null) {
            consulta = consulta.whereGreaterThanOrEqualTo("fecha", desde)
        }
        if (hasta != null) {
            consulta = consulta.whereLessThan("fecha", hasta)
        }
        consulta = consulta.orderBy("fecha", Query.Direction.DESCENDING).limit(limite)
        if (ultimoDoc != null) {
            consulta = consulta.startAfter(ultimoDoc)
        }
        consulta.get().addOnSuccessListener { documentos ->
            val lista = documentos.map { doc -> mapear(doc) }
            val nuevoUltimo = documentos.documents.lastOrNull()
            alCompletar(lista, nuevoUltimo)
        }.addOnFailureListener { error ->
            alFallar(error)
        }
    }

    /**
     * Gastos de [desde, hasta) con una consulta puntual, para los avisos programados y la
     * racha. Usa el mismo índice de fecha que el resumen. Bloquea; llamar desde un hilo de fondo.
     */
    fun enRango(desde: Date, hasta: Date): List<Gasto> {
        val consulta = coleccion()
            .whereGreaterThanOrEqualTo("fecha", desde)
            .whereLessThan("fecha", hasta)
            .get()
        return Tasks.await(consulta, 15, TimeUnit.SECONDS).map { mapear(it) }
    }

    /**
     * Cantidad total de gastos guardados. El servidor los cuenta sin descargarlos; sin
     * conexión devuelve nulo. Bloquea; llamar desde un hilo de fondo.
     */
    fun cantidadTotal(): Long? = try {
        Tasks.await(coleccion().count().get(AggregateSource.SERVER), 10, TimeUnit.SECONDS).count
    } catch (e: Exception) {
        null
    }

    private fun mapear(doc: DocumentSnapshot): Gasto =
        Gasto(
            id = doc.id,
            monto = doc.getDouble("monto") ?: 0.0,
            categoriaId = doc.getString("categoria").orEmpty(),
            detalle = doc.getString("detalle").orEmpty(),
            fecha = doc.getDate("fecha") ?: Date(),
            medioId = (doc.getString("medioId") ?: doc.getString("medioPago")).orEmpty(),
            fijoId = doc.getString("fijoId").orEmpty()
        )

    private fun aMapa(g: Gasto) = hashMapOf(
        "monto" to g.monto,
        "categoria" to g.categoriaId,
        "detalle" to g.detalle.trim(),
        "fecha" to g.fecha,
        "medioId" to g.medioId,
        "fijoId" to g.fijoId
    )
}
