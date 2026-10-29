package ar.edu.utn.frsfco.finanzas

import android.app.Activity
import android.app.Application
import android.content.Context
import android.util.Log
import com.revenuecat.purchases.CustomerInfo
import com.revenuecat.purchases.Offerings
import com.revenuecat.purchases.Package
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.PurchasesConfiguration
import com.revenuecat.purchases.PurchasesTransactionException
import com.revenuecat.purchases.PurchaseParams
import com.revenuecat.purchases.interfaces.UpdatedCustomerInfoListener
import com.revenuecat.purchases.awaitCustomerInfo
import com.revenuecat.purchases.awaitLogIn
import com.revenuecat.purchases.awaitLogOut
import com.revenuecat.purchases.awaitOfferings
import com.revenuecat.purchases.awaitPurchase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Resultado de intentar comprar un plan. */
sealed interface ResultadoCompra {
    data object Exitosa : ResultadoCompra
    data object Cancelada : ResultadoCompra
    data class Fallida(val motivo: String) : ResultadoCompra
}

/**
 * Estado de la suscripción Plus.
 *
 * Las compras las procesa RevenueCat, que guarda quién tiene el permiso "plus". La aplicación
 * solo consulta ese permiso, de modo que ningún dato financiero viaja a ese servicio: lo único
 * que recibe es el identificador de la cuenta. El último estado conocido queda guardado en el
 * teléfono para que el banner no reaparezca mientras se consulta la red.
 */
object Suscripcion {

    private const val PERMISO = "plus"
    private const val PREFERENCIAS = "suscripcion"

    private val _esPlus = MutableStateFlow(false)
    val esPlus: StateFlow<Boolean> = _esPlus.asStateFlow()

    private lateinit var aplicacion: Application
    private var configurada = false
    private var uidActual: String? = null

    /** Sin clave (versión de lanzamiento o clave ausente) la monetización queda apagada. */
    val disponible: Boolean get() = configurada

    /** Configura el servicio una sola vez al arrancar. [uid] es la cuenta que ya tenía sesión, si hay. */
    fun iniciar(app: Application, uid: String?) {
        aplicacion = app
        val clave = BuildConfig.RC_API_KEY
        if (clave.isEmpty()) {
            Log.i(Embudo.ETIQUETA, "compras apagadas: sin clave de la tienda de prueba")
            return
        }
        Purchases.configure(PurchasesConfiguration.Builder(app, clave).appUserID(uid).build())
        Purchases.sharedInstance.updatedCustomerInfoListener = UpdatedCustomerInfoListener { aplicarEstado(it) }
        configurada = true
        uidActual = uid
        _esPlus.value = leerGuardado(uid)
        Log.i(Embudo.ETIQUETA, "compras listas, plus guardado=${_esPlus.value}")
    }

    /** Asocia las compras a la cuenta que acaba de iniciar sesión. */
    suspend fun identificar(uid: String) {
        if (!configurada) return
        if (uid == uidActual) { refrescar(); return }
        try {
            val resultado = Purchases.sharedInstance.awaitLogIn(uid)
            uidActual = uid
            aplicarEstado(resultado.customerInfo)
        } catch (e: Exception) {
            Log.w(Embudo.ETIQUETA, "no se pudo identificar la cuenta: ${e.javaClass.simpleName}")
        }
    }

    /** Desvincula las compras al cerrar sesión, para que otra cuenta no herede el permiso. */
    suspend fun olvidar() {
        if (!configurada) return
        try {
            Purchases.sharedInstance.awaitLogOut()
        } catch (e: Exception) {
            Log.w(Embudo.ETIQUETA, "no se pudo cerrar la cuenta de compras: ${e.javaClass.simpleName}")
        }
        uidActual = null
        _esPlus.value = false
    }

    /** Vuelve a consultar el estado en la red, por ejemplo al abrir la pantalla. */
    suspend fun refrescar() {
        if (!configurada) return
        try {
            aplicarEstado(Purchases.sharedInstance.awaitCustomerInfo())
        } catch (e: Exception) {
            Log.w(Embudo.ETIQUETA, "no se pudo consultar el estado: ${e.javaClass.simpleName}")
        }
    }

    /** Planes que se ofrecen. Los precios los define la consola, no la aplicación. */
    suspend fun planes(): List<Package> {
        val ofertas: Offerings = Purchases.sharedInstance.awaitOfferings()
        val paquetes = ofertas.current?.availablePackages.orEmpty()
        Log.i(Embudo.ETIQUETA, "oferta cargada paquetes=${paquetes.size}")
        return paquetes
    }

    suspend fun comprar(actividad: Activity, paquete: Package): ResultadoCompra =
        try {
            val resultado = Purchases.sharedInstance.awaitPurchase(PurchaseParams.Builder(actividad, paquete).build())
            aplicarEstado(resultado.customerInfo)
            Log.i(Embudo.ETIQUETA, "compra ok plan=${paquete.identifier} plus=${_esPlus.value}")
            ResultadoCompra.Exitosa
        } catch (e: PurchasesTransactionException) {
            if (e.userCancelled) {
                Log.i(Embudo.ETIQUETA, "compra cancelada plan=${paquete.identifier}")
                ResultadoCompra.Cancelada
            } else {
                Log.w(Embudo.ETIQUETA, "compra fallida plan=${paquete.identifier} codigo=${e.code}")
                ResultadoCompra.Fallida(e.error.message)
            }
        }

    private fun aplicarEstado(info: CustomerInfo) {
        val activo = info.entitlements[PERMISO]?.isActive == true
        _esPlus.value = activo
        guardar(uidActual, activo)
        Log.i(Embudo.ETIQUETA, "permiso $PERMISO activo=$activo")
    }

    private fun preferencias() = aplicacion.getSharedPreferences(PREFERENCIAS, Context.MODE_PRIVATE)

    private fun leerGuardado(uid: String?): Boolean =
        uid != null && preferencias().getBoolean("plus_$uid", false)

    private fun guardar(uid: String?, activo: Boolean) {
        if (uid != null) preferencias().edit().putBoolean("plus_$uid", activo).apply()
    }
}
