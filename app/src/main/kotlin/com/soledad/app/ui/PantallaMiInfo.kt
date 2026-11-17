package com.soledad.app.ui

import android.app.Activity
import android.text.InputType
import android.view.View
import com.soledad.app.rag.MiInformacion
import com.soledad.app.soledad.Soledad
import com.soledad.app.ui.Ui.agregar

/** Mi información: la base de conocimiento personal por secciones. Empieza vacía; el texto gris es solo un ejemplo. */
class PantallaMiInfo(h: Anfitrion) : Pantalla(h) {
    override val titulo = Textos.P_INFO

    override fun construir(): View {
        val a: Activity = h.actividad
        val info = MiInformacion.crear(a)
        val col = Ui.columna(a)

        col.agregar(Ui.aviso(a, Textos.INFO_AVISO_TITULO, Textos.INFO_AVISO, Nivel.AVISO))

        val campos = MiInformacion.SECCIONES.map { s ->
            val actual = info.leer(s)
            val c = Ui.campo(
                a, s.titulo, s.ejemplo, actual, ayuda = s.explicacion + if (actual.isBlank()) " " + Textos.INFO_VACIA else "",
                tipo = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, lineas = 4,
            )
            col.agregar(c.vista, Ui.ESP_GRUPO)
            s to c
        }

        col.agregar(Ui.boton(a, Textos.INFO_GUARDAR, Ui.Estilo.PRIMARIO) {
            val textos = campos.map { (s, c) -> s to c.texto }
            h.enSegundoPlano({ textos.forEach { (s, t) -> info.guardar(s, t) } }) { r ->
                r.onSuccess { h.avisar(Textos.INFO_GUARDADA); h.refrescar() }
                    .onFailure { h.avisar(Textos.errorConDetalle(it.message ?: Textos.ERROR_GENERICO), true) }
            }
        }, Ui.ESP_GRUPO)

        col.agregar(Ui.seccion(a, Textos.PROBAR_TITULO), Ui.ESP_GRUPO)
        col.agregar(Ui.nota(a, Textos.PROBAR_NOTA), Ui.ESP_DENTRO)
        col.agregar(Ui.boton(a, Textos.PROBAR_BOTON, Ui.Estilo.SECUNDARIO) { dialogoProbar(h) }, Ui.ESP_DENTRO)
        return col
    }

    companion object {
        /** Diálogo de prueba interna (también lo usa Ajustes). Llama a la IA de verdad y no envía nada. */
        fun dialogoProbar(h: Anfitrion) {
            val a = h.actividad
            val caja = Ui.columna(a)
            caja.agregar(Ui.nota(a, Textos.PROBAR_NOTA))
            val pregunta = Ui.campo(a, Textos.CAMPO_PRUEBA, Textos.CAMPO_PRUEBA_EJ, lineas = 3,
                tipo = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
            caja.agregar(pregunta.vista, Ui.ESP_DENTRO)
            Ui.dialogo(a, Textos.PROBAR_BOTON, caja, Textos.PROBAR_CONFIRMAR, {
                if (pregunta.fallo(if (pregunta.texto.isBlank()) Textos.ERR_PRUEBA_VACIA else null)) return@dialogo false
                val q = pregunta.texto.trim()
                h.avisar(Textos.PROBANDO)
                h.enSegundoPlano({ Soledad.simular(a, q) }) { r ->
                    r.onSuccess { res -> h.avisar(Textos.RESPUESTA_TITULO); Ui.mensaje(a, Textos.RESPUESTA_TITULO, res) }
                        .onFailure { h.avisar(Textos.errorConDetalle(it.message ?: Textos.ERROR_GENERICO), true) }
                }
                true
            })
        }
    }
}
