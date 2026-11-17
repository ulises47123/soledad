package com.soledad.app.soledad

import com.soledad.app.lista.Contacto
import com.soledad.app.lista.ConfigLista
import com.soledad.app.lista.EstadoLista
import com.soledad.app.lista.ListaContactos
import com.soledad.app.lista.RepositorioConfig
import com.soledad.app.lista.VerificadorListaImpl
import com.soledad.app.nucleo.AccionEnvio
import com.soledad.app.nucleo.Enviador
import com.soledad.app.nucleo.EstadoCola
import com.soledad.app.nucleo.IaConRag
import com.soledad.app.nucleo.Mensaje
import com.soledad.app.nucleo.Registro
import com.soledad.app.nucleo.Respuesta
import com.soledad.app.nucleo.ResultadoEnvio
import com.soledad.app.nucleo.VerificadorEnvio
import com.soledad.app.recepcion.ColaPersistente
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone
import kotlin.random.Random
import com.soledad.app.lista.AlmacenMemoria as AlmacenTexto
import com.soledad.app.recepcion.AlmacenMemoria as AlmacenColaMemoria

private const val MIN = 60_000L

class AzarTest {
    @Test fun intervaloSolo20_50_120ConVariacion() {
        val rnd = Random(42)
        val cfg = ConfigSoledad(variacionMin = 5)
        val vistos = HashSet<Int>()
        repeat(5_000) {
            val ms = Azar.proximoIntervaloMs(rnd, cfg)
            val base = listOf(20, 50, 120).firstOrNull { b -> ms in (b - 5) * MIN..(b + 5) * MIN }
            assertTrue("intervalo fuera de 20/50/120 ± 5 min: ${ms / MIN.toDouble()} min", base != null)
            vistos += base!!
        }
        assertEquals(setOf(20, 50, 120), vistos) // los tres salen al azar
    }

    @Test fun sinVariacionSonExactos() {
        val rnd = Random(7)
        val cfg = ConfigSoledad(variacionMin = 0)
        repeat(1_000) { assertTrue(Azar.proximoIntervaloMs(rnd, cfg) / MIN in setOf(20L, 50L, 120L)) }
    }

    @Test fun intervalosInvalidosNoSeUsan() {
        val rnd = Random(1)
        val cfg = ConfigSoledad(intervalosMin = listOf(1, 999), variacionMin = 0)
        repeat(300) { assertTrue(Azar.proximoIntervaloMs(rnd, cfg) / MIN in setOf(20L, 50L, 120L)) }
        val solo50 = ConfigSoledad(intervalosMin = listOf(50), variacionMin = 0)
        repeat(100) { assertEquals(50 * MIN, Azar.proximoIntervaloMs(rnd, solo50)) }
        // la variación tiene tope de 10 minutos
        val mucha = ConfigSoledad(intervalosMin = listOf(20), variacionMin = 60)
        repeat(500) { assertTrue(Azar.proximoIntervaloMs(rnd, mucha) in 10 * MIN..30 * MIN) }
    }

    @Test fun alInstantePorDefectoNoEspera() {
        assertTrue(ConfigSoledad().alInstante)
        val rnd = Random(3)
        repeat(100) { assertEquals(0L, Azar.esperaRespuestaMs(rnd, ConfigSoledad())) }
    }

    @Test fun esperaEntre1y5Minutos() {
        val rnd = Random(3)
        var min = Long.MAX_VALUE; var max = 0L
        repeat(5_000) {
            val e = Azar.esperaRespuestaMs(rnd, ConfigSoledad(alInstante = false))
            assertTrue(e in MIN..5 * MIN)
            min = minOf(min, e); max = maxOf(max, e)
        }
        assertTrue(min < 90_000 && max > 270_000) // recorre todo el rango
        // aunque la configuración venga fuera de rango, se respeta [1, 5]
        repeat(1_000) { assertTrue(Azar.esperaRespuestaMs(rnd, ConfigSoledad(alInstante = false, esperaMinMin = 0, esperaMaxMin = 9)) in MIN..5 * MIN) }
        repeat(1_000) { assertTrue(Azar.esperaRespuestaMs(rnd, ConfigSoledad(alInstante = false, esperaMinMin = 5, esperaMaxMin = 2)) in 2 * MIN..5 * MIN) }
    }

