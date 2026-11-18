package com.soledad.app.ui

import android.app.Activity
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.soledad.app.agenda.MiAgenda
import com.soledad.app.rag.Conocimiento
import com.soledad.app.rag.Fuente
import com.soledad.app.soledad.ConfigSoledad
import com.soledad.app.soledad.Soledad
import com.soledad.app.ui.Ui.agregar

/**
 * Mi cerebro: la conexión con el calendario y las tareas de My Brain, una prueba («¿qué hacés el sábado a la tarde?»)
 * y la información extra que el usuario agrega a mano y que se busca con el RAG.
 */
class PantallaCerebro(h: Anfitrion) : Pantalla(h) {
    override val titulo = Textos.P_CEREBRO

    private fun cambiar(f: (ConfigSoledad) -> ConfigSoledad) = Soledad.ajustes.cambiar(f)

    override fun construir(): View {
        val a: Activity = h.actividad
        val cfg = Soledad.ajustes.leer()
        val col = Ui.columna(a)

        col.agregar(Ui.texto(a, Textos.AGENDA_NOTA, Ui.T_CUERPO, Ui.p.texto))

        // Conexión: qué puede leer ahora mismo.
        col.agregar(Ui.seccion(a, Textos.CEREBRO_CONEXION), Ui.ESP_GRUPO)
        val estado = Ui.tarjeta(a)
        val calOk = MiAgenda.calendarioPermitido(a)
        val tarOk = MiAgenda.tareasPermitidas(a)
        estado.agregar(linea(a, calOk, if (calOk) Textos.CALENDARIO_LISTO else Textos.CALENDARIO_SIN_PERMISO))
        val cuentaEventos = Ui.nota(a, "")
        estado.agregar(cuentaEventos)
        estado.agregar(linea(a, tarOk, if (tarOk) Textos.TAREAS_LISTAS else Textos.TAREAS_SIN_MYBRAIN), Ui.ESP_DENTRO)
        val cuentaTareas = Ui.nota(a, "")
        estado.agregar(cuentaTareas)
        col.agregar(estado, Ui.ESP_DENTRO)
        h.enSegundoPlano({
            val ahora = System.currentTimeMillis()
            val ev = if (calOk) MiAgenda.eventos(a, ahora).count { it.finMs > ahora } else -1
            Triple(ev, MiAgenda.tareas(a)?.size ?: -1, if (tarOk) (MiAgenda.notas(a)?.size ?: -2) else -1)
        }) { r ->
            val (ev, ta, no) = r.getOrDefault(Triple(-1, -1, -1))
            if (ev >= 0) cuentaEventos.text = Textos.cerebroEventos(ev)
            if (ta >= 0) cuentaTareas.text = Textos.cerebroTareas(ta) + when {
                no >= 0 -> " · " + Textos.cerebroNotas(no)
                no == -2 -> " · " + Textos.NOTAS_VERSION_VIEJA
                else -> ""
            }
        }
        if (!calOk) col.agregar(Ui.boton(a, Textos.CALENDARIO_PERMISO, Ui.Estilo.SECUNDARIO) {
            a.requestPermissions(arrayOf(android.Manifest.permission.READ_CALENDAR), CODIGO_CALENDARIO)
        }, Ui.ESP_DENTRO)

        // Qué mira.
        col.agregar(Ui.seccion(a, Textos.AJ_AGENDA), Ui.ESP_GRUPO)
        col.agregar(Ui.interruptor(a, Textos.USAR_CALENDARIO, cfg.usarCalendario, { if (it) Textos.USAR_CALENDARIO_ON else Textos.USAR_CALENDARIO_OFF }) { v ->
            cambiar { it.copy(usarCalendario = v) }
        }, Ui.ESP_DENTRO)
        col.agregar(Ui.interruptor(a, Textos.USAR_TAREAS, cfg.usarTareas, { if (it) Textos.USAR_TAREAS_ON else Textos.USAR_TAREAS_OFF }) { v ->
            cambiar { it.copy(usarTareas = v) }
        }, Ui.ESP_DENTRO)

        col.agregar(Ui.interruptor(a, Textos.USAR_NOTAS, cfg.usarNotas, { if (it) Textos.USAR_NOTAS_ON else Textos.USAR_NOTAS_OFF }) { v ->
            cambiar { it.copy(usarNotas = v) }
        }, Ui.ESP_DENTRO)

        col.agregar(Ui.interruptor(a, Textos.USAR_ANIMO, cfg.usarAnimo, { if (it) Textos.USAR_ANIMO_ON else Textos.USAR_ANIMO_OFF }) { v ->
            cambiar { it.copy(usarAnimo = v) }
        }, Ui.ESP_DENTRO)

        // Probar.
        col.agregar(Ui.seccion(a, Textos.CEREBRO_PROBAR), Ui.ESP_GRUPO)
        col.agregar(Ui.nota(a, Textos.CEREBRO_PROBAR_NOTA), Ui.ESP_DENTRO)
        col.agregar(Ui.boton(a, Textos.CEREBRO_PROBAR_BOTON, Ui.Estilo.PRIMARIO) { dialogoProbar() }, Ui.ESP_DENTRO)
        col.agregar(Ui.boton(a, Textos.VER_AGENDA, Ui.Estilo.SECUNDARIO) {
            h.enSegundoPlano({
                val c = Soledad.ajustes.leer()
                MiAgenda.textoParaIa(a, c.usarCalendario, c.usarTareas)
            }) { r -> Ui.mensaje(a, Textos.VER_AGENDA_TITULO, r.getOrNull().orEmpty().ifBlank { Textos.VER_AGENDA_VACIA }) }
        }, Ui.ESP_DENTRO)

        // Información extra (RAG).
        col.agregar(Ui.seccion(a, Textos.EXTRA_TITULO), Ui.ESP_GRUPO)
        col.agregar(Ui.nota(a, Textos.EXTRA_NOTA), Ui.ESP_DENTRO)
        val extras = try { Conocimiento.crear(a).extras() } catch (e: Exception) { emptyList() }
        if (extras.isEmpty()) col.agregar(Ui.texto(a, Textos.EXTRA_VACIA, Ui.T_CUERPO, Ui.p.tenue), Ui.ESP_DENTRO)
        extras.forEach { col.agregar(filaExtra(a, it), Ui.ESP_DENTRO) }
        col.agregar(Ui.boton(a, Textos.EXTRA_AGREGAR, Ui.Estilo.SECUNDARIO) { dialogoExtra() }, Ui.ESP_DENTRO)
        return col
    }

