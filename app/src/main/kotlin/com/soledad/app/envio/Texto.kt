package com.soledad.app.envio

import java.net.URLEncoder
import java.text.Normalizer

/** Utilidades puras de texto, rutas y escape de shell. Sin Android: se prueban en JVM. */
object Texto {
    fun normalizar(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFC)
            .replace(' ', ' ').replace("‎", "").replace("‏", "")
            .replace(Regex("\\s+"), " ").trim()

    /** Igualdad exacta tras normalizar (espacios, saltos). Un texto «parecido» NO coincide. */
    fun igual(a: String, b: String): Boolean {
        val x = normalizar(a)
        return x.isNotEmpty() && x == normalizar(b)
    }

    /**
     * Para notificaciones: además de la igualdad, acepta que WhatsApp haya recortado el texto con «…»
     * (el prefijo visible debe tener al menos 20 caracteres para no coincidir con cualquier cosa).
     */
    fun coincideEnNotificacion(enviado: String, visto: String): Boolean {
        if (igual(enviado, visto)) return true
        val v = normalizar(visto)
        val recortado = v.endsWith("…") || v.endsWith("...")
        if (!recortado) return false
        val prefijo = v.trimEnd('…', '.').trim()
        return prefijo.length >= 20 && normalizar(enviado).startsWith(prefijo)
    }

    /** Codifica para un parámetro de URL (espacio como %20, UTF-8: los acentos viajan sin teclear). */
    fun urlCodificar(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}

object Rutas {
    val EXTENSIONES_IMAGEN = setOf("jpg", "jpeg", "png", "webp", "gif")
    private val permitida = Regex("^/[\\p{L}\\p{N} _.,()+@\\-/]*$")

    /** Absoluta, sin `..`, sin `.`, sin `//` y solo con caracteres inocuos para el shell. */
    fun valida(ruta: String): Boolean =
        permitida.matches(ruta) && !ruta.contains("//") && ruta.split('/').none { it == ".." || it == "." }

    fun esImagen(ruta: String): Boolean =
        ruta.substringAfterLast('.', "").lowercase() in EXTENSIONES_IMAGEN && !ruta.substringAfterLast('/').startsWith(".")

    /** `ruta` es un hijo directo de `carpetaCanonica` (sin subcarpetas ni `..`). */
    fun hijoDirecto(carpetaCanonica: String, ruta: String): Boolean {
        val base = carpetaCanonica.trimEnd('/') + "/"
        if (!valida(ruta) || !ruta.startsWith(base)) return false
        val resto = ruta.removePrefix(base)
        return resto.isNotEmpty() && !resto.contains('/')
    }

    /** Escapa para el shell entre comillas simples. */
    fun comillas(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** `file://` con cada tramo codificado (los espacios y acentos no rompen el URI). */
    fun uriArchivo(ruta: String): String =
        "file://" + ruta.split('/').joinToString("/") { Texto.urlCodificar(it) }
}

object Jid {
    private val individual = Regex("^(\\d{6,20})(?::\\d+)?@(s\\.whatsapp\\.net|c\\.us)$")

    /** Número de un chat individual, o null (grupo, `@lid`, título). Con él se puede abrir el chat por enlace. */
    fun numero(chatId: String): String? = individual.matchEntire(chatId.trim())?.groupValues?.get(1)

    fun jid(chatId: String): String? = numero(chatId)?.let { "$it@s.whatsapp.net" }
}
