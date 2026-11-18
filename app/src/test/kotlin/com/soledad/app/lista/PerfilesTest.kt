package com.soledad.app.lista

import com.soledad.app.nucleo.Mensaje
import com.soledad.app.nucleo.Veredicto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PerfilesTest {
    @Test fun noAgendadoEsUnTelefonoOTilde() {
        assertTrue(Perfiles.noAgendado("+54 9 388 462-6133"))
        assertTrue(Perfiles.noAgendado("‪+54 9 388 462-6133‬"))
        assertTrue(Perfiles.noAgendado("+54 9 388 462-6133"))
        assertTrue(Perfiles.noAgendado("~Carlos"))
        assertFalse(Perfiles.noAgendado("Mamá"))
        assertFalse(Perfiles.noAgendado("Juan 2"))
        assertFalse(Perfiles.noAgendado("Taller 4455"))
        assertFalse(Perfiles.noAgendado(""))
    }

    @Test fun perfilSegunModoYGrupo() {
        assertEquals(Perfil.NEGOCIO, Perfiles.de("+54 9 388 462-6133", grupo = false, modoNegocio = true))
        assertEquals(Perfil.PERSONAL, Perfiles.de("+54 9 388 462-6133", grupo = false, modoNegocio = false))
        assertEquals(Perfil.PERSONAL, Perfiles.de("+54 9 388 462-6133", grupo = true, modoNegocio = true))
        assertEquals(Perfil.PERSONAL, Perfiles.de("Ana", grupo = false, modoNegocio = true))
    }

    private var negocio = false
    private fun verificador(contactos: List<Contacto> = emptyList()): VerificadorListaImpl {
        val repo = RepositorioConfig(AlmacenMemoria(EscritorYaml.escribir(ConfigLista(interruptorGeneral = true, contactos = contactos))))
        return VerificadorListaImpl(repo, EstadoLista(AlmacenMemoria()), { 1_000_000L }, modoNegocio = { negocio })
    }

    private var n = 0
    private fun msg(nombre: String, texto: String, grupo: Boolean = false) =
        Mensaje("m${n++}", "chat-$nombre", nombre, texto, grupo, 999_000L, "com.whatsapp")

    @Test fun noAgendadoPasaSoloConModoNegocio() {
        val v = verificador()
        assertEquals(Veredicto.NO_RESPONDER, v.evaluar(msg("+54 9 388 111-2222", "hola, cuánto sale la torta?")))
        negocio = true
        assertEquals(Veredicto.RESPONDER_IA, v.evaluar(msg("+54 9 388 111-2222", "hola, cuánto sale la torta?")))
        // Agendado fuera de la lista: nadie le contesta. Grupos: nunca.
        assertEquals(Veredicto.NO_RESPONDER, v.evaluar(msg("Vecino", "hola, cuánto sale la torta?")))
        assertEquals(Veredicto.NO_RESPONDER, v.evaluar(msg("+54 9 388 333-4444", "hola, cuánto sale?", grupo = true)))
    }

    @Test fun enNegocioSePuedeHablarDePlataPeroNoDeCodigos() {
        negocio = true
        val v = verificador(listOf(Contacto("Ana", "Ana")))
        assertEquals(Veredicto.RESPONDER_IA, v.evaluar(msg("+54 9 388 111-2222", "aceptan transferencia o tarjeta?")))
        assertEquals(Veredicto.NO_RESPONDER, v.evaluar(msg("+54 9 388 555-6666", "pasame el codigo que te llegó")))
        // En lo personal la regla de siempre no cambia.
        assertEquals(Veredicto.NO_RESPONDER, v.evaluar(msg("Ana", "me hacés una transferencia?")))
    }
}
