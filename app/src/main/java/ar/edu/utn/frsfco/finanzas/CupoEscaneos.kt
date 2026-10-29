package ar.edu.utn.frsfco.finanzas

import android.content.Context
import java.util.Calendar

/**
 * Cuenta los escaneos de ticket que hizo la cuenta en el mes.
 *
 * El contador vive en el teléfono y no en la base de datos, para no cambiar las reglas de
 * seguridad que comparten todas las versiones. Borrar los datos de la aplicación lo reinicia:
 * sin un servidor propio no hay forma de validarlo en la nube, y para un cupo de cortesía
 * se acepta esa limitación.
 */
object CupoEscaneos {

    private const val PREFERENCIAS = "cupo_escaneos"

    private fun clave(uid: String): String {
        val ahora = Calendar.getInstance()
        return Acceso.claveEscaneos(uid, ahora.get(Calendar.YEAR), ahora.get(Calendar.MONTH) + 1)
    }

    fun usados(contexto: Context, uid: String): Int =
        contexto.getSharedPreferences(PREFERENCIAS, Context.MODE_PRIVATE).getInt(clave(uid), 0)

    fun registrar(contexto: Context, uid: String) {
        val preferencias = contexto.getSharedPreferences(PREFERENCIAS, Context.MODE_PRIVATE)
        preferencias.edit().putInt(clave(uid), preferencias.getInt(clave(uid), 0) + 1).apply()
    }
}
