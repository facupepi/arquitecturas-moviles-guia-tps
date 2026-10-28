package ar.edu.utn.frsfco.finanzas

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import java.util.Date

/**
 * Guarda lo que entra.
 *
 *     usuarios/{uid}/ingresos/{ingreso}
 *
 * Con los gastos solos la aplicación decía cuánto se fue pero nunca cuánto queda,
 * que era lo que todos preguntaban primero.
 */
class IngresosRepositorio {

    private val base = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    private fun coleccion() = base
        .collection("usuarios")
        .document(auth.currentUser?.uid ?: error("no hay sesión abierta"))
        .collection("ingresos")

    fun agregar(ingreso: Ingreso) {
        coleccion().add(aMapa(ingreso))
    }

    fun actualizar(ingreso: Ingreso) {
        coleccion().document(ingreso.id).set(aMapa(ingreso))
    }

    fun borrar(id: String) {
        coleccion().document(id).delete()
    }

    /** Escucha los ingresos del intervalo [desde, hasta), sin traer períodos ajenos. */
    fun escucharEnRango(
        desde: Date,
        hasta: Date,
        alFallar: () -> Unit = {},
        alCambiar: (List<Ingreso>) -> Unit
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
                alCambiar(documentos.map { doc ->
                    Ingreso(
                        id = doc.id,
                        monto = doc.getDouble("monto") ?: 0.0,
                        detalle = doc.getString("detalle").orEmpty(),
                        fecha = doc.getDate("fecha") ?: Date()
                    )
                })
            }

    fun escucharUltimos(
        limite: Long = 12,
        alFallar: () -> Unit = {},
        alCambiar: (List<Ingreso>) -> Unit
    ): ListenerRegistration =
        coleccion()
            .orderBy("fecha", Query.Direction.DESCENDING)
            .limit(limite)
            .addSnapshotListener { documentos, error ->
                if (error != null || documentos == null) {
                    if (error != null) alFallar()
                    return@addSnapshotListener
                }
                alCambiar(documentos.map { doc ->
                    Ingreso(
                        id = doc.id,
                        monto = doc.getDouble("monto") ?: 0.0,
                        detalle = doc.getString("detalle").orEmpty(),
                        fecha = doc.getDate("fecha") ?: Date()
                    )
                })
            }

    fun escuchar(alFallar: () -> Unit = {}, alCambiar: (List<Ingreso>) -> Unit): ListenerRegistration =
        coleccion()
            .orderBy("fecha", Query.Direction.DESCENDING)
            .addSnapshotListener { documentos, error ->
                if (error != null || documentos == null) {
                    if (error != null) alFallar()
                    return@addSnapshotListener
                }
                alCambiar(documentos.map { doc ->
                    Ingreso(
                        id = doc.id,
                        monto = doc.getDouble("monto") ?: 0.0,
                        detalle = doc.getString("detalle").orEmpty(),
                        fecha = doc.getDate("fecha") ?: Date()
                    )
                })
            }

    private fun aMapa(i: Ingreso) = hashMapOf(
        "monto" to i.monto,
        "detalle" to i.detalle.trim(),
        "fecha" to i.fecha
    )
}
