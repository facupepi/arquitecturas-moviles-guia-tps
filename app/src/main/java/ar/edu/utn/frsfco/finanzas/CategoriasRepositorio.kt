package ar.edu.utn.frsfco.finanzas

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

/**
 * Guarda las categorías de cada usuario.
 *
 *     usuarios/{uid}/categorias/{categoria}
 *
 * La primera vez que alguien entra se siembran cinco, para que la aplicación sirva
 * desde el arranque sin obligar a configurar nada.
 */
class CategoriasRepositorio {

    private val base = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    private fun coleccion() = base
        .collection("usuarios")
        .document(auth.currentUser?.uid ?: error("no hay sesión abierta"))
        .collection("categorias")

    /** Crea las categorías iniciales si el usuario todavía no tiene ninguna. */
    fun sembrarSiHaceFalta() {
        // Alcanza con pedir una: si aparece, es que ya están sembradas.
        coleccion().limit(1).get().addOnSuccessListener { resultado ->
            if (!resultado.isEmpty) return@addOnSuccessListener

            val lote = base.batch()
            Categoria.iniciales().forEach { cat ->
                lote.set(coleccion().document(cat.id), aMapa(cat))
            }
            lote.commit()
        }
    }

    /** Nombre de cada categoría por su identificador. Bloquea; llamar desde un hilo de fondo. */
    fun nombres(): Map<String, String> =
        com.google.android.gms.tasks.Tasks.await(coleccion().get(), 10, java.util.concurrent.TimeUnit.SECONDS)
            .associate { it.id to it.getString("nombre").orEmpty() }

    fun escuchar(alCambiar: (List<Categoria>) -> Unit): ListenerRegistration =
        coleccion().addSnapshotListener { documentos, error ->
            if (error != null || documentos == null) return@addSnapshotListener
            alCambiar(documentos.map { doc ->
                Categoria(
                    id = doc.id,
                    nombre = doc.getString("nombre").orEmpty(),
                    color = doc.getString("color") ?: "#5C7CFA",
                    icono = doc.getString("icono") ?: "otros",
                    orden = (doc.getLong("orden") ?: 0L).toInt()
                )
            }.sortedBy { it.orden })
        }

    fun guardar(categoria: Categoria) {
        if (categoria.id.isBlank()) {
            coleccion().add(aMapa(categoria))
        } else {
            coleccion().document(categoria.id).set(aMapa(categoria))
        }
    }

    fun borrar(id: String) {
        coleccion().document(id).delete()
    }

    private fun aMapa(c: Categoria) = hashMapOf(
        "nombre" to c.nombre.trim(),
        "color" to c.color,
        "icono" to c.icono,
        "orden" to c.orden
    )
}
