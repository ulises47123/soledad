package com.soledad.app.soledad

import com.soledad.app.envio.EjecutorRegistrado
import com.soledad.app.envio.FalsoShell
import com.soledad.app.envio.RelojFalso
import com.soledad.app.envio.ResultadoShell
import com.soledad.app.lista.AlmacenMemoria as AlmacenTexto
import com.soledad.app.lista.ConfigLista
import com.soledad.app.lista.Contacto
import com.soledad.app.lista.EstadoLista
import com.soledad.app.lista.ListaContactos
import com.soledad.app.lista.RepositorioConfig
import com.soledad.app.lista.SeleccionAgenda
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
import com.soledad.app.recepcion.AlmacenMemoria as AlmacenColaMemoria
import com.soledad.app.recepcion.ColaPersistente
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone
import kotlin.random.Random

private const val MIN = 60_000L

/** Versión 1.2: a quién responde, responder al llegar, desbloqueo opt-in y el arreglo del selector. */
class Soledad12Test {
    private var ahora = 1_800_000_000_000L
    private val registro = ArrayList<Pair<String, String>>()
    private var cfg = ConfigSoledad(modoPrueba = false, entrarAWhatsApp = false)

    private val repo = RepositorioConfig(AlmacenTexto()).also { it.guardar(ConfigLista(interruptorGeneral = true)) }
    private val estadoLista = EstadoLista(AlmacenTexto())
    private val lista = VerificadorListaImpl(repo, estadoLista, { ahora }, TimeZone.getTimeZone("UTC")) { cfg.responderATodos }
    private val contactos = ListaContactos(repo, estadoLista)
    private val almacenCola = AlmacenColaMemoria()
    private val cola = ColaPersistente(almacenCola, reloj = { ahora })

    var bandeja: List<Mensaje> = emptyList()
    var respuestaNotif = true
    var bloqueado = true
    val enviados = ArrayList<String>()
    val pasos = ArrayList<String>()

    private val fuente = object : FuenteMensajes {
        override fun sinLeer(edadMaximaMs: Long) = bandeja
        override fun sigueSinLeer(chatId: String) = true
        override fun puedeResponderPorNotificacion(chatId: String) = respuestaNotif
    }
    private val telefono = object : Telefono {
        override fun bloqueado() = bloqueado
        override fun shizuku() = true
    }
    private val planificador = object : Planificador {
        override fun programarChequeo(enMs: Long) {}
        override fun programarRespuesta(enMs: Long) {}
        override fun cancelarRespuesta() {}
        override fun cancelarTodo() {}
    }
    private val visita = object : Visita {
        override fun entrarUnMomento(): Pair<Boolean, String> { pasos += "visita(bloqueado=$bloqueado)"; return true to "entré" }
    }
    private val ia = object : IaConRag {
        override fun responder(m: Mensaje, guardar: Boolean) = Respuesta("Después te escribo", "IA_SIN_BASE · nvidia-2")
    }
    private val enviador = object : Enviador {
        override fun enviar(a: AccionEnvio): ResultadoEnvio { pasos += "enviar(bloqueado=$bloqueado)"; enviados += (a as AccionEnvio.Texto).texto; return ResultadoEnvio(true, "") }
    }
    private val verificador = object : VerificadorEnvio {
        override fun verificar(a: AccionEnvio, desdeMs: Long, timeoutMs: Long) = true
    }
    private val reg = object : Registro {
        override fun anotar(m: Mensaje?, etapa: String, detalle: String) { registro += etapa to detalle }
    }

    /** Desbloqueo falso: cuenta llamadas; la «credencial» nunca debería aparecer en el registro. */
    inner class DesbloqueoFalso(var disponible: Boolean = true, var funciona: Boolean = true) : Desbloqueo {
        var llamadas = 0
        var bloqueos = 0
        val credencial = "Clave-Secreta-9876"
        override fun disponible() = disponible
        override fun desbloquear(): Boolean { llamadas++; pasos += "desbloquear"; if (funciona) bloqueado = false; return funciona }
        override fun volverABloquear() { bloqueos++; pasos += "bloquear"; bloqueado = true }
    }

    private fun motor(d: Desbloqueo = SinDesbloqueo) = MotorSoledad(
        config = { cfg }, encendida = { contactos.leerConfig().interruptorGeneral }, apagar = { contactos.setInterruptor(false) },
        lista = lista, cola = cola, ia = ia, enviador = enviador, verificador = verificador, registro = reg,
        fuente = fuente, telefono = telefono, planificador = planificador, visita = visita, estado = EstadoMemoria(),
        rnd = Random(5), reloj = { ahora }, desbloqueo = d,
    ).also { it.activar(); ahora += MIN }

    private var n = 0
    private fun msg(nombre: String, texto: String = "hola, ¿cómo andás?", grupo: Boolean = false) =
        Mensaje("id${n++}", "chat-$nombre", nombre, texto, grupo, ahora - 1_000, "com.whatsapp")

    private fun responderTodo(m: MotorSoledad) { ahora += 6 * MIN; m.responderPendientes() }

