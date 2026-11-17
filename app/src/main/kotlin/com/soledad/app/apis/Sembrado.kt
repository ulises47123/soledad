package com.soledad.app.apis

import java.security.MessageDigest

/** Una clave ya cargada en el gestor (el valor se usa solo para comparar, nunca se muestra). */
class ClaveExistente(val id: String, val proveedor: String, val valor: String?)

/** Una clave a dar de alta con su alias. */
data class Alta(val id: String, val proveedor: String, val valor: String)

/**
 * Importación de claves desde la compilación (local.properties → BuildConfig). Lógica pura:
 *  - no repite una clave que ya está cargada (compara el valor);
 *  - no vuelve a importar una que ya se importó antes (por su huella SHA-256), aunque el usuario la haya quitado;
 *  - cada clave nueva toma el primer alias libre del proveedor (`nvidia-2`, `gemini-5`…), al final del orden.
 */
object Sembrado {
    fun huella(valor: String): String =
        MessageDigest.getInstance("SHA-256").digest(valor.trim().toByteArray()).joinToString("") { "%02x".format(it) }

    /** «a, b ,a» → [a, b] (sin vacíos ni repetidos). */
    fun lista(vararg textos: String): List<String> =
        textos.flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    fun plan(existentes: List<ClaveExistente>, entrada: List<Pair<String, String>>, sembradas: Set<String>): Pair<List<Alta>, Set<String>> {
        val altas = ArrayList<Alta>()
        val marcadas = sembradas.toMutableSet()
        val ids = existentes.map { it.id }.toMutableSet()
        for ((proveedor, valorCrudo) in entrada) {
            val valor = valorCrudo.trim()
            if (valor.isEmpty()) continue
            val h = huella(valor)
            if (h in marcadas) continue
            marcadas += h
            if (existentes.any { it.proveedor == proveedor && it.valor?.trim() == valor }) continue
            if (altas.any { it.valor == valor }) continue
            var n = 1
            while ("$proveedor-$n" in ids) n++
            val id = "$proveedor-$n"
            ids += id
            altas += Alta(id, proveedor, valor)
        }
        return altas to marcadas
    }
}
