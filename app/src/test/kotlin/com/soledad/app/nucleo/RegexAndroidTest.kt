package com.soledad.app.nucleo

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guardia contra una diferencia entre la JVM de los tests y Android: el motor de regex de Android (ICU) lanza
 * PatternSyntaxException con un `}` suelto, y la JVM lo acepta. Eso ya rompió la lectura de claves en el celular
 * sin que ningún test lo notara. Aquí se revisan todas las regex literales del código.
 */
class RegexAndroidTest {

    /** true si la regex tiene un `}` que no cierra un cuantificador `{n,m}` y no está escapado. */
    private fun tieneLlaveSuelta(r: String): Boolean {
        var i = 0
        var abierta = false
        while (i < r.length) {
            val c = r[i]
            when {
                c == '\\' -> i++ // salta el carácter escapado
                c == '{' -> abierta = true
                c == '}' -> { if (!abierta) return true; abierta = false }
            }
            i++
        }
        return false
    }

    @Test
    fun ninguna_regex_tiene_llave_de_cierre_suelta() {
        val literal = Regex("Regex\\(\"((?:[^\"\\\\]|\\\\.)*)\"")
        val raiz = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "app/src/main/kotlin") }.firstOrNull { it.isDirectory }
            ?: File("src/main/kotlin")
        assertTrue("No se encontró el código fuente", raiz.isDirectory)
        val malas = ArrayList<String>()
        raiz.walkTopDown().filter { it.extension == "kt" }.forEach { f ->
            literal.findAll(f.readText()).forEach { m ->
                // Des-escapa el literal de Kotlin ("\\" → "\") para ver la regex real.
                val real = m.groupValues[1].replace("\\\\", "\\")
                if (tieneLlaveSuelta(real)) malas += "${f.name}: $real"
            }
        }
        assertTrue("Regex con «}» suelta (Android las rechaza): $malas", malas.isEmpty())
    }

    @Test
    fun el_detector_funciona_con_los_dos_casos() {
        assertTrue(tieneLlaveSuelta("\\$\\{([A-Za-z0-9_]+)}"))
        assertTrue(!tieneLlaveSuelta("\\$\\{([A-Za-z0-9_]+)\\}"))
        assertTrue(!tieneLlaveSuelta("\\d{1,2}:(\\d{2})"))
    }
}