    @Test fun etiquetaPorDefectoYOpcional() {
        assertEquals("hola (respuesta automática)", Etiqueta.aplicar("hola", ConfigSoledad()))
        assertEquals("hola", Etiqueta.aplicar("hola", ConfigSoledad(etiquetaActiva = false)))
        assertEquals("hola (respuesta automática)", Etiqueta.aplicar("hola (respuesta automática)", ConfigSoledad()))
        assertTrue(ConfigSoledad().etiquetaActiva)
        assertTrue(ConfigSoledad().modoPrueba)
    }
}

class MotorSoledadTest {
    private var ahora = 1_800_000_000_000L
    private val eventos = ArrayList<String>()
    private val registro = ArrayList<Triple<String, String, String>>() // (mensajeId, etapa, detalle)

    // ---- lista blanca real (la del proyecto base) en memoria
    private val repo = RepositorioConfig(AlmacenTexto()).also { it.guardar(ConfigLista(interruptorGeneral = true)) }
    private val estadoLista = EstadoLista(AlmacenTexto())
    private val lista = VerificadorListaImpl(repo, estadoLista, { ahora }, TimeZone.getTimeZone("UTC"))
    private val contactos = ListaContactos(repo, estadoLista)

    private val almacenCola = AlmacenColaMemoria()
    private val cola = ColaPersistente(almacenCola, reloj = { ahora })

    var cfg = ConfigSoledad(modoPrueba = false, entrarAWhatsApp = false)
    var bandeja: List<Mensaje>? = emptyList()
    var enBandeja: Boolean? = true
    var respuestaNotif = true
    var bloqueado = true
    var textoIa: String? = "Ahora no puedo, te escribo más tarde"
    var confirmado = true

    private val fuente = object : FuenteMensajes {
        override fun sinLeer(edadMaximaMs: Long): List<Mensaje>? { eventos += "leer"; return bandeja }
        override fun sigueSinLeer(chatId: String) = enBandeja
        override fun puedeResponderPorNotificacion(chatId: String) = respuestaNotif
    }
    private val telefono = object : Telefono {
        override fun bloqueado() = bloqueado
        override fun shizuku() = true
    }
    val chequeos = ArrayList<Long>()
    var respuestaEn: Long? = null
    var cancelado = false
    private val planificador = object : Planificador {
        override fun programarChequeo(enMs: Long) { chequeos += enMs }
        override fun programarRespuesta(enMs: Long) { respuestaEn = enMs }
        override fun cancelarRespuesta() { respuestaEn = null }
        override fun cancelarTodo() { cancelado = true; respuestaEn = null; eventos += "cancelarTodo" }
    }
    private val visita = object : Visita {
        override fun entrarUnMomento(): Pair<Boolean, String> { eventos += "visita"; return true to "entré" }
    }
    private val ia = object : IaConRag {
        override fun responder(m: Mensaje, guardar: Boolean): Respuesta { eventos += "ia:guardar=$guardar"; return Respuesta(textoIa, "IA_RAG · nvidia") }
    }
    val enviados = ArrayList<AccionEnvio.Texto>()
    private val enviador = object : Enviador {
        override fun enviar(a: AccionEnvio): ResultadoEnvio { enviados += a as AccionEnvio.Texto; eventos += "enviar"; return ResultadoEnvio(true, "método A") }
    }
    private val verificador = object : VerificadorEnvio {
        override fun verificar(a: AccionEnvio, desdeMs: Long, timeoutMs: Long): Boolean { eventos += "verificar"; return confirmado }
    }
    private val reg = object : Registro {
        override fun anotar(m: Mensaje?, etapa: String, detalle: String) { registro += Triple(m?.id ?: "-", etapa, detalle) }
    }
    private val estado = EstadoMemoria()

    private val motor = MotorSoledad(
        config = { cfg },
        encendida = { contactos.leerConfig().interruptorGeneral },
        apagar = { contactos.setInterruptor(false) },
        lista = lista, cola = cola, ia = ia, enviador = enviador, verificador = verificador, registro = reg,
        fuente = fuente, telefono = telefono, planificador = planificador, visita = visita, estado = estado,
        rnd = Random(11), reloj = { ahora },
    )

    private var n = 0
    private fun msg(nombre: String, texto: String = "hola, ¿cómo andás?", grupo: Boolean = false, hace: Long = MIN) =
        Mensaje("id${n++}", "chat-$nombre", nombre, texto, grupo, ahora - hace, "com.whatsapp")

    private fun encender() { motor.activar(); ahora += 10 * MIN; chequeos.clear() }
    private fun etapas() = registro.map { it.second }

