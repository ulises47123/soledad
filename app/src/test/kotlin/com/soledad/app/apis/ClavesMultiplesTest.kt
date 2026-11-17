package com.soledad.app.apis

import com.soledad.app.lista.AlmacenMemoria
import com.soledad.app.nucleo.ClienteModelo
import com.soledad.app.nucleo.Mensaje
import com.soledad.app.nucleo.ResultadoModelo
import com.soledad.app.nucleo.TurnoChat
import com.soledad.app.rag.ConfigRag
import com.soledad.app.rag.IaConRagImpl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Varias claves por proveedor: orden, respaldo clave por clave, estado por clave e importación desde la compilación. */
class ClavesMultiplesTest {
    private lateinit var dir: File
    private val secretos = SecretosFalsos()
    private fun gestor() = GestorApisImpl(File(dir, "config_apis.yaml"), File(dir, "estado.json"), secretos)

    @Before fun preparar() { dir = Files.createTempDirectory("claves").toFile() }
    @After fun limpiar() { dir.deleteRecursively() }

    private fun cargar(g: GestorApisImpl) {
        g.agregarClave("nvidia-1", "nvidia", "nvapi-UNO-aaaa1111", 300, "24h")
        g.agregarClave("nvidia-2", "nvidia", "nvapi-DOS-bbbb2222", 300, "24h")
        g.agregarClave("gemini-1", "gemini", "AQ.gemini-uno-cccc3333", 50, "24h")
        g.agregarClave("gemini-2", "gemini", "AQ.gemini-dos-dddd4444", 50, "24h")
    }

    @Test fun excluirYReordenar() {
        val g = gestor(); cargar(g)
        assertEquals("nvidia-1", g.solicitar("nvidia", emptySet())!!.apiId)
        assertEquals("nvidia-2", g.solicitar("nvidia", setOf("nvidia-1"))!!.apiId)
        assertNull(g.solicitar("nvidia", setOf("nvidia-1", "nvidia-2")))
        g.mover("nvidia-2", -1)
        assertEquals(listOf("nvidia-2", "nvidia-1", "gemini-1", "gemini-2"), g.estado().map { it.id })
        // Cambiar una clave conserva su lugar.
        g.agregarClave("nvidia-1", "nvidia", "nvapi-NUEVA-eeee5555", 300, "24h")
        assertEquals(listOf("nvidia-2", "nvidia-1", "gemini-1", "gemini-2"), g.estado().map { it.id })
        assertEquals("5555", ClavesIa.fin(g.claveDe("nvidia-1")))
    }

    /** Modelo falso: falla según la clave (401, 429, 5xx, vacío, excepción de red). */
    private class Falso(val fallas: Map<String, String?>) : ClienteModelo {
        val usadas = ArrayList<String>()
        override fun generar(clave: String, modelo: String, sistema: String, turnos: List<TurnoChat>, maxTokens: Int, temperatura: Double): ResultadoModelo {
            usadas += clave
            if (clave in fallas) {
                val e = fallas[clave] ?: throw java.io.IOException("sin red")
                return ResultadoModelo(if (e == "vacio") "  " else null, 0, 0, if (e == "vacio") null else e)
            }
            return ResultadoModelo("Hola, después te escribo.", 10, 5, null)
        }
    }

    private fun ia(g: GestorApisImpl, nvidia: Falso, gemini: Falso, avisos: MutableList<String>) = IaConRagImpl(
        { ConfigRag(modeloEmbeddings = "") }, com.soledad.app.rag.AlmacenMemoria(), { g },
        { p -> if (p == "gemini") gemini else nvidia }, alResultadoClave = { id, ok, _ -> avisos += "$id:$ok" },
    )

    private val msg = Mensaje("m1", "c1", "Ana", "hola, ¿qué tal?", false, 0, "com.whatsapp")