    private fun linea(a: Activity, ok: Boolean, t: String): View {
        val nivel = if (ok) Nivel.LISTO else Nivel.AVISO
        return Ui.texto(a, "${Semaforo.simbolo(nivel)} $t", Ui.T_CUERPO, Ui.colorDe(nivel), mono = true, negrita = true)
    }

    private fun filaExtra(a: Activity, f: Fuente): View {
        val nombre = f.titulo.removePrefix(Conocimiento.PREFIJO_EXTRA)
        val card = Ui.tarjeta(a)
        val fila = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val textos = Ui.columna(a)
        textos.addView(Ui.texto(a, nombre, Ui.T_CUERPO, Ui.p.texto, mono = true, negrita = true))
        textos.addView(Ui.nota(a, "${formatoFecha(f.creadaMs)} · ${Textos.extraPartes(f.fragmentos)}"))
        fila.addView(textos, LinearLayout.LayoutParams(0, -2, 1f))
        fila.addView(Ui.boton(a, Textos.QUITAR, Ui.Estilo.PELIGRO, "${Textos.QUITAR} $nombre") {
            Ui.confirmar(a, Textos.extraQuitarTitulo(nombre), Textos.EXTRA_QUITAR_CUERPO, Textos.EXTRA_QUITAR) {
                Conocimiento.crear(a).borrar(f.id)
                h.avisar(Textos.EXTRA_QUITADA); h.refrescar()
            }
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = Ui.dp(a, 12) })
        card.addView(fila)
        return card
    }

    private fun dialogoExtra() {
        val a = h.actividad
        val caja = Ui.columna(a)
        val tipo = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        val titulo = Ui.campo(a, Textos.EXTRA_CAMPO_TITULO, Textos.EXTRA_CAMPO_TITULO_EJ, tipo = tipo)
        val texto = Ui.campo(a, Textos.EXTRA_CAMPO_TEXTO, Textos.EXTRA_CAMPO_TEXTO_EJ, ayuda = Textos.EXTRA_CAMPO_AYUDA, tipo = tipo, lineas = 5)
        caja.agregar(titulo.vista)
        caja.agregar(texto.vista, Ui.ESP_DENTRO)
        Ui.dialogo(a, Textos.EXTRA_AGREGAR, caja, Textos.GUARDAR, {
            if (texto.fallo(if (texto.texto.isBlank()) Textos.EXTRA_ERR_VACIA else null)) return@dialogo false
            val (ti, te) = titulo.texto to texto.texto
            h.enSegundoPlano({ Conocimiento.crear(a).agregarExtra(ti, te) }) { r ->
                r.onSuccess { h.avisar(Textos.EXTRA_GUARDADA); h.refrescar() }
                    .onFailure { h.avisar(Textos.errorConDetalle(it.message ?: Textos.ERROR_GENERICO), true) }
            }
            true
        })
    }

    /** Muestra qué encontró en Mi cerebro para esa pregunta y qué contestaría la IA. No envía nada. */
    private fun dialogoProbar() {
        val a = h.actividad
        val pregunta = Ui.campo(a, Textos.CEREBRO_PREGUNTA, Textos.CEREBRO_PREGUNTA_EJ, lineas = 2,
            tipo = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        Ui.dialogo(a, Textos.CEREBRO_PROBAR_BOTON, pregunta.vista, Textos.PROBAR_CONFIRMAR, {
            if (pregunta.fallo(if (pregunta.texto.isBlank()) Textos.ERR_PRUEBA_VACIA else null)) return@dialogo false
            val q = pregunta.texto.trim()
            h.avisar(Textos.PROBANDO)
            h.enSegundoPlano({
                val c = Soledad.ajustes.leer()
                val agenda = MiAgenda.textoParaIa(a, c.usarCalendario, c.usarTareas, q, usarNotas = c.usarNotas, usarAnimo = c.usarAnimo)
                val encontro = agenda.split("\n\n").filter { it.startsWith("TE PREGUNTAN POR") || it.startsWith("ENCONTRADO EN TU CALENDARIO") || it.startsWith("EN TUS NOTAS") || it.startsWith("TU ÁNIMO") }
                    .joinToString("\n\n").ifEmpty { Textos.CEREBRO_SIN_FECHA }
                encontro to Soledad.simular(a, q)
            }) { r ->
                r.onSuccess { (encontro, respuesta) ->
                    h.avisar(Textos.RESPUESTA_TITULO)
                    Ui.mensaje(a, Textos.RESPUESTA_TITULO, "${Textos.CEREBRO_ENCONTRO}\n$encontro\n\n${Textos.CEREBRO_CONTESTARIA}\n$respuesta")
                }.onFailure { h.avisar(Textos.errorConDetalle(it.message ?: Textos.ERROR_GENERICO), true) }
            }
            true
        })
    }

    companion object {
        const val CODIGO_CALENDARIO = 7006
    }
}
