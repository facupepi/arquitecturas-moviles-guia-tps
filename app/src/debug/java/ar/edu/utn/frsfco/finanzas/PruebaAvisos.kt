package ar.edu.utn.frsfco.finanzas

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

/**
 * Solo existe en la versión de depuración. Ejecuta ya mismo la tarea de un aviso programado,
 * con el mismo código que corre a su hora, para probarlo sin esperar al domingo:
 *
 *     adb shell am broadcast -a ar.edu.utn.frsfco.finanzas.PROBAR_AVISO --es tarea resumen -p <paquete>
 */
class PruebaAvisos : BroadcastReceiver() {

    override fun onReceive(contexto: Context, intento: Intent) {
        val pedido = when (intento.getStringExtra("tarea")) {
            "recordatorio" -> OneTimeWorkRequestBuilder<RecordatorioTarea>().build()
            "resumen" -> OneTimeWorkRequestBuilder<ResumenTarea>().build()
            "inactividad" -> OneTimeWorkRequestBuilder<InactividadTarea>().build()
            else -> return
        }
        WorkManager.getInstance(contexto).enqueue(pedido)
        Log.i(Avisos.ETIQUETA, "prueba: tarea ${intento.getStringExtra("tarea")} encolada")
    }
}
