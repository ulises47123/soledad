package com.soledad.app.apis

import com.soledad.app.nucleo.PermisoApi
import com.soledad.app.nucleo.TurnoChat
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class SecretosFalsos : AlmacenSecretos {
    val m = HashMap<String, String>()
    @Synchronized override fun obtener(nombre: String) = m[nombre]
    @Synchronized override fun guardar(nombre: String, valor: String) { m[nombre] = valor }
    @Synchronized override fun borrar(nombre: String) { m.remove(nombre) }
}

class GestorApisTest {
    private lateinit var dir: File
    private lateinit var secretos: SecretosFalsos
    private var ahora = 1_000_000_000_000L
    private val cfg get() = File(dir, "config_apis.yaml")
    private val est get() = File(dir, "estado_cuotas.json")

    private fun yaml(vararg apis: String, ventana: String = "24h") = buildString {
        append("apis:\n")
        apis.forEach { append("  - id: $it\n    clave: \"\${${it.uppercase()}}\"\n    limite_usos: 3\n    ventana_reinicio: $ventana\n") }
    }

    private fun gestor(antes: () -> Unit = {}) = GestorApisImpl(cfg, est, secretos, { ahora }, antes)

    @Before fun preparar() {
        dir = Files.createTempDirectory("gestor").toFile()
        secretos = SecretosFalsos().also { it.m["A"] = "CLAVE-SECRETA-A"; it.m["B"] = "CLAVE-SECRETA-B" }
        cfg.writeText(yaml("a"))
    }

    @After fun limpiar() { dir.deleteRecursively() }

    @Test fun tresUsosYElCuartoEsNull() {
        val g = gestor()
        repeat(3) { assertNotNull(g.solicitar("gemini")) }
        assertNull(g.solicitar("gemini"))
        val e = g.estado().single()
        assertEquals(3, e.usos)
        assertEquals(ahora + 24 * 3_600_000L, e.bloqueadaHasta)
    }

    @Test fun conDosClavesElCuartoPasaALaSegunda() {
        cfg.writeText(yaml("a", "b"))
        val g = gestor()
        repeat(3) { assertEquals("a", g.solicitar("gemini")!!.apiId) }
        val p = g.solicitar("gemini")!!
        assertEquals("b", p.apiId)
        assertEquals("CLAVE-SECRETA-B", p.clave)
    }

    @Test fun proveedorDistinto_yDesactivada() {
        val g = gestor()
        assertNull(g.solicitar("openai"))
        g.activar("a", false)
        assertNull(g.solicitar("gemini"))
        g.activar("a", true)
        assertNotNull(g.solicitar("gemini"))
    }

    @Test fun reinicioPorVentana24h() {
        val g = gestor()
        repeat(3) { g.solicitar("gemini") }
        ahora += 24 * 3_600_000L - 1
        assertNull(g.solicitar("gemini"))
        ahora += 1
        assertNotNull(g.solicitar("gemini"))
        assertEquals(1, g.estado().single().usos)
    }

    @Test fun reinicioDiarioUtcALaMedianoche() {
        cfg.writeText(yaml("a", ventana = "diario_utc"))
        val dia = 86_400_000L
        ahora = 20_000 * dia + 23 * 3_600_000L // 23:00 UTC
        val g = gestor()
        repeat(3) { g.solicitar("gemini") }
        assertEquals(20_001 * dia, g.estado().single().bloqueadaHasta)
        ahora = 20_001 * dia - 1
        assertNull(g.solicitar("gemini"))
        ahora = 20_001 * dia
        assertNotNull(g.solicitar("gemini"))
    }

    @Test fun reembolsoConExitoFalse() {
        val g = gestor()
        val p = g.solicitar("gemini")!!
        assertEquals(1, g.estado().single().usos)
        g.confirmar(p, false)
        assertEquals(0, g.estado().single().usos)
        g.confirmar(p, false) // idempotente: no devuelve dos veces
        assertEquals(0, g.estado().single().usos)
    }

