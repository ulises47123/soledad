package com.soledad.app.lista

import com.soledad.app.nucleo.MiniYaml
import com.soledad.app.nucleo.Mensaje
import com.soledad.app.nucleo.Veredicto
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class VerificadorListaTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private var ahora = 0L
    private var n = 0

    private fun hora(h: Int, m: Int = 0): Long = Calendar.getInstance(utc).apply { clear(); set(2026, 0, 5, h, m) }.timeInMillis

    private fun armar(c: ConfigLista, almacen: AlmacenMemoria = AlmacenMemoria()): Triple<VerificadorListaImpl, ListaContactos, AlmacenMemoria> {
        val repo = RepositorioConfig(AlmacenMemoria()); repo.guardar(c)
        val est = EstadoLista(almacen)
        return Triple(VerificadorListaImpl(repo, est, { ahora }, utc), ListaContactos(repo, est), almacen)
    }

    private fun msg(texto: String, chat: String = "1@s.whatsapp.net", nombre: String = "Ana", grupo: Boolean = false) =
        Mensaje("m${n++}", chat, nombre, texto, grupo, ahora, "com.whatsapp")

    // Los tests heredados prueban el modo «excluidos» del proyecto base; Soledad usa «permitidos» (ver SoledadTest).
    private val on = ConfigLista(interruptorGeneral = true, modo = ModoLista.EXCLUIDOS)

    @Test fun interruptorApagadoPorDefecto() {
        assertFalse(ConfigLista().interruptorGeneral)
        val (v) = armar(ConfigLista())
        val m = msg("hola, ¿tienen disponible?")
        assertEquals(Veredicto.NO_RESPONDER, v.evaluar(m))
        assertTrue(v.motivo(m).contains("interruptor"))
        assertEquals(Veredicto.RESPONDER_IA, armar(on).first.evaluar(msg("hola, ¿tienen disponible?")))
    }

    @Test fun grupos() {
        val m = msg("hola a todos, ¿alguien sabe?", grupo = true)
        val (v) = armar(on); assertEquals(Veredicto.NO_RESPONDER, v.evaluar(m)); assertTrue(v.motivo(m).contains("grupo"))
        assertEquals(Veredicto.RESPONDER_IA, armar(on.copy(grupos = true)).first.evaluar(m))
    }

    @Test fun horarioNormalYCruzaMedianoche() {
        val normal = on.copy(horarioActivo = true, desde = "09:00", hasta = "18:00")
        ahora = hora(10); assertEquals(Veredicto.RESPONDER_IA, armar(normal).first.evaluar(msg("buenas, consulta")))
        ahora = hora(18); val (v) = armar(normal); val m = msg("buenas, consulta")
        assertEquals(Veredicto.NO_RESPONDER, v.evaluar(m)); assertTrue(v.motivo(m).contains("horario"))
        ahora = hora(8, 59); assertEquals(Veredicto.NO_RESPONDER, armar(normal).first.evaluar(msg("buenas, consulta")))
        val noche = on.copy(horarioActivo = true, desde = "22:00", hasta = "06:00")
        for ((h, ok) in listOf(23 to true, 2 to true, 5 to true, 6 to false, 12 to false, 21 to false, 22 to true)) {
            ahora = hora(h)
            assertEquals("hora $h", if (ok) Veredicto.RESPONDER_IA else Veredicto.NO_RESPONDER, armar(noche).first.evaluar(msg("buenas, consulta")))
        }
    }

    @Test fun triviales() {
        val (v) = armar(on)
        for (t in listOf("ok", "👍", "😂😂", "Foto", "reaccionó con ❤️", "Mensaje de voz", "Gracias", "a")) {
            val m = msg(t); assertEquals(t, Veredicto.NO_RESPONDER, v.evaluar(m)); assertTrue(v.motivo(m).contains("trivial"))
        }
        assertEquals(Veredicto.RESPONDER_IA, v.evaluar(msg("¿a qué hora abren?")))
    }

    @Test fun modoExcluidosYPermitidosMismoContacto() {
        val ana = Contacto("1@s.whatsapp.net", "Ana")
        val m = msg("¿tienen stock del producto?")
        val otro = msg("¿tienen stock del producto?", chat = "2@s.whatsapp.net", nombre = "Beto")
        val (ve) = armar(on.copy(modo = ModoLista.EXCLUIDOS, contactos = listOf(ana)))
        assertEquals(Veredicto.NO_RESPONDER, ve.evaluar(m)); assertTrue(ve.motivo(m).contains("excluidos"))
        assertEquals(Veredicto.RESPONDER_IA, ve.evaluar(otro))
        val (vp) = armar(on.copy(modo = ModoLista.PERMITIDOS, contactos = listOf(ana)))
        assertEquals(Veredicto.RESPONDER_IA, vp.evaluar(m))
        assertEquals(Veredicto.NO_RESPONDER, vp.evaluar(otro)); assertTrue(vp.motivo(otro).contains("permitidos"))
        assertEquals(Veredicto.RESPONDER_IA, armar(on).first.evaluar(m))
        assertEquals(Veredicto.NO_RESPONDER, armar(on.copy(modo = ModoLista.PERMITIDOS)).first.evaluar(m))
    }

    @Test fun sensibleEnMensajeYEnAnteriorSinPersistirTexto() {
        val alm = AlmacenMemoria()
        val (v, lc) = armar(on, alm)
        val a = msg("pásame la clave del wifi"); assertEquals(Veredicto.NO_RESPONDER, v.evaluar(a)); assertTrue(v.motivo(a).contains("clave"))
        val b = msg("¿y a qué hora abren?")
        assertEquals("la palabra sensible vive en un mensaje anterior", Veredicto.NO_RESPONDER, v.evaluar(b))
        assertTrue(v.motivo(b).contains("sensible"))
        assertEquals(Veredicto.NO_RESPONDER, v.evaluar(msg("mi número es 123 456 789")))
        v.verChat(a); v.registrarRespuesta(a.chatId); lc.agregar(Contacto("x", "X"))
        assertFalse("el texto no se persiste", alm.contenido!!.contains("wifi") || alm.contenido!!.contains("clave"))
        assertEquals(Veredicto.RESPONDER_IA, v.evaluar(msg("¿a qué hora abren?", chat = "9@s.whatsapp.net", nombre = "Zoe")))
    }

    @Test fun sensibleSeLiberaTrasTresMensajes() {
        val (v) = armar(on)
        v.evaluar(msg("dame el código"))
        repeat(3) { v.evaluar(msg("pregunta número $it sobre horarios")) }
        assertEquals(Veredicto.RESPONDER_IA, v.evaluar(msg("¿abren el sábado?")))
    }

    @Test fun limitePorHoraSeLiberaYPersiste() {
        ahora = hora(10)
        val alm = AlmacenMemoria()
        val (v) = armar(on.copy(limiteRespuestasHora = 2), alm)
        val chat = "1@s.whatsapp.net"
        assertEquals(Veredicto.RESPONDER_IA, v.evaluar(msg("hola, una consulta")))
        v.registrarRespuesta(chat); ahora += 60_000; v.registrarRespuesta(chat)
        val m = msg("otra consulta")
        assertEquals(Veredicto.NO_RESPONDER, v.evaluar(m)); assertTrue(v.motivo(m).contains("límite"))
        assertEquals(Veredicto.RESPONDER_IA, v.evaluar(msg("hola, una consulta", chat = "2@s.whatsapp.net")))
        val (v2) = armar(on.copy(limiteRespuestasHora = 2), alm)
        assertEquals(Veredicto.NO_RESPONDER, v2.evaluar(msg("otra consulta")))
        ahora = hora(10) + 3_600_000L + 1 // la primera ya cumplió la hora, queda una
        assertEquals(Veredicto.RESPONDER_IA, v.evaluar(msg("otra consulta más")))
        ahora = hora(11) + 2 * 60_000L // ya pasaron ambas
        assertEquals(Veredicto.RESPONDER_IA, v.evaluar(msg("y otra consulta")))
    }

    @Test fun recientesAcotadosSinTexto() {
        val alm = AlmacenMemoria()
        val est = EstadoLista(alm, maxChats = 3)
        val repo = RepositorioConfig(AlmacenMemoria()); repo.guardar(on)
        val v = VerificadorListaImpl(repo, est, { ahora }, utc)
        for (i in 1..5) { ahora = i * 1000L; v.verChat(msg("texto secreto $i", chat = "c$i", nombre = "N$i")) }
        assertEquals(listOf("c5", "c4", "c3"), ListaContactos(repo, est).recientes().map { it.id })
        assertFalse(alm.contenido!!.contains("secreto"))
        assertEquals(listOf("c5", "c4", "c3"), EstadoLista(alm).recientes(10).map { it.id })
    }

    @Test fun yamlEscritoSeRelee() {
        val c = ConfigLista(true, ModoLista.PERMITIDOS, true, true, "22:00", "06:30", listOf("dinero", "mi \"clave\"", "a, b"), 7,
            listOf(Contacto("5491155550000@s.whatsapp.net", "Ana: \"la jefa\""), Contacto("12345", "Beto # x")))
        assertEquals(c, ConfigLista.desdeYaml(EscritorYaml.escribir(c)))
        assertEquals(ConfigLista(), ConfigLista.desdeYaml(EscritorYaml.escribir(ConfigLista())))
        assertNotNull(MiniYaml.parse(EscritorYaml.escribir(c)))
    }

    @Test fun listaContactosEditaYPersiste() {
        val alm = AlmacenMemoria()
        val est = EstadoLista(AlmacenMemoria())
        val lc = ListaContactos(RepositorioConfig(alm), est)
        lc.agregar(Contacto("1", "Ana")); lc.agregar(Contacto("2", "Beto")); lc.agregar(Contacto("1", "Ana R"))
        lc.setInterruptor(true); lc.setModo(ModoLista.PERMITIDOS)
        val otra = ListaContactos(RepositorioConfig(alm), est)
        assertEquals(listOf("Beto", "Ana R"), otra.listar().map { it.nombre })
        assertTrue(otra.leerConfig().interruptorGeneral); assertEquals(ModoLista.PERMITIDOS, otra.leerConfig().modo)
        otra.quitar("2"); assertEquals(listOf("1"), otra.listar().map { it.id })
    }

    @Test fun configPorDefectoDelArchivoYaml() {
        val f = java.io.File("../flujo_bot/verificacion_lista/config.yaml")
        val txt = listOf(f, java.io.File("flujo_bot/verificacion_lista/config.yaml")).firstOrNull { it.exists() }?.readText() ?: return
        val c = ConfigLista.desdeYaml(txt)
        assertFalse(c.interruptorGeneral); assertEquals(ModoLista.PERMITIDOS, c.modo); assertFalse(c.grupos); assertTrue(c.contactos.isEmpty()); assertTrue(c.bloqueadas.contains("banco"))
    }
}
