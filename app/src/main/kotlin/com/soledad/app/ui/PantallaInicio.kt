package com.soledad.app.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import com.soledad.app.Administracion
import com.soledad.app.R
import com.soledad.app.rag.MiInformacion
import com.soledad.app.soledad.Soledad
import com.soledad.app.ui.Ui.agregar
import rikka.shizuku.Shizuku
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun formatoHora(ms: Long): String = SimpleDateFormat("HH:mm", Locale.US).format(Date(ms))
fun formatoFecha(ms: Long): String = SimpleDateFormat("d MMM HH:mm", Locale("es")).format(Date(ms))

/**
 * Inicio. Orden por importancia (better-layout): el interruptor general (la acción principal), el estado, lo que falta,
 * las demás pantallas y «Revisar ahora». Salir vive en la barra superior, siempre visible.
 */
class PantallaInicio(h: Anfitrion) : Pantalla(h) {
    override val titulo = Textos.INICIO_TITULO

    override fun construir(): View {
        val a: Activity = h.actividad
        val col = Ui.columna(a)
        val cfgLista = Administracion.contactos.leerConfig()
        val encendida = cfgLista.interruptorGeneral
        val cfg = Soledad.ajustes.leer()
        val items = Semaforo.evaluar(Datos.entradaSemaforo(a))

        // 1. Tarjeta principal: ícono, estado en grande y el interruptor general.
        col.agregar(principal(a, encendida, cfgLista.contactos.isEmpty(), items, cfg.modoNegocio))

        // 2. Estado en pastillas y datos.
        val nivelModo = if (cfg.modoPrueba) Nivel.AVISO else Nivel.LISTO
        val chips = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        chips.addView(Ui.chip(a, "${Semaforo.simbolo(nivelModo)} ${if (cfg.modoPrueba) Textos.MODO_PRUEBA_CHIP else Textos.MODO_REAL_CHIP}", Ui.colorDe(nivelModo)),
            LinearLayout.LayoutParams(-2, -2))
        chips.addView(Ui.chip(a, Textos.respondeA(cfg.responderATodos, cfgLista.contactos.size), Ui.p.acento),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = Ui.dp(a, Ui.ESP_DENTRO) })
        if (cfg.modoNegocio) chips.addView(Ui.chip(a, Textos.NEGOCIO_CHIP, Ui.p.aviso),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = Ui.dp(a, Ui.ESP_DENTRO) })
        col.agregar(chips, Ui.ESP_DENTRO * 2)

        val ahora = System.currentTimeMillis()
        val prox = Soledad.estado.proximoChequeoMs
        val fila = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        fila.addView(dato(a, Textos.PROXIMA_REVISION,
            if (encendida && prox > 0) formatoHora(prox) else "—",
            if (encendida && prox > 0) relativo(ahora, prox) else Textos.SIN_PROXIMA),
            LinearLayout.LayoutParams(0, -1, 1f).apply { marginEnd = Ui.dp(a, Ui.ESP_DENTRO) })
        fila.addView(dato(a, Textos.PROGRAMADAS, Datos.programadas().size.toString(), Textos.PROGRAMADAS_NOTA), LinearLayout.LayoutParams(0, -1, 1f))
        col.agregar(fila, Ui.ESP_DENTRO * 2)
        val ult = Soledad.estado.ultimoChequeoMs
        col.agregar(Ui.tarjeta(a).apply {
            agregar(Ui.texto(a, Textos.ULTIMO_RESULTADO, Ui.T_NOTA, Ui.p.tenue, mono = true, negrita = true))
            agregar(Ui.texto(a, if (ult > 0) "${formatoFecha(ult)} · ${Soledad.estado.ultimoResultado}" else Textos.SIN_RESULTADO,
                Ui.T_CUERPO, Ui.p.texto).apply { setTextIsSelectable(true) }, 2)
        }, Ui.ESP_DENTRO)

        // 3. Lo que necesita: solo aparece si falta algo.
        if (Semaforo.pendientes(items) > 0) {
            col.agregar(Ui.seccion(a, Textos.SEMAFORO_TITULO + " · " + Semaforo.resumen(items)), Ui.ESP_GRUPO)
            items.filter { it.nivel != Nivel.LISTO }.forEach { col.agregar(filaSemaforo(a, it), Ui.ESP_DENTRO) }
        }

        // 4. Pantallas.
        col.agregar(Ui.seccion(a, Textos.IR_A), Ui.ESP_GRUPO)
        val info = try { MiInformacion.crear(a).completas() } catch (e: Exception) { 0 }
        col.agregar(Ui.filaNav(a, Textos.P_LISTA, Textos.dListaCon(cfgLista.contactos.size), R.drawable.ic_nav_lista) { h.abrir(PantallaLista(h)) }, Ui.ESP_DENTRO)
        col.agregar(Ui.filaNav(a, Textos.P_INFO, Textos.dInfoCon(info, MiInformacion.SECCIONES.size), R.drawable.ic_nav_info) { h.abrir(PantallaMiInfo(h)) }, Ui.ESP_DENTRO)
        val negocio = try { com.soledad.app.rag.MiNegocio.crear(a).completas() } catch (e: Exception) { 0 }
        col.agregar(Ui.filaNav(a, Textos.P_NEGOCIO, Textos.dNegocio(cfg.modoNegocio, negocio, com.soledad.app.rag.MiNegocio.SECCIONES.size),
            R.drawable.ic_nav_negocio) { h.abrir(PantallaNegocio(h)) }, Ui.ESP_DENTRO)
        col.agregar(Ui.filaNav(a, Textos.P_CEREBRO, Textos.D_CEREBRO, R.drawable.ic_nav_cerebro) { h.abrir(PantallaCerebro(h)) }, Ui.ESP_DENTRO)
        col.agregar(Ui.filaNav(a, Textos.P_REGISTRO, Textos.D_REGISTRO, R.drawable.ic_nav_registro) { h.abrir(PantallaRegistro(h)) }, Ui.ESP_DENTRO)
        col.agregar(Ui.filaNav(a, Textos.P_AJUSTES, Textos.D_AJUSTES, R.drawable.ic_nav_ajustes) { h.abrir(PantallaAjustes(h)) }, Ui.ESP_DENTRO)

        // 5. Revisar ahora (secundaria).
        col.agregar(Ui.boton(a, Textos.REVISAR_AHORA, Ui.Estilo.SECUNDARIO) {
            val antes = Soledad.estado.ultimoChequeoMs
            if (!Soledad.revisarAhora(a)) { h.avisar(Textos.REVISAR_APAGADA, true); return@boton }
            h.avisar(Textos.REVISANDO)
            // Cuando termina la revisión se actualizan «Último resultado» y «Próxima revisión» sin salir de la pantalla.
            h.enSegundoPlano({
                val limite = System.currentTimeMillis() + 90_000
                while (Soledad.estado.ultimoChequeoMs == antes && System.currentTimeMillis() < limite) Thread.sleep(700)
                Soledad.estado.ultimoChequeoMs != antes
            }) { r ->
                if (r.getOrDefault(false)) { h.refrescar(); h.avisar(Textos.REVISION_LISTA + " " + Soledad.estado.ultimoResultado) }
                else h.avisar(Textos.REVISION_TARDA, true)
            }
        }, Ui.ESP_GRUPO)
        return col
    }

    /** Tarjeta principal con degradado: el ícono, «Encendida/Apagada» en grande y la palanca. */
    private fun principal(a: Activity, encendida: Boolean, listaVacia: Boolean, items: List<ItemSemaforo>, negocio: Boolean): View {
        val color = if (encendida) Ui.p.acento else Ui.p.tenue
        val caja = Ui.columna(a).apply {
            val r = Ui.dp(a, 20)
            setPadding(r, r, r, r)
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Ui.mezclar(Ui.p.superficie, Ui.p.acento, if (encendida) 0.42f else 0.14f), Ui.p.superficie)).apply {
                cornerRadius = Ui.dp(a, Ui.RADIO_TARJETA).toFloat()
            }
        }
        val fila = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fila.addView(ImageView(a).apply {
            setImageResource(R.mipmap.ic_launcher)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(Ui.dp(a, 56), Ui.dp(a, 56)))
        val textos = Ui.columna(a).apply { setPadding(Ui.dp(a, 14), 0, Ui.dp(a, 8), 0) }
        textos.addView(Ui.texto(a, Textos.INTERRUPTOR_TITULO, Ui.T_NOTA, Ui.p.tenue, mono = true, negrita = true))
        textos.addView(Ui.texto(a, if (encendida) Textos.ESTADO_ENCENDIDA else Textos.ESTADO_APAGADA, 26f, color, negrita = true))
        fila.addView(textos, LinearLayout.LayoutParams(0, -2, 1f))
        val sw = Switch(a).apply {
            isChecked = encendida
            minHeight = Ui.dp(a, Ui.MIN_TOQUE); minimumHeight = Ui.dp(a, Ui.MIN_TOQUE)
            minWidth = Ui.dp(a, Ui.MIN_TOQUE); minimumWidth = Ui.dp(a, Ui.MIN_TOQUE)
            contentDescription = Textos.INTERRUPTOR_TITULO
            thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Ui.p.acento, Ui.p.tenue))
            trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(Ui.mezclar(Ui.p.superficie, Ui.p.acento, 0.55f), Ui.p.borde))
        }
        fila.addView(sw)
        caja.addView(fila)
        caja.agregar(Ui.texto(a, if (!encendida) Textos.LEMA else if (negocio) Textos.INTERRUPTOR_ON_NEGOCIO else Textos.INTERRUPTOR_ON, Ui.T_NOTA, Ui.p.texto), Ui.ESP_DENTRO + 4)
        sw.setOnCheckedChangeListener { _, v ->
            if (v) {
                if (Semaforo.bloqueantes(items) > 0) {
                    h.avisar(Textos.FALTA_PARA_ENCENDER, error = true)
                    h.refrescar()
                    return@setOnCheckedChangeListener
                }
                Soledad.activar(a)
                h.avisar(if (listaVacia) Textos.LISTA_VACIA_AVISO else Textos.ENCENDIDA_AVISO, listaVacia)
            } else {
                Soledad.desactivar(a)
                h.avisar(Textos.APAGADA_AVISO)
            }
            h.refrescar()
        }
        return caja
    }

    /** Dato destacado: etiqueta chica, valor grande y una aclaración. */
    private fun dato(a: Activity, etiqueta: String, valor: String, aclaracion: String): View = Ui.tarjeta(a).apply {
        agregar(Ui.texto(a, etiqueta, Ui.T_NOTA, Ui.p.tenue, mono = true, negrita = true))
        agregar(Ui.texto(a, valor, 24f, Ui.p.texto, negrita = true), 2)
        agregar(Ui.nota(a, aclaracion))
    }

    private fun filaSemaforo(a: Activity, it: ItemSemaforo): View {
        val c = Ui.colorDe(it.nivel)
        val card = Ui.tarjeta(a).apply { background = Ui.fondo(a, Ui.p.superficie, c, Ui.RADIO_TARJETA, 2) }
        card.agregar(Ui.texto(a, "${Semaforo.simbolo(it.nivel)} ${Semaforo.palabra(it.nivel)} · ${it.titulo}", Ui.T_CUERPO, c, mono = true, negrita = true))
        card.agregar(Ui.texto(a, it.detalle, Ui.T_NOTA, Ui.p.texto), Ui.ESP_DENTRO / 2)
        if (it.accion != null && it.textoAccion != null) {
            card.agregar(Ui.boton(a, it.textoAccion, Ui.Estilo.SECUNDARIO, "${it.textoAccion}: ${it.titulo}") { ejecutar(a, it.accion) }, Ui.ESP_DENTRO)
        }
        return card
    }

    private fun ejecutar(a: Activity, accion: Accion) {
        try {
            when (accion) {
                Accion.ABRIR_NOTIFICACIONES -> a.startActivity(
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                        .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, com.soledad.app.recepcion.Escucha.componente(a).flattenToString()),
                )
                Accion.ABRIR_SHIZUKU -> {
                    val i = a.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                    if (i != null) a.startActivity(i) else h.avisar(Textos.ABIERTO_FUERA, true)
                }
                Accion.PEDIR_SHIZUKU -> {
                    Shizuku.addRequestPermissionResultListener { _, _ -> a.runOnUiThread { Soledad.concederConShizuku(a); h.refrescar() } }
                    Shizuku.requestPermission(1001)
                }
                Accion.EXCLUIR_BATERIA -> a.startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${a.packageName}")),
                )
                Accion.ALARMAS_EXACTAS -> a.startActivity(
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${a.packageName}")),
                )
                Accion.IR_AJUSTES -> h.abrir(PantallaAjustes(h))
                Accion.PEDIR_AVISOS -> h.pedirPermisoAvisos()
            }
        } catch (e: Throwable) {
            h.avisar(Textos.ABIERTO_FUERA, true)
        }
    }
}