    // ---------- 2) A quién responde ----------

    @Test fun soloListaBlancaPorDefecto() {
        contactos.agregar(Contacto("Ana", "Ana"))
        val m = motor()
        bandeja = listOf(msg("Ana"), msg("Desconocido"))
        m.chequear()
        assertEquals(listOf("Ana"), cola.programadas().map { it.mensaje.nombre })
        assertFalse(ConfigSoledad().responderATodos)
    }

    @Test fun aTodosRespondeATodosLosIndividualesPeroNuncaAGrupos() {
        cfg = cfg.copy(responderATodos = true)
        val m = motor() // lista blanca vacía
        bandeja = listOf(msg("Desconocido"), msg("Beto"), msg("Familia", grupo = true), msg("Trabajo", grupo = true))
        m.chequear()
        assertEquals(setOf("Desconocido", "Beto"), cola.programadas().map { it.mensaje.nombre }.toSet())
        responderTodo(m)
        assertEquals(2, enviados.size)
        assertTrue(registro.count { it.first == Etapa.IGNORADO && it.second.contains("Grupo") } == 2)
    }

    @Test fun aTodosSigueFiltrandoTemasSensibles() {
        cfg = cfg.copy(responderATodos = true)
        val m = motor()
        bandeja = listOf(msg("Desconocido", "pasame el cbu para la transferencia"))
        m.chequear()
        assertTrue(cola.programadas().isEmpty())
    }

    // ---------- 5) Responder apenas llega ----------

    @Test fun alLlegarAlInstanteRespondeYNoDuplica() {
        contactos.agregar(Contacto("Ana", "Ana"))
        val m = motor()
        bandeja = listOf(msg("Ana"))
        assertEquals(1, m.procesarNuevos())
        assertEquals(ahora, cola.programadas().single().proximoMs)
        assertEquals(1, m.responderPendientes())
        assertEquals(1, enviados.size)
        m.chequear(); assertEquals(0, m.procesarNuevos())
        assertEquals(0, m.responderPendientes())
        assertEquals(1, enviados.size)
    }

    @Test fun alLlegarProgramaConEsperaYNoDuplicaConLaRevision() {
        cfg = cfg.copy(alInstante = false)
        contactos.agregar(Contacto("Ana", "Ana"))
        val m = motor()
        bandeja = listOf(msg("Ana"))
        assertEquals(1, m.procesarNuevos())
        val espera = cola.programadas().single().proximoMs - ahora
        assertTrue(espera in MIN..5 * MIN) // se mantiene la espera al azar
        assertEquals(0, m.procesarNuevos()) // repintado de la misma notificación
        m.chequear()                        // la revisión periódica ve el mismo mensaje
        assertEquals(1, cola.programadas().size)
        responderTodo(m)
        m.chequear(); m.procesarNuevos()
        responderTodo(m)
        assertEquals(1, enviados.size)
    }

    @Test fun alLlegarApagadoNoHaceNada() {
        contactos.agregar(Contacto("Ana", "Ana"))
        cfg = cfg.copy(responderAlLlegar = false)
        val m = motor()
        bandeja = listOf(msg("Ana"))
        assertEquals(0, m.procesarNuevos())
        assertTrue(cola.programadas().isEmpty())
        assertTrue(ConfigSoledad().responderAlLlegar) // activado por defecto
    }

    @Test fun ignoradosSeAnotanUnaSolaVez() {
        val m = motor()
        bandeja = listOf(msg("Desconocido"))
        m.procesarNuevos(); m.chequear(); m.procesarNuevos()
        assertEquals(1, registro.count { it.first == Etapa.IGNORADO })
    }

    // ---------- 6) Desbloqueo opt-in ----------

    @Test fun conElAjusteApagadoNuncaDesbloquea() {
        contactos.agregar(Contacto("Ana", "Ana"))
        respuestaNotif = false
        val d = DesbloqueoFalso()
        val m = motor(d)
        bandeja = listOf(msg("Ana"))
        m.chequear()
        responderTodo(m)
        assertEquals(0, d.llamadas)
        assertTrue(enviados.isEmpty())
        assertTrue(registro.any { it.first == Etapa.PENDIENTE && it.second.contains("desbloqueo está apagado") })
        assertFalse(ConfigSoledad().desbloquear) // apagado por defecto
    }

    @Test fun conResponderEnLaNotificacionNoDesbloquea() {
        contactos.agregar(Contacto("Ana", "Ana"))
        cfg = cfg.copy(desbloquear = true)
        val d = DesbloqueoFalso()
        val m = motor(d)
        bandeja = listOf(msg("Ana"))
        m.chequear(); responderTodo(m)
        assertEquals(0, d.llamadas)
        assertEquals(listOf("enviar(bloqueado=true)"), pasos)
    }

