package com.soledad.app.envio

import android.content.Context
import android.util.Log
import com.soledad.app.nucleo.AccionEnvio
import com.soledad.app.nucleo.Servicios
import com.soledad.app.nucleo.VerificadorEnvio
import java.util.TimeZone

class ResultadoVerificacion(val ok: Boolean, val evidencia: String, val estrategia: String)

/**
 * Confirma que el mensaje SALIÓ. Estrategia A: eco del texto propio en el repintado de la notificación.
 * Estrategia B: `uiautomator dump` con el chat abierto (texto exacto + estado enviado/entregado/leído).
 * Nunca devuelve true sin evidencia, y la evidencia queda en el registro.
 */
class VerificadorWhatsApp(
    private val shell: EjecutorShell,
    private val estado: EstadoNotificaciones = EstadoNotificaciones.global,
    private val reloj: Reloj = RelojReal,
    private val anotar: (String) -> Unit = {},
    private val zona: TimeZone = TimeZone.getDefault(),
    private val paquete: String = "com.whatsapp",
    private val fraccionA: Double = 0.4,
) : VerificadorEnvio {

    @Volatile var ultima: ResultadoVerificacion? = null
        private set

    override fun verificar(a: AccionEnvio, desdeMs: Long, timeoutMs: Long): Boolean {
        val r = detallar(a, desdeMs, timeoutMs)
        ultima = r
        anotar("verificación ${if (r.ok) "CONFIRMADA" else "SIN CONFIRMAR"} [${r.estrategia}]: ${r.evidencia}")
        return r.ok
    }

    fun detallar(a: AccionEnvio, desdeMs: Long, timeoutMs: Long): ResultadoVerificacion {
        if (timeoutMs <= 0) return ResultadoVerificacion(false, "timeout no positivo", "ninguna")
        val inicio = reloj.ahora()
        val total = inicio + timeoutMs
        val notas = ArrayList<String>()

        // A solo sirve con texto (la imagen no deja texto propio en la notificación) y se reparte el plazo.
        if (a is AccionEnvio.Texto && a.texto.isNotBlank()) {
            val limiteA = if (shell.disponible()) inicio + (timeoutMs * fraccionA).toLong() else total
            while (true) {
                estado.eco(a.chatId, a.texto, desdeMs)?.let { return ResultadoVerificacion(true, it, "A-notificación") }
                if (reloj.ahora() >= limiteA) break
                reloj.dormir(250)
            }
            notas.add("A: sin eco en ${reloj.ahora() - inicio} ms")
        } else {
            notas.add("A: no aplica")
        }

        if (!shell.disponible()) {
            return ResultadoVerificacion(false, (notas + "B: Shizuku no disponible").joinToString("; "), "ninguna")
        }
        if (reloj.ahora() >= total) return ResultadoVerificacion(false, (notas + "B: sin plazo").joinToString("; "), "ninguna")

        abrirChat(a.chatId)?.let { notas.add(it) }
        reloj.dormir(minOf(1_200L, maxOf(0L, total - reloj.ahora())))
        var ultimaObs = "sin lectura"
        var intentos = 0
        while (reloj.ahora() < total) {
            intentos++
            val arbol = DumpUi.parsear(volcarUi(shell))
            if (arbol == null) {
                ultimaObs = "dump vacío"
            } else {
                val h = when (a) {
                    is AccionEnvio.Texto -> DumpUi.buscarTexto(arbol, a.texto)
                    is AccionEnvio.Imagen -> DumpUi.buscarImagen(arbol, a.pie)
                }
                ultimaObs = when {
                    h == null -> "el chat abierto no muestra el texto/imagen enviado"
                    h.estado == null -> "encontrado sin estado (${h.nota})"
                    !h.estado.salio -> "encontrado en estado ${h.estado.etiqueta}"
                    !DumpUi.horaCompatible(h.hora, desdeMs, zona) -> "encontrado pero con hora anterior al envío (${h.hora})"
                    else -> return ResultadoVerificacion(
                        true,
                        "nodo en el chat abierto con estado ${h.estado.etiqueta}, hora=${h.hora ?: "n/d"}, tras $intentos lectura(s)",
                        "B-interfaz",
                    )
                }
            }
            if (reloj.ahora() >= total) break
            reloj.dormir(minOf(1_000L, maxOf(0L, total - reloj.ahora())))
        }
        return ResultadoVerificacion(false, (notas + "B: $ultimaObs tras $intentos lectura(s)").joinToString("; "), "ninguna")
    }

    /** Abre el chat para el volcado: enlace con el número, o el contentIntent guardado si el chat no tiene JID. */
    private fun abrirChat(chatId: String): String? {
        val numero = Jid.numero(chatId)
        if (numero != null) {
            val r = shell.ejecutar("am start -a android.intent.action.VIEW -d ${Rutas.comillas("https://wa.me/$numero")} -p $paquete")
            return if (r.ok) null else "no se pudo abrir el chat: ${r.salida.take(80)}"
        }
        val abrir = estado.hilo(chatId)?.abrir ?: return "chat sin JID ni contentIntent: se usa lo que haya en pantalla"
        return try { abrir(); null } catch (e: Exception) { "contentIntent falló (${e.javaClass.simpleName})" }
    }

    companion object {
        fun crear(ctx: Context): VerificadorEnvio {
            val shell = EjecutorRegistrado(ShizukuShell()) { Log.d("Agente/Verif", it) }
            return VerificadorWhatsApp(shell, anotar = { detalle ->
                Log.i("Agente/Verif", detalle)
                try { Servicios.registro.anotar(null, "verificacion_envio", detalle) } catch (e: UninitializedPropertyAccessException) { }
            })
        }
    }
}
