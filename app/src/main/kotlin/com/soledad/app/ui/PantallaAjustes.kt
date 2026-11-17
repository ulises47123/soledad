package com.soledad.app.ui

import android.app.Activity
import android.view.View
import com.soledad.app.Administracion
import com.soledad.app.soledad.ConfigSoledad
import com.soledad.app.soledad.Soledad
import com.soledad.app.ui.Ui.agregar

/** Ajustes: modo prueba, etiqueta, intervalos, espera, cómo entra a WhatsApp y claves de IA. */
class PantallaAjustes(h: Anfitrion) : Pantalla(h) {
    override val titulo = Textos.P_AJUSTES

    private fun cambiar(f: (ConfigSoledad) -> ConfigSoledad) = Soledad.ajustes.cambiar(f)

    override fun construir(): View {
        val a: Activity = h.actividad
        val cfg = Soledad.ajustes.leer()
        val col = Ui.columna(a)

        // Respuestas
        col.agregar(Ui.seccion(a, Textos.AJ_RESPUESTAS))
        col.agregar(Ui.interruptor(a, Textos.MODO_PRUEBA, cfg.modoPrueba, { if (it) Textos.MODO_PRUEBA_ON else Textos.MODO_PRUEBA_OFF }) { v ->
            cambiar { it.copy(modoPrueba = v) }
            h.avisar(if (v) Textos.MODO_PRUEBA_ON else Textos.MODO_PRUEBA_OFF)
        }, Ui.ESP_DENTRO)
        col.agregar(Ui.boton(a, Textos.PROBAR_BOTON, Ui.Estilo.SECUNDARIO) { PantallaMiInfo.dialogoProbar(h) }, Ui.ESP_DENTRO)
        col.agregar(Ui.interruptor(a, Textos.ETIQUETA, cfg.etiquetaActiva, { if (it) Textos.ETIQUETA_ON else Textos.ETIQUETA_OFF }) { v ->
            cambiar { it.copy(etiquetaActiva = v) }
            h.avisar(if (v) Textos.ETIQUETA_ON else Textos.ETIQUETA_OFF)
        }, Ui.ESP_DENTRO * 2)

        // Cuándo revisa
        col.agregar(Ui.seccion(a, Textos.AJ_HORARIOS), Ui.ESP_GRUPO)
        col.agregar(Ui.nota(a, Textos.INTERVALOS_NOTA), Ui.ESP_DENTRO)
        listOf(20 to Textos.INT_20, 50 to Textos.INT_50, 120 to Textos.INT_120).forEach { (min, nombre) ->
            val activo = min in cfg.intervalosMin
            col.agregar(Ui.interruptor(a, nombre, activo, { if (it) "Puede tocar este intervalo." else "No se usa." }) { v ->
                val actual = Soledad.ajustes.leer().intervalosMin
                val nuevo = if (v) (actual + min).distinct().sorted() else actual - min
                if (nuevo.isEmpty()) { h.avisar(Textos.INT_MINIMO, true); h.refrescar(); return@interruptor }
                cambiar { it.copy(intervalosMin = nuevo) }
            }, Ui.ESP_DENTRO)
        }
        val variaciones = listOf(0, 3, 5, 10)
        col.agregar(Ui.filaNav(a, Textos.VARIACION, Textos.variacion(cfg.variacionMin)) {
            Ui.elegir(a, Textos.VARIACION, variaciones.map { Textos.variacion(it) }, variaciones.indexOf(cfg.variacionMin)) { i ->
                cambiar { it.copy(variacionMin = variaciones[i]) }; h.refrescar()
            }
        }, Ui.ESP_DENTRO)
        val minutos = (1..5).toList()
        col.agregar(Ui.filaNav(a, Textos.ESPERA, Textos.espera(cfg.esperaMinMin, cfg.esperaMaxMin)) {
            Ui.elegir(a, Textos.ESPERA_DESDE, minutos.map { "$it min" }, cfg.esperaMinMin - 1) { i ->
                val desde = minutos[i]
                Ui.elegir(a, Textos.ESPERA_HASTA, minutos.filter { it >= desde }.map { "$it min" }, 0) { j ->
                    cambiar { it.copy(esperaMinMin = desde, esperaMaxMin = minutos.filter { m -> m >= desde }[j]) }; h.refrescar()
                }
            }
        }, Ui.ESP_DENTRO)

        // WhatsApp
        col.agregar(Ui.seccion(a, Textos.AJ_WHATSAPP), Ui.ESP_GRUPO)
        col.agregar(Ui.interruptor(a, Textos.ENTRAR, cfg.entrarAWhatsApp, { if (it) Textos.ENTRAR_ON else Textos.ENTRAR_OFF }) { v ->
            cambiar { it.copy(entrarAWhatsApp = v) }
        }, Ui.ESP_DENTRO)
        col.agregar(Ui.interruptor(a, Textos.INTERFAZ, cfg.enviarPorInterfaz, { if (it) Textos.INTERFAZ_ON else Textos.INTERFAZ_OFF }) { v ->
            cambiar { it.copy(enviarPorInterfaz = v) }
        }, Ui.ESP_DENTRO)

        // Claves
        col.agregar(Ui.seccion(a, Textos.AJ_CLAVES), Ui.ESP_GRUPO)
        col.agregar(Ui.nota(a, Textos.CLAVES_NOTA), Ui.ESP_DENTRO)
        val con = Datos.apisConSecreto(a)
        val estados = try { Administracion.apis.estado() } catch (e: Exception) { emptyList() }
        if (estados.isEmpty()) col.agregar(Ui.texto(a, Textos.SIN_CLAVES), Ui.ESP_DENTRO)
        estados.forEach { e ->
            val nivel = if (e.id in con && e.activa) Nivel.LISTO else Nivel.FALTA
            val card = Ui.tarjeta(a)
            card.agregar(Ui.texto(a, "${Semaforo.simbolo(nivel)} ${if (e.id in con) Textos.CLAVE_GUARDADA else Textos.CLAVE_FALTA}",
                Ui.T_NOTA, Ui.colorDe(nivel), mono = true, negrita = true))
            card.agregar(Ui.texto(a, Textos.claveFila(e.id, e.usos, e.limite), Ui.T_CUERPO, Ui.p.texto), 2)
            col.agregar(card, Ui.ESP_DENTRO)
        }
        col.agregar(Ui.boton(a, Textos.CAMBIAR_NVIDIA, Ui.Estilo.SECUNDARIO) { dialogoClave("nvidia", "nvidia-1", 300) }, Ui.ESP_DENTRO)
        col.agregar(Ui.boton(a, Textos.AGREGAR_GEMINI, Ui.Estilo.SECUNDARIO) {
            var n = 1
            val ids = estados.map { it.id }.toSet()
            while ("gemini-$n" in ids) n++
            dialogoClave("gemini", "gemini-$n", 50)
        }, Ui.ESP_DENTRO)
        return col
    }

    private fun dialogoClave(proveedor: String, id: String, limite: Int) {
        val a = h.actividad
        val campo = Ui.campo(a, Textos.CAMPO_CLAVE, Textos.CAMPO_CLAVE_EJ, secreto = true)
        Ui.dialogo(a, if (proveedor == "nvidia") Textos.CAMBIAR_NVIDIA else Textos.AGREGAR_GEMINI, campo.vista, Textos.GUARDAR, {
            if (campo.fallo(Validar.claveApi(campo.texto))) return@dialogo false
            try {
                Administracion.apis.agregarClave(id, proveedor, campo.texto.trim(), limite, "24h")
                // Se borra del campo antes de cerrar: la clave no queda en pantalla.
                campo.edit.setText("")
                h.avisar(Textos.CLAVE_AGREGADA)
                h.refrescar()
                true
            } catch (e: Exception) {
                campo.fallo(Textos.errorConDetalle(e.message ?: Textos.ERROR_GENERICO)); false
            }
        })
    }
}
