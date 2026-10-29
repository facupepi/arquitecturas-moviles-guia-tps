package ar.edu.utn.frsfco.finanzas

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.ads.mediation.admob.AdMobAdapter
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.concurrent.thread

/**
 * Publicidad de la versión gratuita: un banner fijo sobre el menú de abajo.
 *
 * Los anuncios no son personalizados, así que no se usa ningún dato de la cuenta ni de los
 * gastos para elegirlos. Con la suscripción activa el bloque entero desaparece.
 */
object Anuncios {

    private var iniciado = false

    /** Verifica el consentimiento y recién después inicia el servicio de anuncios. */
    private fun iniciar(actividad: Activity, alListo: () -> Unit) {
        if (iniciado) { alListo(); return }
        val consentimiento = UserMessagingPlatform.getConsentInformation(actividad)
        val iniciar = {
            if (consentimiento.canRequestAds() && !iniciado) {
                iniciado = true
                // La inicialización puede tardar: se hace fuera del hilo principal.
                thread { MobileAds.initialize(actividad.applicationContext); actividad.runOnUiThread(alListo) }
            } else if (iniciado) {
                alListo()
            } else {
                Log.i(Embudo.ETIQUETA, "anuncios sin consentimiento")
            }
        }
        consentimiento.requestConsentInfoUpdate(
            actividad, ConsentRequestParameters.Builder().build(),
            { iniciar() },
            { error -> Log.w(Embudo.ETIQUETA, "consentimiento: ${error.message}"); iniciar() }
        )
    }

    /**
     * Instala el bloque de anuncios en una pantalla.
     *
     * @param bloque contenedor con la fila "Quitar anuncios" y el espacio del banner
     * @param menu menú de abajo sobre el que se apoya el bloque, si la pantalla lo tiene
     * @param alCambiarAlto avisa cuánto mide el bloque (cero si no se ve) para reacomodar el resto
     */
    fun instalar(
        actividad: AppCompatActivity,
        bloque: View,
        menu: View?,
        alCambiarAlto: (Int) -> Unit
    ) {
        val contenedor = bloque.findViewById<FrameLayout>(R.id.contenedorBanner)
        val quitar = bloque.findViewById<TextView>(R.id.btnQuitarAnuncios)
        quitar.setOnClickListener {
            Embudo.registrar(actividad, Embudo.QUITAR_ANUNCIOS)
            PlanesSheet().show(actividad.supportFragmentManager, "planes")
        }

        // El bloque se apoya justo encima del menú, sea cual sea su alto en este teléfono.
        menu?.let { barra ->
            barra.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
                val params = bloque.layoutParams as? ViewGroup.MarginLayoutParams ?: return@addOnLayoutChangeListener
                val alto = bottom - top
                if (params.bottomMargin != alto) { params.bottomMargin = alto; bloque.layoutParams = params }
            }
        }
        bloque.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            alCambiarAlto(if (bloque.visibility == View.VISIBLE) bottom - top else 0)
        }

        var banner: AdView? = null
        actividad.lifecycleScope.launch {
            actividad.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(Suscripcion.esPlus, ParametrosRemotos.mostrarBanner) { plus, permitido -> !plus && permitido }
                    .collect { corresponde ->
                        if (!corresponde) {
                            bloque.visibility = View.GONE
                            alCambiarAlto(0)
                            banner?.destroy(); banner = null; contenedor.removeAllViews()
                        } else {
                            bloque.visibility = View.VISIBLE
                            if (banner == null) iniciar(actividad) { banner = cargarBanner(actividad, contenedor) }
                        }
                    }
            }
        }
    }

    private fun cargarBanner(actividad: Activity, contenedor: FrameLayout): AdView {
        val densidad = actividad.resources.displayMetrics.density
        val ancho = (actividad.resources.displayMetrics.widthPixels / densidad).toInt()
        val vista = AdView(actividad).apply {
            setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(actividad, ancho))
            adUnitId = BuildConfig.ADMOB_BANNER_ID
            adListener = object : AdListener() {
                override fun onAdLoaded() { Log.i(Embudo.ETIQUETA, "banner cargado") }
                override fun onAdFailedToLoad(error: LoadAdError) {
                    Log.w(Embudo.ETIQUETA, "banner sin cargar codigo=${error.code} ${error.message}")
                }
                override fun onAdImpression() { Log.i(Embudo.ETIQUETA, "banner impresion") }
            }
        }
        contenedor.removeAllViews()
        contenedor.addView(vista)
        // Anuncios no personalizados: no se usa ningún dato del usuario para elegirlos.
        val extras = Bundle().apply { putString("npa", "1") }
        vista.loadAd(AdRequest.Builder().addNetworkExtrasBundle(AdMobAdapter::class.java, extras).build())
        return vista
    }
}