    @Test fun sinResponderDesbloqueaEnviaYVuelveABloquear() {
        contactos.agregar(Contacto("Ana", "Ana"))
        cfg = cfg.copy(desbloquear = true)
        respuestaNotif = false
        val d = DesbloqueoFalso()
        val m = motor(d)
        bandeja = listOf(msg("Ana"))
        m.chequear(); responderTodo(m)
        assertEquals(listOf("desbloquear", "enviar(bloqueado=false)", "bloquear"), pasos)
        assertEquals(1, d.bloqueos)
        assertTrue(bloqueado)
        assertTrue(registro.none { it.second.contains(d.credencial) })
    }

    @Test fun entrarUnMomentoDesbloqueaYVuelveABloquear() {
        cfg = cfg.copy(desbloquear = true, entrarAWhatsApp = true)
        val d = DesbloqueoFalso()
        val m = motor(d)
        m.chequear()
        assertEquals(listOf("desbloquear", "visita(bloqueado=false)", "bloquear"), pasos)
    }

    @Test fun siElDesbloqueoFallaQuedaPendienteYNoInsiste() {
        contactos.agregar(Contacto("Ana", "Ana"))
        cfg = cfg.copy(desbloquear = true)
        respuestaNotif = false
        val d = DesbloqueoFalso(funciona = false)
        val m = motor(d)
        bandeja = listOf(msg("Ana"))
        m.chequear(); responderTodo(m)
        assertEquals(1, d.llamadas)
        assertTrue(enviados.isEmpty())
        assertEquals(EstadoCola.RECIBIDO, almacenCola.filas.values.single().estado)
        assertTrue(registro.any { it.first == Etapa.PENDIENTE && it.second.contains("no se pudo desbloquear") })
        // Dentro de la hora de pausa no vuelve a intentar.
        ahora += 15 * MIN
        m.responderPendientes()
        assertEquals(1, d.llamadas)
        assertEquals(0, d.bloqueos) // nunca quedó desbloqueado
    }

    @Test fun laCredencialNuncaSeRegistraNiSeLogueaAlDesbloquear() {
        val logs = ArrayList<String>()
        var bloqueada = true
        val shell = FalsoShell { c ->
            when {
                c.contains("mWakefulness") -> ResultadoShell(0, "mWakefulness=Awake")
                c.contains("isKeyguardShowing") -> ResultadoShell(0, "isKeyguardShowing=$bloqueada")
                c.contains("wm size") -> ResultadoShell(0, "Physical size: 720x1600")
                c.startsWith("input keyevent 66") -> { bloqueada = false; ResultadoShell(0, "") }
                else -> ResultadoShell(0, "")
            }
        }
        val d = DesbloqueoShizuku(EjecutorRegistrado(shell) { logs += it }, { "Clave Secreta 9876" }, RelojFalso())
        assertTrue(d.desbloquear())
        val conCredencial = shell.comandos.filter { it.first.contains("Clave Secreta") }
        assertEquals(1, conCredencial.size)
        assertTrue(conCredencial.all { it.second }) // marcado sensible
        assertTrue(logs.none { it.contains("Secreta") })
        d.volverABloquear()
        assertTrue(shell.hubo("input keyevent 223"))
    }

    @Test fun desbloqueoTecleaComoMuchoTresVecesLaCredencial() {
        val shell = FalsoShell { c ->
            when {
                c.contains("mWakefulness") -> ResultadoShell(0, "mWakefulness=Awake")
                c.contains("isKeyguardShowing") -> ResultadoShell(0, "isKeyguardShowing=true") // nunca se desbloquea
                else -> ResultadoShell(0, "")
            }
        }
        val d = DesbloqueoShizuku(shell, { "123456" }, RelojFalso())
        assertFalse(d.desbloquear())
        assertEquals(3, shell.comandos.count { it.second })
        assertFalse(shell.texto().contains("input keyevent 3")) // no fue al inicio si no desbloqueó
        assertEquals("input keyevent 8 9 10 11 12 13", SecuenciaDesbloqueo.comandoCredencial("123456"))
    }

    // ---------- 3) Selector de la agenda: solo agrega ----------

    @Test fun agregarMarcadosNoQuitaANadieAunqueElBuscadorLoEsconda() {
        contactos.agregarNombres(listOf("Nico", "Evangelina Chamb"))
        val sel = SeleccionAgenda(contactos.listar().map { it.nombre })
        // El buscador muestra solo «Ana»; Nico y Evangelina no se ven, pero siguen en la lista.
        sel.marcar("Ana", true)
        sel.marcar("Nico", false) // intento de desmarcar uno que ya estaba: se ignora
        assertTrue(sel.marcado("Nico"))
        assertEquals(listOf("Ana"), sel.aAgregar())
        contactos.agregarNombres(sel.aAgregar())
        assertEquals(listOf("Nico", "Evangelina Chamb", "Ana"), contactos.listar().map { it.nombre })
        // Desmarcar uno recién marcado solo lo saca de la selección.
        val sel2 = SeleccionAgenda(contactos.listar().map { it.nombre })
        sel2.marcar("Beto", true); sel2.marcar("Beto", false)
        assertTrue(sel2.aAgregar().isEmpty())
        assertEquals(3, contactos.listar().size)
    }
}
