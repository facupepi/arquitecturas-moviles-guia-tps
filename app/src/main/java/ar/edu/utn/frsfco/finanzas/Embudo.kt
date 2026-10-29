package ar.edu.utn.frsfco.finanzas

import android.content.Context
import android.os.Bundle
import android.util.Log
import com.google.firebase.analytics.FirebaseAnalytics

/**
 * Eventos que miden el recorrido hasta la compra.
 *
 * Nunca llevan montos ni datos de gastos: solo qué pantalla se vio y qué plan se eligió.
 * Cada evento también queda en el registro del sistema con la etiqueta de monetización,
 * que es lo que se usa para comprobarlos en el teléfono.
 */
object Embudo {

    const val ETIQUETA = "Monetizacion"

    const val PLANES_VISTOS = "paywall_visto"
    const val QUITAR_ANUNCIOS = "quitar_anuncios_toque"
    const val PLAN_ELEGIDO = "plan_elegido"
    const val COMPRA_INICIADA = "compra_iniciada"
    const val COMPRA_OK = "compra_ok"
    const val COMPRA_CANCELADA = "compra_cancelada"
    const val CUPO_AGOTADO = "cupo_escaneo_agotado"

    fun registrar(contexto: Context, evento: String, plan: String? = null) {
        val datos = Bundle().apply { plan?.let { putString("plan", it) } }
        FirebaseAnalytics.getInstance(contexto).logEvent(evento, datos)
        Log.i(ETIQUETA, "evento=$evento" + (plan?.let { " plan=$it" } ?: ""))
    }
}
