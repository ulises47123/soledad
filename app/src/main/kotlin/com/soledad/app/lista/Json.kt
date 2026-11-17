package com.soledad.app.lista

/** JSON mínimo propio (mapas, listas, cadenas, números): evita org.json, que en pruebas JVM de Android está vacío. */
internal object Json {
    fun escribir(v: Any?): String = StringBuilder().also { esc(v, it) }.toString()

    private fun esc(v: Any?, sb: StringBuilder) {
        when (v) {
            null -> sb.append("null")
            is Number, is Boolean -> sb.append(v)
            is Map<*, *> -> {
                sb.append('{')
                v.entries.forEachIndexed { i, e -> if (i > 0) sb.append(','); cad(e.key.toString(), sb); sb.append(':'); esc(e.value, sb) }
                sb.append('}')
            }
            is List<*> -> {
                sb.append('[')
                v.forEachIndexed { i, x -> if (i > 0) sb.append(','); esc(x, sb) }
                sb.append(']')
            }
            else -> cad(v.toString(), sb)
        }
    }

    private fun cad(s: String, sb: StringBuilder) {
        sb.append('"')
        for (c in s) when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n")
            c < ' ' -> sb.append("\\u%04x".format(c.code))
            else -> sb.append(c)
        }
        sb.append('"')
    }

    fun leer(s: String): Any? = Lector(s).valor()

    private class Lector(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }

        fun valor(): Any? {
            ws()
            return when (s[i]) {
                '{' -> {
                    i++
                    val m = LinkedHashMap<String, Any?>()
                    ws()
                    if (s[i] == '}') { i++; return m }
                    while (true) {
                        ws(); val k = cadena(); ws(); i++
                        m[k] = valor(); ws()
                        if (s[i++] == '}') break
                    }
                    m
                }
                '[' -> {
                    i++
                    val l = ArrayList<Any?>()
                    ws()
                    if (s[i] == ']') { i++; return l }
                    while (true) {
                        l.add(valor()); ws()
                        if (s[i++] == ']') break
                    }
                    l
                }
                '"' -> cadena()
                else -> {
                    val ini = i
                    while (i < s.length && s[i] !in ",]} \n\r\t") i++
                    when (val t = s.substring(ini, i)) {
                        "null" -> null
                        "true" -> true
                        "false" -> false
                        else -> t.toLongOrNull() ?: t.toDouble()
                    }
                }
            }
        }

        fun cadena(): String {
            val sb = StringBuilder()
            i++
            while (s[i] != '"') {
                if (s[i] == '\\') {
                    i++
                    when (val n = s[i]) {
                        'n' -> sb.append('\n')
                        'u' -> { sb.append(s.substring(i + 1, i + 5).toInt(16).toChar()); i += 4 }
                        else -> sb.append(n)
                    }
                } else sb.append(s[i])
                i++
            }
            i++
            return sb.toString()
        }
    }
}
