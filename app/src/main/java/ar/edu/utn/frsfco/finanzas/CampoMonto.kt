package ar.edu.utn.frsfco.finanzas

import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText

/**
 * Va agrupando de a miles mientras se escribe un importe.
 *
 * Sin esto, tipear 145000 mostraba "145000" hasta guardar y nadie sabía si había
 * puesto un cero de más. Los centavos se escriben después de la coma y quedan como
 * están; sólo se agrupa la parte entera.
 */
class CampoMonto(private val campo: EditText) : TextWatcher {

    private var acomodando = false

    override fun afterTextChanged(texto: Editable) {
        if (acomodando) return
        acomodando = true

        val partes = texto.toString().replace(".", "").split(',', limit = 2)
        val entero = partes[0].filter { it.isDigit() }
        val centavos = partes.getOrNull(1)?.filter { it.isDigit() }?.take(2)

        val armado = buildString {
            if (entero.isNotEmpty()) append(Plata.agruparMiles(entero))
            if (centavos != null) append(',').append(centavos)
        }

        if (armado != texto.toString()) {
            texto.replace(0, texto.length, armado)
        }
        campo.setSelection(campo.text.length)

        acomodando = false
    }

    override fun beforeTextChanged(s: CharSequence?, i: Int, c: Int, a: Int) = Unit
    override fun onTextChanged(s: CharSequence?, i: Int, b: Int, c: Int) = Unit

    companion object {
        /** Mismo tope que exigen las reglas de Firestore: más arriba la nube rechaza el movimiento. */
        const val MAXIMO = 1_000_000_000.0

        /** Devuelve el aviso que corresponde al importe, o null si se puede guardar. */
        fun error(monto: Double?): Int? = when {
            monto == null || monto <= 0 -> R.string.monto_invalido
            monto > MAXIMO -> R.string.monto_maximo
            else -> null
        }

        /** Deja el campo listo para escribir plata y devuelve el importe cuando se pide. */
        fun preparar(campo: EditText) {
            campo.addTextChangedListener(CampoMonto(campo))
        }

        /** Escribe un importe en el campo con el formato que usa la aplicación. */
        fun escribir(campo: EditText, monto: Double) {
            campo.setText(Plata.numero(monto))
            campo.setSelection(campo.text.length)
        }
    }
}
