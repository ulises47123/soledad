package com.soledad.app.lista

/**
 * Comparación de teléfonos entre la agenda y los chats de WhatsApp.
 * Un contacto de la agenda puede venir como «+54 9 388 462-6133», «388 462-6133» o con paréntesis; el chat de WhatsApp
 * llega como «5493880000000@s.whatsapp.net». Se comparan los últimos dígitos, que es lo único estable entre esos formatos.
 */
object Telefono {
    /** Cuántos dígitos finales se comparan: suficientes para distinguir personas, y ajenos al prefijo de país. */
    private const val CIFRAS = 10
    private const val MIN_CIFRAS = 7

    private val JID_TELEFONO = Regex("^(\\d{6,20})(?::\\d+)?@(?:s\\.whatsapp\\.net|c\\.us)$")

    /** Número (solo dígitos) de un JID de WhatsApp de una persona, o "" si no es uno (grupos, `@lid`, nombres). */
    fun deJid(jid: String): String = JID_TELEFONO.matchEntire(jid.trim())?.groupValues?.get(1).orEmpty()

    /** Solo dígitos de un número tal como lo muestra la agenda. "" si el texto no parece un teléfono. */
    fun digitos(raw: String): String {
        val s = raw.trim()
        if (s.isEmpty() || s.any { !(it.isDigit() || it in "+ -().") }) return ""
        return s.filter { it.isDigit() }
    }

    /** Número de un identificador guardado: un JID de persona, o un teléfono escrito/elegido de la agenda. */
    fun numeroDe(id: String): String = deJid(id).ifEmpty { digitos(id) }

    fun mismo(a: String, b: String): Boolean {
        val x = numeroDe(a)
        val y = numeroDe(b)
        if (x.length < MIN_CIFRAS || y.length < MIN_CIFRAS) return false
        val n = minOf(CIFRAS, x.length, y.length)
        return x.takeLast(n) == y.takeLast(n)
    }
}
