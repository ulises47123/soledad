package com.soledad.app.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.content.res.ColorStateList
import com.soledad.app.Administracion
import com.soledad.app.lista.Contacto
import com.soledad.app.lista.ContactoAgenda
import com.soledad.app.lista.ModoLista
import com.soledad.app.lista.Nombres
import com.soledad.app.nucleo.EstadoCola
import com.soledad.app.nucleo.Servicios
import com.soledad.app.ui.Ui.agregar

/**
 * Lista blanca: Soledad responde SOLO a estos nombres. La acción principal es elegir de la agenda del teléfono
 * (el nombre agendado es el que WhatsApp muestra en la notificación). Escribir a mano queda como alternativa.
 */
class PantallaLista(h: Anfitrion) : Pantalla(h) {
    override val titulo = Textos.P_LISTA
    private var permisoNegado = false

    override fun construir(): View {
        val a: Activity = h.actividad
        val lista = Administracion.contactos
        val cfg = lista.leerConfig()
        // Soledad trabaja siempre en modo «permitidos» y sin grupos: se corrige si alguien editó el archivo a mano.
        if (cfg.modo != ModoLista.PERMITIDOS || cfg.grupos) lista.guardarConfig(cfg.copy(modo = ModoLista.PERMITIDOS, grupos = false))
        val col = Ui.columna(a)

        col.agregar(Ui.texto(a, Textos.LISTA_INTRO))
        col.agregar(Ui.nota(a, Textos.LISTA_GRUPOS), Ui.ESP_DENTRO)

        // Acción principal: elegir de la agenda.
        col.agregar(Ui.boton(a, Textos.ELEGIR_CONTACTOS, Ui.Estilo.PRIMARIO) { elegirDeAgenda() }, Ui.ESP_GRUPO)
        col.agregar(Ui.nota(a, Textos.AGENDA_PRIVACIDAD), Ui.ESP_DENTRO)
        if (permisoNegado && !Agenda.permitido(a)) {
            col.agregar(Ui.aviso(a, Textos.AGENDA_NEGADA_TITULO, Textos.AGENDA_NEGADA, Nivel.AVISO), Ui.ESP_DENTRO)
            col.agregar(Ui.boton(a, Textos.ABRIR_PERMISOS, Ui.Estilo.SECUNDARIO) {
                try {
                    a.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.packageName}")))
                } catch (_: Exception) { h.avisar(Textos.ABIERTO_FUERA, true) }
            }, Ui.ESP_DENTRO)
        }

        col.agregar(Ui.seccion(a, Textos.CONTACTOS_TITULO + " (${cfg.contactos.size})"), Ui.ESP_GRUPO)
        if (cfg.contactos.isEmpty()) col.agregar(Ui.aviso(a, null, Textos.CONTACTOS_VACIO, Nivel.AVISO), Ui.ESP_DENTRO)
        cfg.contactos.forEach { c -> col.agregar(filaContacto(a, c), Ui.ESP_DENTRO) }

