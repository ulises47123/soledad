package com.soledad.app.envio

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.soledad.app.nucleo.RespuestasNotificacion

/** Un texto visto en una notificación. `remitente` null = sin remitente (se trata como propio, ver `propio`). */
class TextoNotif(val texto: String, val remitente: String?) {
    /** «Tú»/«You»/sin remitente. Un remitente con otro nombre es de la otra persona y no cuenta como eco. */
    val propio: Boolean
        get() {
            val r = remitente?.let { Texto.normalizar(it).lowercase() } ?: return true
            return r.isEmpty() || r in REMITENTES_PROPIOS
        }

    companion object {
        val REMITENTES_PROPIOS = setOf("tú", "tu", "you", "yo", "me", "você", "voce")

        /** Línea de resumen «Tú: hola» → propio; «Ana: hola» → de Ana; sin dos puntos → remitente desconocido `nombre`. */
        fun deLinea(linea: String, nombreChat: String): TextoNotif {
            val i = linea.indexOf(": ")
            if (i in 1..30) {
                val quien = linea.substring(0, i)
                return TextoNotif(linea.substring(i + 2), quien)
            }
            return TextoNotif(linea, nombreChat)
        }
    }
}

/** Ejecuta la acción «Responder». Lanza [RespuestaNoVigente] si la notificación ya no acepta respuestas. */
interface Respondedor {
    fun responder(texto: String)
}

class RespuestaNoVigente(mensaje: String) : Exception(mensaje)

class HiloChat(val respondedor: Respondedor?, val abrir: (() -> Unit)?, val vistoMs: Long)

private class Repintado(val ts: Long, val textos: List<TextoNotif>)

/**
 * Memoria acotada de lo que dicen las notificaciones: por chat, la acción «Responder» y el
 * contentIntent vigentes y un búfer corto de repintados (con los textos propios incluidos) para
 * detectar el eco. Un único estado compartido por Enviador, Verificador y la escucha.
 */
class EstadoNotificaciones(
    private val ahora: () -> Long = System::currentTimeMillis,
    private val maxChats: Int = 64,
    private val maxRepintados: Int = 12,
    private val vigenciaMs: Long = 6 * 3_600_000L,
) {
    private val hilos = object : LinkedHashMap<String, HiloChat>(16, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, HiloChat>) = size > maxChats
    }
    private val repintados = object : LinkedHashMap<String, ArrayDeque<Repintado>>(16, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, ArrayDeque<Repintado>>) = size > maxChats
    }

    @Synchronized
    fun registrar(chatId: String, hilo: HiloChat?, textos: List<TextoNotif>) {
        if (hilo != null) hilos[chatId] = hilo
        val cola = repintados.getOrPut(chatId) { ArrayDeque() }
        cola.addLast(Repintado(ahora(), textos.take(20)))
        while (cola.size > maxRepintados) cola.removeFirst()
    }

    /** El hilo guardado si sigue siendo reciente. */
    @Synchronized
    fun hilo(chatId: String): HiloChat? {
        val h = hilos[chatId] ?: return null
        if (ahora() - h.vistoMs > vigenciaMs) { hilos.remove(chatId); return null }
        return h
    }

    @Synchronized
    fun olvidarRespondedor(chatId: String) {
        val h = hilos[chatId] ?: return
        hilos[chatId] = HiloChat(null, h.abrir, h.vistoMs)
    }

    /**
     * Evidencia del eco: un repintado posterior a `desdeMs` que contenga el texto, con remitente propio.
     * Devuelve la descripción de la evidencia o null.
     */
    @Synchronized
    fun eco(chatId: String, texto: String, desdeMs: Long): String? {
        val cola = repintados[chatId] ?: return null
        for (r in cola.reversed()) {
            if (r.ts < desdeMs) continue
            val t = r.textos.firstOrNull { it.propio && Texto.coincideEnNotificacion(texto, it.texto) } ?: continue
            return "eco en notificación a +${r.ts - desdeMs} ms (remitente=${t.remitente ?: "ninguno"})"
        }
        return null
    }

    @Synchronized
    fun cuantosChats(): Int = hilos.size

    @Synchronized
    fun repintadosDe(chatId: String): Int = repintados[chatId]?.size ?: 0

    companion object {
        val global = EstadoNotificaciones()
    }
}

/** Implementación de [RespuestasNotificacion] sobre las notificaciones reales de WhatsApp. */
class RespuestasNotificacionWhatsApp(
    private val ctx: Context,
    private val estado: EstadoNotificaciones = EstadoNotificaciones.global,
) : RespuestasNotificacion {

    override fun alVer(chatId: String, nombre: String, paquete: String, notificacion: Notification) {
        try {
            val accion = notificacion.actions?.firstOrNull { a -> a.remoteInputs?.isNotEmpty() == true }
            val abrir = notificacion.contentIntent?.let { pi -> { pi.send() } }
            val hilo = if (accion != null || abrir != null)
                HiloChat(accion?.let { RespondedorRemoteInput(ctx, it) }, abrir, System.currentTimeMillis()) else null
            estado.registrar(chatId, hilo, extraerTextos(notificacion, nombre))
        } catch (e: Exception) {
            // Una notificación rara no debe romper la escucha.
        }
    }

    @Suppress("DEPRECATION")
    private fun extraerTextos(n: Notification, nombre: String): List<TextoNotif> {
        val ex = n.extras ?: return emptyList()
        val r = ArrayList<TextoNotif>()
        for (clave in listOf(Notification.EXTRA_HISTORIC_MESSAGES, Notification.EXTRA_MESSAGES)) {
            val arr = ex.getParcelableArray(clave) ?: continue
            for (p in arr) {
                val b = p as? Bundle ?: continue
                val texto = b.getCharSequence("text")?.toString() ?: continue
                val quien = b.getCharSequence("sender")?.toString()
                    ?: (b.getParcelable<android.app.Person>("sender_person"))?.name?.toString()
                r.add(TextoNotif(texto, quien))
            }
        }
        if (r.isEmpty()) {
            ex.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.forEach { r.add(TextoNotif.deLinea(it.toString(), nombre)) }
            ex.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.let { r.add(TextoNotif.deLinea(it, nombre)) }
        }
        return r
    }
}

private class RespondedorRemoteInput(private val ctx: Context, private val accion: Notification.Action) : Respondedor {
    override fun responder(texto: String) {
        val entrada = accion.remoteInputs[0]
        val i = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        val b = Bundle().apply { putCharSequence(entrada.resultKey, texto) }
        RemoteInput.addResultsToIntent(arrayOf(entrada), i, b)
        RemoteInput.setResultsSource(i, RemoteInput.SOURCE_FREE_FORM_INPUT)
        try {
            accion.actionIntent.send(ctx, 0, i)
        } catch (e: PendingIntent.CanceledException) {
            throw RespuestaNoVigente("la notificación ya no acepta respuestas")
        }
    }
}
