package com.soledad.app.recepcion

import android.app.Notification
import android.app.Person
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.soledad.app.nucleo.Mensaje
import com.soledad.app.nucleo.Servicios
import com.soledad.app.soledad.Soledad

/**
 * Escucha de notificaciones de WhatsApp. Soledad NO responde en el momento: solo lee la bandeja cuando le toca
 * revisar (`sinLeer`). En vivo únicamente pasa cada notificación a la capa de envío, que guarda la acción «Responder»
 * y detecta el eco de lo enviado (verificación). Con Soledad apagada no hace nada.
 */
class Escucha : NotificationListenerService() {

    override fun onListenerConnected() {
        instancia = this
    }

    override fun onListenerDisconnected() {
        if (instancia === this) instancia = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in ExtractorWhatsapp.PAQUETES) return
        if (!Soledad.encendida(this)) return
        val e = try { extraer(sbn, ExtractorWhatsapp.EDAD_MAXIMA_MS) } catch (_: Throwable) { null }
        // «Responder apenas llega un mensaje»: solo si trae mensajes entrantes de un chat individual (los grupos nunca).
        if (e != null && !e.grupo && e.mensajes.isNotEmpty()) Soledad.alLlegar(applicationContext)
    }

    /**
     * Mensajes sin leer de WhatsApp que siguen en la bandeja (uno por chat, con los textos juntos).
     * null si el sistema no entrega las notificaciones activas.
     */
    fun sinLeer(edadMaximaMs: Long): List<Mensaje>? {
        val activas = try { activeNotifications } catch (_: Exception) { return null } ?: return null
        return activas.filter { it.packageName in ExtractorWhatsapp.PAQUETES }
            .mapNotNull { sbn -> try { extraer(sbn, edadMaximaMs) } catch (_: Throwable) { null } }
            .flatMap { it.mensajes }
    }

    /** Chats (por id) con una notificación de WhatsApp todavía en la bandeja. null si no se puede saber. */
    fun chatsEnBandeja(): Set<String>? {
        val activas = try { activeNotifications } catch (_: Exception) { return null } ?: return null
        return activas.filter { it.packageName in ExtractorWhatsapp.PAQUETES }
            .mapNotNull { sbn -> try { extraer(sbn, Long.MAX_VALUE, avisar = false)?.chatId } catch (_: Throwable) { null } }
            .toSet()
    }

    private fun extraer(sbn: StatusBarNotification, edadMaximaMs: Long, avisar: Boolean = true): Extraccion? {
        val n = sbn.notification ?: return null
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        val ex = n.extras
        val tituloConv = ex.getString(Notification.EXTRA_CONVERSATION_TITLE)
        val titulo = tituloConv ?: ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return null
        val grupo = ex.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION)
        val e = ExtractorWhatsapp.extraerPendientes(
            sbn.packageName, titulo, tituloConv, grupo, n.shortcutId, leer(ex, sbn.postTime, titulo),
            System.currentTimeMillis(), n.channelId, edadMaximaMs,
        )
        // Síncrono: el objeto Notification no sirve después. Guarda «Responder» y el eco para la verificación.
        if (avisar) try { Servicios.notificaciones.alVer(e.chatId, e.nombre, sbn.packageName, n) } catch (_: Throwable) { }
        return e
    }

    @Suppress("DEPRECATION")
    private fun leer(ex: Bundle, postTime: Long, titulo: String): List<MensajeCrudo> {
        val arr = ex.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (arr != null) {
            return arr.mapNotNull { p ->
                val b = p as? Bundle ?: return@mapNotNull null
                val texto = b.getCharSequence("text")?.toString().orEmpty()
                val remitente = b.getCharSequence("sender")?.toString()
                    ?: (b.getParcelable<android.os.Parcelable>("sender_person") as? Person)?.name?.toString()
                MensajeCrudo(b.getLong("time", postTime), texto, remitente)
            }
        }
        val t = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        return if (t.isBlank()) emptyList() else listOf(MensajeCrudo(postTime, t, titulo))
    }

    companion object {
        /** La escucha conectada. null si el sistema no la tiene enlazada. */
        @Volatile var instancia: Escucha? = null
            private set

        fun componente(ctx: Context) = ComponentName(ctx, Escucha::class.java)

        /** Pide al sistema que vuelva a enlazar la escucha (tras Salir, que la desenlaza). */
        fun reenlazar(ctx: Context) {
            try { requestRebind(componente(ctx)) } catch (_: Throwable) { }
        }

        /** Desenlaza la escucha: con Salir, el sistema no vuelve a levantar la app por las notificaciones. */
        fun desenlazar() {
            try { instancia?.requestUnbind() } catch (_: Throwable) { }
            instancia = null
        }
    }
}