    @Test fun pasaALaSiguienteClaveYDespuesAGemini() {
        val g = gestor(); cargar(g)
        val nv = Falso(mapOf("nvapi-UNO-aaaa1111" to "NVIDIA 401 (clave rechazada)", "nvapi-DOS-bbbb2222" to "NVIDIA 429 (demasiadas peticiones)"))
        val ge = Falso(mapOf("AQ.gemini-uno-cccc3333" to null)) // red caída
        val avisos = ArrayList<String>()
        val r = ia(g, nv, ge, avisos).responder(msg)
        assertEquals("Hola, después te escribo.", r.texto)
        assertTrue(r.nota.endsWith("· gemini-2"))
        assertEquals(listOf("nvapi-UNO-aaaa1111", "nvapi-DOS-bbbb2222"), nv.usadas)
        assertEquals(listOf("AQ.gemini-uno-cccc3333", "AQ.gemini-dos-dddd4444"), ge.usadas)
        assertEquals(listOf("nvidia-1:false", "nvidia-2:false", "gemini-1:false", "gemini-2:true"), avisos)
        // Los usos fallidos se devuelven: solo cuenta el que respondió.
        assertEquals(listOf(0, 0, 0, 1), g.estado().map { it.usos })
        // La nota nunca lleva la clave.
        assertFalse(r.nota.contains("AQ.") || r.nota.contains("nvapi"))
    }

    @Test fun primeraClaveBuenaNoTocaLasDemas() {
        val g = gestor(); cargar(g)
        val nv = Falso(emptyMap()); val ge = Falso(emptyMap())
        val r = ia(g, nv, ge, ArrayList()).responder(msg)
        assertTrue(r.nota.endsWith("· nvidia-1"))
        assertEquals(1, nv.usadas.size); assertTrue(ge.usadas.isEmpty())
    }

    @Test fun respuestaVaciaTambienPasaALaSiguiente() {
        val g = gestor(); cargar(g)
        val nv = Falso(mapOf("nvapi-UNO-aaaa1111" to "vacio"))
        val r = ia(g, nv, Falso(emptyMap()), ArrayList()).responder(msg)
        assertTrue(r.nota.endsWith("· nvidia-2"))
    }

    @Test fun todasFallanDevuelveErrorSinClaves() {
        val g = gestor(); cargar(g)
        val todas = listOf("nvapi-UNO-aaaa1111", "nvapi-DOS-bbbb2222", "AQ.gemini-uno-cccc3333", "AQ.gemini-dos-dddd4444")
        val f = Falso(todas.associateWith { "HTTP 503" })
        val r = ia(g, f, f, ArrayList()).responder(msg)
        assertNull(r.texto)
        assertTrue(r.nota.startsWith("error modelo"))
        assertTrue(todas.none { r.nota.contains(it) })
    }

    @Test fun estadoPorClave() {
        var t = 1_000L
        val alm = AlmacenMemoria()
        val e = EstadoClaves(alm) { t }
        e.anotar("nvidia-1", true)
        t = 2_000L; e.anotar("nvidia-1", false, "NVIDIA 401 (clave rechazada)")
        assertTrue(e.fallando("nvidia-1"))
        t = 3_000L; e.anotar("nvidia-1", true)
        assertFalse(e.fallando("nvidia-1"))
        val otra = EstadoClaves(alm) { t } // persiste
        assertEquals(3_000L, otra.de("nvidia-1")!!.okMs)
        assertEquals("NVIDIA 401 (clave rechazada)", otra.de("nvidia-1")!!.error)
        assertEquals("", ClavesIa.fin(null))
    }

    @Test fun importarClavesDeLaCompilacion() {
        assertEquals(listOf("a", "b"), Sembrado.lista("a, b", "a", " "))
        val existentes = listOf(ClaveExistente("nvidia-1", "nvidia", "K1"), ClaveExistente("gemini-1", "gemini", "G1"))
        val entrada = listOf("nvidia" to "K1", "nvidia" to "K2", "gemini" to "G1", "gemini" to "G2")
        val (altas, huellas) = Sembrado.plan(existentes, entrada, emptySet())
        assertEquals(listOf(Alta("nvidia-2", "nvidia", "K2"), Alta("gemini-2", "gemini", "G2")), altas)
        assertEquals(4, huellas.size)
        // Una clave ya importada y después quitada por el usuario no vuelve.
        val (otra, _) = Sembrado.plan(listOf(ClaveExistente("nvidia-1", "nvidia", "K1")), entrada, huellas)
        assertTrue(otra.isEmpty())
        // Las huellas no contienen la clave.
        assertTrue(huellas.none { it.contains("K2") })
    }
}
