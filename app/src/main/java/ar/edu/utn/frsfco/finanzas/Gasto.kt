package ar.edu.utn.frsfco.finanzas

import android.graphics.Color
import java.io.Serializable
import java.util.Date

// Los modelos se marcan como Serializable porque viajan en los argumentos y en el
// estado guardado de las hojas modales: al girar la pantalla el sistema las rehace y
// sin eso llegarían vacías.

/**
 * Un gasto cargado por el usuario.
 *
 * En Firestore el campo se sigue llamando `categoria` y guarda el identificador de
 * la categoría elegida, que ahora es un documento editable y no un valor fijo.
 */
data class Gasto(
    val id: String = "",
    val monto: Double = 0.0,
    val categoriaId: String = "",
    val detalle: String = "",
    val fecha: Date = Date(),
    val medioId: String = "",
    /** Identificador del gasto fijo que lo generó, vacío si se cargó a mano. */
    val fijoId: String = ""
) : Serializable

/**
 * Cómo se pagó un gasto.
 *
 * Igual que las categorías, los define cada usuario: hay quien paga con billetera
 * virtual, con transferencia o con una tarjeta puntual, y una lista fija no alcanzaba.
 */
data class Medio(
    override val id: String = "",
    override val nombre: String = "",
    /** Color en formato #RRGGBB. Se guarda como texto para poder editarlo. */
    val color: String = "#5C7CFA",
    /** Clave del ícono dentro del catálogo disponible. */
    val icono: String = "billetera",
    val orden: Int = 0
) : Etiqueta, Serializable {

    override fun colorEntero(): Int = try {
        Color.parseColor(color)
    } catch (e: IllegalArgumentException) {
        Color.parseColor("#5C7CFA")
    }

    override fun colorSuave(): Int {
        val base = colorEntero()
        return Color.argb(28, Color.red(base), Color.green(base), Color.blue(base))
    }

    override fun iconoDibujable(): Int = Iconos.porClave(icono)

    companion object {
        /** Los que se crean la primera vez, con las claves que ya usan los gastos. */
        fun iniciales(): List<Medio> = listOf(
            Medio("efectivo", "Efectivo", "#0CA678", "billetera", 0),
            Medio("debito", "Débito", "#1C7ED6", "tarjeta", 1),
            Medio("credito", "Crédito", "#D6336C", "tarjeta", 2)
        )

        /** Se muestra cuando un gasto quedó apuntando a un medio borrado. */
        fun desconocido() = Medio("", "Sin especificar", "#8A8A8A", "otros", 99)
    }
}


/** Un ingreso: el sueldo, una venta, lo que sea que entró. */
data class Ingreso(
    val id: String = "",
    val monto: Double = 0.0,
    val detalle: String = "",
    val fecha: Date = Date()
) : Serializable

/**
 * Un gasto que se repite todos los meses, como el alquiler.
 *
 * No se guarda como gasto hasta que llega su día. Cuando llega, la aplicación lo
 * crea sola una única vez por mes.
 */
data class GastoFijo(
    val id: String = "",
    val nombre: String = "",
    val monto: Double = 0.0,
    val categoriaId: String = "",
    val medioId: String = "",
    /** Día del mes en que corresponde. Si el mes es más corto, se usa el último día. */
    val diaDelMes: Int = 1,
    /** Mes (aaaa-mm) en que el usuario borró el gasto generado; ese mes no se vuelve a crear. */
    val omitidoMes: String = ""
) : Serializable
