package com.soledad.app.lista

/** Con qué «cara» contesta Soledad: como vos (PERSONAL) o como tu emprendimiento (NEGOCIO). */
enum class Perfil { PERSONAL, NEGOCIO }

/**
 * Decide el perfil de un chat. Lógica pura (se prueba en la JVM).
 * «No agendado» = WhatsApp muestra el teléfono como título de la notificación (o «~Nombre»): a alguien agendado lo muestra
 * con el nombre de la agenda. Ante la duda se trata como agendado: nunca recibe la respuesta de negocio por error.
 */
object Perfiles {
    /** Marcas invisibles de dirección que WhatsApp mete alrededor de los números, y espacios duros. */
    private val INVISIBLES = Regex("[\\u200B-\\u200F\\u202A-\\u202E\\u2066-\\u2069\\uFEFF]")

    fun noAgendado(nombre: String): Boolean {
        val n = nombre.replace(INVISIBLES, "").replace(' ', ' ').replace(' ', ' ').trim()
        if (n.startsWith("~")) return true
        return Telefono.digitos(n).length >= 7
    }

    fun de(nombre: String, grupo: Boolean, modoNegocio: Boolean): Perfil =
        if (modoNegocio && !grupo && noAgendado(nombre)) Perfil.NEGOCIO else Perfil.PERSONAL

    /** En modo negocio solo se frena lo que huele a estafa; hablar de plata, transferencias o tarjetas es normal. */
    val BLOQUEADAS_NEGOCIO = listOf("clave", "contraseña", "codigo", "pin", "cvv", "token")

    /** Un cliente pregunta varias cosas seguidas: tope más alto que el personal. */
    const val LIMITE_HORA_NEGOCIO = 10
}
