package com.soledad.app.ui

import android.app.Activity
import android.view.View
import com.soledad.app.nucleo.Servicios
import com.soledad.app.recepcion.FilaCola
import com.soledad.app.recepcion.RegistroSqlite
import com.soledad.app.ui.Ui.agregar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Registro: respuestas programadas y qué detectó y qué respondió (o habría respondido en modo prueba). */
class PantallaRegistro(h: Anfitrion) : Pantalla(h) {
    override val titulo = Textos.P_REGISTRO

    override fun construir(): View {
        val a: Activity = h.actividad
        val col = Ui.columna(a)

        col.agregar(Ui.boton(a, Textos.ACTUALIZAR, Ui.Estilo.PRIMARIO) { h.refrescar() })

        col.agregar(Ui.seccion(a, Textos.REGISTRO_PROGRAMADAS), Ui.ESP_GRUPO)
        val prog = Datos.programadas()
        if (prog.isEmpty()) col.agregar(Ui.texto(a, Textos.PROGRAMADAS_VACIO, Ui.T_CUERPO, Ui.p.tenue), Ui.ESP_DENTRO)
        prog.forEach { col.agregar(tarjetaProgramada(a, it), Ui.ESP_DENTRO) }

        col.agregar(Ui.seccion(a, Textos.REGISTRO_EVENTOS), Ui.ESP_GRUPO)
        val eventos = Datos.eventos(150)
        if (eventos.isEmpty()) col.agregar(Ui.texto(a, Textos.REGISTRO_VACIO, Ui.T_CUERPO, Ui.p.tenue), Ui.ESP_DENTRO)
        eventos.forEach { col.agregar(tarjetaEvento(a, it), Ui.ESP_DENTRO) }

        if (eventos.isNotEmpty()) {
            col.agregar(Ui.boton(a, Textos.BORRAR_REGISTRO, Ui.Estilo.PELIGRO) {
                Ui.confirmar(a, Textos.BORRAR_REGISTRO_TITULO, Textos.BORRAR_REGISTRO_CUERPO, Textos.BORRAR_REGISTRO) {
                    (Servicios.registro as? RegistroSqlite)?.borrarTodo()
                    h.avisar(Textos.REGISTRO_BORRADO)
                    h.refrescar()
                }
            }, Ui.ESP_GRUPO)
        }
        return col
    }

    private fun tarjetaProgramada(a: Activity, f: FilaCola): View {
        val card = Ui.tarjeta(a)
        val cuando = maxOf(f.proximoMs, f.leaseHasta)
        card.agregar(Ui.texto(a, "${Textos.SIMBOLO_AVISO} ${f.mensaje.nombre}", Ui.T_CUERPO, Ui.colorDe(Nivel.AVISO), mono = true, negrita = true))
        card.agregar(Ui.texto(a, "«${f.mensaje.texto}»", Ui.T_NOTA, Ui.p.texto).apply { setTextIsSelectable(true) }, 2)
        card.agregar(Ui.nota(a, Textos.programadaPara(formatoHora(cuando), relativo(System.currentTimeMillis(), cuando)) +
            if (f.nota.isNotBlank()) " · ${f.nota}" else ""), 2)
        return card
    }

    private fun tarjetaEvento(a: Activity, e: LineaRegistro): View {
        val (nombre, nivel) = LectorRegistro.etiqueta(e.etapa)
        val card = Ui.tarjeta(a)
        card.agregar(Ui.texto(a, "${Semaforo.simbolo(nivel)} $nombre · ${hora(e.ts)}", Ui.T_NOTA, Ui.colorDe(nivel), mono = true, negrita = true))
        // El detalle completo, sin recortar y seleccionable.
        card.agregar(Ui.texto(a, e.detalle, Ui.T_CUERPO, Ui.p.texto).apply { setTextIsSelectable(true) }, Ui.ESP_DENTRO / 2)
        return card
    }

    private fun hora(ms: Long) = SimpleDateFormat("d MMM HH:mm:ss", Locale("es")).format(Date(ms))
}