    @Test fun alInstanteRespondeSinEsperar() {
        contactos.agregar(Contacto("Ana", "Ana"))
        encender()
        bandeja = listOf(msg("Ana"))
        motor.chequear()
        assertEquals(ahora, cola.programadas().single().proximoMs) // sin espera
        assertEquals(1, motor.responderPendientes())               // en el mismo momento
        assertEquals("chat-Ana", enviados.single().chatId)
    }

    @Test fun contactoDeLaListaSeProgramaEntre1y5MinutosYSeResponde() {
        cfg = cfg.copy(alInstante = false)
        contactos.agregar(Contacto("Ana", "Ana"))
        encender()
        bandeja = listOf(msg("Ana"))
        motor.chequear()
        val prog = cola.programadas()
        assertEquals(1, prog.size)
        val espera = prog[0].proximoMs - ahora
        assertTrue("espera $espera", espera in MIN..5 * MIN)
        assertEquals(prog[0].proximoMs, respuestaEn)
        // antes de que venza la espera, no responde
        assertEquals(0, motor.responderPendientes())
        assertTrue(enviados.isEmpty())
        ahora += espera
        assertEquals(1, motor.responderPendientes())
        assertEquals("Ahora no puedo, te escribo más tarde (respuesta automática)", enviados.single().texto)
        assertEquals("chat-Ana", enviados.single().chatId)
        assertEquals(EstadoCola.VERIFICADO, almacenCola.filas.values.single().estado)
        assertTrue(etapas().contains(Etapa.RESPONDIDO))
        assertNull(respuestaEn) // no queda nada programado
    }

    @Test fun proximaRevisionSale20_50o120MinutosDespues() {
        encender()
        repeat(30) {
            motor.chequear()
            val dif = chequeos.last() - ahora
            assertTrue("${dif / MIN} min", listOf(20, 50, 120).any { b -> dif in (b - 5) * MIN..(b + 5) * MIN })
        }
    }

    @Test fun gruposNuncaSeResponden() {
        // aunque el nombre del grupo esté en la lista blanca
        contactos.agregar(Contacto("Familia", "Familia"))
        encender()
        bandeja = listOf(msg("Familia", grupo = true))
        motor.chequear()
        ahora += 10 * MIN
        motor.responderPendientes()
        assertTrue(cola.programadas().isEmpty())
        assertTrue(enviados.isEmpty())
        assertFalse(eventos.any { it.startsWith("ia") })
        assertTrue(registro.any { it.second == Etapa.IGNORADO && it.third.contains("Grupo") })
    }

    @Test fun contactoFueraDeLaListaSeIgnora() {
        contactos.agregar(Contacto("Ana", "Ana"))
        encender()
        bandeja = listOf(msg("Desconocido", "che, ¿me prestás plata?"), msg("Beto"))
        motor.chequear()
        ahora += 10 * MIN
        motor.responderPendientes()
        assertTrue(enviados.isEmpty())
        assertFalse(eventos.any { it.startsWith("ia") })
        assertEquals(2, registro.count { it.second == Etapa.IGNORADO && it.third.contains("lista blanca") })
        // el texto de quien no está en la lista no queda en el registro
        assertFalse(registro.any { it.third.contains("prestás") })
    }

    @Test fun listaVaciaNoRespondeANadie() {
        encender()
        bandeja = listOf(msg("Ana"), msg("Beto"))
        motor.chequear()
        assertTrue(cola.programadas().isEmpty())
    }

    @Test fun modoPruebaGeneraLaRespuestaPeroNoLaEnvia() {
        cfg = cfg.copy(modoPrueba = true)
        respuestaNotif = false // en modo prueba no hace falta poder enviar
        contactos.agregar(Contacto("Ana", "Ana"))
        encender()
        bandeja = listOf(msg("Ana", "¿vamos al cine el sábado?"))
        motor.chequear()
        ahora += 6 * MIN
        motor.responderPendientes()
        assertTrue(enviados.isEmpty())
        assertFalse(eventos.contains("enviar"))
        assertTrue(eventos.contains("ia:guardar=false")) // no ensucia el historial del chat
        val prueba = registro.single { it.second == Etapa.PRUEBA }.third
        assertTrue(prueba.contains("no enviado") && prueba.contains("(respuesta automática)") && prueba.contains("cine"))
        assertEquals(EstadoCola.DESCARTADO, almacenCola.filas.values.single().estado)
    }

