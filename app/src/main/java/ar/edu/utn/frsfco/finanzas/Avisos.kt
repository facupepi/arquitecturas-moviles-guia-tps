package ar.edu.utn.frsfco.finanzas

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.firebase.analytics.FirebaseAnalytics
import java.util.concurrent.TimeUnit

/**
 * Notificaciones de retención: las que programa el teléfono y las que llegan de Firebase.
 *
 * Cada tipo de aviso tiene su canal, para que la persona pueda apagarlo desde los ajustes
 * del sistema sin perder los demás, y su interruptor en los ajustes de la aplicación.
 * Ningún aviso se envía si no hay permiso o si la persona lo desactivó.
 */
object Avisos {

    const val ETIQUETA = "Retencion"

    const val CANAL_RECORDATORIOS = "recordatorios"
    const val CANAL_RESUMEN = "resumen_semanal"
    const val CANAL_NOVEDADES = "novedades"

    /** Pantalla que se abre al tocar la notificación. */
    const val DESTINO = "destino"
    const val DESTINO_REGISTRAR = "registrar"
    const val DESTINO_RESUMEN = "resumen"
    const val DESTINO_LOGROS = "logros"
    const val DESTINO_PLANES = "planes"

    /** Qué aviso fue, para medir cuáles hacen volver a la aplicación. */
    const val TIPO = "tipo_aviso"
    const val TIPO_RECORDATORIO = "recordatorio_diario"
    const val TIPO_RESUMEN = "resumen_semanal"
    const val TIPO_INACTIVIDAD = "inactividad"
    const val TIPO_CAMPANA = "campana"

    private const val TAREA_RECORDATORIO = "recordatorio_diario"
    private const val TAREA_RESUMEN = "resumen_semanal"
    private const val TAREA_INACTIVIDAD = "aviso_inactividad"

    const val HORA_RESUMEN = 20

    private const val PREFERENCIAS = "avisos"
    private const val CLAVE_RECORDATORIO = "recordatorio"
    private const val CLAVE_HORA = "hora"
    private const val CLAVE_MINUTO = "minuto"
    private const val CLAVE_RESUMEN = "resumen"
    private const val CLAVE_INACTIVIDAD = "inactividad"
    private const val CLAVE_NOVEDADES = "novedades"
    private const val CLAVE_PERMISO_PEDIDO = "permiso_pedido"
    private const val CLAVE_HORA_PROGRAMADA = "hora_programada"

    private fun preferencias(c: Context) = c.getSharedPreferences(PREFERENCIAS, Context.MODE_PRIVATE)

    // Preferencias de la persona. Por defecto todo encendido: el sistema igual pide permiso.
    fun recordatorioActivo(c: Context) = preferencias(c).getBoolean(CLAVE_RECORDATORIO, true)
    fun resumenActivo(c: Context) = preferencias(c).getBoolean(CLAVE_RESUMEN, true)
    fun inactividadActiva(c: Context) = preferencias(c).getBoolean(CLAVE_INACTIVIDAD, true)
    fun novedadesActivas(c: Context) = preferencias(c).getBoolean(CLAVE_NOVEDADES, true)

    /** Hora elegida para el recordatorio; si nunca eligió, la que define la configuración remota. */
    fun hora(c: Context) = preferencias(c).getInt(CLAVE_HORA, ParametrosRemotos.horaRecordatorio())
    fun minuto(c: Context) = preferencias(c).getInt(CLAVE_MINUTO, 0)

    fun guardar(c: Context, clave: String, valor: Boolean) {
        preferencias(c).edit().putBoolean(clave, valor).apply()
        programar(c)
        if (clave == CLAVE_NOVEDADES) Temas.sincronizar(c)
        Log.i(ETIQUETA, "preferencia $clave=$valor")
    }

    fun guardarRecordatorio(c: Context, activo: Boolean) = guardar(c, CLAVE_RECORDATORIO, activo)
    fun guardarResumen(c: Context, activo: Boolean) = guardar(c, CLAVE_RESUMEN, activo)
    fun guardarInactividad(c: Context, activo: Boolean) = guardar(c, CLAVE_INACTIVIDAD, activo)
    fun guardarNovedades(c: Context, activo: Boolean) = guardar(c, CLAVE_NOVEDADES, activo)

    fun guardarHorario(c: Context, hora: Int, minuto: Int) {
        preferencias(c).edit().putInt(CLAVE_HORA, hora).putInt(CLAVE_MINUTO, minuto).apply()
        programar(c)
    }

    /** El permiso se pide una sola vez desde la aplicación; después queda en los ajustes. */
    fun permisoYaPedido(c: Context) = preferencias(c).getBoolean(CLAVE_PERMISO_PEDIDO, false)
    fun marcarPermisoPedido(c: Context) = preferencias(c).edit().putBoolean(CLAVE_PERMISO_PEDIDO, true).apply()

    /** Si el sistema permite mostrar notificaciones de esta aplicación. */
    fun permitidas(c: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(c).areNotificationsEnabled()
    }

