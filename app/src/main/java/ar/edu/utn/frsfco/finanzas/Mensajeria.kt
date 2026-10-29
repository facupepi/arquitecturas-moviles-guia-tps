package ar.edu.utn.frsfco.finanzas

import android.content.Context
import android.util.Log
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Temas de Firebase Cloud Messaging a los que se suscribe esta versión.
 *
 * Todos llevan el prefijo de la versión, de modo que una campaña enviada a un tema nunca
 * llega a las versiones anteriores, que comparten el mismo proyecto de Firebase.
 */
object Temas {

    const val TODOS = "v6_todos"
    const val GRATUITO = "v6_gratuito"
    const val PLUS = "v6_plus"

    /**
     * Deja las suscripciones de acuerdo con el plan y con el ajuste de novedades.
     * Además guarda el plan como propiedad del usuario, para segmentar en la consola.
     */
    fun sincronizar(c: Context) {
        val mensajeria = FirebaseMessaging.getInstance()
        val plus = Suscripcion.esPlus.value
        FirebaseAnalytics.getInstance(c).setUserProperty("plan", if (plus) "plus" else "gratuito")
        FirebaseAnalytics.getInstance(c).setUserProperty("avisos", if (Avisos.permitidas(c)) "permitidos" else "bloqueados")
        FirebaseAnalytics.getInstance(c).setUserProperty("novedades", if (Avisos.novedadesActivas(c)) "si" else "no")
        if (Avisos.novedadesActivas(c)) {
            // Se vuelve a habilitar el registro del teléfono por si se había apagado.
            mensajeria.isAutoInitEnabled = true
            mensajeria.subscribeToTopic(TODOS)
            mensajeria.subscribeToTopic(if (plus) PLUS else GRATUITO)
            mensajeria.unsubscribeFromTopic(if (plus) GRATUITO else PLUS)
            Log.i(Avisos.ETIQUETA, "temas: $TODOS y ${if (plus) PLUS else GRATUITO}")
            if (BuildConfig.DEBUG) {
                // En depuración se muestra el token para enviar mensajes de prueba desde la consola.
                mensajeria.token.addOnSuccessListener { Log.i(Avisos.ETIQUETA, "token=$it") }
            }
        } else {
            listOf(TODOS, GRATUITO, PLUS).forEach { mensajeria.unsubscribeFromTopic(it) }
            // Sin temas no alcanza: una campaña dirigida a la aplicación la mostraría igual el sistema
            // con la aplicación cerrada. Se apaga el registro del teléfono y se borra su token.
            if (mensajeria.isAutoInitEnabled) {
                mensajeria.isAutoInitEnabled = false
                mensajeria.deleteToken()
            }
            Log.i(Avisos.ETIQUETA, "temas: ninguno, novedades desactivadas")
        }
    }
}

/**
 * Recibe los mensajes de Firebase Cloud Messaging.
 *
 * Con la aplicación cerrada o en segundo plano, el sistema muestra solo la notificación de
 * una campaña y, al tocarla, abre la aplicación con los datos adicionales. Con la aplicación
 * abierta el mensaje llega acá y se muestra con el mismo formato que los avisos propios.
 */
class ServicioMensajes : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        Log.i(Avisos.ETIQUETA, "token nuevo")
        Temas.sincronizar(applicationContext)
    }

    override fun onMessageReceived(mensaje: RemoteMessage) {
        val titulo = mensaje.notification?.title ?: mensaje.data["titulo"] ?: return
        val texto = mensaje.notification?.body ?: mensaje.data["texto"].orEmpty()
        Log.i(Avisos.ETIQUETA, "mensaje recibido de ${mensaje.from} destino=${mensaje.data[Avisos.DESTINO]}")
        if (!Avisos.novedadesActivas(this)) return
        Avisos.mostrar(
            this, Avisos.CANAL_NOVEDADES, (mensaje.messageId ?: titulo).hashCode(),
            titulo, texto, mensaje.data[Avisos.DESTINO], Avisos.TIPO_CAMPANA
        )
    }
}