    @Test fun confirmarConExitoMantieneElUso() {
        val g = gestor()
        g.confirmar(g.solicitar("gemini")!!, true)
        assertEquals(1, g.estado().single().usos)
    }

    @Test fun reembolsoTrasReinicioDeVentanaNoRestaUsoNuevo() {
        val g = gestor()
        val viejo = g.solicitar("gemini")!!
        ahora += 25 * 3_600_000L
        g.solicitar("gemini")
        g.confirmar(viejo, false)
        assertEquals(1, g.estado().single().usos)
    }

    @Test fun permisoSinConfirmarCuentaComoUsado() {
        val g = gestor()
        repeat(3) { g.solicitar("gemini") } // nunca confirmados
        assertNull(g.solicitar("gemini"))
    }

    @Test fun concurrenciaNuncaExcedeElLimite() {
        cfg.writeText(yaml("a", "b"))
        val g1 = gestor()
        val g2 = gestor() // dos instancias sobre el mismo archivo
        val pool = Executors.newFixedThreadPool(16)
        val arranque = CountDownLatch(1)
        val concedidos = AtomicInteger()
        val porApi = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()
        val tareas = (1..40).map { n ->
            pool.submit {
                arranque.await()
                val p = (if (n % 2 == 0) g1 else g2).solicitar("gemini")
                if (p != null) { concedidos.incrementAndGet(); porApi.getOrPut(p.apiId) { AtomicInteger() }.incrementAndGet() }
            }
        }
        arranque.countDown()
        tareas.forEach { it.get() }
        pool.shutdown()
        assertEquals(6, concedidos.get())
        assertEquals(3, porApi["a"]!!.get())
        assertEquals(3, porApi["b"]!!.get())
    }

    @Test fun falloAntesDelRenameDejaElArchivoIntacto() {
        val g = gestor()
        g.solicitar("gemini")
        val antes = est.readText()
        val roto = gestor { throw RuntimeException("corte de energía simulado") }
        try { roto.solicitar("gemini"); fail() } catch (e: RuntimeException) { assertTrue(e.message!!.contains("corte")) }
        assertEquals(antes, est.readText())
        JSONObject(est.readText()) // sigue siendo JSON válido
        assertEquals(1, gestor().estado().single().usos)
    }

    @Test fun elEstadoSeReleeAlReiniciarElGestor() {
        val g = gestor()
        repeat(3) { g.solicitar("gemini") }
        val nuevo = gestor()
        assertNull(nuevo.solicitar("gemini"))
        assertEquals(3, nuevo.estado().single().usos)
    }

    @Test fun lasClavesNoAparecenEnElEstadoNiEnElYaml() {
        cfg.writeText(yaml("a"))
        val g = gestor()
        g.agregarClave("nueva", "gemini", "AIza-MUY-SECRETA")
        repeat(3) { g.solicitar("gemini") }
        val p = g.solicitar("gemini")!! // cae en «nueva»
        assertEquals("AIza-MUY-SECRETA", p.clave)
        for (t in listOf(est.readText(), cfg.readText())) {
            assertFalse(t.contains("AIza-MUY-SECRETA"))
            assertFalse(t.contains("CLAVE-SECRETA"))
        }
        assertTrue(cfg.readText().contains("\${API_NUEVA}"))
    }

    @Test fun claveEnClaroEnElYamlSeIgnora() {
        cfg.writeText("apis:\n  - id: x\n    clave: AIzaEnClaro\n")
        assertNull(gestor().solicitar("gemini"))
    }

    @Test fun quitarYReiniciarManual() {
        cfg.writeText(yaml("a", "b"))
        val g = gestor()
        repeat(3) { g.solicitar("gemini") }
        g.reiniciarManual("a")
        assertEquals("a", g.solicitar("gemini")!!.apiId)
        g.quitarClave("b")
        assertEquals(listOf("a"), g.estado().map { it.id })
        assertNull(secretos.m["B"])
    }

