package com.soledad.app.envio

import android.content.Context
import android.util.Log
import com.soledad.app.nucleo.AccionEnvio
import com.soledad.app.nucleo.Enviador
import com.soledad.app.nucleo.ResultadoEnvio

/**
 * Envía por WhatsApp. NO verifica ni decide: `ok` solo significa «se hizo el gesto de enviar».
 * Texto: método A («Responder» de la notificación); si no hay acción vigente o falla, método B (interfaz por Shizuku).
 * Imagen: siempre por interfaz (intent de compartir + tocar Enviar).
 */
class EnviadorWhatsApp(
    private val shell: EjecutorShell,
    private val estado: EstadoNotificaciones = EstadoNotificaciones.global,
    private val reloj: Reloj = RelojReal,
    private val log: (String) -> Unit = {},
    private val paquete: String = "com.whatsapp",
) : Enviador {

    override fun enviar(a: AccionEnvio): ResultadoEnvio = when (a) {
        is AccionEnvio.Texto -> enviarTexto(a)
        is AccionEnvio.Imagen -> enviarImagen(a)
    }

    private fun enviarTexto(a: AccionEnvio.Texto): ResultadoEnvio {
        if (a.texto.isBlank()) return ResultadoEnvio(false, "texto vacío")
        val motivoA: String
        val resp = estado.hilo(a.chatId)?.respondedor
        if (resp == null) {
            motivoA = "sin acción «Responder» vigente"
        } else {
            motivoA = try {
                resp.responder(a.texto)
                return ResultadoEnvio(true, "método A: enviada por «Responder» de la notificación")
            } catch (e: RespuestaNoVigente) {
                estado.olvidarRespondedor(a.chatId)
                "«Responder» caducó"
            } catch (e: Exception) {
                estado.olvidarRespondedor(a.chatId)
                "«Responder» falló (${e.javaClass.simpleName})"
            }
        }
        log("envio: método A no disponible ($motivoA), paso a B")
        val r = enviarTextoPorInterfaz(a)
        return ResultadoEnvio(r.ok, "método B tras A ($motivoA): ${r.nota}")
    }

    private fun enviarTextoPorInterfaz(a: AccionEnvio.Texto): ResultadoEnvio {
        if (!shell.disponible()) return ResultadoEnvio(false, "Shizuku no disponible")
        val numero = Jid.numero(a.chatId) ?: return ResultadoEnvio(false, "el método B necesita el JID de un chat individual")
        val codificado = Texto.urlCodificar(a.texto)
        if (codificado.length > 6000) return ResultadoEnvio(false, "texto demasiado largo para el enlace del método B")
        // El texto va precargado en el enlace (UTF-8 codificado): evita teclear con `input text`, que no admite acentos.
        val inicio = shell.ejecutar(
            "am start -a android.intent.action.VIEW -d ${Rutas.comillas("https://wa.me/$numero?text=$codificado")} -p $paquete",
        )
        if (!inicio.ok || inicio.salida.contains("Error", ignoreCase = true)) {
            return ResultadoEnvio(false, "no se pudo abrir el chat: ${inicio.salida.take(120)}")
        }
        return tocarEnviar("texto")
    }

    private fun enviarImagen(a: AccionEnvio.Imagen): ResultadoEnvio {
        if (!Rutas.valida(a.ruta) || !Rutas.esImagen(a.ruta)) return ResultadoEnvio(false, "ruta de imagen no válida")
        if (!shell.disponible()) return ResultadoEnvio(false, "Shizuku no disponible")
        val jid = Jid.jid(a.chatId) ?: return ResultadoEnvio(false, "la imagen necesita el JID de un chat individual")
        val legible = shell.ejecutar("test -f ${Rutas.comillas(a.ruta)} && test -r ${Rutas.comillas(a.ruta)}")
        if (!legible.ok) return ResultadoEnvio(false, "la imagen no existe o no es legible")
        val pie = a.pie?.takeIf { it.isNotBlank() }?.let { " --es android.intent.extra.TEXT ${Rutas.comillas(it)}" } ?: ""
        val inicio = shell.ejecutar(
            "am start -a android.intent.action.SEND -t 'image/*' --eu android.intent.extra.STREAM ${Rutas.comillas(Rutas.uriArchivo(a.ruta))} " +
                "-e jid ${Rutas.comillas(jid)}$pie -p $paquete",
        )
        if (!inicio.ok || inicio.salida.contains("Error", ignoreCase = true)) {
            return ResultadoEnvio(false, "no se pudo abrir WhatsApp con la imagen: ${inicio.salida.take(120)}")
        }
        return tocarEnviar("imagen")
    }

    /** Busca el botón Enviar con `uiautomator dump` (la pantalla tarda en aparecer) y lo toca. */
    private fun tocarEnviar(que: String): ResultadoEnvio {
        repeat(INTENTOS_BOTON) { i ->
            reloj.dormir(if (i == 0) 1_800 else 1_000)
            val arbol = DumpUi.parsear(volcarUi(shell)) ?: return@repeat
            val boton = DumpUi.botonEnviar(arbol) ?: return@repeat
            val t = shell.ejecutar("input tap ${boton.centroX} ${boton.centroY}")
            return if (t.ok) ResultadoEnvio(true, "método B: toqué Enviar ($que) en ${boton.centroX},${boton.centroY}")
            else ResultadoEnvio(false, "el toque falló: ${t.salida.take(80)}")
        }
        return ResultadoEnvio(false, "no apareció el botón Enviar en $INTENTOS_BOTON intentos")
    }

    companion object {
        private const val INTENTOS_BOTON = 8

        fun crear(ctx: Context): Enviador {
            val shell = EjecutorRegistrado(ShizukuShell()) { Log.d("Agente/Envio", it) }
            return EnviadorWhatsApp(shell, EstadoNotificaciones.global, RelojReal, { Log.i("Agente/Envio", it) })
        }
    }
}

/** Volcado de la interfaz actual como XML ('' si falló). El archivo temporal está en una ruta donde `shell` escribe. */
internal fun volcarUi(shell: EjecutorShell): String {
    val r = shell.ejecutar(
        "uiautomator dump /data/local/tmp/agente_ui.xml >/dev/null 2>&1; cat /data/local/tmp/agente_ui.xml",
        limiteMs = 20_000,
    )
    return if (r.expiro) "" else r.salida
}