    /** Crea los canales. Repetirlo no cambia los que ya existen ni lo que la persona ajustó. */
    fun crearCanales(c: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val gestor = c.getSystemService(NotificationManager::class.java)
        gestor.createNotificationChannels(
            listOf(
                NotificationChannel(CANAL_RECORDATORIOS, c.getString(R.string.canal_recordatorios), NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = c.getString(R.string.canal_recordatorios_detalle) },
                NotificationChannel(CANAL_RESUMEN, c.getString(R.string.canal_resumen), NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = c.getString(R.string.canal_resumen_detalle) },
                NotificationChannel(CANAL_NOVEDADES, c.getString(R.string.canal_novedades), NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = c.getString(R.string.canal_novedades_detalle) }
            )
        )
    }

    /** Muestra una notificación que, al tocarla, abre la aplicación en la pantalla indicada. */
    fun mostrar(c: Context, canal: String, id: Int, titulo: String, texto: String, destino: String?, tipo: String) {
        if (!permitidas(c)) {
            Log.i(ETIQUETA, "aviso $tipo sin permiso, no se muestra")
            return
        }
        val abrir = Intent(c, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra(DESTINO, destino ?: DESTINO_RESUMEN)
            putExtra(TIPO, tipo)
        }
        val pendiente = PendingIntent.getActivity(
            c, id, abrir, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val aviso = NotificationCompat.Builder(c, canal)
            .setSmallIcon(R.drawable.ic_notificacion)
            .setColor(ContextCompat.getColor(c, R.color.verde))
            .setContentTitle(titulo)
            .setContentText(texto)
            .setStyle(NotificationCompat.BigTextStyle().bigText(texto))
            .setContentIntent(pendiente)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(c).notify(id, aviso)
            evento(c, "notificacion_mostrada", TIPO, tipo)
        } catch (e: SecurityException) {
            Log.w(ETIQUETA, "el sistema rechazó el aviso $tipo")
        }
    }

    /**
     * Programa los avisos según las preferencias. Se llama al abrir la aplicación y al cambiar
     * un ajuste. El recordatorio solo se reprograma si cambió el horario, así no se corre.
     */
    fun programar(c: Context) {
        val trabajos = WorkManager.getInstance(c)
        if (recordatorioActivo(c)) {
            val horario = hora(c) * 60 + minuto(c)
            val politica = if (preferencias(c).getInt(CLAVE_HORA_PROGRAMADA, -1) == horario) {
                ExistingPeriodicWorkPolicy.KEEP
            } else {
                ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE
            }
            val pedido = PeriodicWorkRequestBuilder<RecordatorioTarea>(1, TimeUnit.DAYS)
                .setInitialDelay(Retencion.esperaHasta(System.currentTimeMillis(), hora(c), minuto(c)), TimeUnit.MILLISECONDS)
                .build()
            trabajos.enqueueUniquePeriodicWork(TAREA_RECORDATORIO, politica, pedido)
            preferencias(c).edit().putInt(CLAVE_HORA_PROGRAMADA, horario).apply()
        } else {
            trabajos.cancelUniqueWork(TAREA_RECORDATORIO)
            preferencias(c).edit().remove(CLAVE_HORA_PROGRAMADA).apply()
        }
        if (resumenActivo(c)) {
            val pedido = PeriodicWorkRequestBuilder<ResumenTarea>(7, TimeUnit.DAYS)
                .setInitialDelay(Retencion.esperaHastaDomingo(System.currentTimeMillis(), HORA_RESUMEN), TimeUnit.MILLISECONDS)
                .build()
            trabajos.enqueueUniquePeriodicWork(TAREA_RESUMEN, ExistingPeriodicWorkPolicy.KEEP, pedido)
        } else {
            trabajos.cancelUniqueWork(TAREA_RESUMEN)
        }
        if (!inactividadActiva(c)) trabajos.cancelUniqueWork(TAREA_INACTIVIDAD)
    }

    /**
     * Cada vez que la aplicación se usa, el aviso de inactividad se corre hacia adelante.
     * Solo llega si la persona deja pasar todos esos días sin abrirla.
     */
    fun reprogramarInactividad(c: Context) {
        if (!inactividadActiva(c)) return
        val dias = ParametrosRemotos.diasInactividad()
        val pedido = OneTimeWorkRequestBuilder<InactividadTarea>()
            .setInitialDelay(dias.toLong(), TimeUnit.DAYS)
            .build()
        WorkManager.getInstance(c).enqueueUniqueWork(TAREA_INACTIVIDAD, ExistingWorkPolicy.REPLACE, pedido)
        Log.i(ETIQUETA, "aviso de inactividad reprogramado a $dias días")
    }

    /** Al cerrar sesión no queda ningún aviso pendiente de la cuenta anterior. */
    fun cancelarTodo(c: Context) {
        WorkManager.getInstance(c).cancelAllWork()
        preferencias(c).edit().remove(CLAVE_HORA_PROGRAMADA).apply()
    }

    /** Evento de Analytics de la retención. También queda en el registro, para comprobarlo. */
    fun evento(c: Context, nombre: String, clave: String? = null, valor: String? = null) {
        val datos = Bundle().apply { if (clave != null && valor != null) putString(clave, valor) }
        FirebaseAnalytics.getInstance(c).logEvent(nombre, datos)
        Log.i(ETIQUETA, "evento=$nombre" + (valor?.let { " $clave=$it" } ?: ""))
    }
}
