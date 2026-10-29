package ar.edu.utn.frsfco.finanzas

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Date

// Avisos que programa el propio teléfono. No necesitan servidor: el sistema despierta la
// aplicación a la hora indicada aunque esté cerrada, y el contenido se arma con los datos
// de la cuenta. Ningún importe sale del teléfono.

private const val ID_RECORDATORIO = 61
private const val ID_RESUMEN = 62
private const val ID_INACTIVIDAD = 63

/** A la hora elegida, recuerda registrar los gastos del día si todavía no se cargó ninguno. */
class RecordatorioTarea(contexto: Context, parametros: WorkerParameters) : CoroutineWorker(contexto, parametros) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (FirebaseAuth.getInstance().currentUser == null || !Avisos.recordatorioActivo(applicationContext)) {
            return@withContext Result.success()
        }
        try {
            val repo = GastosRepositorio()
            val hoy = Retencion.inicioDelDia(Date())
            // Un gasto recurrente cargado solo no cuenta: el recordatorio es para lo que la persona registra.
            if (repo.enRango(hoy, Retencion.sumarDias(hoy, 1)).any { it.fijoId.isBlank() }) {
                Log.i(Avisos.ETIQUETA, "recordatorio omitido: ya registró gastos hoy")
                return@withContext Result.success()
            }
            val racha = Actividad.rachaActual(repo)
            val c = applicationContext
            val texto = if (racha > 0) {
                c.resources.getQuantityString(R.plurals.recordatorio_con_racha, racha, racha)
            } else {
                c.getString(R.string.recordatorio_sin_racha)
            }
            Avisos.mostrar(c, Avisos.CANAL_RECORDATORIOS, ID_RECORDATORIO, c.getString(R.string.recordatorio_titulo),
                texto, Avisos.DESTINO_REGISTRAR, Avisos.TIPO_RECORDATORIO)
            Result.success()
        } catch (e: Exception) {
            Log.w(Avisos.ETIQUETA, "recordatorio sin datos: ${e.javaClass.simpleName}")
            Result.retry()
        }
    }
}

/** Cada domingo cuenta cuánto se gastó en la semana, en qué y cómo se compara con la anterior. */
class ResumenTarea(contexto: Context, parametros: WorkerParameters) : CoroutineWorker(contexto, parametros) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (FirebaseAuth.getInstance().currentUser == null || !Avisos.resumenActivo(applicationContext)) {
            return@withContext Result.success()
        }
        try {
            val repo = GastosRepositorio()
            val manana = Retencion.sumarDias(Retencion.inicioDelDia(Date()), 1)
            val inicio = Retencion.sumarDias(manana, -7)
            val semana = repo.enRango(inicio, manana)
            val anterior = repo.enRango(Retencion.sumarDias(inicio, -7), inicio).sumOf { it.monto }
            val nombres = CategoriasRepositorio().nombres()
            val resumen = Retencion.resumenSemanal(semana.map { it.categoriaId to it.monto }, anterior)
            if (resumen == null) {
                Log.i(Avisos.ETIQUETA, "resumen omitido: sin gastos en la semana")
                return@withContext Result.success()
            }
            val c = applicationContext
            val partes = mutableListOf(
                c.resources.getQuantityString(R.plurals.resumen_total, resumen.cantidad, Plata.formatear(resumen.total), resumen.cantidad)
            )
            nombres[resumen.categoriaPrincipal]?.takeIf { it.isNotBlank() }?.let {
                partes += c.getString(R.string.resumen_principal, it, resumen.porcentajePrincipal)
            }
            resumen.variacion?.let { v ->
                partes += when {
                    v < 0 -> c.getString(R.string.resumen_menos, -v)
                    v > 0 -> c.getString(R.string.resumen_mas, v)
                    else -> c.getString(R.string.resumen_igual)
                }
            }
            Avisos.mostrar(c, Avisos.CANAL_RESUMEN, ID_RESUMEN, c.getString(R.string.resumen_titulo),
                partes.joinToString(" "), Avisos.DESTINO_RESUMEN, Avisos.TIPO_RESUMEN)
            Result.success()
        } catch (e: Exception) {
            Log.w(Avisos.ETIQUETA, "resumen sin datos: ${e.javaClass.simpleName}")
            Result.retry()
        }
    }
}

/** Llega solo si pasaron los días configurados sin abrir la aplicación. */
class InactividadTarea(contexto: Context, parametros: WorkerParameters) : CoroutineWorker(contexto, parametros) {

    override suspend fun doWork(): Result {
        val c = applicationContext
        if (FirebaseAuth.getInstance().currentUser == null || !Avisos.inactividadActiva(c)) return Result.success()
        val dias = ParametrosRemotos.diasInactividad()
        Avisos.mostrar(c, Avisos.CANAL_RECORDATORIOS, ID_INACTIVIDAD, c.getString(R.string.inactividad_titulo),
            c.getString(R.string.inactividad_texto, dias), Avisos.DESTINO_REGISTRAR, Avisos.TIPO_INACTIVIDAD)
        return Result.success()
    }
}
