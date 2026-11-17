package com.soledad.app.recepcion

import com.soledad.app.nucleo.Mensaje
import java.security.MessageDigest

/** Un mensaje tal como viene en EXTRA_MESSAGES. `remitente` vacío = lo escribió el dueño del teléfono. */
class MensajeCrudo(val hora: Long, val texto: String, val remitente: String?)

class Extraccion(val chatId: String, val nombre: String, val grupo: Boolean, val mensajes: List<Mensaje>)

/** Lógica pura (sin clases de Android) de la lectura de notificaciones, para poder probarla en JVM. */
object ExtractorWhatsapp {
    val PAQUETES = setOf("com.whatsapp", "com.whatsapp.w4b")

    /** WhatsApp reenvía todos los no leídos en cada actualización; lo muy viejo no se procesa (p. ej. tras reinicio). */
    const val EDAD_MAXIMA_MS = 10 * 60_000L

    private val JID_EN_CANAL = Regex("^(\\d{6,20}(?::\\d+)?@(?:s\\.whatsapp\\.net|c\\.us))")

    /**
     * El `shortcutId` de WhatsApp es un identificador interno (`…@lid`), no el teléfono. El id del canal de la
     * notificación, en cambio, empieza con el JID real de la persona (`<número>@s.whatsapp.net_<n>`). Con el número real
     * el chat se puede comparar con la agenda y es lo que necesita el envío por interfaz.
     */
    fun jidDelCanal(canalId: String?): String? = canalId?.let { JID_EN_CANAL.find(it.trim())?.groupValues?.get(1) }

    fun idEstable(chatId: String, hora: Long, texto: String): String {
        val h = MessageDigest.getInstance("SHA-256").digest("$chatId|$hora|$texto".toByteArray(Charsets.UTF_8))
        return h.take(16).joinToString("") { "%02x".format(it) }
    }

    /**
     * Devuelve solo mensajes entrantes (con remitente) y recientes. Los propios (eco de lo que envía el bot o el usuario)
     * se descartan aquí: nunca deben encolarse o el bot se respondería a sí mismo.
     * `crudos` null = la notificación no trae EXTRA_MESSAGES; entonces `textoSimple` es el único texto.
     */
    fun extraer(
        paquete: String,
        titulo: String,
        tituloConversacion: String?,
        esGrupoExtra: Boolean,
        shortcutId: String?,
        crudos: List<MensajeCrudo>,
        ahora: Long,
        canalId: String? = null,
        edadMaximaMs: Long = EDAD_MAXIMA_MS,
    ): Extraccion {
        val grupo = tituloConversacion != null || esGrupoExtra
        val nombre = tituloConversacion ?: titulo
        val chatId = jidDelCanal(canalId) ?: shortcutId?.takeIf { it.isNotBlank() } ?: nombre
        val mensajes = crudos
            .filter { it.texto.isNotBlank() && !it.remitente.isNullOrBlank() && ahora - it.hora <= edadMaximaMs }
            .map {
                val t = it.texto.trim()
                Mensaje(idEstable(chatId, it.hora, t), chatId, nombre, t, grupo, it.hora, paquete)
            }
        return Extraccion(chatId, nombre, grupo, mensajes)
    }

    /**
     * Para la revisión de mensajes que ya estaban en la bandeja. Varios mensajes sin contestar del mismo chat se juntan en UNO
     * (el texto de todos, en orden) para que el bot responda una sola vez con todo el contexto en vez de una por mensaje.
     * El id es el del último mensaje: así coincide con el que tendría si hubiera llegado en vivo, y si ya se trató, la cola lo
     * reconoce como duplicado y no se vuelve a responder.
     */
    fun extraerPendientes(
        paquete: String,
        titulo: String,
        tituloConversacion: String?,
        esGrupoExtra: Boolean,
        shortcutId: String?,
        crudos: List<MensajeCrudo>,
        ahora: Long,
        canalId: String?,
        edadMaximaMs: Long,
    ): Extraccion {
        val base = extraer(paquete, titulo, tituloConversacion, esGrupoExtra, shortcutId, crudos, ahora, canalId, edadMaximaMs)
        if (base.mensajes.size <= 1) return base
        val ordenados = base.mensajes.sortedBy { it.recibidoMs }
        val unico = ordenados.last().copy(texto = ordenados.joinToString("\n") { it.texto })
        return Extraccion(base.chatId, base.nombre, base.grupo, listOf(unico))
    }
}
