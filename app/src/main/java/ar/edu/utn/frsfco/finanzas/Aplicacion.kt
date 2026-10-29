package ar.edu.utn.frsfco.finanzas

import android.app.Application
import android.content.res.Configuration
import com.google.firebase.auth.FirebaseAuth
import java.util.Locale

/**
 * Arranque de la aplicación.
 *
 * Deja el idioma en español de Argentina para todo lo que arman las bibliotecas, como
 * los nombres de los meses del calendario. Hay que reponerlo en cada cambio de
 * configuración porque el sistema vuelve a imponer el idioma del equipo al rehacer
 * los recursos. Las pantallas además fuerzan el suyo en [PantallaBase].
 */
class Aplicacion : Application() {

    override fun onCreate() {
        super.onCreate()
        Idioma.fijar()
        ParametrosRemotos.iniciar()
        Suscripcion.iniciar(this, FirebaseAuth.getInstance().currentUser?.uid)
    }

    override fun onConfigurationChanged(nueva: Configuration) {
        super.onConfigurationChanged(nueva)
        Idioma.fijar()
    }
}

/** El único idioma en el que está escrita la aplicación. */
object Idioma {

    val español: Locale = Locale("es", "AR")

    fun fijar() = Locale.setDefault(español)
}
