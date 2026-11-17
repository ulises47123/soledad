package com.soledad.app.rag

import java.text.Normalizer
import kotlin.math.ln

class Fuente(val id: Long, val titulo: String, val creadaMs: Long, val fragmentos: Int)
class Fragmento(val id: Long, val fuenteId: Long, val texto: String)
class Hallazgo(val fragmento: Fragmento, val puntaje: Double)

/** BM25 en memoria, 100 % local: la base de conocimiento nunca sale del celular. Puro Kotlin (probable en JVM). */
class Indice(fragmentos: List<Fragmento>) {
    private class Doc(val frag: Fragmento, val tf: Map<String, Int>, val largo: Int)

    private val docs: List<Doc> = fragmentos.map { f ->
        val t = tokens(f.texto)
        Doc(f, t.groupingBy { it }.eachCount(), t.size)
    }
    private val df: Map<String, Int> = HashMap<String, Int>().also { m ->
        docs.forEach { d -> d.tf.keys.forEach { m[it] = (m[it] ?: 0) + 1 } }
    }
    private val promedio = if (docs.isEmpty()) 1.0 else docs.sumOf { it.largo }.toDouble() / docs.size

    fun buscar(consulta: String, k: Int, minimo: Double): List<Hallazgo> {
        if (docs.isEmpty() || k <= 0) return emptyList()
        val q = tokens(consulta).distinct()
        if (q.isEmpty()) return emptyList()
        val n = docs.size.toDouble()
        return docs.map { d ->
            var s = 0.0
            for (t in q) {
                val f = d.tf[t] ?: continue
                val nt = (df[t] ?: 0).toDouble()
                val idf = ln(1.0 + (n - nt + 0.5) / (nt + 0.5))
                s += idf * (f * (K1 + 1)) / (f + K1 * (1 - B + B * d.largo / promedio))
            }
            Hallazgo(d.frag, s)
        }.filter { it.puntaje >= minimo && it.puntaje > 0.0 }
            .sortedWith(compareByDescending<Hallazgo> { it.puntaje }.thenBy { it.fragmento.id })
            .take(k)
    }

    companion object {
        private const val K1 = 1.4
        private const val B = 0.75

        private val STOP = setOf(
            "de", "la", "el", "en", "y", "a", "los", "las", "un", "una", "que", "es", "por", "con", "para", "se", "del", "al", "lo", "como", "mas",
            "pero", "sus", "le", "ya", "o", "este", "si", "porque", "esta", "entre", "cuando", "muy", "sin", "sobre", "me", "mi", "te", "tu", "yo",
            "hola", "buenas", "buen", "dia", "dias", "tarde", "noche", "gracias", "favor", "quiero", "puedo", "puede", "hay", "son", "ser", "tengo",
        )

        /** Minúsculas, sin acentos (la «ñ» se conserva), sin palabras vacías y con raíz simple de plural. */
        fun tokens(texto: String): List<String> {
            val sinTilde = Normalizer.normalize(texto.lowercase().replace("ñ", "\u0001"), Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "").replace("\u0001", "ñ")
            return sinTilde.split(Regex("[^a-z0-9ñ]+")).filter { it.length >= 2 && it !in STOP }.map { raiz(it) }
        }

        private fun raiz(t: String) = when {
            t.length > 4 && t.endsWith("es") -> t.dropLast(2)
            t.length > 3 && t.endsWith("s") -> t.dropLast(1)
            else -> t
        }

        /** Parte un texto en fragmentos de ~120–200 palabras respetando los párrafos. */
        fun trocear(texto: String): List<String> {
            val parrafos = texto.replace("\r", "").split(Regex("\n\\s*\n")).map { it.trim() }.filter { it.isNotEmpty() }
            val salida = ArrayList<String>()
            val actual = StringBuilder()
            var palabras = 0
            fun cerrar() { if (actual.isNotBlank()) salida.add(actual.toString().trim()); actual.clear(); palabras = 0 }
            for (p in parrafos) {
                val ws = p.split(Regex("\\s+"))
                if (ws.size > 200) {
                    cerrar()
                    ws.chunked(160).forEach { salida.add(it.joinToString(" ")) }
                    continue
                }
                if (palabras + ws.size > 200) cerrar()
                if (actual.isNotEmpty()) actual.append("\n")
                actual.append(p)
                palabras += ws.size
                if (palabras >= 120) cerrar()
            }
            cerrar()
            return salida
        }
    }
}
