package ar.edu.utn.frsfco.finanzas

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import androidx.lifecycle.lifecycleScope
import ar.edu.utn.frsfco.finanzas.databinding.ActivityLoginBinding
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Pantalla de entrada.
 *
 * Si ya hay una sesión abierta pasa directo al resumen. Si no, ofrece entrar con una
 * cuenta de Google, o probar la aplicación sin dar ninguna cuenta. Pedir la cuenta
 * antes de dejar ver nada hacía que la gente cerrara la aplicación en la primera
 * pantalla.
 */
class LoginActivity : PantallaBase() {

    private lateinit var binding: ActivityLoginBinding
    private val auth by lazy { FirebaseAuth.getInstance() }
    private val acceso by lazy { AccesoGoogle(this) }

    companion object {
        private const val TAG = "TP2-Login"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnEntrar.setOnClickListener { entrarConGoogle() }
        binding.btnProbar.setOnClickListener { probarSinCuenta() }
        binding.btnAgregarCuenta.setOnClickListener { abrirAltaDeCuenta() }
    }

    override fun onNewIntent(nuevo: Intent) {
        super.onNewIntent(nuevo)
        // Con singleTask, una notificación tocada llega acá; se conserva para pasar el destino.
        setIntent(nuevo)
    }

    override fun onStart() {
        super.onStart()
        // Firebase guarda la sesión, así que sólo se pide la cuenta la primera vez.
        if (auth.currentUser != null) {
            irAlResumen()
        }
    }

    /**
     * Abre una sesión sin cuenta para poder probar.
     *
     * Los gastos quedan guardados igual, colgando de un identificador anónimo. Si más
     * adelante la persona entra con Google, ese mismo identificador se conserva y no
     * pierde nada de lo que cargó.
     */
    private fun probarSinCuenta() {
        mostrarEspera(true)
        lifecycleScope.launch {
            try {
                auth.signInAnonymously().await()
                Log.d(TAG, "entró sin cuenta")
                irAlResumen()
            } catch (e: Exception) {
                mostrarEspera(false)
                Log.e(TAG, "no se pudo entrar sin cuenta", e)
                avisar(getString(R.string.error_invitado))
            }
        }
    }

    private fun entrarConGoogle() {
        mostrarEspera(true)
        lifecycleScope.launch {
            try {
                if (acceso.entrar() == AccesoGoogle.Resultado.YA_EXISTIA) {
                    avisar(getString(R.string.cuenta_ya_existia))
                }
                irAlResumen()
            } catch (e: GetCredentialCancellationException) {
                // El usuario cerró el selector de cuentas. No es un error.
                Log.d(TAG, "el usuario canceló la elección de cuenta")
                mostrarEspera(false)
            } catch (e: NoCredentialException) {
                // El teléfono no tiene ninguna cuenta de Google. El sistema no permite
                // crearla desde acá, así que se ofrece abrir la pantalla que sí lo hace.
                mostrarEspera(false)
                binding.btnAgregarCuenta.visibility = View.VISIBLE
                avisar(getString(R.string.sin_cuentas))
            } catch (e: GetCredentialException) {
                mostrarEspera(false)
                Log.e(TAG, "falló el pedido de credenciales", e)
                avisar(getString(R.string.error_entrada))
            } catch (e: Exception) {
                mostrarEspera(false)
                Log.e(TAG, "falló la autenticación", e)
                avisar(getString(R.string.error_entrada))
            }
        }
    }

    /**
     * Abre la pantalla del sistema que agrega una cuenta de Google. Agregar la cuenta
     * es una tarea del sistema operativo y ninguna aplicación puede hacerla por su
     * cuenta, así que lo mejor posible es llevar al usuario hasta ahí.
     */
    private fun abrirAltaDeCuenta() {
        val alta = Intent(Settings.ACTION_ADD_ACCOUNT).apply {
            putExtra(Settings.EXTRA_ACCOUNT_TYPES, arrayOf("com.google"))
        }
        if (alta.resolveActivity(packageManager) != null) {
            startActivity(alta)
            return
        }
        // Algunos teléfonos no exponen esa pantalla. Se cae a los ajustes de cuentas.
        val ajustes = Intent(Settings.ACTION_SYNC_SETTINGS)
        if (ajustes.resolveActivity(packageManager) != null) {
            startActivity(ajustes)
        } else {
            avisar(getString(R.string.sin_pantalla_cuentas))
        }
    }

    private fun irAlResumen() {
        // Si se entró tocando una notificación, el destino viaja hasta el resumen.
        // Si el resumen ya estaba abierto, lo reutiliza y le entrega el destino como pedido nuevo.
        startActivity(Intent(this, PrincipalActivity::class.java).apply {
            intent.extras?.let { putExtras(it) }
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        finish()
    }

    /**
     * Mientras se resuelve la entrada, el botón se queda sin texto ni ícono y en su
     * lugar gira el indicador. Como el indicador está encima del botón y no debajo,
     * la pantalla no se mueve.
     */
    private fun mostrarEspera(esperando: Boolean) {
        binding.progreso.visibility = if (esperando) View.VISIBLE else View.GONE
        binding.btnEntrar.isEnabled = !esperando
        binding.btnProbar.isEnabled = !esperando
        binding.btnEntrar.text = if (esperando) "" else getString(R.string.btn_entrar)
        binding.btnEntrar.icon = if (esperando) {
            null
        } else {
            ContextCompat.getDrawable(this, R.drawable.ic_google)
        }
    }

    private fun avisar(mensaje: String) {
        Toast.makeText(this, mensaje, Toast.LENGTH_LONG).show()
    }
}
