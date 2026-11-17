package com.soledad.app.rag

import com.soledad.app.nucleo.ClienteModelo
import com.soledad.app.nucleo.EstadoApi
import com.soledad.app.nucleo.GestorApis
import com.soledad.app.nucleo.Mensaje
import com.soledad.app.nucleo.PermisoApi
import com.soledad.app.nucleo.ResultadoModelo
import com.soledad.app.nucleo.TurnoChat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IaConRagTest {
    private val eventos = ArrayList<String>()
    private var darPermiso = true
    private var resultado = ResultadoModelo("Abrimos de lunes a viernes de 9 a 18.", 120, 15, null)
    private var lanzar = false
    var ultimoSistema = ""
    var ultimosTurnos: List<TurnoChat> = emptyList()

    private val apis = object : GestorApis {
        override fun solicitar(proveedor: String): PermisoApi? {
            eventos += "solicitar:$proveedor"
            return if (darPermiso) PermisoApi("k1", "CLAVE", "tok") else null
        }
        override fun confirmar(permiso: PermisoApi, exito: Boolean) { eventos += "confirmar:$exito" }
        override fun estado(): List<EstadoApi> = emptyList()
    }
    private val modelo = object : ClienteModelo {
        override fun generar(clave: String, modelo: String, sistema: String, turnos: List<TurnoChat>, maxTokens: Int, temperatura: Double): ResultadoModelo {
            eventos += "generar:$clave"
            ultimoSistema = sistema; ultimosTurnos = turnos
            if (lanzar) throw RuntimeException("sin red")
            return resultado
        }
    }

    private var ahora = 1_000_000_000L
    // Configuración del proyecto base (solo Gemini, sin embeddings, con caché): los tests heredados siguen valiendo.
    private val base = ConfigRag(
        proveedor = "gemini", modelo = "gemini-2.5-flash", proveedorRespaldo = "", modeloEmbeddings = "",
        puntajeMinimo = 0.5, permitirSinBase = false, cacheDias = 7,
    )
    private var cfg = base
    private val almacen = AlmacenMemoria()
    private val ia = IaConRagImpl({ cfg }, almacen, { apis }, { _ -> modelo }, { ahora })
    private val conocimiento = Conocimiento(almacen, ia) { ahora }

    private fun msg(t: String, chat: String = "c1") = Mensaje("id" + t.hashCode(), chat, "Ana", t, false, ahora, "com.whatsapp")
    private fun baseHorario() = conocimiento.agregarTexto("Horarios", "Nuestro horario de atención es de lunes a viernes de 9 a 18 horas. Los sábados cerramos al mediodía.")

    @Test fun permisoSePideAntesDeLlamarAlModelo() {
        baseHorario()
        val r = ia.responder(msg("¿Cuál es el horario de atención?"))
        assertEquals(listOf("solicitar:gemini", "generar:CLAVE", "confirmar:true"), eventos)
        assertTrue(r.nota.startsWith("IA_RAG"))
        assertEquals(120, r.tokensEntrada)
    }

    @Test fun confirmarSeLlamaConFalloDelModelo() {
        baseHorario()
        resultado = ResultadoModelo(null, 0, 0, "HTTP 500")
        val r = ia.responder(msg("horario de atención"))
        assertNull(r.texto)
        assertEquals(listOf("solicitar:gemini", "generar:CLAVE", "confirmar:false"), eventos)
    }

    @Test fun confirmarSeLlamaSiElModeloLanzaExcepcion() {
        baseHorario()
        lanzar = true
        val r = ia.responder(msg("horario de atención"))
        assertNull(r.texto)
        assertEquals("confirmar:false", eventos.last())
    }

    @Test fun sinPermisoNoHayLlamadaAlModelo() {
        baseHorario()
        darPermiso = false
        val r = ia.responder(msg("horario de atención"))
        assertNull(r.texto)
        assertEquals("sin cupo de API", r.nota)
        assertEquals(listOf("solicitar:gemini"), eventos)
    }

    @Test fun aciertoDeCacheNoPidePermisoNiLlama() {
        baseHorario()
        ia.responder(msg("horario de atención"))
        eventos.clear()
        val r = ia.responder(msg("Atención: ¿horario?", chat = "otro")) // mismos tokens, otro orden/forma
        assertEquals(emptyList<String>(), eventos)
        assertEquals("CACHE", r.nota)
        assertEquals(0, r.tokensEntrada + r.tokensSalida)
        assertNotNull(r.texto)
    }

    @Test fun agendaVaEnElPromptYCambiaLaCache() {
        var agenda = "Eventos de los próximos 7 días:\n- mañana 18:00 a 20:00: Seguridad Informática"
        val conAgenda = IaConRagImpl({ cfg }, almacen, { apis }, { _ -> modelo }, { ahora }, agenda = { agenda })
        baseHorario()
        conAgenda.responder(msg("¿Cuál es el horario de atención mañana?"))
        assertTrue(ultimoSistema.contains("NOTAS y MI AGENDA"))
        val ultimo = ultimosTurnos.last().texto
        assertTrue(ultimo.contains("MI AGENDA:\n") && ultimo.contains("Seguridad Informática"))
        assertTrue(ultimo.indexOf("MI AGENDA") < ultimo.indexOf("MENSAJE:"))
        // Si la agenda cambia, no se reutiliza la respuesta guardada.
        agenda = "Eventos de los próximos 7 días: ninguno."
        eventos.clear()
        val r = conAgenda.responder(msg("¿Cuál es el horario de atención mañana?"))
        assertTrue(r.nota.startsWith("IA_RAG"))
        assertTrue(eventos.contains("generar:CLAVE"))
    }

    @Test fun sinAgendaElPromptQuedaComoAntes() {
        baseHorario()
        ia.responder(msg("¿Cuál es el horario de atención?"))
        assertFalse(ultimoSistema.contains("MI AGENDA"))
        assertFalse(ultimosTurnos.last().texto.contains("MI AGENDA"))
    }

    @Test fun cacheVenceSegunCacheDias() {
        baseHorario()
        ia.responder(msg("horario de atención"))
        eventos.clear()
        ahora += 8 * IaConRagImpl.DIA_MS
        ia.responder(msg("horario de atención"))
        assertEquals("solicitar:gemini", eventos.first())
    }

    @Test fun sinBaseNoPidePermisoNiLlama() {
        val r = ia.responder(msg("horario de atención"))
        assertEquals("SIN_BASE", r.nota)
        assertNull(r.texto)
        assertTrue(eventos.isEmpty())
        baseHorario()
        val r2 = ia.responder(msg("¿venden zapatos rojos?")) // base existe pero sin hallazgos
        assertEquals("SIN_BASE", r2.nota)
        assertTrue(eventos.isEmpty())
    }

    @Test fun permitirSinBaseLlamaAlModelo() {
        cfg = cfg.copy(permitirSinBase = true)
        val r = ia.responder(msg("hola, ¿quién eres?"))
        assertNotNull(r.texto)
        assertEquals("solicitar:gemini", eventos.first())
    }

    @Test fun promptSoloTopKYRecortaHistorial() {
        repeat(6) { conocimiento.agregarTexto("F$it", "Producto$it tiene garantía de $it años y envío gratis garantía") }
        cfg = cfg.copy(topK = 2, historial = 2, puntajeMinimo = 0.01)
        for (i in 1..3) { almacen.guardarTurno("c1", "user", "pregunta $i", ahora); almacen.guardarTurno("c1", "model", "respuesta $i", ahora) }
        almacen.guardarTurno("c1", "user", "x".repeat(2000), ahora)
        ia.responder(msg("garantía y envío gratis"))
        val ultimo = ultimosTurnos.last().texto
        assertEquals(2, ultimo.lines().count { it.startsWith("- ") })
        // historial=2 -> los 2 últimos turnos (respuesta 3 y la "x"), empieza con user: se descarta el turno 'model' inicial
        assertEquals(listOf("user", "user"), ultimosTurnos.map { it.rol })
        assertTrue(ultimosTurnos.first().texto.length <= 601)
        assertFalse(ultimosTurnos.any { it.texto.contains("pregunta 1") })
    }

    @Test fun bm25OrdenaPorRelevanciaConAcentos() {
        conocimiento.agregarTexto("A", "La cotización del envío internacional depende del peso y el país de destino.")
        conocimiento.agregarTexto("B", "Aceptamos pagos con tarjeta, transferencia bancaria y efectivo en sucursal.")
        conocimiento.agregarTexto("C", "Dirección: Avenida Colón 123, Cañete. Estacionamiento gratuito para clientes.")
        val idx = Indice(almacen.fragmentos())
        val h = idx.buscar("¿Cómo pago? ¿Aceptan tarjeta de crédito?", 3, 0.1)
        assertTrue(h.first().fragmento.texto.startsWith("Aceptamos pagos"))
        val h2 = idx.buscar("direccion canete colon", 3, 0.1) // sin acentos, 'ñ' distinta de 'n'
        assertTrue(h2.isEmpty() || !h2.first().fragmento.texto.contains("tarjeta"))
        val h3 = idx.buscar("DIRECCIÓN avenida COLÓN", 3, 0.1)
        assertTrue(h3.first().fragmento.texto.startsWith("Dirección"))
        val h4 = idx.buscar("cotizacion del envio", 3, 0.1)
        assertTrue(h4.first().fragmento.texto.startsWith("La cotización"))
        assertTrue(Indice.tokens("Envíos y horarios").containsAll(listOf("envio", "horario")))
    }

    @Test fun importarTxtYMdTrocea() {
        val parrafos = (1..8).joinToString("\n\n") { n -> "Párrafo $n " + (1..60).joinToString(" ") { "palabra$n$it" } + " reembolso" }
        val id = conocimiento.importar("politicas.txt", parrafos.byteInputStream())
        val frs = almacen.fragmentos().filter { it.fuenteId == id }
        assertTrue(frs.size in 2..5)
        assertTrue(frs.all { it.texto.split(Regex("\\s+")).size <= 200 })
        val md = "# Garantía\n\n**Importante:** la garantía cubre *defectos de fábrica* por 12 meses."
        val id2 = conocimiento.importar("garantia.md", md.byteInputStream())
        val f2 = almacen.fragmentos().first { it.fuenteId == id2 }
        assertFalse(f2.texto.contains("#") || f2.texto.contains("**"))
        val h = Indice(almacen.fragmentos()).buscar("¿qué cubre la garantía?", 3, 0.1)
        assertEquals(id2, h.first().fragmento.fuenteId)
        assertEquals(2, conocimiento.fuentes().size)
        conocimiento.borrar(id)
        assertEquals(1, conocimiento.fuentes().size)
        assertTrue(almacen.fragmentos().none { it.fuenteId == id })
    }

    @Test fun indiceSeInvalidaAlBorrarFuente() {
        val id = baseHorario()
        assertTrue(ia.responder(msg("horario de atención")).nota.startsWith("IA_RAG"))
        conocimiento.borrar(id)
        assertEquals("SIN_BASE", ia.responder(msg("horario de atención")).nota)
    }

    @Test fun etiquetaSoloSiEstaActiva() {
        baseHorario()
        assertEquals("Abrimos de lunes a viernes de 9 a 18.", ia.responder(msg("horario de atención")).texto)
        cfg = cfg.copy(etiquetaActiva = true, etiqueta = "[Auto]")
        val r = ia.responder(msg("horario de atención")) // sale de la caché: la etiqueta se aplica igual
        assertTrue(r.texto!!.endsWith("\n[Auto]"))
    }

    @Test fun limpiaMarkdownYAcortaYContabilizaTokens() {
        baseHorario()
        resultado = ResultadoModelo("**Hola**\n- abrimos `9` a 18. " + "Más texto. ".repeat(80), 100, 40, null)
        val r = ia.responder(msg("horario de atención"))
        assertFalse(r.texto!!.contains("*") || r.texto!!.contains("`") || r.texto!!.contains("\n"))
        assertTrue(r.texto!!.length <= 401)
        assertEquals(100 to 40, conocimiento.tokensHoy())
    }

    @Test fun simularNoGuardaHistorialYSiUsaElFlujo() {
        baseHorario()
        val r = conocimiento.simular("horario de atención")
        assertNotNull(r.texto)
        assertEquals(listOf("solicitar:gemini", "generar:CLAVE", "confirmar:true"), eventos)
        assertTrue(almacen.turnos("__simulacion__", 10).isEmpty())
    }

    @Test fun configYaml() {
        val c = ConfigRag.desdeYaml("modelo: m1\ntop_k: 5\npuntaje_minimo: 2.5\netiqueta_activa: true\netiqueta: \"[IA]\"\ncache_dias: 0\npermitir_sin_base: true\nhistorial: 0\npersonalidad: \"Hola: soy yo\"")
        assertEquals(ConfigRag(modelo = "m1", topK = 5, puntajeMinimo = 2.5, etiquetaActiva = true, etiqueta = "[IA]", cacheDias = 0, permitirSinBase = true, historial = 0, personalidad = "Hola: soy yo"), c)
        assertEquals(ConfigRag(), ConfigRag.desdeYaml(null))
    }
}
