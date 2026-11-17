package com.soledad.app.rag

import com.soledad.app.nucleo.ClienteEmbeddings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class SemanticoTest {
    /** Embeddings falsos: un eje por tema. */
    private val falso = object : ClienteEmbeddings {
        var llamadas = 0
        override fun vectores(clave: String, modelo: String, textos: List<String>, tipo: String): List<FloatArray> {
            llamadas++
            return textos.map { t ->
                floatArrayOf(if ("fútbol" in t || "deporte" in t) 1f else 0f, if ("música" in t || "canción" in t) 1f else 0f, 0.1f)
            }
        }
    }

    @Test fun buscaPorSignificadoYGuardaVectores() {
        val alm = AlmacenMemoria()
        alm.agregarFuente("Gustos", listOf("Me encanta el fútbol, soy de Gimnasia", "Escucho mucha música de cuarteto"), 0)
        val b = BuscadorSemantico(alm, falso) { "K" }
        val h = b.buscar("¿qué deporte te gusta?", alm.fragmentos(), 1, 0.5, "m")!!
        assertTrue(h.single().fragmento.texto.contains("fútbol"))
        val antes = falso.llamadas
        b.buscar("¿qué canción escuchás?", alm.fragmentos(), 1, 0.5, "m")
        assertEquals(antes + 1, falso.llamadas) // los vectores de la base ya estaban guardados
        assertNull(BuscadorSemantico(alm, falso) { null }.buscar("x", alm.fragmentos(), 1, 0.5, "m")) // sin clave: solo BM25
    }

    @Test fun miInformacionEmpiezaVaciaYSeparaEstiloDeDatos() {
        val dir = Files.createTempDirectory("mi_info").toFile()
        val alm = AlmacenMemoria()
        val ia = IaConRagImpl({ ConfigRag() }, alm, { error("no se usa") }, { error("no se usa") })
        val info = MiInformacion(dir, Conocimiento(alm, ia))
        assertEquals(0, info.completas())
        assertEquals("", info.instruccionesSistema())
        val como = MiInformacion.SECCIONES.first { it.id == "como_contesto" }
        val gustos = MiInformacion.SECCIONES.first { it.id == "gustos" }
        info.guardar(como, "Escribo corto.")
        info.guardar(gustos, "Me gusta el rock.")
        assertTrue(info.instruccionesSistema().contains("Escribo corto."))
        assertEquals(listOf("Gustos"), alm.fuentes().map { it.titulo }) // el estilo no va a la búsqueda
        info.guardar(gustos, "Me gusta el jazz.")
        assertEquals(1, alm.fuentes().size)
        assertTrue(alm.fragmentos().single().texto.contains("jazz"))
        assertEquals(2, info.completas())
    }
}