    @Test fun bloqueadoYSinResponderQuedaPendienteSinDesbloquear() {
        respuestaNotif = false
        bloqueado = true
        contactos.agregar(Contacto("Ana", "Ana"))
        encender()
        bandeja = listOf(msg("Ana"))
        motor.chequear()
        ahora += 6 * MIN
        motor.responderPendientes()
        assertTrue(enviados.isEmpty())
        assertFalse(eventos.any { it.startsWith("ia") }) // ni siquiera gasta IA
        assertTrue(registro.any { it.second == Etapa.PENDIENTE && it.third.contains("bloqueado") })
        assertEquals(EstadoCola.RECIBIDO, almacenCola.filas.values.single().estado)
        // Con el botón «Responder» disponible después, se responde.
        respuestaNotif = true
        ahora = maxOf(ahora + 11 * MIN, estado.proximoChequeoMs)
        motor.responderPendientes()
        assertEquals(1, enviados.size)
    }

    @Test fun siLoLeisteVosNoResponde() {
        contactos.agregar(Contacto("Ana", "Ana"))
        encender()
        bandeja = listOf(msg("Ana"))
        motor.chequear()
        enBandeja = false
        ahora += 6 * MIN
        motor.responderPendientes()
        assertTrue(enviados.isEmpty())
        assertTrue(registro.any { it.second == Etapa.DESCARTADO && it.third.contains("ya lo leíste") })
    }

    @Test fun mensajesAnterioresAEncenderNoCuentan() {
        contactos.agregar(Contacto("Ana", "Ana"))
        encender()
        bandeja = listOf(msg("Ana", hace = 30 * MIN)) // llegó antes de encender
        motor.chequear()
        assertTrue(cola.programadas().isEmpty())
    }

    @Test fun mismoMensajeNoSeRespondeDosVeces() {
        contactos.agregar(Contacto("Ana", "Ana"))
        encender()
        val m = msg("Ana")
        bandeja = listOf(m)
        motor.chequear()
        ahora += 6 * MIN
        motor.responderPendientes()
        motor.chequear() // la notificación sigue ahí con el mismo mensaje
        ahora += 6 * MIN
        motor.responderPendientes()
        assertEquals(1, enviados.size)
    }

    @Test fun salirCancelaTodo() {
        contactos.agregar(Contacto("Ana", "Ana"))
        encender()
        bandeja = listOf(msg("Ana"), msg("Beto").copy(nombre = "Ana", chatId = "chat-Ana2"))
        motor.chequear()
        assertEquals(2, cola.programadas().size)
        motor.salir()
        assertTrue(cancelado)                                 // alarmas canceladas
        assertNull(respuestaEn)
        assertFalse(contactos.leerConfig().interruptorGeneral) // interruptor apagado
        assertTrue(cola.programadas().isEmpty())               // respuestas programadas descartadas
        assertTrue(almacenCola.filas.values.all { it.estado == EstadoCola.DESCARTADO })
        // y nada vuelve a correr aunque llegue una alarma vieja
        eventos.clear()
        ahora += 10 * MIN
        motor.chequear()
        assertEquals(0, motor.responderPendientes())
        assertFalse(eventos.contains("leer"))
        assertTrue(enviados.isEmpty())
    }

    @Test fun apagadaNoLeeNiResponde() {
        contactos.agregar(Contacto("Ana", "Ana"))
        bandeja = listOf(msg("Ana"))
        contactos.setInterruptor(false)
        motor.chequear()
        assertFalse(eventos.contains("leer"))
        assertTrue(cola.programadas().isEmpty())
    }

    @Test fun visitaSoloSiEstaActivada() {
        encender()
        motor.chequear()
        assertFalse(eventos.contains("visita"))
        cfg = cfg.copy(entrarAWhatsApp = true)
        motor.chequear()
        assertTrue(eventos.contains("visita"))
    }

    @Test fun masMensajesDelMismoChatSeJuntanEnUnaRespuesta() {
        contactos.agregar(Contacto("Ana", "Ana"))
        encender()
        bandeja = listOf(msg("Ana", "hola"))
        motor.chequear()
        bandeja = listOf(msg("Ana", "hola\n¿estás?"))
        motor.chequear()
        assertEquals(1, cola.programadas().size)
        assertEquals("hola\n¿estás?", cola.programadas().single().mensaje.texto)
        ahora += 6 * MIN
        motor.responderPendientes()
        assertEquals(1, enviados.size)
    }
}