        // Alternativa secundaria: a mano (para nombres que WhatsApp muestra distinto que la agenda).
        col.agregar(Ui.seccion(a, Textos.A_MANO_TITULO), Ui.ESP_GRUPO)
        col.agregar(Ui.nota(a, Textos.A_MANO_NOTA), Ui.ESP_DENTRO)
        val campo = Ui.campo(a, Textos.CAMPO_NOMBRE, Textos.CAMPO_NOMBRE_EJ,
            tipo = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS or InputType.TYPE_TEXT_VARIATION_PERSON_NAME)
        col.agregar(campo.vista, Ui.ESP_DENTRO)
        col.agregar(Ui.boton(a, Textos.AGREGAR_A_MANO, Ui.Estilo.SECUNDARIO) {
            val n = campo.texto.trim()
            if (campo.fallo(Validar.nombreContacto(n, lista.listar().map { it.nombre }))) return@boton
            lista.agregarNombres(listOf(n))
            campo.edit.setText("")
            h.avisar(Textos.contactoAgregado(n))
            h.refrescar()
        }, Ui.ESP_DENTRO)
        return col
    }

    private fun filaContacto(a: Activity, c: Contacto): View {
        val card = Ui.tarjeta(a)
        val fila = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fila.addView(Ui.texto(a, c.nombre, Ui.T_CUERPO, Ui.p.texto, mono = true, negrita = true), LinearLayout.LayoutParams(0, -2, 1f))
        fila.addView(Ui.boton(a, Textos.QUITAR, Ui.Estilo.PELIGRO, Textos.quitarContacto(c.nombre)) {
            Ui.confirmar(a, Textos.quitarContactoTitulo(c.nombre), Textos.QUITAR_CONTACTO_CUERPO, "${Textos.QUITAR} contacto") {
                Administracion.contactos.quitar(c.id)
                // Las respuestas programadas para ese contacto no salen.
                try {
                    Servicios.cola.programadas().filter { Nombres.mismo(it.mensaje.nombre, c.nombre) }
                        .forEach { Servicios.cola.marcar(it.id, EstadoCola.DESCARTADO, "contacto quitado de la lista blanca") }
                } catch (_: Exception) { }
                h.avisar(Textos.contactoQuitado(c.nombre))
                h.refrescar()
            }
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = Ui.dp(a, 12) })
        card.addView(fila)
        return card
    }

    // ---------- Selector de la agenda ----------

    private fun elegirDeAgenda() {
        h.pedirPermisoContactos { ok ->
            if (!ok) {
                permisoNegado = true
                h.avisar(Textos.AGENDA_NEGADA, error = true)
                h.refrescar()
                return@pedirPermisoContactos
            }
            permisoNegado = false
            h.enSegundoPlano({ Agenda.leer(h.actividad) }) { r ->
                r.onSuccess { mostrarSelector(it) }.onFailure { h.avisar(Textos.AGENDA_ERROR, true) }
            }
        }
    }

    private fun mostrarSelector(agenda: List<ContactoAgenda>) {
        val a = h.actividad
        if (agenda.isEmpty()) { h.avisar(Textos.AGENDA_VACIA, true); return }
        val lista = Administracion.contactos
        val enLista = lista.listar().map { it.nombre }
        // Chats que Soledad ya vio en sus revisiones (solo nombres, de chats individuales).
        val escribieron = lista.recientes(200).map { it.nombre }
        val elegidos = LinkedHashSet<String>()

        val caja = Ui.columna(a)
        val buscador = Ui.campo(a, Textos.BUSCAR_CONTACTO, Textos.BUSCAR_CONTACTO_EJ, tipo = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        val resumen = Ui.nota(a, "").apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        val filas = Ui.columna(a)
        caja.agregar(buscador.vista)
        caja.agregar(resumen, Ui.ESP_DENTRO)
        caja.agregar(filas, Ui.ESP_DENTRO)

        fun pintar() {
            filas.removeAllViews()
            val todos = Nombres.filtrar(agenda, buscador.texto)
            val visibles = todos.take(MAX_FILAS)
            resumen.text = Textos.resumenAgenda(visibles.size, todos.size, elegidos.size)
            for (c in visibles) {
                val ya = enLista.any { Nombres.mismo(it, c.nombre) }
                val escribio = escribieron.any { Nombres.mismo(it, c.nombre) }
                val detalle = listOfNotNull(
                    Nombres.finNumero(c.numero).takeIf { it.isNotEmpty() }?.let { "…$it" },
                    if (escribio) Textos.YA_TE_ESCRIBIO else null,
                    if (ya) Textos.YA_EN_LISTA else null,
                ).joinToString(" · ")
                val cb = CheckBox(a).apply {
                    text = if (detalle.isEmpty()) c.nombre else "${c.nombre}\n$detalle"
                    textSize = Ui.T_CUERPO
                    setTextColor(Ui.p.texto)
                    minHeight = Ui.dp(a, Ui.MIN_TOQUE)
                    buttonTintList = ColorStateList.valueOf(Ui.p.acento)
                    isChecked = ya || c.nombre in elegidos
                    isEnabled = !ya
                    setOnCheckedChangeListener { _, v ->
                        if (v) elegidos += c.nombre else elegidos -= c.nombre
                        resumen.text = Textos.resumenAgenda(visibles.size, todos.size, elegidos.size)
                    }
                }
                filas.agregar(cb, 2)
            }
        }
        buscador.edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, af: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = pintar()
        })
        pintar()

        Ui.dialogo(a, Textos.ELEGIR_CONTACTOS, caja, Textos.AGREGAR_ELEGIDOS, {
            if (elegidos.isEmpty()) { h.avisar(Textos.NADA_ELEGIDO); return@dialogo true }
            val n = lista.agregarNombres(elegidos.toList())
            h.avisar(Textos.contactosAgregados(n))
            h.refrescar()
            true
        })
    }

    companion object {
        /** Tope de filas dibujadas a la vez; el buscador encuentra el resto. */
        const val MAX_FILAS = 150
    }
}
