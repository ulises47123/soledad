package com.soledad.app.ui

import android.app.Activity
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.soledad.app.Administracion
import com.soledad.app.apis.ClavesIa
import com.soledad.app.nucleo.EstadoApi
import com.soledad.app.soledad.ConfigSoledad
import com.soledad.app.soledad.CredencialTelefono
import com.soledad.app.soledad.Soledad
import com.soledad.app.ui.Ui.agregar

/**
 * Ajustes: a quién responde, modo prueba, etiqueta, cuándo revisa, WhatsApp, desbloqueo (opt-in) y claves de IA
 * (varias por proveedor, en orden, con su estado).
 */
class PantallaAjustes(h: Anfitrion) : Pantalla(h) {
    override val titulo = Textos.P_AJUSTES

    private fun cambiar(f: (ConfigSoledad) -> ConfigSoledad) = Soledad.ajustes.cambiar(f)

    override fun construir(): View {
        val a: Activity = h.actividad
        val cfg = Soledad.ajustes.leer()
        val col = Ui.columna(a)

        // A quién responde
        col.agregar(Ui.seccion(a, Textos.AJ_A_QUIEN))
        col.agregar(Ui.radio(a, Textos.A_LISTA, Textos.A_LISTA_FRASE, !cfg.responderATodos) {
            if (cfg.responderATodos) {
                cambiar { it.copy(responderATodos = false) }
                h.avisar(Textos.A_LISTA_AVISO); h.refrescar()
            }
        }, Ui.ESP_DENTRO)
        col.agregar(Ui.radio(a, Textos.A_TODOS, Textos.A_TODOS_FRASE, cfg.responderATodos) {
            if (!cfg.responderATodos) {
                Ui.confirmar(a, Textos.A_TODOS_TITULO, Textos.A_TODOS_RIESGO, Textos.A_TODOS_CONFIRMAR) {
                    cambiar { it.copy(responderATodos = true) }
                    h.avisar(Textos.A_TODOS_AVISO); h.refrescar()
                }
            }
        }, Ui.ESP_DENTRO)

        // Respuestas
        col.agregar(Ui.seccion(a, Textos.AJ_RESPUESTAS), Ui.ESP_GRUPO)
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
        col.agregar(Ui.interruptor(a, Textos.AL_LLEGAR, cfg.responderAlLlegar, { if (it) Textos.AL_LLEGAR_ON else Textos.AL_LLEGAR_OFF }) { v ->
            cambiar { it.copy(responderAlLlegar = v) }
        }, Ui.ESP_DENTRO)
        col.agregar(Ui.nota(a, Textos.INTERVALOS_NOTA), Ui.ESP_DENTRO * 2)
        listOf(1 to Textos.INT_1, 20 to Textos.INT_20, 50 to Textos.INT_50, 120 to Textos.INT_120).forEach { (min, nombre) ->
            val activo = min in cfg.intervalosMin
            col.agregar(Ui.interruptor(a, nombre, activo, { if (!it) Textos.INT_NO else if (min == 1) Textos.INT_1_SI else Textos.INT_SI }) { v ->
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
        col.agregar(Ui.interruptor(a, Textos.AL_INSTANTE, cfg.alInstante, { if (it) Textos.AL_INSTANTE_ON else Textos.AL_INSTANTE_OFF }) { v ->
            cambiar { it.copy(alInstante = v) }; h.refrescar()
        }, Ui.ESP_DENTRO)
        if (!cfg.alInstante) {
            val minutos = (1..5).toList()
            col.agregar(Ui.filaNav(a, Textos.ESPERA, Textos.espera(cfg.esperaMinMin, cfg.esperaMaxMin)) {
                Ui.elegir(a, Textos.ESPERA_DESDE, minutos.map { "$it min" }, cfg.esperaMinMin - 1) { i ->
                    val desde = minutos[i]
                    Ui.elegir(a, Textos.ESPERA_HASTA, minutos.filter { it >= desde }.map { "$it min" }, 0) { j ->
                        cambiar { it.copy(esperaMinMin = desde, esperaMaxMin = minutos.filter { m -> m >= desde }[j]) }; h.refrescar()
                    }
                }
            }, Ui.ESP_DENTRO)
        }

        // WhatsApp
        col.agregar(Ui.seccion(a, Textos.AJ_WHATSAPP), Ui.ESP_GRUPO)
        col.agregar(Ui.interruptor(a, Textos.ENTRAR, cfg.entrarAWhatsApp, { if (it) Textos.ENTRAR_ON else Textos.ENTRAR_OFF }) { v ->
            cambiar { it.copy(entrarAWhatsApp = v) }
        }, Ui.ESP_DENTRO)
        col.agregar(Ui.interruptor(a, Textos.INTERFAZ, cfg.enviarPorInterfaz, { if (it) Textos.INTERFAZ_ON else Textos.INTERFAZ_OFF }) { v ->
            cambiar { it.copy(enviarPorInterfaz = v) }
        }, Ui.ESP_DENTRO)

        // Desbloqueo (opt-in)
        construirDesbloqueo(a, col, cfg)

        // Claves
        construirClaves(a, col)
        return col
    }

    // ---------- Desbloqueo ----------

    private fun construirDesbloqueo(a: Activity, col: LinearLayout, cfg: ConfigSoledad) {
        val cred = CredencialTelefono.crear(a)
        val hay = try { cred.hay() } catch (_: Exception) { false }
        col.agregar(Ui.seccion(a, Textos.AJ_DESBLOQUEO), Ui.ESP_GRUPO)
        col.agregar(Ui.aviso(a, Textos.DESBLOQUEO_RIESGO_TITULO, Textos.DESBLOQUEO_RIESGO, Nivel.AVISO), Ui.ESP_DENTRO)
        col.agregar(Ui.interruptor(a, Textos.DESBLOQUEAR, cfg.desbloquear, { if (it) Textos.DESBLOQUEAR_ON else Textos.DESBLOQUEAR_OFF }) { v ->
            if (v && !hay) {
                h.avisar(Textos.DESBLOQUEO_SIN_CREDENCIAL, true)
                h.refrescar()
                dialogoCredencial(activarAlGuardar = true)
                return@interruptor
            }
            cambiar { it.copy(desbloquear = v) }
            h.avisar(if (v) Textos.DESBLOQUEAR_ON else Textos.DESBLOQUEAR_OFF)
        }, Ui.ESP_DENTRO)
        val nivel = if (hay) Nivel.LISTO else Nivel.AVISO
        col.agregar(Ui.texto(a, "${Semaforo.simbolo(nivel)} ${if (hay) Textos.CREDENCIAL_GUARDADA else Textos.CREDENCIAL_FALTA}",
            Ui.T_NOTA, Ui.colorDe(nivel), negrita = true), Ui.ESP_DENTRO)
        col.agregar(Ui.boton(a, if (hay) Textos.CAMBIAR_CREDENCIAL else Textos.GUARDAR_CREDENCIAL, Ui.Estilo.SECUNDARIO) {
            dialogoCredencial(activarAlGuardar = false)
        }, Ui.ESP_DENTRO)
        if (hay) {
            col.agregar(Ui.boton(a, Textos.BORRAR_CREDENCIAL, Ui.Estilo.PELIGRO) {
                Ui.confirmar(a, Textos.BORRAR_CREDENCIAL_TITULO, Textos.BORRAR_CREDENCIAL_CUERPO, Textos.BORRAR_CREDENCIAL) {
                    cred.borrar()
                    cambiar { it.copy(desbloquear = false) }
                    h.avisar(Textos.CREDENCIAL_BORRADA); h.refrescar()
                }
            }, Ui.ESP_DENTRO)
        }
    }

    private fun dialogoCredencial(activarAlGuardar: Boolean) {
        val a = h.actividad
        val campo = Ui.campo(a, Textos.CAMPO_CREDENCIAL, Textos.CAMPO_CREDENCIAL_EJ, ayuda = Textos.CAMPO_CREDENCIAL_AYUDA, secreto = true)
        Ui.dialogo(a, Textos.GUARDAR_CREDENCIAL, campo.vista, Textos.GUARDAR, {
            val t = campo.texto
            if (campo.fallo(if (t.length < 4 || t.contains('\n')) Textos.ERR_CREDENCIAL else null)) return@dialogo false
            try {
                CredencialTelefono.crear(a).guardar(t)
                campo.edit.setText("") // no queda en pantalla
                if (activarAlGuardar) cambiar { it.copy(desbloquear = true) }
                h.avisar(Textos.CREDENCIAL_OK)
                h.refrescar()
                true
            } catch (e: Exception) {
                campo.fallo(Textos.errorConDetalle(e.javaClass.simpleName)); false
            }
        })
    }

    // ---------- Claves ----------

    private fun construirClaves(a: Activity, col: LinearLayout) {
        col.agregar(Ui.seccion(a, Textos.AJ_CLAVES), Ui.ESP_GRUPO)
        col.agregar(Ui.nota(a, Textos.CLAVES_NOTA), Ui.ESP_DENTRO)
        val estados = try { Administracion.apis.estado() } catch (e: Exception) { emptyList() }
        if (estados.isEmpty()) col.agregar(Ui.texto(a, Textos.SIN_CLAVES), Ui.ESP_DENTRO)
        estados.forEachIndexed { i, e -> col.agregar(tarjetaClave(a, e, i, estados.size), Ui.ESP_DENTRO) }

        col.agregar(Ui.boton(a, Textos.PROBAR_CLAVES, Ui.Estilo.SECUNDARIO) {
            h.avisar(Textos.PROBANDO_CLAVES)
            h.enSegundoPlano({ Soledad.probarClaves(a) }) { r ->
                r.onSuccess { l ->
                    val cuerpo = if (l.isEmpty()) Textos.SIN_CLAVES else l.joinToString("\n") { p ->
                        "${if (p.ok) Textos.SIMBOLO_LISTO else Textos.SIMBOLO_FALTA} ${p.id} (…${p.fin}): ${p.detalle}"
                    }
                    h.refrescar()
                    Ui.mensaje(a, Textos.PROBAR_CLAVES, cuerpo)
                }.onFailure { h.avisar(Textos.errorConDetalle(it.javaClass.simpleName), true) }
            }
        }, Ui.ESP_DENTRO * 2)
        val ids = estados.map { it.id }.toSet()
        col.agregar(Ui.boton(a, Textos.AGREGAR_NVIDIA, Ui.Estilo.SECUNDARIO) { dialogoClave("nvidia", libre("nvidia", ids), 300, nueva = true) }, Ui.ESP_DENTRO)
        col.agregar(Ui.boton(a, Textos.AGREGAR_GEMINI, Ui.Estilo.SECUNDARIO) { dialogoClave("gemini", libre("gemini", ids), 50, nueva = true) }, Ui.ESP_DENTRO)
    }

    private fun libre(proveedor: String, ids: Set<String>): String {
        var n = 1
        while ("$proveedor-$n" in ids) n++
        return "$proveedor-$n"
    }

    private fun tarjetaClave(a: Activity, e: EstadoApi, i: Int, total: Int): View {
        val est = ClavesIa.estado(a).de(e.id)
        val fin = ClavesIa.fin(try { Administracion.apis.claveDe(e.id) } catch (_: Exception) { null })
        val (nivel, linea) = when {
            fin.isEmpty() -> Nivel.FALTA to Textos.CLAVE_FALTA
            est == null || (est.okMs == 0L && est.errorMs == 0L) -> Nivel.AVISO to Textos.CLAVE_SIN_USO
            est.errorMs > est.okMs -> Nivel.FALTA to Textos.claveError(formatoFecha(est.errorMs), est.error)
            else -> Nivel.LISTO to Textos.claveOk(formatoFecha(est.okMs))
        }
        val card = Ui.tarjeta(a)
        card.agregar(Ui.texto(a, "${i + 1}. ${e.id}" + if (fin.isNotEmpty()) "  ·  …$fin" else "", Ui.T_CUERPO, Ui.p.texto, mono = true, negrita = true))
        card.agregar(Ui.texto(a, "${Semaforo.simbolo(nivel)} $linea", Ui.T_NOTA, Ui.colorDe(nivel), negrita = true), 2)
        card.agregar(Ui.nota(a, Textos.usosHoy(e.usos, e.limite)), 2)
        val fila = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun mover(delta: Int) { Administracion.apis.mover(e.id, delta); h.avisar(Textos.ORDEN_CAMBIADO); h.refrescar() }
        val lp = { LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = Ui.dp(a, 8) } }
        if (i > 0) fila.addView(Ui.boton(a, "↑", Ui.Estilo.SECUNDARIO, Textos.subirClave(e.id)) { mover(-1) }, lp())
        if (i < total - 1) fila.addView(Ui.boton(a, "↓", Ui.Estilo.SECUNDARIO, Textos.bajarClave(e.id)) { mover(+1) }, lp())
        fila.addView(Ui.boton(a, Textos.CAMBIAR, Ui.Estilo.SECUNDARIO, Textos.cambiarClave(e.id)) {
            dialogoClave(e.proveedor, e.id, e.limite, nueva = false)
        }, lp())
        fila.addView(Ui.boton(a, Textos.QUITAR, Ui.Estilo.PELIGRO, Textos.quitarClave(e.id)) {
            Ui.confirmar(a, Textos.quitarClaveTitulo(e.id), Textos.QUITAR_CLAVE_CUERPO, Textos.QUITAR_CLAVE) {
                Administracion.apis.quitarClave(e.id)
                ClavesIa.estado(a).olvidar(e.id)
                h.avisar(Textos.claveQuitada(e.id)); h.refrescar()
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        card.agregar(fila, Ui.ESP_DENTRO)
        return card
    }

    private fun dialogoClave(proveedor: String, id: String, limite: Int, nueva: Boolean) {
        val a = h.actividad
        val campo = Ui.campo(a, Textos.CAMPO_CLAVE, Textos.CAMPO_CLAVE_EJ, secreto = true,
            tipo = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val titulo = if (nueva) (if (proveedor == "nvidia") Textos.AGREGAR_NVIDIA else Textos.AGREGAR_GEMINI) else Textos.cambiarClave(id)
        Ui.dialogo(a, titulo, campo.vista, Textos.GUARDAR, {
            if (campo.fallo(Validar.claveApi(campo.texto))) return@dialogo false
            try {
                Administracion.apis.agregarClave(id, proveedor, campo.texto.trim(), limite, "24h")
                ClavesIa.estado(a).olvidar(id)
                // Se borra del campo antes de cerrar: la clave no queda en pantalla.
                campo.edit.setText("")
                h.avisar(Textos.claveGuardada(id))
                h.refrescar()
                true
            } catch (e: Exception) {
                campo.fallo(Textos.errorConDetalle(e.message ?: Textos.ERROR_GENERICO)); false
            }
        })
    }
}
