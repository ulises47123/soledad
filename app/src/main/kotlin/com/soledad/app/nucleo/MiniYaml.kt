package com.soledad.app.nucleo

/**
 * Lector de un subconjunto de YAML, suficiente para los archivos de configuración del bot
 * (SnakeYAML 2.x no corre en Android). Lo único que entiende:
 *  - mapas por indentación con espacios (no tabuladores): `clave: valor` y `clave:` + bloque anidado
 *  - listas con `- valor` y `- clave: valor` (los demás pares del elemento alineados con la primera clave)
 *  - escalares: "entre comillas dobles" (con \" \\ \n), 'entre comillas simples', true/false, null/~, enteros, decimales
 *  - listas en línea simples: [a, b, "c d"]
 *  - comentarios con # (fuera de comillas)
 * No soporta: anclas, etiquetas, bloques `|` o `>`, mapas en línea `{}`, varios documentos.
 */
object MiniYaml {
    private class Linea(val indent: Int, val texto: String)

    fun parse(fuente: String): Any? {
        val ls = fuente.lines().mapNotNull { cruda ->
            val sin = quitarComentario(cruda.trimEnd())
            if (sin.isBlank()) null else Linea(sin.length - sin.trimStart().length, sin.trim())
        }.toMutableList()
        if (ls.isEmpty()) return null
        return Lector(ls).bloque(ls[0].indent)
    }

    private class Lector(val ls: MutableList<Linea>) {
        var i = 0

        fun bloque(indent: Int): Any? = if (esItem(ls[i].texto)) lista(indent) else mapa(indent)

        fun mapa(indent: Int): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            while (i < ls.size && ls[i].indent == indent && !esItem(ls[i].texto)) {
                val p = partir(ls[i].texto) ?: error("YAML: se esperaba «clave: valor» en «${ls[i].texto}»")
                i++
                m[p.first] = valorDe(p.second, indent)
            }
            return m
        }

        fun valorDe(resto: String, indent: Int): Any? {
            if (resto.isNotEmpty()) return escalar(resto)
            if (i < ls.size && (ls[i].indent > indent || (ls[i].indent == indent && esItem(ls[i].texto)))) return bloque(ls[i].indent)
            return null
        }

        fun lista(indent: Int): List<Any?> {
            val out = ArrayList<Any?>()
            while (i < ls.size && ls[i].indent == indent && esItem(ls[i].texto)) {
                val resto = ls[i].texto.removePrefix("-").trimStart()
                if (resto.isEmpty()) {
                    i++
                    out.add(if (i < ls.size && ls[i].indent > indent) bloque(ls[i].indent) else null)
                } else if (partir(resto) != null && !resto.startsWith("\"") && !resto.startsWith("'") && !resto.startsWith("[")) {
                    // Elemento mapa: su contenido empieza en la columna posterior al guion.
                    val columna = indent + (ls[i].texto.length - resto.length)
                    ls[i] = Linea(columna, resto)
                    out.add(mapa(columna))
                } else {
                    i++
                    out.add(escalar(resto))
                }
            }
            return out
        }
    }

    private fun esItem(t: String) = t == "-" || t.startsWith("- ")

    /** Parte «clave: resto» en el primer `:` seguido de espacio o fin de línea y fuera de comillas. null si no hay. */
    private fun partir(t: String): Pair<String, String>? {
        var comilla = 0.toChar()
        for (k in t.indices) {
            val c = t[k]
            if (comilla != 0.toChar()) {
                if (c == comilla) comilla = 0.toChar()
            } else if (c == '"' || c == '\'') {
                comilla = c
            } else if (c == ':' && (k + 1 == t.length || t[k + 1] == ' ')) {
                return desenvolver(t.substring(0, k).trim()) to t.substring(k + 1).trim()
            }
        }
        return null
    }

    private fun desenvolver(s: String): String =
        if (s.length >= 2 && ((s[0] == '"' && s.last() == '"') || (s[0] == '\'' && s.last() == '\''))) s.substring(1, s.length - 1) else s

    private fun quitarComentario(l: String): String {
        var comilla = 0.toChar()
        for (k in l.indices) {
            val c = l[k]
            if (comilla != 0.toChar()) {
                if (c == comilla) comilla = 0.toChar()
            } else if (c == '"' || c == '\'') {
                comilla = c
            } else if (c == '#' && (k == 0 || l[k - 1] == ' ' || l[k - 1] == '\t')) {
                return l.substring(0, k).trimEnd()
            }
        }
        return l
    }

    private fun escalar(s0: String): Any? {
        val s = s0.trim()
        if (s.startsWith("\"")) return cadenaDoble(s)
        if (s.startsWith("'")) return s.substring(1, s.lastIndexOf('\'').coerceAtLeast(1)).replace("''", "'")
        if (s.startsWith("[") && s.endsWith("]")) return enLinea(s.substring(1, s.length - 1))
        return when {
            s.equals("true", true) -> true
            s.equals("false", true) -> false
            s == "~" || s.equals("null", true) -> null
            Regex("-?\\d+").matches(s) -> s.toLongOrNull()?.let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else it } ?: s
            Regex("-?\\d+\\.\\d+").matches(s) -> s.toDouble()
            else -> s
        }
    }

    private fun cadenaDoble(s: String): String {
        val sb = StringBuilder()
        var k = 1
        while (k < s.length) {
            val c = s[k]
            if (c == '\\' && k + 1 < s.length) {
                sb.append(when (val n = s[k + 1]) { 'n' -> '\n'; 't' -> '\t'; else -> n })
                k += 2
            } else if (c == '"') {
                break
            } else {
                sb.append(c)
                k++
            }
        }
        return sb.toString()
    }

    private fun enLinea(s: String): List<Any?> {
        if (s.isBlank()) return emptyList()
        val out = ArrayList<Any?>()
        val actual = StringBuilder()
        var comilla = 0.toChar()
        for (c in s) {
            if (comilla != 0.toChar()) {
                actual.append(c)
                if (c == comilla) comilla = 0.toChar()
            } else if (c == '"' || c == '\'') {
                comilla = c
                actual.append(c)
            } else if (c == ',') {
                out.add(escalar(actual.toString()))
                actual.clear()
            } else {
                actual.append(c)
            }
        }
        out.add(escalar(actual.toString()))
        return out
    }
}

@Suppress("UNCHECKED_CAST")
fun Any?.mapa(): Map<String, Any?> = (this as? Map<String, Any?>) ?: emptyMap()

@Suppress("UNCHECKED_CAST")
fun Any?.lista(): List<Any?> = (this as? List<Any?>) ?: emptyList()

fun Any?.texto(): String? = this?.toString()
