package com.soledad.app.ui

import android.app.Activity
import android.text.InputType
import android.view.View
import com.soledad.app.lista.Perfil
import com.soledad.app.rag.MiNegocio
import com.soledad.app.soledad.Soledad
import com.soledad.app.ui.Ui.agregar

/**
 * Mi negocio: el interruptor del modo negocio y lo que Soledad sabe del emprendimiento.
 * Con el modo encendido, a quien NO está agendado le contesta como el negocio; a tu lista blanca, como vos.
 */
class PantallaNegocio(h: Anfitrion) : Pantalla(h) {
    override val titulo = Textos.P_NEGOCIO

    override fun construir(): View {
        val a: Activity = h.actividad
        val negocio = MiNegocio.crear(a)
        val cfg = Soledad.ajustes.leer()
        val col = Ui.columna(a)

        col.agregar(Ui.interruptor(a, Textos.NEGOCIO_MODO, cfg.modoNegocio, { if (it) Textos.NEGOCIO_ON else Textos.NEGOCIO_OFF }) { v ->
            if (!v) {
                Soledad.ajustes.cambiar { it.copy(modoNegocio = false) }
                h.avisar(Textos.NEGOCIO_APAGADO); h.refrescar()
                return@interruptor
            }
            if (!negocio.listo()) {
                h.avisar(Textos.NEGOCIO_FALTA, error = true); h.refrescar()
                return@interruptor
            }
            h.refrescar() // la palanca vuelve a su lugar hasta que confirme
            Ui.confirmar(a, Textos.NEGOCIO_CONFIRMAR_TITULO, Textos.NEGOCIO_RIESGO, Textos.NEGOCIO_CONFIRMAR) {
                Soledad.ajustes.cambiar { it.copy(modoNegocio = true) }
                h.avisar(Textos.NEGOCIO_ENCENDIDO); h.refrescar()
            }
        })
        col.agregar(Ui.nota(a, Textos.NEGOCIO_REGLA), Ui.ESP_DENTRO)

        col.agregar(Ui.aviso(a, Textos.INFO_AVISO_TITULO, Textos.NEGOCIO_AVISO, Nivel.AVISO), Ui.ESP_GRUPO)
        val campos = MiNegocio.SECCIONES.map { s ->
            val actual = negocio.leer(s)
            val c = Ui.campo(
                a, s.titulo, s.ejemplo, actual, ayuda = s.explicacion,
                tipo = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, lineas = 4,
            )
            col.agregar(c.vista, Ui.ESP_GRUPO)
            s to c
        }
        col.agregar(Ui.boton(a, Textos.NEGOCIO_GUARDAR, Ui.Estilo.PRIMARIO) {
            val textos = campos.map { (s, c) -> s to c.texto }
            if (campos[0].second.fallo(if (textos[0].second.isBlank() && cfg.modoNegocio) Textos.NEGOCIO_ERR_VACIO else null)) return@boton
            h.enSegundoPlano({ textos.forEach { (s, t) -> negocio.guardar(s, t) } }) { r ->
                r.onSuccess { h.avisar(Textos.NEGOCIO_GUARDADO); h.refrescar() }
                    .onFailure { h.avisar(Textos.errorConDetalle(it.message ?: Textos.ERROR_GENERICO), true) }
            }
        }, Ui.ESP_GRUPO)

        col.agregar(Ui.seccion(a, Textos.PROBAR_TITULO), Ui.ESP_GRUPO)
        col.agregar(Ui.nota(a, Textos.NEGOCIO_PROBAR_NOTA), Ui.ESP_DENTRO)
        col.agregar(Ui.boton(a, Textos.PROBAR_BOTON, Ui.Estilo.SECUNDARIO) { dialogoProbar() }, Ui.ESP_DENTRO)
        return col
    }

    private fun dialogoProbar() {
        val a = h.actividad
        if (!MiNegocio.crear(a).listo()) { h.avisar(Textos.NEGOCIO_FALTA, true); return }
        val pregunta = Ui.campo(a, Textos.CAMPO_PRUEBA, Textos.NEGOCIO_PRUEBA_EJ, lineas = 2,
            tipo = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        Ui.dialogo(a, Textos.PROBAR_BOTON, pregunta.vista, Textos.PROBAR_CONFIRMAR, {
            if (pregunta.fallo(if (pregunta.texto.isBlank()) Textos.ERR_PRUEBA_VACIA else null)) return@dialogo false
            val q = pregunta.texto.trim()
            h.avisar(Textos.PROBANDO)
            h.enSegundoPlano({ Soledad.simular(a, q, Perfil.NEGOCIO) }) { r ->
                r.onSuccess { res -> h.avisar(Textos.RESPUESTA_TITULO); Ui.mensaje(a, Textos.RESPUESTA_TITULO, res) }
                    .onFailure { h.avisar(Textos.errorConDetalle(it.message ?: Textos.ERROR_GENERICO), true) }
            }
            true
        })
    }
}
