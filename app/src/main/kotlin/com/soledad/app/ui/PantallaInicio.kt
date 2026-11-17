package com.soledad.app.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
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

        // 1. Cabecera con el ícono y el interruptor general.
        val cabecera = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        cabecera.addView(ImageView(a).apply {
            setImageResource(R.mipmap.ic_launcher)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(Ui.dp(a, 56), Ui.dp(a, 56)))
        cabecera.addView(Ui.texto(a, Textos.LEMA, Ui.T_CUERPO, Ui.p.tenue).apply {
            setPadding(Ui.dp(a, 12), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        col.agregar(cabecera)

        col.agregar(Ui.interruptor(a, Textos.INTERRUPTOR_TITULO, encendida, { if (it) Textos.INTERRUPTOR_ON else Textos.INTERRUPTOR_OFF }) { v ->
            if (v) {
                if (Semaforo.bloqueantes(items) > 0) {
                    h.avisar(Textos.FALTA_PARA_ENCENDER, error = true)
                    h.refrescar()
                    return@interruptor
                }
                Soledad.activar(a)
                h.avisar(if (cfgLista.contactos.isEmpty()) Textos.LISTA_VACIA_AVISO else Textos.ENCENDIDA_AVISO, cfgLista.contactos.isEmpty())
            } else {
                Soledad.desactivar(a)
                h.avisar(Textos.APAGADA_AVISO)
            }
            h.refrescar()
        }, Ui.ESP_GRUPO)

        // 2. Estado.
        col.agregar(Ui.seccion(a, Textos.ESTADO_TITULO), Ui.ESP_GRUPO)
        val tarjeta = Ui.tarjeta(a)
        val nivel = if (encendida) Nivel.LISTO else Nivel.AVISO
        tarjeta.agregar(Ui.texto(a, "${Semaforo.simbolo(nivel)} ${if (encendida) Textos.ESTADO_ENCENDIDA else Textos.ESTADO_APAGADA}",
            Ui.T_CUERPO, Ui.colorDe(nivel), mono = true, negrita = true))
        val nivelModo = if (cfg.modoPrueba) Nivel.AVISO else Nivel.LISTO
        tarjeta.agregar(Ui.texto(a, "${Semaforo.simbolo(nivelModo)} ${if (cfg.modoPrueba) Textos.MODO_PRUEBA_CHIP else Textos.MODO_REAL_CHIP}",
            Ui.T_NOTA, Ui.colorDe(nivelModo), negrita = true), Ui.ESP_DENTRO / 2)

        val ahora = System.currentTimeMillis()
        val prox = Soledad.estado.proximoChequeoMs
        tarjeta.agregar(dato(a, Textos.PROXIMA_REVISION,
            if (encendida && prox > 0) "${formatoHora(prox)} (${relativo(ahora, prox)})" else Textos.SIN_PROXIMA), Ui.ESP_DENTRO * 2)
        val ult = Soledad.estado.ultimoChequeoMs
        tarjeta.agregar(dato(a, Textos.ULTIMO_RESULTADO,
            if (ult > 0) "${formatoFecha(ult)} · ${Soledad.estado.ultimoResultado}" else Textos.SIN_RESULTADO), Ui.ESP_DENTRO)
        tarjeta.agregar(dato(a, Textos.PROGRAMADAS, Datos.programadas().size.toString()), Ui.ESP_DENTRO)
        col.agregar(tarjeta, Ui.ESP_DENTRO)

        // 3. Lo que necesita (solo lo pendiente, con su acción; lo listo se resume en una línea).
        col.agregar(Ui.seccion(a, Textos.SEMAFORO_TITULO), Ui.ESP_GRUPO)
        col.agregar(Ui.texto(a, Semaforo.resumen(items), Ui.T_CUERPO, Ui.p.texto).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }, Ui.ESP_DENTRO)
        items.filter { it.nivel != Nivel.LISTO }.forEach { col.agregar(filaSemaforo(a, it), Ui.ESP_DENTRO) }
        items.filter { it.nivel == Nivel.LISTO }.takeIf { it.isNotEmpty() }?.let { listos ->
            col.agregar(Ui.nota(a, "${Textos.SIMBOLO_LISTO} " + listos.joinToString(" · ") { it.titulo }), Ui.ESP_DENTRO)
        }

        // 4. Pantallas.
        col.agregar(Ui.seccion(a, Textos.IR_A), Ui.ESP_GRUPO)
        val info = try { MiInformacion.crear(a).completas() } catch (e: Exception) { 0 }
        col.agregar(Ui.filaNav(a, Textos.P_LISTA, Textos.dListaCon(cfgLista.contactos.size)) { h.abrir(PantallaLista(h)) }, Ui.ESP_DENTRO)
        col.agregar(Ui.filaNav(a, Textos.P_INFO, Textos.dInfoCon(info, MiInformacion.SECCIONES.size)) { h.abrir(PantallaMiInfo(h)) }, Ui.ESP_DENTRO)
        col.agregar(Ui.filaNav(a, Textos.P_REGISTRO, Textos.D_REGISTRO) { h.abrir(PantallaRegistro(h)) }, Ui.ESP_DENTRO)
        col.agregar(Ui.filaNav(a, Textos.P_AJUSTES, Textos.D_AJUSTES) { h.abrir(PantallaAjustes(h)) }, Ui.ESP_DENTRO)

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

    private fun dato(a: Activity, etiqueta: String, valor: String): View = Ui.columna(a).apply {
        agregar(Ui.texto(a, etiqueta, Ui.T_NOTA, Ui.p.tenue, mono = true, negrita = true))
        agregar(Ui.texto(a, valor, Ui.T_CUERPO, Ui.p.texto).apply { setTextIsSelectable(true) }, 2)
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
