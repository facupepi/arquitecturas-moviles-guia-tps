package ar.edu.utn.frsfco.finanzas

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import java.util.Calendar
import java.util.Date

/**
 * Guarda los gastos que se repiten todos los meses.
 *
 *     usuarios/{uid}/fijos/{fijo}
 *
 * El alquiler y las expensas eran los dos gastos más grandes y los más aburridos de
 * cargar, y olvidarse uno dejaba el total del mes muy por debajo de la realidad.
 */
class FijosRepositorio {

    private val base = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    private fun coleccion() = base
        .collection("usuarios")
        .document(auth.currentUser?.uid ?: error("no hay sesión abierta"))
        .collection("fijos")

    fun guardar(fijo: GastoFijo) {
        if (fijo.id.isBlank()) {
            coleccion().add(aMapa(fijo))
        } else {
            coleccion().document(fijo.id).set(aMapa(fijo))
        }
    }

    fun borrar(id: String) {
        coleccion().document(id).delete()
    }

    /** El usuario borró el gasto de este mes: el recurrente sigue, pero este mes no se vuelve a crear solo. */
    fun omitirEsteMes(id: String) {
        val ref = coleccion().document(id)
        ref.get().addOnSuccessListener { doc ->
            // Si el recurrente ya no existe no se lo vuelve a crear. Se escribe el documento entero porque
            // las reglas piden todos los campos y los recurrentes viejos guardaban el medio como medioPago.
            if (!doc.exists()) return@addOnSuccessListener
            val medio = (doc.getString("medioId") ?: doc.getString("medioPago")).orEmpty()
            ref.set(
                hashMapOf(
                    "nombre" to doc.getString("nombre").orEmpty(),
                    "monto" to (doc.getDouble("monto") ?: 0.0),
                    "categoria" to doc.getString("categoria").orEmpty(),
                    "medioId" to medio,
                    "diaDelMes" to (doc.getLong("diaDelMes") ?: 1L).toInt(),
                    "omitidoMes" to claveDeMes(Calendar.getInstance())
                )
            )
        }
    }

    fun escuchar(alCambiar: (List<GastoFijo>) -> Unit): ListenerRegistration =
        coleccion().addSnapshotListener { documentos, error ->
            if (error != null || documentos == null) return@addSnapshotListener
            alCambiar(documentos.map { doc ->
                GastoFijo(
                    id = doc.id,
                    nombre = doc.getString("nombre").orEmpty(),
                    monto = doc.getDouble("monto") ?: 0.0,
                    categoriaId = doc.getString("categoria").orEmpty(),
                    // Los movimientos cargados antes guardaban la clave en "medioPago";
                        // los medios sembrados usan esas mismas claves como id.
                        medioId = (doc.getString("medioId")
                            ?: doc.getString("medioPago")).orEmpty(),
                    diaDelMes = (doc.getLong("diaDelMes") ?: 1L).toInt(),
                    omitidoMes = doc.getString("omitidoMes").orEmpty()
                )
            }.sortedBy { it.diaDelMes })
        }

    companion object {

        /**
         * Crea los gastos de este mes que todavía faltan.
         *
         * Se apoya en los gastos que ya están cargados en lugar de llevar una marca
         * aparte, así que no puede duplicar nada aunque se ejecute muchas veces.
         * Sólo alcanza a los fijos cuyo día ya pasó.
         */
        fun materializar(
            fijos: List<GastoFijo>,
            gastos: List<Gasto>,
            repositorio: GastosRepositorio
        ) {
            val hoy = Calendar.getInstance()
            val yaCreados = gastos
                .filter { it.fijoId.isNotBlank() && esDeEsteMes(it.fecha, hoy) }
                .map { it.fijoId }
                .toSet()

            fijos.forEach { fijo ->
                if (fijo.id in yaCreados) return@forEach
                if (fijo.omitidoMes == claveDeMes(hoy) || "${fijo.id}|${claveDeMes(hoy)}" in omitidosEnSesion) return@forEach
                if (fijo.diaDelMes > hoy.get(Calendar.DAY_OF_MONTH)) return@forEach

                repositorio.agregar(
                    Gasto(
                        monto = fijo.monto,
                        categoriaId = fijo.categoriaId,
                        detalle = fijo.nombre,
                        fecha = elDiaDeEsteMes(fijo.diaDelMes),
                        medioId = fijo.medioId,
                        fijoId = fijo.id
                    )
                )
            }
        }

        /** Si el mes es más corto que el día pedido, cae en el último día. */
        private fun elDiaDeEsteMes(dia: Int): Date = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, minOf(dia, getActualMaximum(Calendar.DAY_OF_MONTH)))
            set(Calendar.HOUR_OF_DAY, 9)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.time

        /**
         * Gastos de recurrentes que el usuario acaba de borrar. Se anota al instante, antes de que
         * Firestore avise del borrado, para que el recurrente no los vuelva a crear en esa carrera.
         */
        private val omitidosEnSesion = java.util.Collections.synchronizedSet(mutableSetOf<String>())

        fun omitirAhora(fijoId: String) {
            omitidosEnSesion += "$fijoId|${claveDeMes(Calendar.getInstance())}"
        }

        private fun claveDeMes(c: Calendar) = "%04d-%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1)

        fun esDeEsteMes(fecha: Date, hoy: Calendar): Boolean {
            val otra = Calendar.getInstance().apply { time = fecha }
            return otra.get(Calendar.YEAR) == hoy.get(Calendar.YEAR) &&
                otra.get(Calendar.MONTH) == hoy.get(Calendar.MONTH)
        }
    }

    private fun aMapa(f: GastoFijo) = hashMapOf(
        "nombre" to f.nombre.trim(),
        "monto" to f.monto,
        "categoria" to f.categoriaId,
        "medioId" to f.medioId,
        "diaDelMes" to f.diaDelMes
    ).apply { if (f.omitidoMes.isNotBlank()) put("omitidoMes", f.omitidoMes) }
}