    @Test fun configDeEjemploDelProyectoSeLee() {
        val ejemplo = File("tareas/gestor_apis/gestor_apis/config_apis.yaml")
        if (!ejemplo.exists()) return
        cfg.writeText(ejemplo.readText())
        assertEquals(listOf("gemini-1", "gemini-2"), gestor().estado().map { it.id })
    }
}

class ClienteGeminiTest {
    private class HttpFalso(val codigo: Int, val cuerpo: String, val falla: Boolean = false) : ClienteHttp {
        var url = ""; var cab = emptyMap<String, String>(); var enviado = ""; var llamadas = 0
        override fun post(url: String, cabeceras: Map<String, String>, cuerpo: String, conexionMs: Int, lecturaMs: Int): RespuestaHttp {
            llamadas++; this.url = url; cab = cabeceras; enviado = cuerpo
            if (falla) throw java.net.SocketTimeoutException("lento")
            return RespuestaHttp(codigo, this.cuerpoRespuesta())
        }
        private fun cuerpoRespuesta() = cuerpo
    }

    private val turnos = listOf(TurnoChat("user", "hola"))

    @Test fun clienteEnCabeceraYTokensParseados() {
        val h = HttpFalso(200, """{"candidates":[{"content":{"parts":[{"text":"Hola "},{"text":"mundo"}]}}],"usageMetadata":{"promptTokenCount":12,"candidatesTokenCount":5,"thoughtsTokenCount":2}}""")
        val r = ClienteGemini(h).generar("AIza-K", "gemini-2.5-flash", "sé breve", turnos, 300, 0.7)
        assertEquals("Hola mundo", r.texto)
        assertEquals(12, r.tokensEntrada); assertEquals(7, r.tokensSalida); assertNull(r.error)
        assertEquals("AIza-K", h.cab["x-goog-api-key"])
        assertFalse(h.url.contains("AIza-K")); assertFalse(h.url.contains("key="))
        assertTrue(h.url.endsWith("/gemini-2.5-flash:generateContent"))
        val gc = JSONObject(h.enviado).getJSONObject("generationConfig")
        assertEquals(300, gc.getInt("maxOutputTokens"))
        assertEquals(0.7, gc.getDouble("temperature"), 1e-9)
        assertEquals(0, gc.getJSONObject("thinkingConfig").getInt("thinkingBudget"))
    }

    @Test fun modeloNo25NoLlevaRazonamiento() {
        val h = HttpFalso(200, """{"candidates":[{"content":{"parts":[{"text":"x"}]}}]}""")
        ClienteGemini(h).generar("k", "gemini-2.0-flash", "s", turnos, 10, 0.1)
        assertFalse(JSONObject(h.enviado).getJSONObject("generationConfig").has("thinkingConfig"))
    }

    @Test fun errorHttpLegibleYSinReintento() {
        val h = HttpFalso(429, """{"error":{"message":"Resource exhausted"}}""")
        val r = ClienteGemini(h).generar("k", "gemini-2.5-flash", "s", turnos, 10, 0.1)
        assertNull(r.texto)
        assertTrue(r.error!!, r.error!!.contains("429") && r.error!!.contains("Resource exhausted"))
        assertEquals(1, h.llamadas)
    }

    @Test fun errorDeRedLegible() {
        val r = ClienteGemini(HttpFalso(0, "", falla = true)).generar("k", "gemini-2.5-flash", "s", turnos, 10, 0.1)
        assertTrue(r.error!!.startsWith("Sin conexión"))
    }

    @Test fun respuestaSinTexto() {
        val r = ClienteGemini(HttpFalso(200, """{"candidates":[{"finishReason":"SAFETY"}]}""")).generar("k", "gemini-2.5-flash", "s", turnos, 10, 0.1)
        assertTrue(r.error!!.contains("SAFETY"))
    }
}
